package com.morpheusdata.iloconsole.services

import com.morpheusdata.model.ComputeServer
import groovy.util.logging.Slf4j

/**
 * HPE / iLO detection AND identifying-info extraction.
 *
 * Why one service for both: in v0.1.3 detection worked but the "Detected:"
 * line in the tab was blank. Detection used server.properties[name] (the
 * MorpheusModel property map), while the tab template used direct Groovy
 * property access (server?.hardwareProductVendor). For some hosts the two
 * paths return different values — likely because ComputeServer is Hibernate-
 * managed and direct getter calls hit lazy-init issues once the session
 * that loaded the entity is closed, whereas the .properties map was hydrated
 * eagerly at load time.
 *
 * Lesson: USE ONE READER. extractHardwareInfo() returns what we'll display,
 * matchesHpeIlo() decides from the same data. They cannot disagree.
 *
 * Both methods are bulletproof — any Throwable inside is swallowed and a
 * safe default is returned. show() runs on every host page render, so a
 * bug here cannot be allowed to take down the host page.
 *
 * v0.1.48 — expanded scope. Previously matched only "HPE ProLiant" hosts.
 * The iLO management processor ships on many HPE server families beyond
 * ProLiant — Synergy compute modules, Apollo HPC, BladeSystem legacy
 * blades, Edgeline edge servers, Alletra 4000/5000/6000 servers,
 * Cray XD, Superdome Flex compute nodes, and MicroServer (sometimes
 * reported without the ProLiant prefix).
 *
 * v0.1.49 — added `ilo-host:X` label as an opt-in signal for HPE
 * families we haven't listed yet, while keeping the HPE-only gate
 * strict. The label bypasses the model-token check but NOT the HPE
 * vendor/model check. A user who attaches `ilo-host:X` to a Dell
 * iDRAC or Cisco CIMC by mistake still doesn't see the tab there —
 * the plugin's purpose is HPE iLO management, and cluttering other
 * hardware's host detail pages with a broken iLO tab would be a bug.
 */
@Slf4j
class IloDetectionService {

    private static final List<String> HPE_VENDOR_MATCHES = [
            'hpe', 'hp', 'hewlett packard enterprise', 'hewlett-packard'
    ]

    /**
     * v0.1.48 — HPE server families known to ship with iLO. Order is
     * roughly by prevalence in the wild. All tokens are matched against
     * the LOWERCASED model string with .contains(), so partial matches
     * work ("HPE ProLiant DL380 Gen11" contains "proliant"; "HPE
     * Synergy 480 Gen11" contains "synergy").
     *
     * Intentionally NOT included: generic tokens like "compute" (matches
     * too many things including HPE Compute Fabric which isn't iLO),
     * "storage" (Alletra 9000 storage arrays don't have iLO), and
     * "server" (matches basically anything). The label-based override
     * handles anything the token list doesn't.
     */
    private static final List<String> HPE_ILO_MODEL_TOKENS = [
            'proliant',      // Traditional rack (DL) / tower (ML) / blade (BL)
            'synergy',       // Composable compute modules
            'apollo',        // HPC / dense compute chassis
            'bladesystem',   // Legacy blade infrastructure
            'edgeline',      // Edge / remote-office servers
            'microserver',   // Sometimes reported without "ProLiant" prefix
            'alletra',       // Alletra 4000/5000/6000 compute (not 9000 storage)
            'cray xd',       // HPE Cray XD supercomputer nodes
            'superdome',     // Superdome Flex / X mission-critical nodes
    ]

    /**
     * Label that (a) marks a host as iLO-managed and (b) supplies the
     * iLO IP/hostname the plugin talks to. If this label is present,
     * we skip the vendor/model fingerprint check entirely — the user
     * has told us their intent explicitly.
     */
    private static final String ILO_OPT_IN_LABEL_PREFIX = 'ilo-host:'

    /**
     * Returns a Map of the displayable identifying info for a ComputeServer.
     * Keys: vendor, model, serial, source ("properties"|"direct"|null).
     * "source" indicates which access path produced data.
     */
    Map extractHardwareInfo(ComputeServer server) {
        Map result = [vendor: null, model: null, serial: null, source: null]
        if (server == null) return result

        // Path 1: MorpheusModel.properties map (Hibernate-eager-loaded).
        try {
            String vendor = readProperty(server, ['hardwareProductVendor', 'serverVendor'])
            String model  = readProperty(server, ['hardwareProductName', 'serverModel'])
            String serial = readProperty(server, ['serialNumber'])
            if (vendor || model || serial) {
                result.vendor = vendor
                result.model = model
                result.serial = serial
                result.source = 'properties'
                return result
            }
        } catch (Throwable t) {
            log.debug("properties[] read threw: ${t.message}")
        }

        // Path 2: fallback to direct getter access — wrapped so a thrown
        // LazyInitializationException or MissingPropertyException can never
        // bubble out.
        try {
            String vendor = directGet(server, 'hardwareProductVendor') ?: directGet(server, 'serverVendor')
            String model  = directGet(server, 'hardwareProductName')  ?: directGet(server, 'serverModel')
            String serial = directGet(server, 'serialNumber')
            result.vendor = vendor
            result.model = model
            result.serial = serial
            result.source = 'direct'
        } catch (Throwable t) {
            log.debug("direct getter read threw: ${t.message}")
        }
        return result
    }

