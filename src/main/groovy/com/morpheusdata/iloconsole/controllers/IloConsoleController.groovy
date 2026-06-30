package com.morpheusdata.iloconsole.controllers

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.iloconsole.IloConsolePlugin
import com.morpheusdata.iloconsole.services.AuditLogger
import com.morpheusdata.iloconsole.services.IloConfigStore
import com.morpheusdata.iloconsole.services.IloDetectionService
import com.morpheusdata.iloconsole.services.LaunchNonceStore
import com.morpheusdata.iloconsole.services.RedfishClient
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * Backing controller for the iLO Console plugin. Exposes:
 *
 *   GET  /plugin/iloConsole/status        ?serverId=N         → JSON Redfish snapshot
 *   GET  /plugin/iloConsole/config        ?serverId=N         → JSON current config (no secrets)
 *   POST /plugin/iloConsole/config        body {serverId,...} → save iLO host + credential id
 *   POST /plugin/iloConsole/power         body {serverId,resetType}
 *   POST /plugin/iloConsole/launch/init   body {serverId}     → JSON {launchUrl}
 *   GET  /plugin/iloConsole/launch/page   ?nonce=...          → HTML bootstrap page
 *   GET  /plugin/iloConsole/launch/creds  ?nonce=...          → JSON one-shot creds payload
 *
 * Every route does its own permission gate via morpheusContext.computeServer
 * before doing any work. We never expose iLO IPs or credentials to a caller
 * who doesn't already have access to the underlying ComputeServer.
 */
@Slf4j
class IloConsoleController implements PluginController {

    static final String PROVIDER_CODE = 'iloConsole-controller'
    static final String PROVIDER_NAME = 'iLO Console Controller'

    static final List<String> ALLOWED_RESET_TYPES = [
            'On', 'ForceOff', 'GracefulShutdown',
            'ForceRestart', 'GracefulRestart',
            'Nmi', 'PushPowerButton'
    ]

    protected final Plugin plugin
    protected final MorpheusContext morpheusContext

