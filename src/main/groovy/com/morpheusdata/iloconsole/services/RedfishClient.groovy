package com.morpheusdata.iloconsole.services

import com.morpheusdata.core.util.HttpApiClient
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

import java.net.URI

/**
 * Minimal HPE iLO Redfish client. Mirrors the auth pattern from the SiLO
 * Home Assistant integration:
 *
 *   1. POST /redfish/v1/SessionService/Sessions { UserName, Password }
 *      -> response headers carry X-Auth-Token and Location
 *   2. All subsequent reads send X-Auth-Token: &lt;token&gt;
 *   3. DELETE &lt;Location&gt; with X-Auth-Token to release the session
 *
 * Per the SiLO design notes: per-request Basic auth burns iLO session slots
 * and eventually triggers NoValidSession errors. Session-token uses one slot
 * per polling cycle.
 *
 * Self-signed certs: iLOs ship with self-signed certs by default. Verification
 * is controlled by the verifySsl flag.
 */
@Slf4j
class RedfishClient {

    final String iloHost                 // bare IP or hostname, no scheme
    final boolean verifySsl
    final HttpApiClient http

    private String authToken              // X-Auth-Token from last login
    private String sessionLocation        // URI to DELETE on logout

    RedfishClient(String iloHost, boolean verifySsl) {
        this.iloHost = iloHost
        this.verifySsl = verifySsl
        this.http = new HttpApiClient()
    }

    private String baseUrl() { return "https://${iloHost}" }

