package com.morpheusdata.iloconsole.services

import com.morpheusdata.model.ComputeServer
import groovy.util.logging.Slf4j

/**
 * HPE / ProLiant detection AND identifying-info extraction.
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
 * matchesHpeProliant() decides from the same data. They cannot disagree.
 *
 * Both methods are bulletproof — any Throwable inside is swallowed and a
 * safe default is returned. show() runs on every host page render, so a
 * bug here cannot be allowed to take down the host page.
 */
@Slf4j
class IloDetectionService {

    private static final List<String> HPE_VENDOR_MATCHES = [
            'hpe', 'hp', 'hewlett packard enterprise', 'hewlett-packard'
    ]
    private static final String PROLIANT_TOKEN = 'proliant'

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

    boolean matchesHpeProliant(ComputeServer server) {
        if (server == null) return false
        try {
            Map info = extractHardwareInfo(server)
            if (info.vendor == null || info.model == null) return false
            String v = info.vendor.toString().toLowerCase().trim()
            String m = info.model.toString().toLowerCase().trim()
            if (!HPE_VENDOR_MATCHES.any { v.contains(it) }) return false
            if (!m.contains(PROLIANT_TOKEN)) return false
            return true
        } catch (Throwable t) {
            log.debug("iLO detection threw on server ${safeId(server)}: ${t.message}")
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

    private static Object safeId(ComputeServer server) {
        try { return server?.id } catch (Throwable t) { return '?' }
    }
}