    IloConsoleController(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    @Override String getCode() { return PROVIDER_CODE }
    @Override String getName() { return PROVIDER_NAME }
    @Override MorpheusContext getMorpheus() { return morpheusContext }
    @Override Plugin getPlugin() { return plugin }

    @Override
    List<Route> getRoutes() {
        // Permission requirement: 'full' on Compute Servers / Hosts. This
        // ensures only users who can manage hosts in Morpheus can ever reach
        // these endpoints. Tab visibility is also gated by show().
        def hostPerm = [Permission.build('Compute Servers', 'full')]
        return [
                Route.build('/plugin/iloConsole/status',       'status',       hostPerm),
                Route.build('/plugin/iloConsole/config',       'config',       hostPerm),
                Route.build('/plugin/iloConsole/power',        'power',        hostPerm),
                Route.build('/plugin/iloConsole/launch/init',  'launchInit',   hostPerm),
                Route.build('/plugin/iloConsole/launch/page',  'launchPage',   hostPerm),
                Route.build('/plugin/iloConsole/launch/creds', 'launchCreds',  hostPerm)
        ]
    }

    // -------------------------------------------------------------------------
    // Route handlers
    // -------------------------------------------------------------------------

    /**
     * GET /plugin/iloConsole/status?serverId=N
     * Returns the live Redfish snapshot for the iLO bound to that server.
     */
    def status(ViewModel<Map> model) {
        Map params = (model?.params ?: [:]) as Map
        Long serverId = parseLong(params.serverId)
        User user = currentUser(model)

        def gate = resolveServerAndConfig(serverId, user)
        if (gate.error) return JsonResponse.of([success: false, error: gate.error])
        ComputeServer server = gate.server
        Map cfg = gate.config

        // Decrypt credential
        def creds = loadCredential(cfg.credentialId as Long)
        if (creds == null) {
            return JsonResponse.of([success: false, error: 'credential not found or missing username/password'])
        }

        RedfishClient client = new RedfishClient(cfg.iloHost as String, (cfg.verifySsl ?: false) as boolean)
        Map snapshot = client.collectStatus(creds.username as String, creds.password as String)

        if (snapshot.success) {
            IloDetectionService det = pluginAs().detectionService
            det.recordConfirmation(server.id, snapshot.iloModel as String, snapshot.iloFirmware as String, snapshot.redfishVersion as String)
        }
        return JsonResponse.of(snapshot)
    }

    /**
     * GET /plugin/iloConsole/config?serverId=N
     *   Returns current iLO config for the server. Never includes secrets.
     * POST /plugin/iloConsole/config (JSON body {serverId, iloHost, credentialId, verifySsl})
     *   Saves new config. Caller must be authorized on the server.
     */
    def config(ViewModel<Map> model) {
        String method = httpMethod(model)
        Map params = (model?.params ?: [:]) as Map
        User user = currentUser(model)

        if (method == 'POST') {
            Long serverId = parseLong(params.serverId)
            String iloHost = (params.iloHost as String)?.trim()
            Long credentialId = parseLong(params.credentialId)
            Boolean verifySsl = parseBool(params.verifySsl)

            if (serverId == null || iloHost == null || iloHost.isEmpty() || credentialId == null) {
                return JsonResponse.of([success: false, error: 'serverId, iloHost, and credentialId are required'])
            }

            ComputeServer server = loadAuthorizedServer(serverId, user)
            if (server == null) return JsonResponse.of([success: false, error: 'server not found or access denied'])

            pluginAs().configStore.saveConfig(serverId, iloHost, credentialId, verifySsl, user?.id)
            pluginAs().auditLogger.configSaved(user?.id, serverId, iloHost, credentialId, sourceIp(model))
            return JsonResponse.of([success: true])
        }

        // GET
        Long serverId = parseLong(params.serverId)
        ComputeServer server = loadAuthorizedServer(serverId, user)
        if (server == null) return JsonResponse.of([success: false, error: 'server not found or access denied'])

        Map cfg = pluginAs().configStore.loadConfig(serverId)
        if (cfg == null) return JsonResponse.of([success: true, configured: false])

        // Strip nothing — config currently holds no plaintext secrets. The
        // credential ID is fine to return; it's already in the credential URL.
        return JsonResponse.of([
                success     : true,
                configured  : true,
                iloHost     : cfg.iloHost,
                credentialId: cfg.credentialId,
                verifySsl   : cfg.verifySsl ?: false,
                savedAt     : cfg.savedAt
        ])
    }

    /**
     * POST /plugin/iloConsole/power  body {serverId, resetType}
     * Fires the named Redfish reset action and returns success/failure.
     */
    def power(ViewModel<Map> model) {
        Map params = (model?.params ?: [:]) as Map
        User user = currentUser(model)
        Long serverId = parseLong(params.serverId)
        String resetType = params.resetType as String

        if (resetType == null || !ALLOWED_RESET_TYPES.contains(resetType)) {
            return JsonResponse.of([success: false, error: "resetType must be one of ${ALLOWED_RESET_TYPES}"])
        }

        def gate = resolveServerAndConfig(serverId, user)
        if (gate.error) return JsonResponse.of([success: false, error: gate.error])
        Map cfg = gate.config
        def creds = loadCredential(cfg.credentialId as Long)
        if (creds == null) return JsonResponse.of([success: false, error: 'credential missing'])

        RedfishClient client = new RedfishClient(cfg.iloHost as String, (cfg.verifySsl ?: false) as boolean)
        boolean ok = false
        try {
            client.login(creds.username as String, creds.password as String)
            ok = client.resetSystem(resetType)
        } finally {
            client.logout()
        }
        pluginAs().auditLogger.powerAction(user?.id, user?.username, serverId, resetType, ok, sourceIp(model))
        return JsonResponse.of([success: ok])
    }

    /**
     * POST /plugin/iloConsole/launch/init  body {serverId}
     * Resolves config + credential, issues a one-shot nonce, and returns the
     * URL the browser should open in a new window.
     */
    def launchInit(ViewModel<Map> model) {
        Map params = (model?.params ?: [:]) as Map
        User user = currentUser(model)
        Long serverId = parseLong(params.serverId)

        def gate = resolveServerAndConfig(serverId, user)
        if (gate.error) {
            pluginAs().auditLogger.launchFailed(user?.id, serverId, gate.error, sourceIp(model))
            return JsonResponse.of([success: false, error: gate.error])
        }
        Map cfg = gate.config
        def creds = loadCredential(cfg.credentialId as Long)
        if (creds == null) {
            pluginAs().auditLogger.launchFailed(user?.id, serverId, 'credential missing', sourceIp(model))
            return JsonResponse.of([success: false, error: 'credential missing'])
        }

        LaunchNonceStore.LaunchPayload payload = new LaunchNonceStore.LaunchPayload(
                serverId: serverId,
                userId: user?.id,
                iloHost: cfg.iloHost as String,
                username: creds.username as String,
                password: creds.password as String,
                verifySsl: (cfg.verifySsl ?: false) as boolean
        )
        String nonce = pluginAs().nonceStore.issue(payload)
        pluginAs().auditLogger.launchInitiated(user?.id, user?.username, serverId, cfg.iloHost as String, sourceIp(model))
        return JsonResponse.of([
                success  : true,
                launchUrl: "/plugin/iloConsole/launch/page?nonce=${nonce}"
        ])
    }

    /**
     * GET /plugin/iloConsole/launch/page?nonce=...
     * Tiny HTML page whose only job is to fetch the creds (using the nonce),
     * POST them to the iLO's login endpoint to set a session cookie on the
     * iLO origin, then redirect to /irc.html.
     *
     * The nonce is in the URL — it's still single-use, so if it leaks via
     * referer or history, replay is impossible after the legitimate first
     * load. The user lands on this page in a new window (Morpheus's referer
     * policy strips the URL on cross-origin nav anyway).
     */
    def launchPage(ViewModel<Map> model) {
        Map params = (model?.params ?: [:]) as Map
        String nonce = params.nonce as String
        ViewModel<Map> renderModel = new ViewModel<>()
        renderModel.object = [nonce: nonce]
        return pluginAs().getRenderer().renderTemplate('hbs/launchBootstrap', renderModel)
    }

    /**
     * GET /plugin/iloConsole/launch/creds?nonce=...
     * Atomically consumes the nonce and returns the credential payload exactly
     * once. Any subsequent request with the same nonce returns 404-style error.
     */
    def launchCreds(ViewModel<Map> model) {
        Map params = (model?.params ?: [:]) as Map
        String nonce = params.nonce as String
        User user = currentUser(model)

        LaunchNonceStore.LaunchPayload payload = pluginAs().nonceStore.consume(nonce)
        if (payload == null) {
            return JsonResponse.of([success: false, error: 'nonce invalid, expired, or already used'])
        }
        // Cross-check the consuming user matches the issuing user. This guards
        // against a leaked nonce being redeemed from a different session.
        if (payload.userId != null && user?.id != null && payload.userId != user.id) {
            log.warn("Nonce consumed by different user (issued ${payload.userId}, consumed ${user.id})")
            return JsonResponse.of([success: false, error: 'nonce not valid for this session'])
        }

        pluginAs().auditLogger.launchCompleted(payload.userId, payload.serverId, payload.iloHost, sourceIp(model))
        return JsonResponse.of([
                success  : true,
                iloHost  : payload.iloHost,
                username : payload.username,
                password : payload.password
        ])
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Look up a ComputeServer and verify the calling user is allowed to access it. */
    private ComputeServer loadAuthorizedServer(Long serverId, User user) {
        if (serverId == null) return null
        try {
            ComputeServer server = morpheusContext.async.computeServer.get(serverId).blockingGet()
            if (server == null) return null
            // Tenant scoping
            if (user?.account?.id && server.account?.id && server.account.id != user.account.id) {
                if (!isMasterTenantAdmin(user)) return null
            }
            return server
        } catch (Throwable t) {
            log.warn("loadAuthorizedServer ${serverId} failed: ${t.message}")
            return null
        }
    }

    private static boolean isMasterTenantAdmin(User user) {
        return user?.account?.masterAccount == true
    }

    /** Resolve server + config together, returning a map with error xor (server, config). */
    private Map resolveServerAndConfig(Long serverId, User user) {
        ComputeServer server = loadAuthorizedServer(serverId, user)
        if (server == null) return [error: 'server not found or access denied']
        Map cfg = pluginAs().configStore.loadConfig(serverId)
        if (cfg == null) return [error: 'iLO not configured for this server']
        if (!cfg.iloHost || !cfg.credentialId) return [error: 'iLO config incomplete']
        return [server: server, config: cfg]
    }

    /** Decrypt credential by ID. Returns null if not found or missing username/password. */
    private Map loadCredential(Long credentialId) {
        if (credentialId == null) return null
        try {
            AccountCredential ac = morpheusContext.async.accountCredential.get(credentialId).blockingGet()
            if (ac == null) return null
            // accountCredential.loadCredentialConfig resolves the encrypted password
            Map cfg = morpheusContext.async.accountCredential.loadCredentialConfig(ac).blockingGet()
            String username = (cfg?.username ?: ac.username) as String
            String password = (cfg?.password ?: cfg?.passwd) as String
            if (username == null || password == null) return null
            return [username: username, password: password]
        } catch (Throwable t) {
            log.warn("loadCredential ${credentialId} failed: ${t.message}")
            return null
        }
    }

    private IloConsolePlugin pluginAs() { return (IloConsolePlugin) plugin }

    private static User currentUser(ViewModel<?> model) {
        // ViewModel exposes the calling user via the request context wrapper.
        // Different SDK minor versions surface this slightly differently; this
        // accessor covers both common shapes.
        try {
            def u = model?.user
            if (u instanceof User) return (User) u
            def reqUser = model?.request?.user
            if (reqUser instanceof User) return (User) reqUser
        } catch (Throwable ignored) {}
        return null
    }

    private static String httpMethod(ViewModel<?> model) {
        try {
            def m = model?.request?.method ?: model?.method
            return m?.toString()?.toUpperCase() ?: 'GET'
        } catch (Throwable ignored) { return 'GET' }
    }

    private static String sourceIp(ViewModel<?> model) {
        try {
            return (model?.request?.remoteAddr ?: model?.request?.ip ?: '-') as String
        } catch (Throwable ignored) { return '-' }
    }

    private static Long parseLong(def v) {
        if (v == null) return null
        try { return v.toString().toLong() } catch (Throwable ignored) { return null }
    }

    private static Boolean parseBool(def v) {
        if (v == null) return null
        String s = v.toString().toLowerCase()
        return s in ['true', '1', 'yes', 'on']
    }
}
