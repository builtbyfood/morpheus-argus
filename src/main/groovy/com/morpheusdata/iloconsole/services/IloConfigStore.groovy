package com.morpheusdata.iloconsole.services

import com.morpheusdata.model.ComputeServer
import groovy.util.logging.Slf4j

/**
 * Reads per-server iLO configuration from labels on the ComputeServer.
 *
 * Morpheus exposes server.labels as a List&lt;Label&gt;, where Label is a model
 * object (account, name, value, ...). We try to extract the displayable
 * string from each Label defensively: name first, then value, then toString.
 *
 * Label keys we look for:
 *   ilo-host:&lt;ip-or-hostname&gt;
 *   ilo-cred:&lt;credential-id&gt;
 *   ilo-verify-ssl:true                (optional; default false for self-signed)
 *   ilo-readonly:true                  (optional; hides credentials and launch buttons,
 *                                       leaves only the read-only status panel)
 *
 * Wrapped in try/catch returning a safe empty result. Never throws.
 */
@Slf4j
class IloConfigStore {

    static final String HOST_PREFIX        = 'ilo-host:'
    static final String CRED_PREFIX        = 'ilo-cred:'
    static final String VERIFY_SSL_PREFIX  = 'ilo-verify-ssl:'
    static final String READONLY_PREFIX    = 'ilo-readonly:'

    Map loadConfig(ComputeServer server) {
        Map result = [iloHost: null, credentialId: null, verifySsl: false, readonly: false, configured: false, rawLabels: []]
        if (server == null) return result
        try {
            Collection<String> labels = collectLabels(server)
            result.rawLabels = labels.toList()
            for (String label : labels) {
                if (label == null) continue
                String s = label.trim()
                if (s.isEmpty()) continue
                if (s.startsWith(HOST_PREFIX)) {
                    String v = s.substring(HOST_PREFIX.length()).trim()
                    if (!v.isEmpty()) result.iloHost = v
                } else if (s.startsWith(CRED_PREFIX)) {
                    String v = s.substring(CRED_PREFIX.length()).trim()
                    try { result.credentialId = Long.parseLong(v) } catch (Throwable ignored) {}
                } else if (s.startsWith(VERIFY_SSL_PREFIX)) {
                    String v = s.substring(VERIFY_SSL_PREFIX.length()).trim().toLowerCase()
                    result.verifySsl = (v in ['true', '1', 'yes', 'on'])
                } else if (s.startsWith(READONLY_PREFIX)) {
                    String v = s.substring(READONLY_PREFIX.length()).trim().toLowerCase()
                    result.readonly = (v in ['true', '1', 'yes', 'on'])
                }
            }
            result.configured = (result.iloHost != null && result.credentialId != null)
        } catch (Throwable t) {
            log.warn("loadConfig threw for server ${server?.id}: ${t.message}", t)
        }
        return result
    }

    /**
     * Defensively pull string representations out of server.labels.
     *
     * server.labels is documented as List&lt;Label&gt; (model objects), but
     * older code in the wild also returns Collection&lt;String&gt;. Handle both,
     * and probe the Label object for `name`, `value`, `displayName`, then
     * fall back to toString().
     */
    private static Collection<String> collectLabels(ComputeServer server) {
        Set<String> out = new LinkedHashSet<>()
        def raw
        try { raw = server.labels } catch (Throwable t) { return out }
        if (raw == null) return out

        if (raw instanceof Collection) {
            raw.each { entry ->
                String s = extractLabelString(entry)
                if (s) out.add(s)
            }
        } else {
            // single value? toString it and split on whitespace/comma
            String s = raw.toString()
            s.split('[,\\s]+').each { if (it) out.add(it) }
        }
        return out
    }

    private static String extractLabelString(Object entry) {
        if (entry == null) return null
        if (entry instanceof String) {
            String s = entry.trim()
            return s.isEmpty() ? null : s
        }
        // Try common property names on the Label model
        for (String prop : ['name', 'value', 'displayName', 'label']) {
            try {
                def v = entry.properties[prop]
                if (v != null) {
                    String s = v.toString().trim()
                    if (!s.isEmpty()) return s
                }
            } catch (Throwable ignored) {
                // try next
            }
        }
        // Fallback
        try {
            String s = entry.toString()?.trim()
            return (s == null || s.isEmpty()) ? null : s
        } catch (Throwable t) {
            return null
        }
    }
}