    /**
     * v0.1.48 — replaces matchesHpeProliant. Returns true for any
     * HPE server family known to ship with iLO.
     *
     * v0.1.49 — closed a gap: the label-based opt-in must NOT
     * substitute for the HPE check. Otherwise a user who attached
     * `ilo-host:X` labels to a Dell iDRAC or Cisco CIMC host by
     * mistake would see the iLO tab there too — cluttering non-HPE
     * hardware. The label is a "yes, this HPE host has iLO" signal,
     * not a "this thing is HPE" signal.
     *
     * Gating logic:
     *   1. Requires an HPE signal (vendor field OR model field must
     *      contain a known HPE identifier). Non-HPE hardware —
     *      regardless of labels — never sees the tab.
     *   2. Given HPE is confirmed, we pass if EITHER:
     *      - the model contains a known iLO-managed family token
     *        (proliant, synergy, apollo, etc.) — the zero-config
     *        auto-detect path
     *      - OR the host has an explicit `ilo-host:X` label — the
     *        opt-in path for future HPE families we haven't listed
     *        yet, and for hosts where Morpheus knows the model is
     *        HPE but not which family
     *
     * Non-HPE hardware (Dell, Cisco, Supermicro, etc.) never
     * matches, even if it has ilo-host: labels applied. That's the
     * point — the tab must only appear on HPE gear.
     */
    boolean matchesHpeIlo(ComputeServer server) {
        if (server == null) return false
        try {
            Map info = extractHardwareInfo(server)
            String v = info.vendor?.toString()?.toLowerCase()?.trim() ?: ''
            String m = info.model?.toString()?.toLowerCase()?.trim() ?: ''

            boolean vendorIsHpe = v && HPE_VENDOR_MATCHES.any { v.contains(it) }
            boolean modelHasHpeFamily = m && HPE_ILO_MODEL_TOKENS.any { m.contains(it) }

            // Gate 1: MUST have an HPE signal from vendor or model.
            // This is what keeps Dell / Cisco / Supermicro / etc.
            // from getting the tab — even if they've been mislabeled.
            if (!vendorIsHpe && !modelHasHpeFamily) return false

            // Gate 2: given we know it's HPE, either the model tells
            // us it's an iLO family (auto-detect), OR the user
            // asserted iLO-managed via a label (opt-in for HPE
            // families outside our token list).
            if (modelHasHpeFamily) return true
            if (hasIloOptInLabel(server)) return true

            // HPE vendor but unknown model family and no opt-in label
            // — probably an HPE product without iLO (networking,
            // storage array, etc.). Don't show the tab.
            return false
        } catch (Throwable t) {
            log.debug("iLO detection threw on server ${safeId(server)}: ${t.message}")
            return false
        }
    }

    /**
     * v0.1.48 — kept as a backward-compat alias so anything calling the
     * old name still works. Delegates to matchesHpeIlo.
     */
    @Deprecated
    boolean matchesHpeProliant(ComputeServer server) {
        return matchesHpeIlo(server)
    }

    /**
     * True if the server has an `ilo-host:X` label. Used as the opt-in
     * override so hosts with the label always get the iLO tab, even
     * when Morpheus's vendor/model fingerprint is missing or the model
     * name isn't in our HPE_ILO_MODEL_TOKENS list.
     *
     * Defensive: swallow any exception reading labels — a failure here
     * means we fall back to the fingerprint check, which is fine.
     */
    private static boolean hasIloOptInLabel(ComputeServer server) {
        try {
            def raw = server.labels
            if (raw == null) return false
            if (raw instanceof Collection) {
                for (def entry : raw) {
                    String s = extractLabelString(entry)
                    if (s != null && s.toLowerCase().startsWith(ILO_OPT_IN_LABEL_PREFIX)) return true
                }
                return false
            }
            // Single value / string form — split on comma/whitespace like
            // IloConfigStore does and check each piece.
            String s = raw.toString()
            for (String piece : s.split('[,\\s]+')) {
                if (piece != null && piece.toLowerCase().startsWith(ILO_OPT_IN_LABEL_PREFIX)) return true
            }
            return false
        } catch (Throwable ignored) {
            return false
        }
    }

    /** First non-empty value from the .properties map for any of these keys. */
    private static String readProperty(ComputeServer server, List<String> keys) {
        Map props = null
        try { props = server.properties as Map } catch (Throwable ignored) { return null }
        if (props == null) return null
        for (String k : keys) {
            try {
                def v = props[k]
                if (v != null) {
                    String s = v.toString().trim()
                    if (!s.isEmpty()) return s
                }
            } catch (Throwable ignored) {}
        }
        return null
    }

    /** Direct getter access via Groovy property syntax, exception-safe. */
    private static String directGet(ComputeServer server, String name) {
        try {
            def v = server.getProperty(name)
            if (v == null) return null
            String s = v.toString().trim()
            return s.isEmpty() ? null : s
        } catch (Throwable t) {
            return null
        }
    }

    /**
     * Mirror of the private helper in IloConfigStore — probes common
     * property names on the Label model, then falls back to toString.
     * Kept private/local so IloDetectionService has no runtime
     * dependency on IloConfigStore for this cheap-read path.
     */
    private static String extractLabelString(Object entry) {
        if (entry == null) return null
        if (entry instanceof String) {
            String s = entry.trim()
            return s.isEmpty() ? null : s
        }
        for (String prop : ['name', 'value', 'displayName', 'label']) {
            try {
                def v = entry.properties[prop]
                if (v != null) {
                    String s = v.toString().trim()
                    if (!s.isEmpty()) return s
                }
            } catch (Throwable ignored) {}
        }
        try {
            String s = entry.toString().trim()
            return s.isEmpty() ? null : s
        } catch (Throwable ignored) {
            return null
        }
    }

    private static Object safeId(ComputeServer server) {
        try { return server?.id } catch (Throwable t) { return '?' }
    }
}
