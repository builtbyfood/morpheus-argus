package com.morpheusdata.iloconsole.services

import groovy.util.logging.Slf4j

/**
 * Audit logging for iLO Console actions. Writes structured AUDIT lines into
 * the standard Morpheus log, grep-friendly:
 *
 *   grep 'AUDIT iloConsole' /var/log/morpheus/morpheus-ui/current
 */
@Slf4j
class AuditLogger {

    void tabRendered(Long userId, String username, Long serverId, String iloHost) {
        write('tab_rendered', [user_id: userId, username: username, server_id: serverId, ilo_host: iloHost])
    }

    void statusFetched(Long serverId, String iloHost, boolean success, String error) {
        write('status_fetched', [server_id: serverId, ilo_host: iloHost, success: success, error: error])
    }

    private static void write(String event, Map fields) {
        StringBuilder sb = new StringBuilder("AUDIT iloConsole event=").append(event)
        fields.each { k, v ->
            sb.append(' ').append(k).append('=').append(v == null ? '-' : v.toString().replace(' ', '_'))
        }
        log.info(sb.toString())
    }
}
