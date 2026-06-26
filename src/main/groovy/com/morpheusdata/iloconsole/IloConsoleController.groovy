package com.morpheusdata.iloconsole

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.iloconsole.services.IloConfigStore
import com.morpheusdata.iloconsole.services.IloCredentialService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * Plugin controller for iLO Console launch endpoints.
 *
 * Routes (registered relative to the appliance root):
 *   GET /iloConsole/launch?serverId=N  → returns the launcher HTML (auto-submits
 *                                         form to iLO /json/login_session in a
 *                                         hidden iframe, then redirects the popup
 *                                         to /html5/irc.html once the cookie is set).
 *
 * Security model (v0.1.22):
 *   - Route is gated by the custom plugin permission "ilo-console", declared on
 *     IloConsolePlugin via setPermissions(). Master tenant admin role typically
 *     gets this automatically when the plugin loads. Other roles need explicit
 *     grant under Administration → Roles → [role] → Plugin Access → iLO Console.
 *   - Previous attempts (0.1.16-0.1.21) referenced built-in role codes
 *     ("admin-cm", "compute-cm", "admin") that aren't valid in the plugin-route
 *     permission namespace, and all returned 403.
 *   - Tenant scoping: server.account.id must match request user's account, OR the
 *     user must be a master-tenant admin. Master-tenant detection uses account.masterAccount.
 *   - Audit: every launch attempt is logged via SLF4J at INFO with user + server + result.
 *   - Password never appears in any URL — it's inlined into the launcher HTML which is
 *     served only to this authenticated user over HTTPS. Password is HTML-escaped to
 *     prevent layout breakage and JS-escaped for the form value.
 */
@Slf4j
class IloConsoleController implements PluginController {

    Plugin plugin
    MorpheusContext morpheusContext