    /**
     * POST to SessionService/Sessions and capture X-Auth-Token + Location.
     * Throws on non-success.
     */
    void login(String username, String password) {
        HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                headers: ['Content-Type': 'application/json', 'Accept': 'application/json'],
                body: JsonOutput.toJson([UserName: username, Password: password]),
                ignoreSSL: !verifySsl,
                contentType: 'application/json'
        )
        def resp = http.callJsonApi(baseUrl(), '/redfish/v1/SessionService/Sessions', opts, 'POST')
        if (resp == null || !resp.success) {
            throw new IllegalStateException("iLO ${iloHost}: session login failed (${resp?.errorCode})")
        }
        Map<String, String> headers = normalizeHeaders(resp.headers)
        this.authToken = headers['x-auth-token']
        this.sessionLocation = headers['location']
        if (!this.authToken) {
            throw new IllegalStateException("iLO ${iloHost}: 201 but no X-Auth-Token header")
        }
        log.debug("iLO ${iloHost}: session established")
    }

    void logout() {
        if (!authToken || !sessionLocation) return
        try {
            HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                    headers: ['X-Auth-Token': authToken, 'Accept': 'application/json'],
                    ignoreSSL: !verifySsl
            )
            http.callJsonApi(baseUrl(), sessionLocation, opts, 'DELETE')
        } catch (Throwable t) {
            log.warn("iLO ${iloHost}: logout failed (server-side timeout will reap it): ${t.message}")
        } finally {
            this.authToken = null
            this.sessionLocation = null
        }
    }

    /**
     * v0.1.46 — delete every OTHER session belonging to the same iLO user
     * we just logged in as, keeping only the current session (identified
     * by sessionLocation).
     *
     * Rationale: each tab render creates two sessions — one for
     * collectStatus (properly logged out in the finally block at the end
     * of the render) and one via acquireLaunchToken for the sessionkey
     * URL launch path (intentionally NOT logged out so the user's click
     * on the launch URL arrives at a still-valid token). The launch
     * token session leaks by design, and repeated tab renders without
     * console launches still accumulate them because iLO only reaps
     * idle sessions after ~30 minutes. Combined with any human operator
     * activity on the same iLO account, the ~13-session pool can
     * saturate in an afternoon. Once saturated, PATCH writes start
     * failing with misleading `PropertyNotWritableOrUnknown` errors
     * (see the v0.1.41 session-limit documentation) even though reads
     * keep working — the failure mode is intermittent and hard to
     * blame on the plugin.
     *
     * v0.1.47 — CRITICAL fix. v0.1.46's `sPath == sessionLocation`
     * comparison never matched because:
     *   - `sessionLocation` from the Location header on POST /Sessions
     *     is an ABSOLUTE URL on HPE iLO 6/7 (e.g.
     *     `https://192.168.0.248/redfish/v1/SessionService/Sessions/xxx`).
     *   - `Members[].@odata.id` from the Sessions collection is a
     *     RELATIVE PATH (e.g. `/redfish/v1/SessionService/Sessions/xxx`).
     * The comparison saw them as different strings, cleanup deleted
     * every session including the one we just created for the render,
     * subsequent GETs 401'd, and the tab rendered with nulls
     * everywhere. Now we normalize both sides to path form before
     * comparing, plus paranoid guards:
     *   (a) If normalization returns null/empty for our current session,
     *       skip cleanup entirely rather than risk a wildcard delete.
     *   (b) Even if the normalized comparison somehow still misses (e.g.
     *       trailing slashes, case sensitivity), we also compare the
     *       LAST PATH SEGMENT (the session id) so a session with our id
     *       is never deleted.
     *   (c) Cap the delete count at (member count - 1) so we always
     *       leave at least one session alive on this iLO. Belt AND
     *       suspenders.
     *
     * Design:
     *   - Only delete sessions belonging to the SAME UserName we
     *     authenticated as. Other users' sessions (iLO web UI,
     *     administrators, other integrations) are strictly off-limits;
     *     killing an operator's live console session would be much
     *     worse than a saturated session pool.
     *   - Skip the current session using the normalized path comparison
     *     plus the id-tail safety net.
     *   - Best-effort: swallow any per-session DELETE failure so a
     *     single stuck session doesn't abort cleanup of the rest. iLO
     *     will reap what we couldn't after the timeout.
     *
     * Returns the number of sessions actually deleted, for surfacing in
     * the diagnostics row.
     */
    int cleanupOwnStaleSessions(String username) {
        if (!authToken || !username) return 0
        // v0.1.47 — normalize our own session location to a path so we
        // can compare against Members[].@odata.id (which is always a
        // path).
        String ourSessionPath = normalizeSessionPath(sessionLocation)
        String ourSessionId   = tailSegment(ourSessionPath)
        if (!ourSessionPath || !ourSessionId) {
            log.warn("iLO ${iloHost}: cleanupOwnStaleSessions skipped — sessionLocation could not be normalized (${sessionLocation})")
            return 0
        }
        int deleted = 0
        try {
            def sessColl = getJson('/redfish/v1/SessionService/Sessions')
            if (sessColl == null) return 0
            List members = (sessColl instanceof Map && (sessColl as Map).get('Members') instanceof List)
                    ? (sessColl as Map).get('Members') as List
                    : Collections.emptyList()
            // v0.1.47 — safety cap: never delete more than (memberCount - 1)
            // sessions on this iLO in one cleanup pass. Even if every
            // filter above somehow lets the current session through, this
            // guarantees we can't kill the last session standing.
            int maxDeletes = Math.max(0, members.size() - 1)
            for (def sRef : members) {
                if (deleted >= maxDeletes) {
                    log.info("iLO ${iloHost}: cleanupOwnStaleSessions hit safety cap of ${maxDeletes} deletes; stopping")
                    break
                }
                String sPathRaw = null
                if (sRef instanceof Map) sPathRaw = (sRef as Map).get('@odata.id') as String
                if (!sPathRaw) continue
                String sPath = normalizeSessionPath(sPathRaw)
                String sId   = tailSegment(sPath)
                // Skip the session we're currently using for this render.
                // Belt (path match) AND suspenders (id tail match).
                if (sPath == ourSessionPath) continue
                if (sId && sId == ourSessionId) continue
                def sess = null
                try { sess = getJson(sPath) } catch (Throwable ignoredGet) { continue }
                if (!(sess instanceof Map)) continue
                String sUser = (sess as Map).get('UserName') as String
                // Only touch sessions owned by the same user we
                // authenticated as — never other users.
                if (!sUser || sUser != username) continue
                try {
                    HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                            headers: ['X-Auth-Token': authToken, 'Accept': 'application/json'],
                            ignoreSSL: !verifySsl
                    )
                    def resp = http.callJsonApi(baseUrl(), sPath, opts, 'DELETE')
                    if (resp != null && resp.success) deleted++
                } catch (Throwable delErr) {
                    log.debug("iLO ${iloHost}: cleanup DELETE ${sPath} failed: ${delErr.message}")
                }
            }
        } catch (Throwable t) {
            log.warn("iLO ${iloHost}: cleanupOwnStaleSessions failed: ${t.message}")
        }
        if (deleted > 0) log.info("iLO ${iloHost}: cleaned up ${deleted} stale session(s) for user ${username}")
        return deleted
    }

    /**
     * v0.1.47 — normalize a session URL/path to path form for
     * comparison. HPE iLO's Location header on POST /Sessions is
     * sometimes an absolute URL and sometimes a relative path
     * depending on firmware; @odata.id inside the Sessions collection
     * is always relative. Comparing the two directly without
     * normalization was the v0.1.46 bug.
     *
     * Also strips any trailing slash so `/Sessions/xxx/` and
     * `/Sessions/xxx` compare equal.
     */
    private static String normalizeSessionPath(String loc) {
        if (!loc) return null
        String path = loc
        if (path.contains('://')) {
            try {
                URI u = new URI(loc)
                path = u.path
            } catch (Throwable ignoredUri) {
                // Manual fallback if URI parsing fails.
                int schemeEnd = loc.indexOf('://')
                if (schemeEnd >= 0) {
                    int pathStart = loc.indexOf('/', schemeEnd + 3)
                    path = (pathStart >= 0) ? loc.substring(pathStart) : ''
                }
            }
        }
        if (path && path.length() > 1 && path.endsWith('/')) {
            path = path.substring(0, path.length() - 1)
        }
        return path
    }

    /**
     * v0.1.47 — return the last path segment of a URL/path. Used as a
     * secondary safety-net check so a session's id can match even if
     * the path normalization somehow drifted.
     */
    private static String tailSegment(String pathOrUrl) {
        if (!pathOrUrl) return null
        String s = pathOrUrl
        if (s.endsWith('/')) s = s.substring(0, s.length() - 1)
        int lastSlash = s.lastIndexOf('/')
        return (lastSlash >= 0 && lastSlash < s.length() - 1) ? s.substring(lastSlash + 1) : s
    }

    /**
     * v0.1.36 — Get the current X-Auth-Token without logging out. Used by
     * the sessionkey-URL launch path: the tab renders a link like
     *   https://&lt;iloHost&gt;/irc.html?sessionkey=&lt;TOKEN&gt;
     * which iLO accepts deterministically (no cookie commit race). The
     * caller is responsible for NOT calling logout() on this client
     * afterward — the session is intended for the user to consume via
     * the rendered link. iLO will reap it after its idle TTL if unused.
     */
    String getAuthToken() {
        return authToken
    }

    /**
     * v0.1.36 — Mint a fresh Redfish session and return the token, leaving
     * the session live. Used at tab render time to power the sessionkey
     * URL launch button. Returns null on any failure so the caller can
     * fall back to the legacy form-POST flow without exception handling.
     *
     * NOTE: this leaks a session per tab render. iLO 6 default max sessions
     * is generous (typically 10) and idle sessions are reaped after ~30 min,
     * so this is fine for the design — but don't call it from a polling loop.
     */
    static String acquireLaunchToken(String iloHost, boolean verifySsl, String username, String password) {
        if (!iloHost || !username || password == null) return null
        try {
            RedfishClient client = new RedfishClient(iloHost, verifySsl)
            client.login(username, password)
            String token = client.authToken
            // Intentionally do NOT call client.logout() — we want the session
            // to outlive this method so the user's click on the launch URL
            // arrives at a still-valid sessionkey.
            return token
        } catch (Throwable t) {
            log.warn("iLO ${iloHost}: acquireLaunchToken failed: ${t.message}")
            return null
        }
    }

    /** GET a Redfish path. Returns parsed JSON Map/List, or null on error. */
    Object getJson(String path) {
        if (!authToken) throw new IllegalStateException("not logged in")
        HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                headers: ['X-Auth-Token': authToken, 'Accept': 'application/json'],
                ignoreSSL: !verifySsl
        )
        def resp = http.callJsonApi(baseUrl(), path, opts, 'GET')
        if (resp == null || !resp.success) {
            log.debug("iLO ${iloHost} GET ${path} failed: ${resp?.errorCode}")
            return null
        }
        return resp.data
    }

    /**
     * v0.1.38 — PATCH a Redfish path with a JSON body. Returns a small
     * result Map: [success: boolean, errorCode: int?, errorMessage: String?].
     * Used by the UID indicator LED control: a PATCH to /Systems/1 with
     * {"IndicatorLED":"Blinking"} (or "Lit"/"Off") flips the front-panel
     * UID light so a datacenter tech can find the box.
     *
     * Designed to never throw on caller side — every failure mode collapses
     * into success=false with an errorMessage suitable for surfacing in
     * the rendered tab. We do NOT swallow exceptions inside callJsonApi
     * itself; that path is already defensive in the Morpheus HTTP client.
     *
     * v0.1.39 — `extraHeaders` lets callers add per-call headers (e.g.
     * `If-Match: *` to satisfy strict Redfish servers that require ETag
     * concurrency on PATCH). null/empty maps preserve v0.1.38 behavior.
     */
    Map patchJson(String path, Map body, Map<String, String> extraHeaders = null) {
        if (!authToken) {
            return [success: false, errorMessage: 'not logged in']
        }
        try {
            // Base headers — same set as v0.1.38. Caller-supplied extras
            // are merged on top so a clashing key wins from the caller.
            Map<String, String> headers = [
                    'X-Auth-Token': authToken,
                    'Content-Type': 'application/json',
                    'Accept'      : 'application/json'
            ]
            if (extraHeaders) headers.putAll(extraHeaders)
            HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                    headers    : headers,
                    body       : JsonOutput.toJson(body),
                    contentType: 'application/json',
                    ignoreSSL  : !verifySsl
            )
            def resp = http.callJsonApi(baseUrl(), path, opts, 'PATCH')
            if (resp == null) {
                return [success: false, errorMessage: 'null response from PATCH']
            }
            if (!resp.success) {
                // v0.1.39 — Pull the Redfish error MessageId out of the
                // response body when iLO returns one. Standard Redfish
                // error shape is:
                //   { "error": {
                //       "code": "Base.1.0.GeneralError",
                //       "message": "A general error has occurred...",
                //       "@Message.ExtendedInfo": [
                //         { "MessageId": "Base.1.0.PropertyNotWritable",
                //           "Message": "The property IndicatorLED is..." }
                //       ]
                //     }
                //   }
                // Extracting the MessageId is what turns "iLO returned 400"
                // (useless) into "iLO returned 400 (PropertyNotWritable)"
                // (immediately actionable — try a different property name).
                String redfishMsgId = null
                String redfishMsg   = null
                try {
                    def errData = resp.data
                    if (errData instanceof Map) {
                        def err = errData.error
                        if (err instanceof Map) {
                            def extInfo = err['@Message.ExtendedInfo']
                            if (extInfo instanceof List && extInfo.size() > 0) {
                                def first = extInfo[0]
                                if (first instanceof Map) {
                                    redfishMsgId = first.MessageId as String
                                    redfishMsg   = first.Message as String
                                }
                            }
                            if (!redfishMsg) redfishMsg = err.message as String
                        }
                    }
                } catch (Throwable ignored) {
                    // Body wasn't parseable Redfish error JSON — leave
                    // redfishMsgId null and let the HTTP code stand alone.
                }
                String baseMsg = resp.errorCode ? "iLO returned ${resp.errorCode}" : 'PATCH failed'
                String enriched = redfishMsgId ? "${baseMsg} (${redfishMsgId})" : baseMsg
                return [success         : false,
                        errorCode       : resp.errorCode,
                        errorMessage    : enriched,
                        redfishMessageId: redfishMsgId,
                        redfishMessage  : redfishMsg]
            }
            return [success: true]
        } catch (Throwable t) {
            log.warn("iLO ${iloHost} PATCH ${path} threw: ${t.message}")
            return [success: false, errorMessage: t.message ?: t.class.simpleName]
        }
    }

    /**
     * v0.1.38 — Set the front-panel UID indicator LED. Tries the
     * Systems/1 endpoint first (where HPE iLO 6/7 accepts PATCH on most
     * firmware versions) and falls back to Chassis/1 on 4xx — some
     * older iLO firmware exposes the IndicatorLED property on Chassis/1
     * only. `value` must be one of: 'Off', 'Lit', 'Blinking'.
     *
     * Returns the same [success, errorMessage] shape as patchJson so
     * callers can render a single inline banner from the result.
     *
     * v0.1.39 — extended to a six-step probe chain to rule out the three
     * non-RBAC failure modes we've seen in real environments:
     *
     *   1. PATCH /Systems/1 {IndicatorLED} — plain (v0.1.38 behavior).
     *   2. PATCH /Systems/1 {IndicatorLED} + `If-Match: *` — for iLO
     *      firmware that requires ETag concurrency on PATCH and rejects
     *      missing-If-Match as 403 (rather than the spec-correct 412).
     *   3. PATCH /Chassis/1 {IndicatorLED} — plain (v0.1.38 fallback).
     *   4. PATCH /Chassis/1 {IndicatorLED} + `If-Match: *`.
     *   5. PATCH /Systems/1 {LocationIndicatorActive} — DMTF's newer
     *      boolean replacement. iLO 6 1.5+/iLO 7 firmware has been
     *      observed to make IndicatorLED read-only and only accept
     *      writes via this property. Mapping: 'Off' → false, anything
     *      else → true. Loses Lit-vs-Blinking distinction.
     *   6. PATCH /Chassis/1 {LocationIndicatorActive}.
     *
     * v0.1.40 — extended further with two HPE OEM probe steps after
     * field reports of iLO 6 v1.74 returning iLO.2.37.PropertyNotWritableOrUnknown
     * on both DMTF properties. From HPE's iLO 6 changelog:
     *   "Added Oem.Hpe.IndicatorLED: ... This is a fallback added for
     *    clients that want to continue to use IndicatorLED."
     * In other words, on this firmware DMTF IndicatorLED is read-only,
     * LocationIndicatorActive also rejects writes, and HPE moved the
     * writable Lit/Blinking/Off property to Oem.Hpe.IndicatorLED. The
     * body shape is the standard nested-OEM form:
     *
     *   7. PATCH /Systems/1 {Oem: {Hpe: {IndicatorLED: value}}}.
     *   8. PATCH /Chassis/1 {Oem: {Hpe: {IndicatorLED: value}}}.
     *
     * The result Map carries an `attempts` list with each step's path,
     * If-Match flag, property name used, HTTP code, and Redfish
     * MessageId so the diagnostics row can show exactly what was tried
     * and why each step failed. The aggregate `errorMessage` carries a
     * tailored hint based on the dominant HTTP code and (for 400)
     * MessageId:
     *
     *   - 401 → "iLO session token expired" (rare; concurrent admin
     *     sessions).
     *   - 403 → "iLO returned 403 on all attempts — the iLO user
     *     account likely lacks 'Configure iLO Settings' privilege.
     *     See TROUBLESHOOTING.md → iLO user privileges."
     *   - 400 + PropertyNotWritable / iLO.*.PropertyNotWritableOrUnknown
     *     across ALL 8 attempts → "Property is genuinely unavailable
     *     for write on this hardware. File an issue."
     *   - 400 + PropertyValueNotInList → "The value '<value>' isn't
     *     accepted by this firmware. Some iLO 6 firmware supports only
     *     Off/Blinking — try a different button."
     *   - 404 → "IndicatorLED property not exposed on this firmware."
     *   - other → generic message + code.
     *
     * The result also carries `propertyUsed` ('IndicatorLED',
     * 'LocationIndicatorActive', or 'Oem.Hpe.IndicatorLED') so the
     * UI's optimistic-override path knows whether the badge should
     * reflect the requested Lit/Blinking faithfully (IndicatorLED and
     * Oem.Hpe.IndicatorLED both support all three values) or be
     * normalized (LocationIndicatorActive maps both Lit and Blinking
     * to a single 'true' state).
     */
    Map setIndicatorLed(String value) {
        // Whitelist defensively — never proxy arbitrary user input into
        // an iLO PATCH body.
        if (!(value in ['Off', 'Lit', 'Blinking'])) {
            return [success: false, errorMessage: "invalid UID state '${value}'"]
        }
        Map indicatorBody = [IndicatorLED: value]
        Boolean liaValue = (value != 'Off')
        Map liaBody = [LocationIndicatorActive: liaValue]
        // v0.1.40 — nested-OEM body shape per HPE iLO 6 Redfish docs.
        // The Oem/Hpe block sits at the top level of the resource so
        // the writable IndicatorLED at Oem.Hpe.IndicatorLED is reached
        // by PATCHing the parent resource with this nested structure.
        Map oemBody = [Oem: [Hpe: [IndicatorLED: value]]]
        Map<String, String> ifMatchStar = ['If-Match': '*']
        List attempts = []

        // Helper closure (param names path/withIfMatch/prop/resp deliberately
        // chosen to not collide with any enclosing-method local — see the
        // Groovy 3 sandbox-vs-runtime shadowing hazard documented in CHANGELOG).
        Closure recordAttempt = { String path, boolean withIfMatch, String prop, Map resp ->
            attempts << [
                    path            : path,
                    ifMatch         : withIfMatch,
                    property        : prop,
                    success         : (resp?.success ?: false),
                    errorCode       : resp?.errorCode,
                    errorMessage    : resp?.errorMessage,
                    redfishMessageId: resp?.redfishMessageId
            ]
        }

        // Step 1: /Systems/1, IndicatorLED, no If-Match.
        Map a1 = patchJson('/redfish/v1/Systems/1', indicatorBody)
        recordAttempt('/Systems/1', false, 'IndicatorLED', a1)
        if (a1.success) return [success: true, attempts: attempts, propertyUsed: 'IndicatorLED']

        // Step 2: /Systems/1, IndicatorLED, with If-Match: *.
        Map a2 = patchJson('/redfish/v1/Systems/1', indicatorBody, ifMatchStar)
        recordAttempt('/Systems/1', true, 'IndicatorLED', a2)
        if (a2.success) return [success: true, attempts: attempts, propertyUsed: 'IndicatorLED']

        // Step 3: /Chassis/1, IndicatorLED, no If-Match.
        Map a3 = patchJson('/redfish/v1/Chassis/1', indicatorBody)
        recordAttempt('/Chassis/1', false, 'IndicatorLED', a3)
        if (a3.success) return [success: true, attempts: attempts, propertyUsed: 'IndicatorLED']

        // Step 4: /Chassis/1, IndicatorLED, with If-Match: *.
        Map a4 = patchJson('/redfish/v1/Chassis/1', indicatorBody, ifMatchStar)
        recordAttempt('/Chassis/1', true, 'IndicatorLED', a4)
        if (a4.success) return [success: true, attempts: attempts, propertyUsed: 'IndicatorLED']

        // Step 5: /Systems/1, LocationIndicatorActive (DMTF newer property).
        Map a5 = patchJson('/redfish/v1/Systems/1', liaBody)
        recordAttempt('/Systems/1', false, 'LocationIndicatorActive', a5)
        if (a5.success) return [success: true, attempts: attempts, propertyUsed: 'LocationIndicatorActive']

        // Step 6: /Chassis/1, LocationIndicatorActive.
        Map a6 = patchJson('/redfish/v1/Chassis/1', liaBody)
        recordAttempt('/Chassis/1', false, 'LocationIndicatorActive', a6)
        if (a6.success) return [success: true, attempts: attempts, propertyUsed: 'LocationIndicatorActive']

        // Step 7: /Systems/1, Oem.Hpe.IndicatorLED — HPE OEM nested
        // property. This is where iLO 6 v1.74 actually keeps the
        // writable bit per HPE's own changelog. Body shape is
        // {Oem:{Hpe:{IndicatorLED:value}}}.
        Map a7 = patchJson('/redfish/v1/Systems/1', oemBody)
        recordAttempt('/Systems/1', false, 'Oem.Hpe.IndicatorLED', a7)
        if (a7.success) return [success: true, attempts: attempts, propertyUsed: 'Oem.Hpe.IndicatorLED']

        // Step 8: /Chassis/1, Oem.Hpe.IndicatorLED.
        Map a8 = patchJson('/redfish/v1/Chassis/1', oemBody)
        recordAttempt('/Chassis/1', false, 'Oem.Hpe.IndicatorLED', a8)
        if (a8.success) return [success: true, attempts: attempts, propertyUsed: 'Oem.Hpe.IndicatorLED']

        // All eight attempts failed. Pick the dominant HTTP code: if every
        // attempt returned the same code, that's the verdict. Otherwise
        // prefer the Systems/1 IndicatorLED result (a1), which is the
        // most common signal — Chassis/1 PATCH commonly 4xx's even on
        // healthy boxes that DO allow Systems/1 PATCH.
        Integer dominantCode = null
        Set codes = attempts.findAll { it.errorCode != null }.collect { it.errorCode as Integer } as Set
        if (codes.size() == 1) {
            dominantCode = codes.iterator().next() as Integer
        } else {
            dominantCode = (a1.errorCode ?: a2.errorCode ?: a3.errorCode ?: a4.errorCode ?: a5.errorCode ?: a6.errorCode ?: a7.errorCode ?: a8.errorCode) as Integer
        }

        // For 400 specifically, the Redfish MessageId is the most
        // informative signal. Walk the attempts and pick the first
        // non-null MessageId — they should all be the same when the
        // property/value is the issue, and different MessageIds across
        // attempts means a mixed firmware quirk we want to see in the
        // diagnostics row anyway.
        String msgId = null
        attempts.each { Map at ->
            if (msgId == null && at?.redfishMessageId) msgId = at.redfishMessageId as String
        }

        String hint
        switch (dominantCode) {
            case 401:
                hint = "iLO returned 401 — session token rejected. Retry, or check for concurrent iLO admin sessions."
                break
            case 403:
                hint = "iLO returned 403 on all attempts. Two common causes — both worth checking before changing iLO RBAC: (1) iLO concurrent session pressure can manifest as 403 on writes while reads still work — close stale console windows and check the iloSessions diagnostic row; (2) the iLO user may need higher privileges, but the specific privilege varies by firmware version. See TROUBLESHOOTING.md → iLO user privileges."
                break
            case 400:
                if (msgId && (msgId.contains('PropertyNotWritable') || msgId.contains('PropertyReadOnly') || msgId.contains('PropertyNotWritableOrUnknown'))) {
                    hint = "iLO returned 400 (${msgId}) on all 8 attempts (DMTF IndicatorLED, LocationIndicatorActive, AND HPE OEM Oem.Hpe.IndicatorLED). The UID control property is genuinely unavailable for write on this firmware/hardware. File a GitHub issue with the iLO version and the uidActionAttempts row."
                } else if (msgId && (msgId.contains('PropertyValueNotInList') || msgId.contains('PropertyValueNotInAllowableValues') || msgId.contains('PropertyValueTypeError'))) {
                    hint = "iLO returned 400 (${msgId}) — the value '${value}' isn't in the allowed list for this firmware. Some HPE iLO firmware supports only Off and Blinking (not Lit) — try a different button."
                } else if (msgId) {
                    hint = "iLO returned 400 (${msgId}) on all attempts. See TROUBLESHOOTING.md → UID change failed for known message-IDs, and the Diagnostics row uidActionAttempts for the full breakdown."
                } else {
                    hint = "iLO returned 400 on all attempts (no Redfish MessageId in the response body). Check Diagnostics → uidActionAttempts for the iLO error message."
                }
                break
            case 404:
                hint = "iLO returned 404 — none of IndicatorLED, LocationIndicatorActive, or Oem.Hpe.IndicatorLED is writable on this firmware/hardware combination."
                break
            case 405:
                hint = "iLO returned 405 — PATCH not allowed on this resource. Likely an iLO firmware quirk; please open a GitHub issue with the iLO version."
                break
            default:
                hint = (dominantCode ? "iLO returned ${dominantCode} on all attempts." : "PATCH failed on all attempts.")
        }

        return [success: false, errorMessage: hint, errorCode: dominantCode, attempts: attempts, redfishMessageId: msgId]
    }

    /**
     * Aggregator. Pulls the standard tab status set in a single logged-in
     * session and returns a Map ready to bind to the template. Always logs
     * out before returning.
     */
    Map collectStatus(String username, String password, String uidAction = null) {
        Map result = [success: false, error: null]
        try {
            login(username, password)

            // v0.1.46 — clean up stale sessions for our user before doing
            // any other work. Prevents session pool saturation from
            // repeated tab renders (each render's launch-token session
            // was designed to leak so the sessionkey stays valid for the
            // user's console launch — but stale ones from previous
            // renders accumulate until iLO's ~30-minute idle reap kicks
            // in). This runs after login so we have an auth token, and
            // before any other work so the pool is clean before any
            // reads or subsequent acquireLaunchToken create fresh
            // sessions. Only touches sessions for the same UserName we
            // authenticated as — other users' sessions (iLO web UI,
            // administrators, external integrations) are strictly
            // off-limits. Best-effort: cleanup errors don't abort the
            // render, they just get logged and surfaced in the
            // diagnostics row.
            result.sessionsCleanedUp = cleanupOwnStaleSessions(username)

            // v0.1.38 — if the user clicked an Off/Lit/Blinking button in
            // the UID cell, the click navigates to ?argusUidAction=<value>
            // and renderTemplate forwards that value here. Do the PATCH
            // BEFORE the bulk-read so the subsequent GET on /Systems/1
            // and /Chassis/1 reflects the new state. We also stash the
            // requested value so we can optimistically override the read
            // value below if the GETs lag iLO's internal commit by a
            // tick or two.
            if (uidAction) {
                Map uidResult = setIndicatorLed(uidAction)
                result.uidActionRequested = uidAction
                result.uidActionSuccess   = uidResult.success
                result.uidActionError     = uidResult.errorMessage
                // v0.1.39 — bubble up the per-attempt detail so diagnostics
                // can show which paths were tried (Systems/1 vs Chassis/1,
                // IndicatorLED vs LocationIndicatorActive) and whether
                // If-Match: * was used. Helps distinguish RBAC (every
                // attempt the same code) from firmware quirks (different
                // codes per path) and tells the badge-render block which
                // property carried the write through.
                result.uidActionAttempts     = uidResult.attempts
                result.uidActionPropertyUsed = uidResult.propertyUsed
                result.uidActionMessageId    = uidResult.redfishMessageId
                log.info("iLO ${iloHost}: UID PATCH ${uidAction} → success=${uidResult.success}${uidResult.errorMessage ? ' (' + uidResult.errorMessage + ')' : ''} — attempts: ${uidResult.attempts}")
            }

            def root = getJson('/redfish/v1/') ?: [:]
            def system = getJson('/redfish/v1/Systems/1') ?: [:]
            def manager = getJson('/redfish/v1/Managers/1') ?: [:]
            def thermal = getJson('/redfish/v1/Chassis/1/Thermal') ?: [:]

            result.success = true
            result.redfishVersion = root.RedfishVersion
            result.iloModel = manager.Model ?: 'iLO'
            // HPE iLO firmware string often begins with the model name (e.g.
            // "iLO 6 v1.74") — strip that to avoid duplicating it in the UI.
            String rawFw = manager.FirmwareVersion as String
            if (rawFw && result.iloModel && rawFw.toLowerCase().startsWith((result.iloModel as String).toLowerCase() + ' ')) {
                result.iloFirmware = rawFw.substring(((result.iloModel as String) + ' ').length()).trim()
            } else {
                result.iloFirmware = rawFw
            }
            // v0.1.29 — more from the /Managers/1 response
            result.iloDateTime = manager.DateTime                          // "2026-06-26T13:42:18Z"
            result.iloDateTimeOffset = manager.DateTimeLocalOffset         // "+00:00"
            // License is HPE-OEM. The exact path varies by iLO version; the
            // common shape is Oem.Hpe.License.{LicenseType, LicenseString}.
            def hpeOem = manager.Oem?.Hpe ?: [:]
            result.iloLicenseType = hpeOem.License?.LicenseType            // "iLO Advanced" / "iLO Standard" / etc.
            result.iloLicenseKey  = hpeOem.License?.LicenseKey
            result.iloLicenseExpire = hpeOem.License?.LicenseExpire
            result.powerState = system.PowerState
            result.health = system.Status?.HealthRollup ?: system.Status?.Health
            result.biosVersion = system.BiosVersion
            result.cpuCount = system.ProcessorSummary?.Count
            result.cpuModel = system.ProcessorSummary?.Model
            result.memoryGiB = system.MemorySummary?.TotalSystemMemoryGiB
            result.memoryHealth = system.MemorySummary?.Status?.HealthRollup
            result.serial = system.SerialNumber
            result.sku = system.SKU
            result.hostName = system.HostName

            // v0.1.29 — more data from the /Systems/1 response we already have
            result.bootProgress = system.BootProgress?.LastState           // "OSRunning" / "SystemHardwareInitializationComplete" / etc.
            result.bootSource   = system.Boot?.BootSourceOverrideTarget    // "None" / "Pxe" / "Hdd" / etc.
            result.bootSourceEnabled = system.Boot?.BootSourceOverrideEnabled
            // TrustedModules is a small array (usually 0-1 entries) per Redfish spec
            def tpms = (system.TrustedModules ?: [])
            def tpm = tpms.find { it.ModuleType }
            if (tpm) {
                result.tpmModuleType = tpm.ModuleType                       // "TPM2_0", "TPM1_2"
                result.tpmState = tpm.Status?.State                         // "Enabled" / "Disabled"
                result.tpmInterface = tpm.InterfaceType
            }

            // v0.1.32 — HPE OEM extensions on /Systems/1. v0.1.33: guarded
            // in its own try-catch — previously a malformed OEM payload could
            // throw and skip every block below, including all Chassis/Power/
            // Network/Drives reads.
            try {
                def hpeSystemOem = system.Oem?.Hpe ?: [:]
                // DeviceDiscoveryComplete tells us whether iLO has finished
                // probing all attached hardware. If this isn't
                // "vMainDeviceDiscoveryComplete", drives/storage may legitimately
                // not appear yet — surface it so the user knows to wait or reboot.
                result.deviceDiscovery = hpeSystemOem.DeviceDiscoveryComplete?.DeviceDiscovery
                result.amsStatus = hpeSystemOem.AggregateHealthStatus?.AgentlessManagementService?.Status?.Health
                // AggregateHealthStatus breaks the health rollup down by subsystem,
                // so when overall Health isn't "OK" we can show *which* subsystem
                // is reporting it. Each value is OK / Warning / Critical / null.
                def ahs = hpeSystemOem.AggregateHealthStatus ?: [:]
                result.healthBreakdown = [
                        biosOrHardware    : ahs.BiosOrHardwareHealth?.Status?.Health,
                        fans              : ahs.Fans?.Status?.Health,
                        fanRedundancy     : ahs.FanRedundancy?.Status?.Health,
                        memory            : ahs.Memory?.Status?.Health,
                        network           : ahs.Network?.Status?.Health,
                        powerSupplies     : ahs.PowerSupplies?.Status?.Health,
                        psuRedundancy     : ahs.PowerSupplyRedundancy?.Status?.Health,
                        processors        : ahs.Processors?.Status?.Health,
                        storage           : ahs.Storage?.Status?.Health,
                        temperatures      : ahs.Temperatures?.Status?.Health,
                        smartStorageBatt  : ahs.SmartStorageBattery?.Status?.Health
                ].findAll { k, v -> v != null }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} OEM AggregateHealthStatus read failed: ${t.message}")
            }

            // Thermal reads — temperatures + fans. v0.1.33: wrapped in
            // try-catch since `.sort { it.name as String }` previously threw
            // NPE on sensors with null Name, escaping past all subsequent
            // Chassis/Power/Network/Drives reads and emptying most of the tab.
            // Sort closures now coerce null names to empty string so the
            // comparator is stable across sparse iLO responses.
            try {
                def temps = (thermal.Temperatures ?: []).findAll {
                    it.Status?.State == 'Enabled' && it.ReadingCelsius != null
                }
                // v0.1.27 — keep the top-4 hottest for the Power & Cooling summary card
                result.temperatures = temps.collect {
                    [name: it.Name, c: it.ReadingCelsius, upperCrit: it.UpperThresholdCritical, upperWarn: it.UpperThresholdNonCritical]
                }.sort { -((it.c ?: 0) as Integer) }.take(4)
                // v0.1.32 — keep the full list for the "All Temperatures" collapsible
                result.allTemperatures = temps.collect {
                    [
                            name      : it.Name,
                            c         : it.ReadingCelsius,
                            upperWarn : it.UpperThresholdNonCritical,
                            upperCrit : it.UpperThresholdCritical,
                            physicalCtx: it.PhysicalContext,
                            health    : it.Status?.Health
                    ]
                }.sort { ((it.name ?: '') as String) }

                def fans = (thermal.Fans ?: []).findAll {
                    it.Status?.State == 'Enabled' && it.Reading != null
                }
                result.fans = fans.collect { [name: it.Name, pct: it.Reading, health: it.Status?.Health] }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Thermal read/parse failed: ${t.message}")
                result.temperatures = result.temperatures ?: []
                result.allTemperatures = result.allTemperatures ?: []
                result.fans = result.fans ?: []
            }

            // v0.1.27 — additional data groups. Each block is defensive so a
            // single missing endpoint doesn't blank the whole tab.

            // Power & PSU
            try {
                def power = getJson('/redfish/v1/Chassis/1/Power') ?: [:]
                def pc = (power.PowerControl ?: [])[0]
                if (pc != null) {
                    // v0.1.28: iLO 6 on entry-level ProLiant (e.g. MicroServer Gen11)
                    // sometimes reports PowerConsumedWatts: 0 even when the server is
                    // running — the firmware doesn't update the instant reading
                    // frequently. PowerMetrics.AverageConsumedWatts is usually
                    // accurate. Prefer the average when current reads 0.
                    Integer current = pc.PowerConsumedWatts as Integer
                    Integer average = pc.PowerMetrics?.AverageConsumedWatts as Integer
                    result.powerConsumedWatts = (current != null && current > 0) ? current : (average ?: current)
                    result.powerAvgWatts = average
                    result.powerCapacityWatts = pc.PowerCapacityWatts
                    result.powerLimitWatts = pc.PowerLimit?.LimitInWatts
                    // v0.1.32 — extended metrics: min/max over the iLO sampling
                    // interval, useful for capacity planning visibility.
                    result.powerMinWatts = pc.PowerMetrics?.MinConsumedWatts
                    result.powerMaxWatts = pc.PowerMetrics?.MaxConsumedWatts
                    result.powerIntervalMin = pc.PowerMetrics?.IntervalInMin
                }
                def psus = (power.PowerSupplies ?: []).findAll {
                    it.Status?.State == 'Enabled' || it.PowerSupplyType
                }
                result.psuCount = psus.size()
                result.psuHealthyCount = psus.count { it.Status?.Health == 'OK' }
                result.psuRedundancy = (power.Redundancy ?: [])[0]?.Status?.Health
                result.psuModel = psus[0]?.Model
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Power read failed: ${t.message}")
            }

            // Chassis: asset tag, SKU, indicator LED
            try {
                def chassis = getJson('/redfish/v1/Chassis/1') ?: [:]
                result.assetTag = chassis.AssetTag
                result.chassisSku = chassis.SKU
                // v0.1.38 — read IndicatorLED from Chassis/1 first, then
                // fall back to Systems/1. Different HPE firmware versions
                // populate one or the other; defender (iLO 6 Gen11) only
                // reports it on Systems/1, which is why v0.1.37's read
                // (Chassis only) silently dropped the cell from the
                // System card.
                //
                // v0.1.39 — additional fallback to LocationIndicatorActive
                // (DMTF newer boolean property). iLO 6 1.74+ on Gen11
                // populates this instead of IndicatorLED on some firmware
                // revisions, which is why v0.1.38's badge showed "—"
                // (em-dash = Unknown) even with a healthy UID system. We
                // map true → 'Lit' (we can't distinguish Lit from
                // Blinking from a boolean) and false → 'Off', and stash
                // which property we ended up reading from in
                // indicatorLedSource so diagnostics can show it.
                //
                // v0.1.40 — final fallback to Oem.Hpe.IndicatorLED. HPE
                // documents this OEM property as the canonical read-AND-
                // write property on modern iLO 6 firmware where the
                // DMTF top-level IndicatorLED is read-only and may not
                // even be populated. Try this last so we prefer DMTF
                // standard properties when they're available — but use
                // it when both are absent. Lit/Blinking/Off values
                // round-trip faithfully.
                String iLed = (chassis.IndicatorLED ?: system.IndicatorLED) as String
                if (iLed && !iLed.equalsIgnoreCase('Unknown')) {
                    result.indicatorLed       = iLed
                    result.indicatorLedSource = 'IndicatorLED'
                } else {
                    // Boolean fallback. .containsKey check distinguishes
                    // "property present, value=false" from "property
                    // missing entirely" — we only want the fallback to
                    // take effect when the new property is actually
                    // present, otherwise we'd misreport every box that
                    // has neither property as 'Off'.
                    Boolean lia = null
                    if (chassis instanceof Map && (chassis as Map).containsKey('LocationIndicatorActive')) {
                        lia = (chassis as Map).LocationIndicatorActive as Boolean
                    } else if (system instanceof Map && (system as Map).containsKey('LocationIndicatorActive')) {
                        lia = (system as Map).LocationIndicatorActive as Boolean
                    }
                    if (lia != null) {
                        result.indicatorLed       = lia ? 'Lit' : 'Off'
                        result.indicatorLedSource = 'LocationIndicatorActive'
                    } else {
                        // v0.1.40 — Oem.Hpe.IndicatorLED.
                        def chOem = (chassis instanceof Map) ? (chassis as Map).Oem : null
                        def chHpe = (chOem instanceof Map) ? (chOem as Map).Hpe : null
                        def chHpeLed = (chHpe instanceof Map) ? (chHpe as Map).IndicatorLED : null
                        def sysOem = (system instanceof Map) ? (system as Map).Oem : null
                        def sysHpe = (sysOem instanceof Map) ? (sysOem as Map).Hpe : null
                        def sysHpeLed = (sysHpe instanceof Map) ? (sysHpe as Map).IndicatorLED : null
                        String oemLed = (chHpeLed ?: sysHpeLed) as String
                        if (oemLed && !oemLed.equalsIgnoreCase('Unknown') && !oemLed.equalsIgnoreCase('Null')) {
                            result.indicatorLed       = oemLed
                            result.indicatorLedSource = 'Oem.Hpe.IndicatorLED'
                        } else {
                            result.indicatorLed       = iLed   // null or 'Unknown'
                            result.indicatorLedSource = null
                        }
                    }
                }
                // After a successful PATCH the GET above can lag iLO's
                // internal commit by a tick. Override optimistically so
                // the user sees the requested state immediately and
                // doesn't double-click thinking nothing happened.
                //
                // v0.1.39 — when the PATCH succeeded via LocationIndicatorActive
                // (boolean — no Lit/Blinking distinction), normalize the
                // optimistic override too: requested 'Lit' or 'Blinking'
                // both display as 'Lit' on the badge, because iLO has no
                // way to confirm which one we got.
                if (result.uidActionSuccess && result.uidActionRequested) {
                    String requested = result.uidActionRequested as String
                    String prop = result.uidActionPropertyUsed as String
                    if (prop == 'LocationIndicatorActive' && requested == 'Blinking') {
                        // Map Blinking → Lit for the badge when we can't
                        // tell them apart from the property's boolean type.
                        result.indicatorLed = 'Lit'
                    } else {
                        result.indicatorLed = requested
                    }
                    // v0.1.40 — keep the diagnostics source consistent
                    // with the property that carried the write through.
                    if (prop) result.indicatorLedSource = prop
                }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Chassis read failed: ${t.message}")
            }

            // Host network — first enabled EthernetInterface with a useful field
            try {
                def ethColl = getJson('/redfish/v1/Systems/1/EthernetInterfaces') ?: [:]
                def members = ethColl.Members ?: []
                for (def member : members) {
                    String memberPath = member?.'@odata.id' as String
                    if (!memberPath) continue
                    def eth = getJson(memberPath)
                    if (eth == null) continue
                    String mac = eth.MACAddress
                    def ipv4Addresses = eth.IPv4Addresses ?: []
                    String ip = ipv4Addresses.find { it.Address }?.Address
                    if (ip || (mac && eth.LinkStatus == 'LinkUp')) {
                        result.hostMac = mac
                        result.hostIp = ip
                        result.hostLink = eth.LinkStatus
                        result.hostSpeedMbps = eth.SpeedMbps
                        break
                    } else if (mac && !result.hostMac) {
                        result.hostMac = mac
                        result.hostLink = eth.LinkStatus
                    }
                }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} EthernetInterfaces read failed: ${t.message}")
            }

            // v0.1.28: iLO's own management network (its dedicated/shared NIC)
            try {
                def iloEthColl = getJson('/redfish/v1/Managers/1/EthernetInterfaces') ?: [:]
                def iloMembers = iloEthColl.Members ?: []
                for (def member : iloMembers) {
                    String memberPath = member?.'@odata.id' as String
                    if (!memberPath) continue
                    def eth = getJson(memberPath)
                    if (eth == null) continue
                    // Prefer the interface that's actually carrying traffic
                    def ip = (eth.IPv4Addresses ?: []).find { it.Address }?.Address
                    if (ip && !result.iloIp) {
                        result.iloMac = eth.MACAddress
                        result.iloIp = ip
                        result.iloHostName = eth.HostName ?: eth.FQDN
                        result.iloFqdn = eth.FQDN
                        break
                    } else if (eth.MACAddress && !result.iloMac) {
                        result.iloMac = eth.MACAddress
                        result.iloHostName = eth.HostName ?: eth.FQDN
                    }
                }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} iLO EthernetInterfaces read failed: ${t.message}")
            }

            // Drive enumeration. iLO 6 deprecated the legacy SmartStorage OEM
            // path entirely; the canonical place to look is the DMTF Redfish
            // Storage model. But entry-level ProLiant Gen11 (e.g. MicroServer
            // Gen11) without a Smart Array controller doesn't always populate
            // /Systems/1/Storage — direct-attached SATA via chipset AHCI
            // requires HPE's Agentless Management Service (AMS) running on
            // the host OS to be visible, and NVMe drives may appear under
            // additional chassis entries that RDE storage devices register
            // (e.g. /Chassis/DE040000/Drives/0).
            //
            // Lookup chain (each step runs only if drives is still empty):
            //   1. /Systems/1/Storage/<n>/Drives/<n>           (modern controller)
            //   2. /Systems/1/SimpleStorage/<n>/Devices/<n>    (legacy)
            //   3. /Chassis/1/Drives                           (direct chassis collection)
            //   4. /Chassis/1.Links.Drives                     (chassis Links array)
            //   5. /Chassis collection walk → each chassis Drives + Links.Drives
            //      (catches RDE devices that register their own chassis IDs)
            //
            // The drivesDiag map records what each step returned so the user
            // can see in Diagnostics whether iLO is enumerating drives at all.
            Map drivesDiag = [:]
            try {
                List drives = []

                // (1) Modern: /Systems/1/Storage → controllers → Drives[]
                def storageColl = getJson('/redfish/v1/Systems/1/Storage') ?: [:]
                int sysStorageMembers = (storageColl.Members ?: []).size()
                drivesDiag['sysStorageMembers'] = sysStorageMembers
                (storageColl.Members ?: []).each { storMember ->
                    String storPath = storMember?.'@odata.id' as String
                    if (!storPath) return
                    def stor = getJson(storPath)
                    if (stor == null) return
                    (stor.Drives ?: []).each { driveRef ->
                        String drivePath = driveRef?.'@odata.id' as String
                        if (!drivePath) return
                        def drive = getJson(drivePath)
                        if (drive == null) return
                        drives << buildDriveEntry(drive)
                    }
                }
                drivesDiag['afterSysStorage'] = drives.size()

                // (2) Legacy: /SimpleStorage
                if (drives.isEmpty()) {
                    def simpleColl = getJson('/redfish/v1/Systems/1/SimpleStorage') ?: [:]
                    int simpleMembers = (simpleColl.Members ?: []).size()
                    drivesDiag['simpleStorageMembers'] = simpleMembers
                    (simpleColl.Members ?: []).each { ssMember ->
                        String ssPath = ssMember?.'@odata.id' as String
                        if (!ssPath) return
                        def ss = getJson(ssPath)
                        if (ss == null) return
                        (ss.Devices ?: []).each { dev ->
                            Long bytes = dev.CapacityBytes as Long
                            drives << [
                                    model     : dev.Model,
                                    serial    : null,
                                    mediaType : null,
                                    protocol  : null,
                                    capacityGB: bytes ? (bytes / 1_000_000_000L) as Long : null,
                                    health    : dev.Status?.Health,
                                    state     : dev.Status?.State,
                                    failurePredicted: null
                            ]
                        }
                    }
                    drivesDiag['afterSimpleStorage'] = drives.size()
                }

                // (3) Direct chassis Drives collection: /Chassis/1/Drives
                if (drives.isEmpty()) {
                    def chDrives = getJson('/redfish/v1/Chassis/1/Drives')
                    int chDrivesMembers = (chDrives?.Members ?: []).size()
                    drivesDiag['chassis1DrivesMembers'] = chDrivesMembers
                    (chDrives?.Members ?: []).each { dRef ->
                        String dPath = dRef?.'@odata.id' as String
                        if (!dPath) return
                        def drive = getJson(dPath)
                        if (drive == null) return
                        drives << buildDriveEntry(drive)
                    }
                    drivesDiag['afterChassis1Drives'] = drives.size()
                }

                // (4) Chassis Links.Drives[] array (some firmwares put refs here)
                if (drives.isEmpty()) {
                    def chassis = getJson('/redfish/v1/Chassis/1')
                    int chLinkDrives = (chassis?.Links?.Drives ?: []).size()
                    drivesDiag['chassis1LinksDrives'] = chLinkDrives
                    (chassis?.Links?.Drives ?: []).each { dRef ->
                        String dPath = dRef?.'@odata.id' as String
                        if (!dPath) return
                        def drive = getJson(dPath)
                        if (drive == null) return
                        drives << buildDriveEntry(drive)
                    }
                    drivesDiag['afterChassis1Links'] = drives.size()
                }

                // (5) Walk all chassis entries (RDE devices register their own)
                if (drives.isEmpty()) {
                    def chassisColl = getJson('/redfish/v1/Chassis') ?: [:]
                    List allChassis = (chassisColl.Members ?: []) as List
                    drivesDiag['chassisCollectionMembers'] = allChassis.size()
                    allChassis.each { cRef ->
                        String cPath = cRef?.'@odata.id' as String
                        if (!cPath || cPath.endsWith('/Chassis/1') || cPath.endsWith('/Chassis/1/')) return
                        def ch = getJson(cPath)
                        if (ch == null) return
                        // Try Drives collection on this chassis
                        def chDriveColl = getJson("${cPath}/Drives".toString())
                        (chDriveColl?.Members ?: []).each { dRef ->
                            String dPath = dRef?.'@odata.id' as String
                            if (!dPath) return
                            def drive = getJson(dPath)
                            if (drive == null) return
                            drives << buildDriveEntry(drive)
                        }
                        // Try Links.Drives on this chassis too
                        (ch.Links?.Drives ?: []).each { dRef ->
                            String dPath = dRef?.'@odata.id' as String
                            if (!dPath) return
                            def drive = getJson(dPath)
                            if (drive == null) return
                            drives << buildDriveEntry(drive)
                        }
                    }
                    drivesDiag['afterChassisCollection'] = drives.size()
                }

                result.drives = drives
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Drives read failed: ${t.message}")
                drivesDiag['exception'] = t.message
            }
            result.drivesDiag = drivesDiag

            // Recent IML events (Integrated Management Log) — last 5.
            // v0.1.28: try with OData $top first (efficient on supported firmware),
            // fall back to fetching the full Members list and taking the tail.
            // Some iLO 6 firmware doesn't honor OData filters on the IML endpoint.
            try {
                def iml = getJson('/redfish/v1/Managers/1/LogServices/IML/Entries?$top=5')
                if (iml == null || !(iml.Members)) {
                    iml = getJson('/redfish/v1/Managers/1/LogServices/IML/Entries')
                }
                def members = (iml?.Members ?: []) as List
                // IML entries are usually returned chronologically — take the last 5
                def tail = members.size() > 5 ? members.subList(members.size() - 5, members.size()) : members
                def entries = tail.reverse().collect { e ->
                    [
                            created : e.Created,
                            severity: e.Severity,
                            message : e.Message
                    ]
                }
                result.recentEvents = entries
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} IML read failed: ${t.message}")
            }

            // v0.1.29: per-DIMM detail. Walks /Systems/1/Memory and fetches each
            // member. iLO 6 returns ~16-24 slots even on small servers (most
            // empty), so we filter to populated DIMMs only.
            try {
                def memColl = getJson('/redfish/v1/Systems/1/Memory') ?: [:]
                List dimms = []
                (memColl.Members ?: []).each { mRef ->
                    String mPath = mRef?.'@odata.id' as String
                    if (!mPath) return
                    def dimm = getJson(mPath)
                    if (dimm == null) return
                    Integer cap = dimm.CapacityMiB as Integer
                    if (cap == null || cap == 0) return  // empty slot — skip
                    dimms << [
                            slot         : dimm.DeviceLocator ?: dimm.Id,
                            capacityGiB  : cap ? (cap / 1024) as Integer : null,
                            speedMHz     : dimm.OperatingSpeedMhz,
                            manufacturer : dimm.Manufacturer,
                            partNumber   : dimm.PartNumber,
                            type         : dimm.MemoryDeviceType,
                            health       : dimm.Status?.Health,
                            state        : dimm.Status?.State
                    ]
                }
                result.dimms = dimms
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Memory detail read failed: ${t.message}")
            }

            // v0.1.29: active iLO sessions (HPE OEM). Tells you who else is
            // logged into this iLO right now — useful for "is the console
            // already in use?" before launching IRC.
            //
            // v0.1.41 — also capture the total session count INCLUDING our
            // own. iLO 6 caps concurrent sessions at ~13 across all clients
            // (web UI, IRC console, REST API, sessionkey launches); writes
            // start failing with misleading PropertyNotWritableOrUnknown
            // errors before reads do when the pool is saturated. The total
            // count surfaces this pressure in the Diagnostics row before
            // it triggers cryptic UID PATCH failures.
            try {
                def sessColl = getJson('/redfish/v1/SessionService/Sessions') ?: [:]
                List sessionsMembers = (sessColl.Members ?: []) as List
                result.totalSessionCount = sessionsMembers.size()
                List sessions = []
                sessionsMembers.each { sRef ->
                    String sPath = sRef?.'@odata.id' as String
                    if (!sPath) return
                    def sess = getJson(sPath)
                    if (sess == null) return
                    // Skip the session we just created for this status pull
                    // (its UserName equals the credential we logged in with).
                    if (sess.'@odata.id' == sessionLocation) return
                    sessions << [
                            userName : sess.UserName,
                            sourceIp : sess.ClientOriginIPAddress ?: sess.Oem?.Hpe?.MySession?.ClientOriginIp,
                            sessionType: sess.Oem?.Hpe?.MySession?.SessionType ?: 'Web'
                    ]
                }
                result.activeSessions = sessions
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Sessions read failed: ${t.message}")
            }

            // v0.1.32: firmware inventory — a summary of the firmware versions
            // iLO knows about (System BIOS, iLO itself, network adapters, etc.).
            // Useful for "is this server up to date" without needing to deep-dive
            // into iLO's own UI. From the standard /UpdateService/FirmwareInventory
            // collection, but iLO populates it with HPE-specific component names.
            try {
                def fwColl = getJson('/redfish/v1/UpdateService/FirmwareInventory') ?: [:]
                List components = []
                (fwColl.Members ?: []).each { fwRef ->
                    String fwPath = fwRef?.'@odata.id' as String
                    if (!fwPath) return
                    def fw = getJson(fwPath)
                    if (fw == null) return
                    components << [
                            name    : fw.Name,
                            version : fw.Version,
                            updateable : fw.Updateable
                    ]
                }
                result.firmwareInventory = components
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} FirmwareInventory read failed: ${t.message}")
            }

            // v0.1.34 — Power Trend from /Chassis/1/EnvironmentMetrics +
            // /Chassis/1/Power/PowerMeter.
            //
            // EnvironmentMetrics (DMTF) gives the current PowerWatts reading
            // plus an optional ReadingRangeMin/Max. PowerMeter (HPE OEM under
            // /Chassis/1/Power) is where iLO 6 puts the historical sample
            // array — a rolling buffer of per-minute or per-five-minute
            // PowerConsumed averages over the last 24h. Together they fill
            // the Power Trend card; either alone is enough to render the
            // gauge half.
            //
            // Gen11 MicroServer typically returns Reading=null on both
            // endpoints (the hardware tier doesn't track power), in which
            // case powerTrend ends up with all-null fields and the
            // ServerTabProvider renders the "no history available" empty
            // state.
            try {
                Map trend = [
                        current     : null,
                        units       : 'W',
                        history     : [],
                        min         : null,
                        max         : null,
                        avg         : null,
                        intervalMin : null,
                        source      : null
                ]
                // EnvironmentMetrics — current + range
                try {
                    def em = getJson('/redfish/v1/Chassis/1/EnvironmentMetrics')
                    if (em != null) {
                        def pw = em.PowerWatts ?: [:]
                        if (pw.Reading != null) {
                            trend.current = pw.Reading as Integer
                            trend.source = 'EnvironmentMetrics'
                        }
                        if (pw.ReadingRangeMin != null) trend.min = pw.ReadingRangeMin as Integer
                        if (pw.ReadingRangeMax != null) trend.max = pw.ReadingRangeMax as Integer
                    }
                } catch (Throwable t) {
                    log.debug("iLO ${iloHost} EnvironmentMetrics read failed: ${t.message}")
                }
                // PowerMeter — historical samples. HPE OEM extension under
                // /Chassis/1/Power/PowerMeter on iLO 5, plus a peer endpoint
                // /Chassis/1/Power/FastPowerMeter that carries the same
                // schema on iLO 5 (~20 minute high-res).
                //
                // v0.1.44 — added FastPowerMeter probe fallback for
                // firmware that only populates one of the two URIs.
                //
                // v0.1.45 — property name fix. iLO 5 uses:
                //   { Samples: [{Time, Average, Maximum}, ...] }
                // iLO 6 / iLO 7 renamed the array to `PowerDetail` with
                // richer fields per entry:
                //   { PowerDetail: [{Time, Average, Peak, Minimum,
                //                    CpuWatts, DimmWatts, GpuWatts,
                //                    SharedFanWatts}, ...] }
                // Reference: iLO 7 changelog HpePowerMeter.v2_1_0 →
                // v2_2_0 adds PowerDetail[{item}].SharedFanWatts;
                // iLO 6 v1.68 resource map lists the same HpePowerMeter
                // type at both /PowerMeter and /FastPowerMeter URIs.
                //
                // Behavior: try PowerDetail first (iLO 6/7, current fleet);
                // fall back to Samples (iLO 5, still supported). Map
                // Peak→trend max on iLO 6/7, Maximum→trend max on iLO 5.
                // Capture Minimum from PowerDetail entries when present
                // (iLO 5 didn't emit this in Samples).
                //
                // Diagnostic capture: each probe records its URL and the
                // integer sample count it returned (or -1 for HTTP null).
                // Both probes are always attempted for the diagnostics row
                // so future firmware variance is visible in one render
                // without redeploying.
                List meterProbes = []
                Closure<Map> readMeter = { String path, String sourceLabel ->
                    try {
                        def pm = getJson(path)
                        if (pm == null) {
                            meterProbes << [path: path, count: -1]
                            return null
                        }
                        // v0.1.46 — capture top-level keys of the response
                        // so the diagnostics row can show what iLO actually
                        // returned. Same forensics pattern that unblocked
                        // the settings and UID chains: when the caller sees
                        // 0 samples we want to see the response body's
                        // shape, not guess. Filter out @odata.* metadata
                        // keys since those aren't informative and eat
                        // display width.
                        List topKeys = null
                        if (pm instanceof Map) {
                            topKeys = ((pm as Map).keySet().collect { it as String })
                                    .findAll { !(it as String).startsWith('@') } as List
                        }
                        // v0.1.45 — PowerDetail (iLO 6/7) preferred over
                        // Samples (iLO 5). If both are populated (shouldn't
                        // happen but be defensive), take whichever is
                        // longer, which is likely PowerDetail on iLO 6/7.
                        //
                        // v0.1.46 — explicit instanceof checks instead of
                        // relying on Groovy truthy semantics. v0.1.45 threw
                        // `java.lang.Integer.isEmpty()` on this codepath —
                        // one of the two Redfish properties resolved to a
                        // non-List type on iLO 6 v1.76 (likely an integer
                        // count companion property or a nested object),
                        // and the downstream `if (pd && sm)` truthy check
                        // invoked `.isEmpty()` on it. instanceof-and-cast
                        // is rock solid: if the value is anything other
                        // than a List, we treat it as absent. The concrete
                        // shape doesn't matter to us; we only care about
                        // the array of history entries.
                        def rawPd = null
                        def rawSm = null
                        if (pm instanceof Map) {
                            rawPd = (pm as Map).get('PowerDetail')
                            rawSm = (pm as Map).get('Samples')
                        }
                        List pd = (rawPd instanceof List) ? (rawPd as List) : Collections.emptyList()
                        List sm = (rawSm instanceof List) ? (rawSm as List) : Collections.emptyList()
                        // Prefer PowerDetail (iLO 6/7) if it has entries.
                        // Fall back to Samples (iLO 5) if PowerDetail is
                        // empty but Samples isn't. If both empty, entries
                        // stays empty and we return null via the guard.
                        List entries
                        if (!pd.isEmpty()) {
                            entries = pd
                        } else if (!sm.isEmpty()) {
                            entries = sm
                        } else {
                            entries = Collections.emptyList()
                        }
                        meterProbes << [path: path, count: entries.size(), topKeys: topKeys]
                        if (entries.isEmpty()) return null
                        int keep = Math.min(entries.size(), 60)
                        List tail = entries.subList(entries.size() - keep, entries.size())
                        List hist = tail.collect { entry ->
                            // v0.1.46 — same instanceof-and-cast pattern
                            // for entries. If a sample entry isn't a Map
                            // (shouldn't happen but be defensive), skip
                            // its property accesses so this closure can't
                            // trip Groovy's dynamic method dispatch on an
                            // unexpected type.
                            if (!(entry instanceof Map)) return null
                            Map s = entry as Map
                            [
                                    ts  : s.get('Time') as String,
                                    avg : (s.get('Average') != null ? (s.get('Average') as Integer) : null),
                                    // v0.1.45 — Peak (iLO 6/7) supersedes
                                    // Maximum (iLO 5); check both so this
                                    // works across the fleet.
                                    max : (s.get('Peak')    != null ? (s.get('Peak')    as Integer)
                                            : (s.get('Maximum') != null ? (s.get('Maximum') as Integer) : null)),
                                    // v0.1.45 — Minimum only exists on
                                    // PowerDetail; null on iLO 5 Samples.
                                    min : (s.get('Minimum') != null ? (s.get('Minimum') as Integer) : null)
                            ]
                        }.findAll { it != null && (it.avg != null || it.max != null) }
                        if (hist.isEmpty()) return null
                        return [history: hist, source: sourceLabel]
                    } catch (Throwable t) {
                        log.debug("iLO ${iloHost} ${sourceLabel} read failed: ${t.message}")
                        meterProbes << [path: path, count: -1, error: (t.message ?: t.class.simpleName)]
                        return null
                    }
                }
                Map meterResult = readMeter('/redfish/v1/Chassis/1/Power/PowerMeter', 'PowerMeter')
                if (meterResult == null) {
                    // Fallback for firmware that only populates the
                    // 20-minute FastPowerMeter (all Gen11+ on iLO 6 v1.76
                    // in field testing).
                    meterResult = readMeter('/redfish/v1/Chassis/1/Power/FastPowerMeter', 'FastPowerMeter')
                }
                trend.meterProbes = meterProbes
                if (meterResult != null) {
                    List hist = meterResult.history as List
                    trend.history = hist
                    // Sample-derived stats — only set if we don't have
                    // them from EnvironmentMetrics already. v0.1.45 uses
                    // per-entry Minimum when PowerDetail is the source,
                    // otherwise falls back to min-of-avgs like before.
                    List avgs = hist.collect { ((it as Map).avg ?: (it as Map).max) as Integer }.findAll { it != null }
                    if (avgs) {
                        List mins = hist.collect { (it as Map).min as Integer }.findAll { it != null }
                        if (trend.min == null) trend.min = (mins ? mins.min() : avgs.min()) as Integer
                        if (trend.max == null) trend.max = avgs.max() as Integer
                        trend.avg = ((avgs.sum() as Integer) / avgs.size()) as Integer
                    }
                    trend.source = trend.source ? "${trend.source}+${meterResult.source}" : (meterResult.source as String)
                }
                // Cross-fill from the existing /Power read so the card has
                // *something* useful even if EnvironmentMetrics and PowerMeter
                // are both empty.
                if (trend.current == null && result.powerConsumedWatts != null) {
                    trend.current = result.powerConsumedWatts as Integer
                }
                if (trend.min == null && result.powerMinWatts != null) trend.min = result.powerMinWatts as Integer
                if (trend.max == null && result.powerMaxWatts != null) trend.max = result.powerMaxWatts as Integer
                if (trend.avg == null && result.powerAvgWatts != null) trend.avg = result.powerAvgWatts as Integer
                if (trend.intervalMin == null && result.powerIntervalMin != null) trend.intervalMin = result.powerIntervalMin as Integer
                result.powerTrend = trend
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} PowerTrend block failed: ${t.message}")
                result.powerTrend = [current:null, units:'W', history:[], min:null, max:null, avg:null, intervalMin:null, source:null]
            }

            // v0.1.34 — Network Adapters walk.
            //
            // /Chassis/1/NetworkAdapters is the modern DMTF collection. Each
            // NetworkAdapter resource carries Model/Manufacturer/Serial/Part/
            // Firmware.Current.VersionString, plus references to:
            //   - NetworkPorts (older schema, the iLO 6 1.x norm)
            //   - Ports (newer schema, iLO 7+)
            // We try NetworkPorts first since that's what iLO 6 1.74 publishes.
            //
            // Per-port fields:
            //   - LinkStatus: "LinkUp"/"LinkDown"/"NoLink"/"Starting"
            //   - CurrentLinkSpeedMbps (or LinkSpeedMbps on some firmware)
            //   - ActiveLinkTechnology: "Ethernet" usually
            //   - AssociatedNetworkAddresses: [MAC, ...] (array, port may have multiple)
            //   - Oem.Hpe.PortHealth: HPE port-level health
            //   - Oem.Hpe.LinkStatus / Oem.Hpe.PortStatus: HPE-specific status
            //   - PhysicalPortNumber: physical port index
            //
            // Both NetworkAdapters card AND NICPortLED card consume this data —
            // we walk once, render twice (NIC LED card filters to a compact
            // per-port view focused on health/link rather than full detail).
            try {
                def naColl = getJson('/redfish/v1/Chassis/1/NetworkAdapters') ?: [:]
                List adapters = []
                (naColl.Members ?: []).each { naRef ->
                    String naPath = naRef?.'@odata.id' as String
                    if (!naPath) return
                    def na = getJson(naPath)
                    if (na == null) return
                    // Adapter-level fields
                    String fwVer = na.Firmware?.Current?.VersionString ?: na.FirmwarePackageVersion
                    Map adapter = [
                            id          : na.Id,
                            name        : na.Name,
                            model       : na.Model,
                            manufacturer: na.Manufacturer,
                            serial      : na.SerialNumber,
                            partNumber  : na.PartNumber,
                            firmware    : fwVer,
                            sku         : na.SKU,
                            health      : na.Status?.Health,
                            state       : na.Status?.State,
                            ports       : []
                    ]
                    // Determine ports collection URL — NetworkPorts first
                    // (iLO 6 1.x), Ports second (Redfish 2022+). Both fields
                    // are odata-references to a Collection.
                    String portsPath = (na.NetworkPorts?.'@odata.id' as String) ?: (na.Ports?.'@odata.id' as String)
                    if (portsPath) {
                        def portsColl = getJson(portsPath)
                        (portsColl?.Members ?: []).each { pRef ->
                            String pPath = pRef?.'@odata.id' as String
                            if (!pPath) return
                            def p = getJson(pPath)
                            if (p == null) return
                            // MAC: AssociatedNetworkAddresses is the canonical
                            // location on NetworkPort; on newer "Ports" it's
                            // Ethernet.AssociatedMACAddresses. Try both.
                            List macs = (p.AssociatedNetworkAddresses ?: p.Ethernet?.AssociatedMACAddresses ?: []) as List
                            // Speed: NetworkPort uses CurrentLinkSpeedMbps; older firmware
                            // sometimes uses LinkSpeedMbps; the newer Ports schema reports
                            // CurrentSpeedGbps. Walk in that priority order.
                            Integer speedMbps = null
                            if (p.CurrentLinkSpeedMbps != null) {
                                speedMbps = p.CurrentLinkSpeedMbps as Integer
                            } else if (p.LinkSpeedMbps != null) {
                                speedMbps = p.LinkSpeedMbps as Integer
                            } else if (p.CurrentSpeedGbps != null) {
                                speedMbps = ((p.CurrentSpeedGbps as Integer) * 1000) as Integer
                            }
                            def portOem = p.Oem?.Hpe ?: [:]
                            // v0.1.36 — capture WWPN for FibreChannel/SAS HBA ports.
                            // Format varies by firmware:
                            //   iLO 6:  p.FibreChannel?.WWPN  or  p.Oem?.Hpe?.WWPN
                            //   iLO 5:  p.FibreChannel?.WWPN
                            // SAS HBAs may not report WWPN at all.
                            String wwpn = null
                            try {
                                wwpn = (p.FibreChannel?.WWPN as String)?.trim() ?:
                                        (portOem?.WWPN as String)?.trim() ?:
                                        null
                            } catch (Throwable ignored) {}
                            adapter.ports << [
                                    id               : p.Id,
                                    name             : p.Name,
                                    portNumber       : p.PhysicalPortNumber ?: p.PortId,
                                    linkStatus       : p.LinkStatus,
                                    speedMbps        : speedMbps,
                                    activeTech       : p.ActiveLinkTechnology,
                                    macs             : macs.collect { it?.toString() }.findAll { it },
                                    wwpn             : wwpn,
                                    health           : p.Status?.Health,
                                    state            : p.Status?.State,
                                    // HPE OEM extensions — drive the NIC LED card
                                    hpePortHealth    : portOem.PortHealth,
                                    hpeLinkStatus    : portOem.LinkStatus,
                                    hpePortStatus    : portOem.PortStatus,
                                    hpeFlexLOM       : portOem.FlexLOM,
                                    hpeNicCapacity   : portOem.NicCapacity
                            ]
                        }
                        // Sort ports by physical port number / id — iLO often
                        // returns them in random order, which makes for an
                        // ugly UI table.
                        adapter.ports = adapter.ports.sort { ((it.portNumber ?: it.id ?: '') as String) }
                    }
                    adapters << adapter
                }
                // Sort adapters by name so the UI ordering is stable across
                // refreshes (Members order from iLO isn't guaranteed stable).
                result.networkAdapters = adapters.sort { ((it.name ?: it.id ?: '') as String) }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} NetworkAdapters read failed: ${t.message}")
                result.networkAdapters = []
            }

            // v0.1.34 — Volumes / RAID walk.
            //
            // Walks /Systems/1/Storage and for each controller fetches the
            // Volumes collection. On Gen11 MicroServer without a Smart Array
            // controller, /Systems/1/Storage returns zero members and this
            // block produces an empty list — the card then doesn't render.
            //
            // Per-volume fields from the DMTF Volume resource:
            //   - Id, Name, DisplayName (HPE OEM sometimes)
            //   - RAIDType: "RAID0"/"RAID1"/"RAID5"/"RAID6"/"RAID10"/"RAID50"/"RAID60"
            //   - CapacityBytes
            //   - Status.Health / Status.State
            //   - Links.Drives[]: list of refs to constituent drives → count = stripe width
            //   - Operations[]: in-progress operations (rebuild, init, expand) with PercentComplete
            //   - WriteCachePolicy, ReadCachePolicy (HPE OEM)
            //   - Encrypted (HPE OEM)
            try {
                List volumes = []
                def storColl = getJson('/redfish/v1/Systems/1/Storage') ?: [:]
                (storColl.Members ?: []).each { storMember ->
                    String storPath = storMember?.'@odata.id' as String
                    if (!storPath) return
                    def stor = getJson(storPath)
                    if (stor == null) return
                    // Controller name for grouping in the UI
                    String ctlName = null
                    def storCtls = (stor.StorageControllers ?: stor.Controllers ?: []) as List
                    if (storCtls) {
                        ctlName = storCtls[0]?.Name ?: storCtls[0]?.Model
                    }
                    if (!ctlName) ctlName = (stor.Name ?: stor.Id) as String
                    // Volumes collection — usually $stor.Volumes['@odata.id'],
                    // sometimes just $storPath + '/Volumes'.
                    String volPath = (stor.Volumes?.'@odata.id' as String) ?: "${storPath}/Volumes".toString()
                    def volColl = getJson(volPath)
                    (volColl?.Members ?: []).each { vRef ->
                        String vPath = vRef?.'@odata.id' as String
                        if (!vPath) return
                        def v = getJson(vPath)
                        if (v == null) return
                        Long capBytes = v.CapacityBytes as Long
                        Long capGB = capBytes ? (capBytes / 1_000_000_000L) as Long : null
                        // Drive count = stripe width. Links.Drives is an array
                        // of refs; we just count it rather than walking.
                        int driveCount = (v.Links?.Drives ?: []).size()
                        // In-progress operation, if any
                        def op = (v.Operations ?: [])[0]
                        String opName = op?.OperationName as String
                        Integer opPct = op?.PercentComplete as Integer
                        def volOem = v.Oem?.Hpe ?: [:]
                        volumes << [
                                id          : v.Id,
                                name        : v.Name ?: v.DisplayName ?: volOem.LogicalDriveName,
                                raidType    : v.RAIDType ?: volOem.Raid,
                                capacityGB  : capGB,
                                health      : v.Status?.Health,
                                state       : v.Status?.State,
                                driveCount  : driveCount,
                                opName      : opName,
                                opPct       : opPct,
                                controller  : ctlName,
                                encrypted   : (v.Encrypted == true) || (volOem.LogicalDriveEncryption == true),
                                writeCache  : v.WriteCachePolicy ?: volOem.WriteCachePolicy,
                                readCache   : v.ReadCachePolicy ?: volOem.ReadCachePolicy,
                                bootable    : volOem.BootVolume
                        ]
                    }
                }
                result.volumes = volumes.sort { ((it.controller ?: '') as String) + ((it.name ?: it.id ?: '') as String) }
            } catch (Throwable t) {
                log.debug("iLO ${iloHost} Volumes read failed: ${t.message}")
                result.volumes = []
            }
        } catch (Throwable t) {
            log.warn("iLO ${iloHost} status collection failed: ${t.message}", t)
            // v0.1.33: record the partial-failure info but DO NOT flip success
            // to false if we already got the core system reads — partial data
            // is better than no data, and we surface the error in Diagnostics.
            // If the very first reads (login + Systems/1) failed, success is
            // still false at this point and the error tab renders normally.
            result.partialError = "${t.class.simpleName}: ${t.message}".toString()
            if (!result.success) {
                result.error = t.message
            }
        } finally {
            logout()
        }
        return result
    }

    /**
     * Normalize a drive entry from any of the shapes we walk:
     *   - Standard Redfish /Systems/1/Storage/<n>/Drives/<n> (Drive resource)
     *   - HPE OEM /Systems/1/SmartStorage/.../PhysicalDrives/<n>
     *     (uses CapacityGB/CapacityMiB instead of CapacityBytes,
     *      "InterfaceType" and "MediaType" instead of Protocol/MediaType)
     *   - /Chassis/<id>/Drives/<n> (Drive resource — same shape as Storage path,
     *     used by RDE storage devices that register their own chassis IDs)
     *
     * v0.1.33: enriched to capture HPE OEM extension fields documented in the
     * iLO 6 v1.77 storage resource definitions — drive temperature, lifetime
     * power-on hours, NVMe identifier, predicted media life, bay/slot info
     * via Location[], encryption status, drive form factor, and the richer
     * Oem.Hpe.DriveStatus (separate from base Status, sometimes more accurate).
     */
    private static Map buildDriveEntry(def d) {
        Long capacityGB = null
        if (d.CapacityBytes) {
            capacityGB = ((d.CapacityBytes as Long) / 1_000_000_000L) as Long
        } else if (d.CapacityGB) {
            capacityGB = d.CapacityGB as Long
        } else if (d.CapacityMiB) {
            // 1 MiB = 1048576 bytes; convert to GB (decimal) to match other paths
            capacityGB = (((d.CapacityMiB as Long) * 1_048_576L) / 1_000_000_000L) as Long
        }
        String protocol  = (d.Protocol as String) ?: (d.InterfaceType as String)
        // HPE iLO 6 publishes a richer status under Oem.Hpe.DriveStatus —
        // prefer it when present since the base Status.Health is sometimes
        // a roll-up that hides specific drive problems.
        def hpeOem = d.Oem?.Hpe ?: [:]
        String healthFromOem = hpeOem.DriveStatus?.Health
        String stateFromOem  = hpeOem.DriveStatus?.State
        // Location[] is an array; the most useful entry's Info field
        // describes where the drive physically sits (e.g. "Box 1 Bay 3",
        // "Bay 5", "Slot 21" for NVMe AICs).
        String locationInfo = null
        def loc = (d.Location ?: [])[0]
        if (loc?.Info) {
            locationInfo = loc.Info as String
        } else if (d.PhysicalLocation?.PartLocation?.ServiceLabel) {
            // Standard Redfish location fallback
            locationInfo = d.PhysicalLocation.PartLocation.ServiceLabel as String
        }
        return [
                model           : d.Model,
                serial          : d.SerialNumber,
                mediaType       : d.MediaType as String,
                protocol        : protocol,
                capacityGB      : capacityGB,
                health          : healthFromOem ?: (d.Status?.Health),
                state           : stateFromOem  ?: (d.Status?.State),
                failurePredicted: d.FailurePredicted,
                // v0.1.33 enrichments
                tempC           : hpeOem.CurrentTemperatureCelsius,
                tempHealth      : hpeOem.TemperatureStatus?.Health,
                powerOnHours    : hpeOem.PowerOnHours,
                nvmeId          : hpeOem.NVMeId,
                healthUpdateMode: hpeOem.HealthUpdated,            // "Boot" or "Dynamic"
                location        : locationInfo,
                formFactor      : d.DriveFormFactor,               // "M2_2280", "Drive2_5", "EDSFF_E3_Short", ...
                encryptionStatus: d.EncryptionStatus,              // "Unencrypted" / "Unlocked" / "Locked"
                lifeLeftPct     : d.PredictedMediaLifeLeftPercent, // 100 = pristine, lower = worn
                hotspareType    : d.HotspareType                   // "None" / "Global" / "Chassis" / "Dedicated"
        ]
    }

    private static Map<String, String> normalizeHeaders(def raw) {
        Map<String, String> out = [:]
        if (raw == null) return out
        if (raw instanceof Map) {
            raw.each { k, v ->
                String key = k.toString().toLowerCase()
                if (v instanceof List) out[key] = v[0]?.toString()
                else out[key] = v?.toString()
            }
        } else if (raw instanceof List) {
            raw.each { entry ->
                if (entry instanceof Map.Entry) {
                    out[entry.key.toString().toLowerCase()] = entry.value?.toString()
                }
            }
        }
        return out
    }
}