    IloConsoleController(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    @Override String getCode()              { 'ilo-console-controller' }
    @Override String getName()              { 'iLO Console Controller' }
    @Override Plugin getPlugin()            { plugin }
    @Override MorpheusContext getMorpheus() { morpheusContext }

    @Override
    List<Route> getRoutes() {
        // v0.1.22: pass a LIST of permissions (not a single Permission), and
        // reference the "ilo-console" permission that IloConsolePlugin declares
        // via setPermissions(). This is the pattern from two confirmed-working
        // example plugins (wabbas-morpheus/morpheus-plugins CatalogsController
        // and martezr/morpheus-datadog-instance-tab-plugin). The Plugin docs
        // example with a single Permission and the built-in "admin" code was
        // wrong/incomplete — plugin route permissions live in their own
        // namespace and must be declared by the plugin.
        def p = { String path, String method ->
            Route.build(path, method, [Permission.build("ilo-console", "full")])
        }
        [
                p("/iloConsole/launch", "launch")
        ]
    }

    /**
     * GET /iloConsole/launch?serverId=N
     *
     * Returns either:
     *   - launcher HTML (auto-submits form to iLO and redirects)
     *   - error HTML with explanation
     */
    def launch(ViewModel<Map> model) {
        Long serverId = null
        try { serverId = model?.request?.getParameter('serverId') as Long } catch (Throwable ignored) {}
        if (!serverId) return errorPage("Missing required parameter: serverId")

        // ── Resolve the server ────────────────────────────────────────────
        ComputeServer server = null
        try {
            def svc = morpheusContext.services?.computeServer
            java.lang.reflect.Method m = svc?.class?.methods?.find {
                it.name == 'listById' && it.parameterCount == 1
            }
            if (m != null) {
                Object result = m.invoke(svc, [serverId])
                if (result instanceof Collection && !((Collection) result).isEmpty()) {
                    Object first = ((Collection) result).iterator().next()
                    if (first instanceof ComputeServer) server = first as ComputeServer
                }
            }
        } catch (Throwable t) {
            log.warn("launch: resolving server ${serverId} threw: ${t.message}", t)
        }
        if (!server) return errorPage("Server ${serverId} not found")

        // ── Tenant scoping ────────────────────────────────────────────────
        User user = null
        try { user = model?.user as User } catch (Throwable ignored) {}
        if (user == null) {
            log.warn("launch: no user in model — refusing")
            return errorPage("Unauthorized")
        }
        boolean masterTenantAdmin = false
        try { masterTenantAdmin = (user.account?.masterAccount == true) } catch (Throwable ignored) {}
        if (!masterTenantAdmin && server.account?.id != user.account?.id) {
            log.warn("AUDIT iloConsole event=launch.deny reason=tenant " +
                    "user=${user.username} serverId=${serverId} serverAccount=${server.account?.id} userAccount=${user.account?.id}")
            return errorPage("Access denied: this server belongs to a different tenant")
        }

        // ── Load iLO config from server labels ────────────────────────────
        IloConfigStore configStore = ((IloConsolePlugin) plugin).configStore
        Map cfg = configStore.loadConfig(server)
        if (!cfg.configured) {
            return errorPage("Server '${server.name}' has no iLO host/credential configured. " +
                    "Add labels <code>ilo-host:&lt;ip&gt;</code> and <code>ilo-cred:&lt;id&gt;</code>.")
        }

        // ── Resolve credentials ───────────────────────────────────────────
        Map creds = IloCredentialService.loadCredential(morpheusContext, cfg.credentialId as Long)
        if (creds?.error) {
            log.warn("AUDIT iloConsole event=launch.deny reason=cred user=${user.username} " +
                    "serverId=${serverId} error=${creds.error}")
            return errorPage("Credential lookup failed: ${creds.error}")
        }

        log.info("AUDIT iloConsole event=launch.ok user=${user.username} serverId=${serverId} " +
                "serverName=${server.name} iloHost=${cfg.iloHost} credentialId=${cfg.credentialId}")

        // ── Build the launcher HTML ───────────────────────────────────────
        return HTMLResponse.success(buildLauncherHtml(
                cfg.iloHost as String,
                creds.username as String,
                creds.password as String,
                server.name as String
        ))
    }

    /**
     * Auto-submitting login form. POSTs to /json/login_session in a hidden iframe
     * so the response (which is JSON) doesn't replace the page; when the iframe
     * finishes loading, the iLO session cookie is set on the iLO's origin, and we
     * navigate the top window to /irc.html.
     *
     * v0.1.21 design — degrades gracefully:
     *   - JS runs + cookie sets + we read iframe-load → auto-navigate (one-click)
     *   - JS runs + iframe never loads → show manual fallback after 6s timeout
     *   - JS blocked (CSP) → manual Connect button + Open Console link are visible
     *     from the start (default state); user clicks Connect, then Open Console
     *   - Cookie blocked (SameSite) → iLO shows its login form; user falls back to
     *     the Morpheus tab's "Show credentials" panel for copy/paste
     *
     * iLO 5/6 compatible. The console path is `/irc.html`. iLO 4 differs
     * (`/html5/keyboard.html`) and would need a vendor/firmware check; out of
     * scope for this iteration.
     */
    private static String buildLauncherHtml(String iloHost, String username, String password, String serverName) {
        String safeHost = jsString(iloHost)
        String safeUser = jsString(username)
        String safePass = jsString(password)
        String safeName = htmlEscape(serverName)
        String safeHostHtml = htmlEscape(iloHost)
        String safeUserHtml = htmlEscape(username)
        String safePassHtml = htmlEscape(password)

        return """\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>iLO Console — ${safeName}</title>
<meta name="referrer" content="no-referrer">
<style>
  html, body { margin:0; padding:0; min-height:100%; background:#0b1218; color:#dbe6ef; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif; }
  .wrap { display:flex; flex-direction:column; align-items:center; padding:40px 20px; text-align:center; }
  h1 { margin:0 0 8px; font-size:18px; font-weight:500; }
  .sub { color:#7a8a98; font-size:13px; margin-bottom:32px; }
  .spinner { width:48px; height:48px; border:3px solid rgba(255,255,255,0.08); border-top-color:#01A982; border-radius:50%; animation:spin 0.9s linear infinite; margin-bottom:20px; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .target { font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; color:#01A982; font-size:14px; }
  .btn { display:inline-block; padding:10px 22px; border-radius:3px; font-size:13px; font-weight:600; text-decoration:none; cursor:pointer; border:0; font-family:inherit; }
  .btn-primary { background:#01A982; color:#fff; }
  .btn-primary:hover { background:#018a6a; }
  .btn-secondary { background:transparent; border:1px solid rgba(255,255,255,0.2); color:#dbe6ef; padding:9px 21px; }
  .btn-secondary:hover { border-color:#01A982; }
  .row { display:flex; gap:12px; justify-content:center; margin-top:16px; flex-wrap:wrap; }
  .note { color:#7a8a98; font-size:11px; margin-top:32px; max-width:520px; line-height:1.6; }
  .fail { color:#d9534f; }
  .fail a { color:#d9534f; }
  .creds { background:rgba(255,255,255,0.02); border:1px solid rgba(1,169,130,0.3); border-radius:4px; padding:12px 16px; margin-top:24px; font-size:12px; }
  .creds .row-kv { display:flex; gap:24px; justify-content:center; flex-wrap:wrap; }
  .creds code { background:rgba(255,255,255,0.06); padding:3px 10px; border-radius:3px; font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; user-select:all; -webkit-user-select:all; cursor:text; }
  /* When JS runs successfully, it adds .js-active to <html>. We hide the manual UI in that case until the timeout fires. */
  html.js-active #manual-area { display:none; }
  html.js-active.js-fallback #manual-area { display:block; }
  html.js-active #status-text { display:block; }
  html.js-active #initial-help { display:none; }
</style>
</head>
<body>

<div class="wrap">

  <div class="spinner" id="spinner"></div>
  <h1>Connecting to iLO</h1>
  <div class="sub"><span class="target">${safeName}</span> · <span class="target">${safeHostHtml}</span></div>

  <div id="status-text" style="display:none;">Authenticating…</div>

  <!-- Hidden iframe receives the JSON login response. Sandboxed: allow-forms (so the form
       can target it) and allow-same-origin (so iLO's own cookies can land on its origin). -->
  <iframe name="lframe" id="lframe" style="display:none" sandbox="allow-forms allow-same-origin"></iframe>

  <!-- The login form. JS will .submit() this on load if it can run. If JS is blocked,
       the user sees the form's submit button below and clicks it themselves. -->
  <form id="lf" method="POST" action="https://${safeHostHtml}/json/login_session" target="lframe" autocomplete="off" style="margin:0;">
    <input type="hidden" name="method" value="login">
    <input type="hidden" name="user_login" value="${safeUserHtml}">
    <input type="hidden" name="password" value="${safePassHtml}">

    <!-- Manual fallback area. Default-shown (in case JS doesn't run); the inline <style>
         block above hides it once <html> gets the .js-active class, which our script
         only adds if it successfully executed. -->
    <div id="manual-area">
      <p id="initial-help" class="note" style="margin-top:0;">
        Click <strong>Connect &amp; open console</strong> below to log into iLO and load the remote console.
        If the popup gets stuck at iLO's login page, switch back to the Morpheus tab and use
        <strong>Show credentials</strong> to copy them in by hand.
      </p>
      <div class="row">
        <button type="submit" class="btn btn-primary" autofocus>Connect &amp; open console</button>
        <a class="btn btn-secondary" href="https://${safeHostHtml}/" target="_self" rel="noopener">Open iLO root</a>
      </div>

      <div class="creds">
        <div style="opacity:0.7; margin-bottom:8px; font-size:11px;">If iLO prompts for login, paste these (single click selects, then Ctrl+C / Cmd+C):</div>
        <div class="row-kv">
          <span><strong>Username:</strong> <code>${safeUserHtml}</code></span>
          <span><strong>Password:</strong> <code>${safePassHtml}</code></span>
        </div>
      </div>
    </div>
  </form>

  <noscript>
    <p class="note">JavaScript is disabled or blocked, so auto-login can't run. Click <strong>Connect &amp; open console</strong> above — the form will post your credentials to iLO and set the session cookie. Then open <a href="https://${safeHostHtml}/irc.html" target="_self">the console</a>.</p>
  </noscript>

</div>

<script>
(function() {
  // If we got here, JS executed — flag <html> so CSS hides the manual UI by default.
  document.documentElement.classList.add('js-active');

  var host = ${safeHost};
  var consoleUrl = 'https://' + host + '/irc.html';
  var rootUrl = 'https://' + host + '/';
  var statusEl = document.getElementById('status-text');
  var iframe = document.getElementById('lframe');
  var lf = document.getElementById('lf');
  var navigated = false;
  var iframeLoaded = false;

  function go() {
    if (navigated) return;
    navigated = true;
    if (statusEl) statusEl.textContent = 'Opening console…';
    // Clear credentials from the form inputs before navigation so they don't
    // linger in the history of this document.
    try {
      lf.querySelector('input[name="user_login"]').value = '';
      lf.querySelector('input[name="password"]').value = '';
    } catch (e) {}
    window.location.href = consoleUrl;
  }

  function showFallback(message) {
    // Reveal the manual buttons + creds and update the status message.
    document.documentElement.classList.add('js-fallback');
    var spin = document.getElementById('spinner');
    if (spin) spin.style.display = 'none';
    if (statusEl) {
      statusEl.innerHTML = '<span class="fail">' + message + '</span>';
    }
  }

  iframe.addEventListener('load', function() {
    iframeLoaded = true;
    // Give iLO a moment to finish setting the cookie, then navigate.
    setTimeout(go, 400);
  });

  // Auto-submit the form on page load. If anything throws here, the user still
  // has the visible Connect button.
  try {
    lf.submit();
  } catch (e) {
    showFallback('Auto-submit failed: ' + (e.message || e) + '. Click Connect below.');
    return;
  }

  // Hard timeout: if neither the iframe loaded nor we navigated within 6s,
  // assume something went wrong (cert error, network, CSP) and show the manual
  // controls.
  setTimeout(function() {
    if (!navigated && !iframeLoaded) {
      showFallback('Auto-login didn\\u0027t complete. The browser may have blocked iLO\\u0027s self-signed certificate, or the iLO is unreachable. Try opening <a href="' + rootUrl + '" target="_self">' + rootUrl + '</a> first to accept the cert, then click Connect.');
    } else if (iframeLoaded && !navigated) {
      // Iframe loaded but go() didn't fire — shouldn't happen, but defensive.
      go();
    }
  }, 6000);
})();
</script>
</body>
</html>
"""
    }

    private static HTMLResponse errorPage(String msg) {
        return HTMLResponse.success("""\
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>iLO Console Error</title>
<style>
  body { background:#0b1218; color:#dbe6ef; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif; padding:60px 40px; max-width:600px; margin:0 auto; }
  h1 { font-size:18px; color:#d9534f; border-bottom:1px solid rgba(217,83,79,0.3); padding-bottom:8px; }
  p { line-height:1.6; }
  button { background:#01A982; color:#fff; border:0; padding:8px 18px; border-radius:3px; cursor:pointer; font-size:13px; }
</style></head>
<body>
<h1>Launch failed</h1>
<p>${msg}</p>
<p><button onclick="window.close()">Close</button></p>
</body></html>
""")
    }

    /** Escape for safe inclusion as a JSON-quoted JS string literal. */
    private static String jsString(String s) {
        if (s == null) return '""'
        StringBuilder sb = new StringBuilder('"')
        s.each { String c ->
            switch (c) {
                case '\\': sb.append('\\\\'); break
                case '"':  sb.append('\\"'); break
                case '\n': sb.append('\\n'); break
                case '\r': sb.append('\\r'); break
                case '\t': sb.append('\\t'); break
                case '<':  sb.append('\\u003c'); break
                case '>':  sb.append('\\u003e'); break
                case '&':  sb.append('\\u0026'); break
                case "'":  sb.append('\\u0027'); break
                case '/':  sb.append('\\/'); break
                default:
                    int code = c.codePointAt(0)
                    if (code < 0x20) {
                        sb.append(String.format('\\u%04x', code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Minimal HTML attribute/text escape. */
    private static String htmlEscape(String s) {
        if (s == null) return ''
        return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&#39;')
    }
}
