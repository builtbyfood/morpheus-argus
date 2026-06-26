package com.morpheusdata.iloconsole

import com.morpheusdata.core.AbstractServerTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.iloconsole.services.IloConfigStore
import com.morpheusdata.iloconsole.services.IloDetectionService
import com.morpheusdata.iloconsole.services.RedfishClient
import com.morpheusdata.model.Account
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * Matches the working pattern from builtbyfood/morpheus-vm-folders:
 *   - Map-based view model (ViewModel<Map>)
 *   - inline-HTML fallback on Throwable in renderTemplate
 *   - bulletproof show()
 *
 * With the Plugin now installing its own HandlebarsRenderer (0.1.7), this
 * method should actually be invoked.
 */
@Slf4j
class IloConsoleServerTabProvider extends AbstractServerTabProvider {

    Plugin plugin
    MorpheusContext morpheusContext

    IloConsoleServerTabProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    @Override String getCode() { return 'ilo-console-server-tab' }
    @Override String getName() { return 'iLO' }
    @Override Plugin getPlugin() { return plugin }
    @Override MorpheusContext getMorpheus() { return morpheusContext }

    @Override
    Boolean show(ComputeServer server, User user, Account account) {
        try {
            log.info("iLO show() called — server id=${safeId(server)} name=${safeName(server)} user=${user?.username}")
            if (server == null) return false
            IloDetectionService detection = ((IloConsolePlugin) plugin).detectionService
            boolean result = detection?.matchesHpeProliant(server) ?: false
            log.info("iLO show() returning ${result}")
            return result
        } catch (Throwable t) {
            log.warn("iLO show() threw — defaulting to hidden: ${t.message}", t)
            return false
        }
    }

    @Override
    HTMLResponse renderTemplate(ComputeServer server) {
        log.info("iLO renderTemplate() called — server id=${safeId(server)} name=${safeName(server)}")
        try {
            IloConsolePlugin p = (IloConsolePlugin) plugin

            Map diag = buildDiagnostics(server)
            log.info("iLO render diagnostics: ${diag}")

            Map hw = [:]
            try { hw = p.detectionService.extractHardwareInfo(server) } catch (Throwable ignored) {}

            Map cfg = [iloHost: null, credentialId: null, verifySsl: false, configured: false, rawLabels: []]
            try { cfg = p.configStore.loadConfig(server) } catch (Throwable t) {
                log.warn("loadConfig threw: ${t.message}")
            }

            Map status = null
            String errorMsg = null
            String credSource = null
            if (cfg.configured) {
                Map credResult = com.morpheusdata.iloconsole.services.IloCredentialService.loadCredential(morpheusContext, cfg.credentialId as Long)
                if (credResult?.error) {
                    errorMsg = credResult.error
                    credSource = credResult.source
                } else {
                    credSource = credResult.source
                    try {
                        RedfishClient client = new RedfishClient(cfg.iloHost as String, cfg.verifySsl as boolean)
                        status = client.collectStatus(credResult.username as String, credResult.password as String)
                        if (status?.success) {
                            status.powerClass = (status.powerState == 'On') ? 'ok' : 'bad'
                            status.healthClass = (status.health == 'OK') ? 'ok' : 'warn'
                        } else {
                            errorMsg = status?.error
                        }
                    } catch (Throwable t) {
                        log.warn("iLO status fetch threw: ${t.message}", t)
                        errorMsg = "${t.class.simpleName}: ${t.message}"
                    }
                }
            }

            List rawLabels = (cfg.rawLabels as List) ?: []
            String labelsCsv = rawLabels.collect { it.toString() }.join(', ')

            // ── Build inline HTML ────────────────────────────────────────
            //
            // CSP note (0.1.20): the Morpheus 9.0 host page sets a CSP of
            //   script-src 'self' 'nonce-…' 'unsafe-inline' 'strict-dynamic' …
            // Under 'strict-dynamic', both 'unsafe-inline' and 'self' are
            // ignored, and only scripts carrying the page nonce are allowed
            // to execute. Inline event-handler attributes (onclick="…", etc.)
            // CAN NEVER carry a nonce by browser spec — only <script> tags
            // can. We don't have a clean accessor to the per-request nonce
            // from inline HTML output (the HandlebarsRenderer model binding
            // is empty on this appliance — see 0.1.15 notes), so the only
            // reliable path is to ship zero JavaScript in the tab.
            //
            // All interactions below are pure CSS:
            //   - Show/hide credentials panel: hidden <input type="checkbox">
            //     + ~ sibling selector on the panel.
            //   - Reveal/mask password: second hidden checkbox + sibling
            //     selectors on .ilo-pass-mask / .ilo-pass-real.
            //   - "Copy" credentials: each value lives in an element with
            //     user-select:all so a single click selects it; Ctrl+C copies.
            //
            // The Launch Console button is a plain <a target> link to iLO's
            // root URL. No pre-auth iframe (the previous /json/login_session
            // approach was blocked by SameSite=Strict anyway). The user
            // pastes credentials into iLO's own login page; the popup is
            // same-origin with iLO from there and /irc.html WebSocket works.
            StringBuilder html = new StringBuilder()
            html.append('<div style="padding:0; font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',sans-serif; color:#dbe6ef; font-size:13px;">')

            // Resolve credentials for embedding into the (CSS-toggleable)
            // credentials panel. Same security profile as before: rendered
            // into HTML served only to the authorized user over HTTPS, lives
            // in this user's DOM until the tab is closed, not transmitted
            // anywhere else by anything in this template.
            Map launchData = null
            if (cfg.configured && status?.success && credSource) {
                Map credResult2 = com.morpheusdata.iloconsole.services.IloCredentialService.loadCredential(morpheusContext, cfg.credentialId as Long)
                if (credResult2 && !credResult2.error) {
                    launchData = [
                            iloHost : cfg.iloHost,
                            username: credResult2.username,
                            password: credResult2.password
                    ]
                }
            }

            if (launchData) {
                String pfx = "iloTab_${server?.id ?: 'x'}"
                String iloRootUrl  = "https://${launchData.iloHost}/"
                // v0.1.24: text/plain JSON-injection form-POST launch.
                //
                // 0.1.23 sent the credentials as application/x-www-form-urlencoded
                // (the HTML form default), and iLO responded:
                //
                //     Malformed object, expected '{' at start of object.
                //
                // iLO's /json/login_session is JSON-only — it parses the request
                // body as JSON regardless of Content-Type, and form-encoded data
                // (`method=login&...`) starts with 'm' not '{'.
                //
                // HTML forms cannot natively send application/json, and the
                // tab's CSP forbids inline JS that could use fetch(). But the
                // text/plain form encoding has a useful property: it joins
                // name=value pairs with a literal '=' character and CRLF, with
                // no URL-encoding. So if we put the JSON-open in the input's
                // name and the JSON-close in its value, the body emerges as
                // valid JSON — with the stray '=' safely tucked inside the
                // value of an "x" key that iLO ignores:
                //
                //   name  = {"method":"login","user_login":"<u>","password":"<p>","x":"
                //   value = "}
                //   body  = {"method":"login","user_login":"<u>","password":"<p>","x":"="}
                //
                // The trick is well-known among CSRF researchers and works
                // because iLO is JSON-only and doesn't validate Content-Type.
                //
                // Click 1 ("Launch Console") posts this body to iLO, iLO sets
                // the sessionKey cookie on its origin (top-level cross-origin
                // form navigation honors SameSite=Lax). Click 2 ("Open Console")
                // navigates the same popup to /irc.html, which loads
                // pre-authenticated.
                String iloLoginUrl = "https://${launchData.iloHost}/json/login_session"
                String iloIrcUrl   = "https://${launchData.iloHost}/irc.html"
                String jsonUser    = jsonStringEscape(launchData.username as String)
                String jsonPass    = jsonStringEscape(launchData.password as String)
                String injectName  = '{"method":"login","user_login":"' + jsonUser + '","password":"' + jsonPass + '","x":"'
                String injectValue = '"}'

                // CSS-only toggle stylesheet. Scoped to the ${pfx} container
                // so it can't collide with anything else on the page.
                html.append("""<style>
#${pfx}-root .ilo-toggle { position:absolute; left:-9999px; opacity:0; }
/* v0.1.27 — base + primary button rules, applied identically to <a> and <button>.
   appearance:none disables the UA's native <button> rounding/font/border so
   the form-submit button is pixel-identical to the <a> companion. font:inherit
   pulls the host page's font family rather than the UA's default. */
#${pfx}-root .ilo-btn,
#${pfx}-root .ilo-btn-primary,
#${pfx}-root button.ilo-btn,
#${pfx}-root button.ilo-btn-primary {
  display:inline-block; box-sizing:border-box;
  padding:6px 14px; border-radius:3px; border:1px solid;
  font:inherit; font-size:12px; line-height:1.4; font-weight:600; letter-spacing:0.02em;
  text-decoration:none; cursor:pointer; user-select:none; vertical-align:middle;
  appearance:none; -webkit-appearance:none; -moz-appearance:none;
  margin:0;
}
#${pfx}-root .ilo-btn {
  background:transparent; border-color:rgba(255,255,255,0.15); color:#dbe6ef;
  padding:5px 12px; font-size:11px; font-weight:400;
}
#${pfx}-root .ilo-btn:hover { border-color:#01A982; color:#fff; }
#${pfx}-root .ilo-btn-primary { background:#01A982; border-color:#01A982; color:#fff; }
#${pfx}-root .ilo-btn-primary:hover { background:#018a6a; border-color:#018a6a; }
#${pfx}-root #${pfx}-cred-toggle:focus + .ilo-btn,
#${pfx}-root #${pfx}-pass-toggle:focus + label .ilo-btn {
  outline:2px solid rgba(1,169,130,0.5); outline-offset:1px;
}
#${pfx}-root .ilo-cred-panel { display:none; }
#${pfx}-root #${pfx}-cred-toggle:checked ~ .ilo-cred-panel { display:block; }
#${pfx}-root .ilo-pass-mask { display:inline; }
#${pfx}-root .ilo-pass-real { display:none; }
#${pfx}-root #${pfx}-pass-toggle:checked ~ .ilo-cred-panel .ilo-pass-mask { display:none; }
#${pfx}-root #${pfx}-pass-toggle:checked ~ .ilo-cred-panel .ilo-pass-real { display:inline; }
#${pfx}-root .ilo-copyable {
  background:rgba(255,255,255,0.06); padding:2px 8px; border-radius:3px;
  font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; font-size:12px;
  cursor:text; user-select:all; -webkit-user-select:all;
}
#${pfx}-root .ilo-copyable:hover { background:rgba(1,169,130,0.15); }
/* v0.1.23 form-POST launch: the form is just a structural wrapper for
   POST-with-credentials; display:contents lets its child button sit in
   the flex header row alongside the other buttons as if the <form>
   weren't there. .ilo-btn-primary on a <button> needs explicit font
   inheritance since browsers default <button> typography differently. */
#${pfx}-root .ilo-launch-form { display:contents; margin:0; padding:0; border:0; }
#${pfx}-root .ilo-ro-pill {
  display:inline-block; padding:3px 9px; border-radius:10px;
  background:rgba(255,193,7,0.15); border:1px solid rgba(255,193,7,0.4);
  color:#ffc107; font-size:10px; font-weight:600;
  text-transform:uppercase; letter-spacing:0.05em;
}
</style>""")

                html.append("<div id=\"${pfx}-root\">")

                // The two CSS-driver checkboxes. They MUST be siblings of
                // .ilo-cred-panel for the ~ selector to match.
                html.append("<input type=\"checkbox\" class=\"ilo-toggle\" id=\"${pfx}-cred-toggle\">")
                html.append("<input type=\"checkbox\" class=\"ilo-toggle\" id=\"${pfx}-pass-toggle\">")

                // Header row with title + (when not readonly) launch buttons +
                // Show-credentials toggle. Readonly mode shows just the title
                // and a small yellow "Read-only" pill.
                html.append('<div style="display:flex; align-items:center; gap:12px; padding:12px 16px; background:rgba(255,255,255,0.03); border-radius:4px; margin-bottom:14px; flex-wrap:wrap;">')
                html.append('<h2 style="margin:0; font-size:15px; font-weight:600; flex:1; min-width:140px; text-transform:none;">iLO</h2>')
                if (cfg.readonly) {
                    html.append('<span class="ilo-ro-pill">Read-only</span>')
                } else {
                    // v0.1.24 launch UI: text/plain form-POST + companion link.
                    //
                    // The form has ONE crafted hidden input. enctype="text/plain"
                    // writes `<name>=<value>` for each field, no URL encoding.
                    // Our name+value concatenation yields valid JSON that iLO's
                    // /json/login_session parser accepts (see comment block
                    // above where injectName/injectValue are constructed).
                    //
                    // v0.1.30: each button has a `title` attribute that the
                    // browser surfaces as a native hover tooltip — quick
                    // explanation of what the button does, without needing the
                    // user to read the README first.
                    String tgt = "iloConsole_${server?.id}"
                    html.append("<label for=\"${pfx}-cred-toggle\" class=\"ilo-btn\" title=\"Reveals the configured iLO username and password for manual copy/paste. Pure HTML/CSS \u2014 nothing transmitted.\">Show credentials</label>")
                    html.append("<form class=\"ilo-launch-form\" method=\"POST\" enctype=\"text/plain\" action=\"${escapeHtml(iloLoginUrl)}\" target=\"${tgt}\" autocomplete=\"off\">")
                    html.append("<input type=\"hidden\" name=\"${escapeHtml(injectName)}\" value=\"${escapeHtml(injectValue)}\">")
                    html.append('<button type="submit" class="ilo-btn-primary" title="Auto-logs into iLO with the stored credentials, then opens the IRC console pre-authenticated. One click does both steps.">&#9658; Launch Console</button>')
                    html.append('</form>')
                    html.append("<a class=\"ilo-btn-primary\" href=\"${escapeHtml(iloIrcUrl)}\" target=\"${tgt}\" rel=\"noopener\" title=\"Opens the IRC console using the existing iLO session cookie set by Launch Console. Use after the popup has authenticated, or to re-open the console in the same window.\">&#10142; Open Console</a>")
                }

                html.append('</div>') // end header row

                // v0.1.25/0.1.26: one-click launch via nonced inline JS.
                //
                // If we can read the per-request CSP nonce from Spring's
                // RequestContextHolder, we emit a <script nonce="…"> that
                // hooks the Launch Console button's click event: it opens
                // the popup window (or reuses it) using window.open's
                // user-activation grant, then schedules a setTimeout that
                // navigates the popup to /irc.html ~1.5s later — by which
                // time iLO has set the sessionKey cookie on its own origin
                // in response to the form-POST.
                //
                // Skipped entirely in readonly mode (no Launch button to
                // hook). Also skipped if the nonce isn't available; in that
                // case the two-button manual flow from 0.1.24 still works.
                String nonce = cfg.readonly ? '' : getCspNonce()
                log.info("iLO tab render — readonly=${cfg.readonly}, CSP nonce: ${nonce ? "present (length=${nonce.length()})" : 'missing (one-click JS disabled)'}")
                if (nonce) {
                    // Define small constants for the JS — these go literally
                    // into the script as string literals, so they need
                    // JSON-string-escape rather than HTML escape.
                    String tgt = "iloConsole_${server?.id}"
                    String jsIrcUrl = jsonStringEscape(iloIrcUrl)
                    String jsTarget = jsonStringEscape(tgt)
                    String rootId   = "${pfx}-root"
                    html.append("<script nonce=\"${escapeHtml(nonce)}\">")
                    html.append("""
(function() {
  try {
    var root = document.getElementById("${jsonStringEscape(rootId)}");
    if (!root) return;
    var form = root.querySelector('form.ilo-launch-form');
    var btn  = form ? form.querySelector('button[type="submit"]') : null;
    if (!form || !btn) return;
    var ircUrl = "${jsIrcUrl}";
    var target = "${jsTarget}";
    var delayMs = 1500;
    var navigated = false;

    btn.addEventListener('click', function() {
      // Open (or refocus) the popup so we have a window reference under
      // the user-activation grant from this click. window.open with an
      // empty URL and an existing name returns a reference to the
      // existing window without navigating it — the form's default
      // submission that follows will then navigate it to the iLO POST.
      var win;
      try { win = window.open('', target); } catch (e) { win = null; }
      if (!win) return;
      // Schedule the follow-up navigation to IRC. We can set
      // win.location.href cross-origin from the opener, even after the
      // popup has navigated away to iLO.
      setTimeout(function() {
        if (navigated) return;
        navigated = true;
        try { if (!win.closed) { win.location.href = ircUrl; } } catch (e) { /* ignore */ }
      }, delayMs);
      // Do NOT preventDefault — let the form's default action POST the
      // credentials to /json/login_session in the same named window.
    });
  } catch (err) {
    // Any failure leaves the two-button manual flow intact.
  }
})();
""")
                    html.append('</script>')
                }

                // The credentials panel. Sibling of both checkboxes above.
                // Suppressed entirely in readonly mode — when ilo-readonly:true
                // is set, the credentials never appear in the DOM, so a
                // dev-tools peek shows nothing sensitive.
                if (!cfg.readonly) {
                    html.append('<div class="ilo-cred-panel" style="background:rgba(255,255,255,0.02); border:1px solid rgba(1,169,130,0.3); border-radius:4px; padding:12px 16px; margin-bottom:14px; font-size:12px;">')

                    html.append('<div style="margin-bottom:10px; opacity:0.7; font-size:11px;">Two-step launch: click <strong>Launch Console</strong> (the popup briefly shows iLO\'s session response — that\'s expected), then <strong>Open Console</strong> to load the IRC interface. If the popup gets stuck at iLO\'s login screen, paste these by hand. Single-click a value to select it, then Ctrl+C (Cmd+C on Mac) to copy.</div>')

                    html.append('<div style="display:flex; gap:24px; align-items:center; flex-wrap:wrap;">')

                    // Username row — always shown when panel is open.
                    html.append('<div style="display:flex; align-items:center; gap:8px;">')
                    html.append('<strong>Username:</strong>')
                    html.append("<code class=\"ilo-copyable\">${escapeHtml(launchData.username as String)}</code>")
                    html.append('</div>')

                    // Password row — masked by default, .ilo-pass-real shown when
                    // the pass-toggle checkbox is checked.
                    html.append('<div style="display:flex; align-items:center; gap:8px;">')
                    html.append('<strong>Password:</strong>')
                    html.append('<span class="ilo-pass-mask"><code class="ilo-copyable" style="user-select:none; cursor:default;">&bull;&bull;&bull;&bull;&bull;&bull;&bull;&bull;</code></span>')
                    html.append("<span class=\"ilo-pass-real\"><code class=\"ilo-copyable\">${escapeHtml(launchData.password as String)}</code></span>")
                    html.append("<label for=\"${pfx}-pass-toggle\" class=\"ilo-btn\" style=\"padding:2px 8px;\">Reveal</label>")
                    html.append('</div>')

                    html.append('</div>') // end inline kv row

                    html.append('<div style="margin-top:10px; font-size:11px; opacity:0.5; line-height:1.5;">')
                    html.append('Credentials are only visible in this browser tab. Nothing is transmitted anywhere by toggling Show/Reveal &mdash; the password is just unmasked locally. ')
                    html.append("If the auto-login flow doesn't work, open <a href=\"${escapeHtml(iloRootUrl)}\" target=\"iloConsole_${server?.id}\" rel=\"noopener\" style=\"color:#01A982;\">the iLO root page</a> directly &mdash; it presents the same login form.")
                    html.append('</div>')

                    html.append('</div>') // end .ilo-cred-panel
                }

                html.append('</div>') // end #${pfx}-root
            } else {
                // No launchData (server not configured, status failed, or
                // credential resolution failed). Still show a header bar so
                // users see "iLO" and know where they are. The detailed
                // error/configure-access cards below the header explain
                // what went wrong.
                html.append('<div style="display:flex; align-items:center; gap:12px; padding:12px 16px; background:rgba(255,255,255,0.03); border-radius:4px; margin-bottom:14px; flex-wrap:wrap;">')
                html.append('<h2 style="margin:0; font-size:15px; font-weight:600; flex:1; min-width:140px; text-transform:none;">iLO</h2>')
                if (cfg.configured && cfg.iloHost) {
                    // We have an iLO host but couldn't pull status — still
                    // offer a plain link so the user can at least open iLO
                    // directly.
                    html.append("<a class=\"ilo-btn-primary\" style=\"display:inline-block; background:#01A982; color:#fff; padding:6px 14px; border-radius:3px; text-decoration:none; font-size:12px; font-weight:600; letter-spacing:0.02em;\" href=\"https://${escapeHtml(cfg.iloHost as String)}/\" target=\"iloConsole_${server?.id}\" rel=\"noopener\">&#9658; Open iLO</a>")
                }
                html.append('</div>')
            }

            // Detected hardware
            html.append('<div style="background:rgba(255,255,255,0.02); padding:10px 14px; border-radius:4px; margin-bottom:14px; font-size:12px;">')
            if (hw.vendor) {
                html.append("<strong>Detected:</strong> ${escapeHtml(hw.vendor as String)}")
                if (hw.model) html.append(" &middot; ${escapeHtml(hw.model as String)}")
                if (hw.serial) html.append(" &middot; S/N ${escapeHtml(hw.serial as String)}")
            } else {
                html.append("<span style=\"opacity:0.6;\">No vendor/model fields could be read from this host record.</span>")
            }
            html.append('</div>')

            // Error banner
            if (errorMsg) {
                html.append('<div style="background:rgba(217,83,79,0.10); border-left:3px solid #d9534f; padding:10px 14px; margin-bottom:14px;">')
                html.append("<strong>iLO error:</strong> ${escapeHtml(errorMsg)}")
                if (credSource) html.append("<div style=\"font-size:11px; opacity:0.7; margin-top:4px;\">credential path: ${escapeHtml(credSource)}</div>")
                html.append('</div>')
            }

            // v0.1.32 — Health drill-down banner. When overall Health isn't
            // "OK", show *which* subsystems are reporting a problem so the
            // user knows where to look. Driven by HPE's
            // Oem.Hpe.AggregateHealthStatus, which breaks the rollup down
            // by Fans, Memory, Network, PowerSupplies, Processors, Storage,
            // Temperatures, etc.
            if (status?.success && status.health && status.health != 'OK') {
                Map breakdown = (status.healthBreakdown ?: [:]) as Map
                List bad = breakdown.findAll { k, v -> v && v != 'OK' }.collect { k, v -> [name: k, level: v] }
                if (bad) {
                    html.append('<div style="background:rgba(217,83,79,0.06); border-left:3px solid #d9534f; padding:10px 14px; margin-bottom:14px; font-size:12px;">')
                    html.append('<strong style="color:#d9534f;">Health attention:</strong> ')
                    html.append(bad.collect { e ->
                        String label = humanizeHealthKey(e.name as String)
                        String color = (e.level == 'Critical') ? '#d9534f' : '#ffc107'
                        "<span style=\"color:${color}; font-weight:600;\">${escapeHtml(label)}</span> <span style=\"opacity:0.7;\">(${escapeHtml(e.level as String)})</span>"
                    }.join(' &middot; '))
                    html.append('</div>')
                }
            }

            // v0.1.27 — multi-card status layout. Each card is conditional on
            // its underlying data being present, so a host that doesn't return
            // (say) thermal data just doesn't get a Power & Cooling card.
            if (status?.success) {
                // ── System card ──
                html.append('<div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                html.append('<h3 style="margin:0 0 12px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">System</h3>')
                html.append('<div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(180px, 1fr)); gap:12px 24px;">')
                html.append(statusItem('Power', status.powerState as String, status.powerClass as String))
                html.append(statusItem('Health', status.health as String, status.healthClass as String))
                html.append(kvItem('iLO', "${status.iloModel ?: ''}${status.iloFirmware ? ' &middot; ' + status.iloFirmware : ''}"))
                html.append(kvItem('BIOS', status.biosVersion as String))
                html.append(kvItem('CPU', "${status.cpuCount ?: ''}&times; ${escapeHtml(status.cpuModel as String)}"))
                html.append(kvItem('Memory', "${status.memoryGiB ?: '?'} GiB"))
                if (status.serial)      html.append(kvItem('Serial',     status.serial as String))
                if (status.assetTag)    html.append(kvItem('Asset Tag',  status.assetTag as String))
                if (status.chassisSku)  html.append(kvItem('SKU',        status.chassisSku as String))
                if (status.indicatorLed) {
                    String led = status.indicatorLed as String
                    // v0.1.31: match what's physically on the server's front panel.
                    // HPE UID LED is BLUE when lit/blinking, dark when off.
                    // Off = default grey pill (no light). Lit/Blinking = blue pill.
                    String ledClass = (led == 'Lit' || led == 'Blinking') ? 'info' : null
                    html.append(statusItem('Indicator LED', led, ledClass))
                }
                // v0.1.29 additions to System card
                if (status.tpmModuleType) {
                    String tpmText = status.tpmModuleType as String
                    String tpmState = status.tpmState as String
                    String tpmClass = (tpmState == 'Enabled') ? 'ok' : (tpmState == 'Disabled' ? 'warn' : null)
                    if (tpmState) tpmText += " \u00b7 ${tpmState}"
                    if (tpmClass) {
                        html.append(statusItem('TPM', tpmText, tpmClass))
                    } else {
                        html.append(kvItem('TPM', tpmText))
                    }
                }
                if (status.bootProgress) {
                    String bp = status.bootProgress as String
                    // v0.1.30: green for any "in a healthy boot state" value —
                    // OS already running OR mid-boot in a normal sequence.
                    // Warn for unusual stops (setup entered, errors). Other
                    // Redfish values: PrimaryProcessorInitializationStarted,
                    // SystemHardwareInitializationComplete, SetupEntered,
                    // OSBootStarted, OSRunning, OEM.
                    String bpClass = (bp in ['OSRunning', 'OSBootStarted',
                                              'SystemHardwareInitializationComplete',
                                              'PrimaryProcessorInitializationStarted']) ? 'ok' : 'warn'
                    html.append(statusItem('Boot Progress', humanizeBootProgress(bp), bpClass))
                }
                if (status.bootSource && status.bootSource != 'None') {
                    html.append(kvItem('Boot Override', status.bootSource as String))
                }
                if (status.hostName) {
                    html.append(kvItem('Hostname', status.hostName as String))
                }
                html.append('</div></div>')

                // ── Power & Cooling card ──
                // Renders if we have *any* of: power draw, PSU info, temps, or fans.
                boolean hasPower = (status.powerConsumedWatts != null) || (status.psuCount as Integer ?: 0) > 0
                boolean hasThermal = !((status.temperatures ?: []).isEmpty() && (status.fans ?: []).isEmpty())
                if (hasPower || hasThermal) {
                    html.append('<div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                    html.append('<h3 style="margin:0 0 12px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">Power &amp; Cooling</h3>')
                    html.append('<div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(180px, 1fr)); gap:12px 24px;">')

                    // Power draw — v0.1.28 shows current and (when present and
                    // different) the longer-term average iLO has tracked. Some
                    // firmwares only update the instant reading periodically;
                    // average is more reflective of actual draw. v0.1.31: when
                    // both current and average come back zero/null (e.g. iLO 6
                    // on MicroServer Gen11, which doesn't track power on this
                    // hardware tier), we show "Not reported" rather than a
                    // misleading "0 W". v0.1.32: include min/max from
                    // PowerMetrics when present.
                    if (status.powerConsumedWatts != null) {
                        Integer current = status.powerConsumedWatts as Integer
                        Integer avg = status.powerAvgWatts as Integer
                        Integer min = status.powerMinWatts as Integer
                        Integer max = status.powerMaxWatts as Integer
                        boolean haveCurrent = (current != null && current > 0)
                        boolean haveAvg = (avg != null && avg > 0)
                        String draw
                        if (!haveCurrent && !haveAvg) {
                            draw = '<span style="opacity:0.5;">Not reported</span>'
                        } else {
                            draw = "${current ?: 0} W"
                            if (haveAvg && avg != current) {
                                draw += " \u00b7 avg ${avg} W"
                            }
                            if (min && max && (min != max)) {
                                draw += "<div style=\"font-size:11px; opacity:0.55; margin-top:2px;\">range ${min}\u2013${max} W over last ${status.powerIntervalMin ?: 24} min</div>"
                            }
                            if (status.powerCapacityWatts) draw += " / ${status.powerCapacityWatts} W"
                        }
                        html.append(kvItem('Power Draw', draw))
                    }

                    // PSU
                    if ((status.psuCount as Integer ?: 0) > 0) {
                        int total = status.psuCount as Integer
                        int healthy = status.psuHealthyCount as Integer ?: 0
                        String psuText = "${healthy}/${total} OK"
                        if (status.psuRedundancy) {
                            psuText += " \u00b7 ${escapeHtml(status.psuRedundancy as String)}"
                        }
                        String psuClass = (healthy == total) ? 'ok' : 'warn'
                        html.append(statusItem('PSU', psuText, psuClass))
                    }

                    // Hottest CPU temperature + ambient
                    def temps = (status.temperatures ?: []) as List
                    def cpuTemps = temps.findAll {
                        String n = (it.name as String) ?: ''
                        n.toLowerCase().contains('cpu') || n.toLowerCase().contains('proc')
                    }
                    if (cpuTemps) {
                        def hot = cpuTemps.max { (it.c as Integer) }
                        String tClass = ((hot.c as Integer) >= 80) ? 'warn' : 'ok'
                        // v0.1.28: use Unicode '°' rather than '&deg;' so statusItem's
                        // escapeHtml() doesn't convert '&' into '&amp;' and render as
                        // the literal text '&deg;'.
                        // v0.1.35: dual C/F via formatTemp() — same call site, just
                        // emits "${c} °C / ${f} °F" now.
                        html.append(statusItem('CPU Temp', formatTemp(hot.c as Integer), tClass))
                    }
                    def ambient = temps.find {
                        String n = (it.name as String) ?: ''
                        n.toLowerCase().contains('ambient') || n.toLowerCase().contains('inlet')
                    }
                    if (ambient) {
                        html.append(kvItem('Ambient', formatTemp(ambient.c as Integer)))
                    }

                    // Fans
                    def fans = (status.fans ?: []) as List
                    if (fans) {
                        // Average % across fans, plus count summary
                        int n = fans.size()
                        Number avg = fans.collect { (it.pct ?: 0) as Number }.sum()
                        avg = avg ? (avg.intValue() / n) : 0
                        html.append(kvItem('Fans', "${n} active &middot; avg ${avg}%"))
                    }
                    html.append('</div></div>')
                }

                // ── Power Trend (v0.1.34) ──
                // Inline SVG sparkline of historical power draw from
                // /Chassis/1/Power/PowerMeter, side-by-side with a "current
                // draw" gauge derived from EnvironmentMetrics and the existing
                // Power read. Renders an empty-state ("no history available")
                // when iLO doesn't return /PowerMeter samples — which is the
                // common case on entry-tier ProLiant (e.g. MicroServer Gen11),
                // where the hardware doesn't track per-minute samples.
                //
                // Honors the v0.1.31 convention from the Power & Cooling card:
                // a reading of 0 is treated as "not reported" rather than
                // "genuinely 0 W draw", since iLO 6 on entry hardware returns
                // 0 when the sensor isn't populated. A null OR zero reading
                // is filtered out of all the gating + display logic below.
                Map ptrend = (status.powerTrend ?: [:]) as Map
                Closure ptValid = { v -> v != null && (v as Integer) > 0 }
                boolean ptHaveCurrent = ptValid(ptrend.current)
                boolean ptHaveMin     = ptValid(ptrend.min)
                boolean ptHaveAvg     = ptValid(ptrend.avg)
                boolean ptHaveMax     = ptValid(ptrend.max)
                List ptHist = (ptrend.history ?: []) as List
                boolean ptHaveHist = ptHist.size() >= 2
                boolean ptrendHasAny = ptHaveCurrent || ptHaveMin || ptHaveAvg || ptHaveMax || ptHaveHist
                if (ptrendHasAny) {
                    html.append('<div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                    html.append('<h3 style="margin:0 0 12px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">Power Trend</h3>')
                    html.append('<div style="display:flex; align-items:center; gap:20px; flex-wrap:wrap;">')

                    // Left: sparkline (if history) or "no history" placeholder
                    if (ptHaveHist) {
                        html.append(renderPowerSparkline(ptHist))
                    } else {
                        html.append('<div style="flex:1; min-width:240px; padding:18px 12px; background:rgba(255,255,255,0.02); border-radius:3px; font-size:11px; opacity:0.55; text-align:center; line-height:1.5;">')
                        html.append('No history available<br><span style="font-size:10px;">iLO didn\'t return /Power/PowerMeter samples on this hardware tier</span>')
                        html.append('</div>')
                    }

                    // Right: current + min/avg/max gauge
                    String unitStr = escapeHtml((ptrend.units ?: 'W') as String)
                    html.append('<div style="flex:0 0 auto; min-width:120px;">')
                    if (ptHaveCurrent) {
                        html.append("<div style=\"font-size:28px; font-weight:700; letter-spacing:-0.02em; line-height:1;\">${ptrend.current} <span style=\"font-size:14px; font-weight:400; opacity:0.6;\">${unitStr}</span></div>")
                        html.append('<div style="font-size:10px; text-transform:uppercase; letter-spacing:0.05em; opacity:0.55; margin-top:4px;">Current</div>')
                    } else {
                        html.append('<div style="font-size:13px; opacity:0.55;">Current reading not reported</div>')
                    }
                    List statParts = []
                    if (ptHaveMin) statParts << "min ${ptrend.min}"
                    if (ptHaveAvg) statParts << "avg ${ptrend.avg}"
                    if (ptHaveMax) statParts << "max ${ptrend.max}"
                    if (statParts) {
                        html.append("<div style=\"font-size:11px; opacity:0.65; margin-top:10px;\">${statParts.join(' \u00b7 ')} ${unitStr}</div>")
                    }
                    if (ptrend.intervalMin) {
                        html.append("<div style=\"font-size:10px; opacity:0.45; margin-top:2px;\">over last ${ptrend.intervalMin} min</div>")
                    }
                    html.append('</div>') // gauge column
                    html.append('</div>') // flex row
                    if (ptrend.source) {
                        html.append("<div style=\"font-size:10px; opacity:0.4; margin-top:10px;\">Source: ${escapeHtml(ptrend.source as String)}</div>")
                    }
                    html.append('</div>')
                }

                // ── Network card ──
                // v0.1.28: includes both the host NIC (what the OS sees) and the
                // iLO's own management NIC. Renders if either has data.
                boolean hasHostNet = status.hostMac || status.hostIp
                boolean hasIloNet  = status.iloMac  || status.iloIp || status.iloHostName
                if (hasHostNet || hasIloNet) {
                    html.append('<div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                    html.append('<h3 style="margin:0 0 12px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">Network</h3>')
                    html.append('<div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(180px, 1fr)); gap:12px 24px;">')
                    if (status.hostMac) html.append(kvItem('Host MAC', status.hostMac as String))
                    if (status.hostIp)  html.append(kvItem('Host IP',  status.hostIp as String))
                    if (status.hostLink) {
                        String lClass = (status.hostLink == 'LinkUp') ? 'ok' : 'bad'
                        String lText = status.hostLink as String
                        if (status.hostSpeedMbps) lText += " \u00b7 ${status.hostSpeedMbps} Mbps"
                        html.append(statusItem('Link', lText, lClass))
                    }
                    if (status.iloIp)       html.append(kvItem('iLO IP',     status.iloIp as String))
                    if (status.iloMac)      html.append(kvItem('iLO MAC',    status.iloMac as String))
                    if (status.iloHostName) html.append(kvItem('iLO Host',   status.iloHostName as String))
                    // v0.1.29 — iLO date/time + license
                    if (status.iloDateTime) {
                        String dt = status.iloDateTime as String
                        if (status.iloDateTimeOffset) dt += " (${status.iloDateTimeOffset})"
                        html.append(kvItem('iLO Date/Time', dt))
                    }
                    if (status.iloLicenseType) {
                        html.append(kvItem('iLO License', status.iloLicenseType as String))
                    }
                    html.append('</div></div>')
                }

                // ── Network Adapters (v0.1.34) ──
                // Per-adapter detail from /Chassis/1/NetworkAdapters. Each
                // adapter renders its model/serial/firmware + a ports table
                // (link state, speed, MACs). Distinct from the simpler
                // "Network" card above which only summarizes the primary
                // host NIC's IP/MAC/link.
                List netAdapters = (status.networkAdapters ?: []) as List
                if (netAdapters) {
                    Integer totalPorts = (netAdapters.sum { ((it.ports ?: []) as List).size() } ?: 0) as Integer
                    html.append('<details style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
                    html.append("<summary style=\"cursor:pointer; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;\">Network Adapters &mdash; ${netAdapters.size()} adapter${netAdapters.size() == 1 ? '' : 's'}, ${totalPorts} port${totalPorts == 1 ? '' : 's'}</summary>")
                    netAdapters.eachWithIndex { Map a, int idx ->
                        String topPad = (idx == 0) ? '4px' : '12px'
                        String borderTop = (idx == 0) ? '' : 'border-top:1px solid rgba(255,255,255,0.04); '
                        html.append("<div style=\"margin-top:12px; padding-top:${topPad}; ${borderTop}\">")
                        // Adapter identity header (inline kv)
                        html.append('<div style="display:flex; flex-wrap:wrap; gap:6px 16px; align-items:baseline; margin-bottom:8px;">')
                        html.append("<strong style=\"font-size:13px;\">${escapeHtml((a.name ?: a.id ?: 'Adapter') as String)}</strong>")
                        if (a.model && a.model != a.name) {
                            html.append("<span style=\"font-size:12px; opacity:0.75;\">${escapeHtml(a.model as String)}</span>")
                        }
                        if (a.manufacturer) {
                            html.append("<span style=\"font-size:11px; opacity:0.55;\">${escapeHtml(a.manufacturer as String)}</span>")
                        }
                        if (a.serial) {
                            html.append("<span style=\"font-size:11px; opacity:0.55; font-family:ui-monospace,monospace;\">S/N ${escapeHtml(a.serial as String)}</span>")
                        }
                        if (a.firmware) {
                            html.append("<span style=\"font-size:11px; opacity:0.55; font-family:ui-monospace,monospace;\">FW ${escapeHtml(a.firmware as String)}</span>")
                        }
                        if (a.health) {
                            String aColor = (a.health == 'OK') ? '#28a745' : (a.health == 'Warning' ? '#ffc107' : '#d9534f')
                            html.append("<span style=\"font-size:11px; font-weight:600; color:${aColor};\">${escapeHtml(a.health as String)}</span>")
                        }
                        html.append('</div>')

                        // Ports sub-table
                        List adapterPorts = (a.ports ?: []) as List
                        if (adapterPorts) {
                            html.append('<table style="width:100%; font-size:12px; border-collapse:collapse;">')
                            html.append('<thead><tr style="opacity:0.55; font-size:10px; text-transform:uppercase; letter-spacing:0.05em;">')
                            html.append('<th style="text-align:left; padding:0 12px 6px 0;">Port</th>')
                            html.append('<th style="text-align:left; padding:0 12px 6px 0;">Link</th>')
                            html.append('<th style="text-align:right; padding:0 12px 6px 0;">Speed</th>')
                            html.append('<th style="text-align:left; padding:0 0 6px 0;">MAC</th>')
                            html.append('</tr></thead><tbody>')
                            adapterPorts.each { Map port ->
                                String link = (port.linkStatus as String) ?: '\u2014'
                                String lColor = (link == 'LinkUp') ? '#28a745' : ((link == 'LinkDown' || link == 'NoLink') ? '#7a8a98' : '#ffc107')
                                String speedStr = port.speedMbps ? "${port.speedMbps} Mbps" : '\u2014'
                                String macStr = ((port.macs ?: []) as List).join(', ')
                                html.append('<tr style="border-top:1px solid rgba(255,255,255,0.04);">')
                                html.append("<td style=\"padding:6px 12px 6px 0; font-family:ui-monospace,monospace;\">${escapeHtml((port.portNumber ?: port.name ?: port.id ?: '\u2014') as String)}</td>")
                                html.append("<td style=\"padding:6px 12px 6px 0; color:${lColor}; font-weight:600;\">${escapeHtml(link)}</td>")
                                html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace;\">${escapeHtml(speedStr)}</td>")
                                html.append("<td style=\"padding:6px 0; opacity:0.8; font-family:ui-monospace,monospace; font-size:11px;\">${escapeHtml(macStr ?: '\u2014')}</td>")
                                html.append('</tr>')
                            }
                            html.append('</tbody></table>')
                        }
                        html.append('</div>') // end adapter section
                    }
                    html.append('</details>')
                }

                // ── NIC Port LEDs (v0.1.34) ──
                // At-a-glance per-port health view using HPE Oem.Hpe.PortHealth
                // plus base Status.Health and LinkStatus. Each port becomes
                // a colored dot so a problem is visible without expanding
                // the Network Adapters details above. Kept separate (not
                // folded into Network Adapters) at user's request — useful
                // for quick "any red lights?" scans during incident triage.
                List portLedAdapters = (status.networkAdapters ?: []) as List
                List allLedPorts = []
                portLedAdapters.each { Map a ->
                    ((a.ports ?: []) as List).each { Map port ->
                        allLedPorts << [adapter: a, port: port]
                    }
                }
                if (allLedPorts) {
                    html.append('<div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                    html.append('<h3 style="margin:0 0 12px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">NIC Port LEDs</h3>')
                    html.append('<div style="display:flex; flex-wrap:wrap; gap:14px 18px;">')
                    allLedPorts.each { Map entry ->
                        Map a = entry.adapter as Map
                        Map port = entry.port as Map
                        String link = (port.linkStatus as String) ?: ''
                        String hpeHealth = (port.hpePortHealth as String) ?: ''
                        String baseHealth = (port.health as String) ?: ''
                        // Determination order (most-specific wins):
                        //   1. HPE OEM critical / base critical → red
                        //   2. HPE OEM warning / base warning   → yellow
                        //   3. LinkUp                            → green
                        //   4. LinkDown / NoLink                 → gray (dim)
                        //   5. unknown                           → gray
                        String dotColor = '#7a8a98'
                        String label
                        if (hpeHealth == 'Critical' || baseHealth == 'Critical') {
                            dotColor = '#d9534f'; label = 'Critical'
                        } else if (hpeHealth == 'Warning' || baseHealth == 'Warning') {
                            dotColor = '#ffc107'; label = 'Warning'
                        } else if (link == 'LinkUp') {
                            dotColor = '#28a745'
                            label = 'Link Up'
                            if (port.speedMbps) label += " \u00b7 ${port.speedMbps} Mbps"
                        } else if (link == 'LinkDown' || link == 'NoLink') {
                            dotColor = '#5a6a78'
                            label = 'Link Down'
                        } else if (link) {
                            label = link
                        } else {
                            label = '\u2014'
                        }
                        String portLabel = (port.portNumber ?: port.name ?: port.id ?: '?') as String
                        // Native browser tooltip — no JS needed for hover info.
                        String tip = "${a.name ?: a.id}: Port ${portLabel} \u2014 ${label}"
                        if (port.macs) tip += " (${((port.macs ?: []) as List).join(', ')})"
                        html.append("<div style=\"display:flex; align-items:center; gap:8px;\" title=\"${escapeHtml(tip)}\">")
                        html.append("<span style=\"display:inline-block; width:10px; height:10px; border-radius:50%; background:${dotColor}; box-shadow:0 0 6px ${dotColor}55; flex-shrink:0;\"></span>")
                        html.append('<div style="font-size:11px; line-height:1.3;">')
                        html.append("<div><strong>Port ${escapeHtml(portLabel)}</strong></div>")
                        html.append("<div style=\"opacity:0.65;\">${escapeHtml(label)}</div>")
                        html.append('</div>')
                        html.append('</div>')
                    }
                    html.append('</div>') // end ports row
                    // Compact legend
                    html.append('<div style="margin-top:12px; display:flex; flex-wrap:wrap; gap:16px; font-size:10px; opacity:0.55;">')
                    html.append('<span><span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:#28a745; vertical-align:middle; margin-right:5px;"></span>Link Up</span>')
                    html.append('<span><span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:#5a6a78; vertical-align:middle; margin-right:5px;"></span>Link Down</span>')
                    html.append('<span><span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:#ffc107; vertical-align:middle; margin-right:5px;"></span>Warning</span>')
                    html.append('<span><span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:#d9534f; vertical-align:middle; margin-right:5px;"></span>Critical</span>')
                    html.append('</div>')
                    html.append('</div>')
                }

                // ── DIMMs (v0.1.29) ──
                def dimms = (status.dimms ?: []) as List
                if (dimms) {
                    Integer totalGiB = dimms.sum { (it.capacityGiB as Integer) ?: 0 } as Integer
                    html.append('<details style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
                    html.append("<summary style=\"cursor:pointer; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;\">DIMMs &mdash; ${dimms.size()} populated, ${totalGiB} GiB total</summary>")
                    html.append('<table style="width:100%; margin-top:10px; font-size:12px; border-collapse:collapse;">')
                    html.append('<thead><tr style="opacity:0.55; font-size:10px; text-transform:uppercase; letter-spacing:0.05em;">')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Slot</th>')
                    html.append('<th style="text-align:right; padding:0 12px 6px 0;">Capacity</th>')
                    html.append('<th style="text-align:right; padding:0 12px 6px 0;">Speed</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Type</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Manufacturer</th>')
                    html.append('<th style="text-align:left; padding:0 0 6px 0;">Part Number</th>')
                    html.append('</tr></thead><tbody>')
                    dimms.each { d ->
                        html.append('<tr style="border-top:1px solid rgba(255,255,255,0.04);">')
                        html.append("<td style=\"padding:6px 12px 6px 0; font-family:ui-monospace,monospace;\">${escapeHtml((d.slot ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace;\">${d.capacityGiB ?: '\u2014'} GiB</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace; opacity:0.8;\">${d.speedMHz ?: '\u2014'} MHz</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; opacity:0.8;\">${escapeHtml((d.type ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; opacity:0.8;\">${escapeHtml((d.manufacturer ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 0; opacity:0.6; font-family:ui-monospace,monospace; font-size:11px;\">${escapeHtml((d.partNumber ?: '\u2014') as String)}</td>")
                        html.append('</tr>')
                    }
                    html.append('</tbody></table></details>')
                }

                // ── Active iLO sessions (v0.1.29) ──
                // Useful for "is the console already open?" before launching IRC.
                def activeSessions = (status.activeSessions ?: []) as List
                if (activeSessions) {
                    html.append('<details style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
                    html.append("<summary style=\"cursor:pointer; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;\">Active iLO Sessions &mdash; ${activeSessions.size()} other ${activeSessions.size() == 1 ? 'user' : 'users'}</summary>")
                    html.append('<table style="width:100%; margin-top:10px; font-size:12px; border-collapse:collapse;">')
                    html.append('<thead><tr style="opacity:0.55; font-size:10px; text-transform:uppercase; letter-spacing:0.05em;">')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">User</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">From IP</th>')
                    html.append('<th style="text-align:left; padding:0 0 6px 0;">Session</th>')
                    html.append('</tr></thead><tbody>')
                    activeSessions.each { s ->
                        html.append('<tr style="border-top:1px solid rgba(255,255,255,0.04);">')
                        html.append("<td style=\"padding:6px 12px 6px 0;\">${escapeHtml((s.userName ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; font-family:ui-monospace,monospace;\">${escapeHtml((s.sourceIp ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 0; opacity:0.8;\">${escapeHtml((s.sessionType ?: '\u2014') as String)}</td>")
                        html.append('</tr>')
                    }
                    html.append('</tbody></table></details>')
                }

                // ── Drives card (v0.1.28; v0.1.33 enriched with HPE OEM fields) ──
                def drives = (status.drives ?: []) as List
                if (drives) {
                    html.append('<div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                    html.append("<h3 style=\"margin:0 0 12px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;\">Drives &mdash; ${drives.size()}</h3>")
                    html.append('<table style="width:100%; font-size:12px; border-collapse:collapse;">')
                    html.append('<thead><tr style="opacity:0.55; font-size:10px; text-transform:uppercase; letter-spacing:0.05em;">')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Model</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Type</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Location</th>')
                    html.append('<th style="text-align:right; padding:0 12px 6px 0;">Capacity</th>')
                    html.append('<th style="text-align:right; padding:0 12px 6px 0;">Temp</th>')
                    html.append('<th style="text-align:right; padding:0 12px 6px 0;">Life Left</th>')
                    html.append('<th style="text-align:right; padding:0 12px 6px 0;">Power-On</th>')
                    html.append('<th style="text-align:left; padding:0 0 6px 0;">Health</th>')
                    html.append('</tr></thead><tbody>')
                    drives.each { d ->
                        String h = (d.health as String) ?: '\u2014'
                        String hColor = (h == 'OK') ? '#28a745' : (h == 'Warning' ? '#ffc107' : (h == 'Critical' ? '#d9534f' : '#7a8a98'))
                        String cap = d.capacityGB ? "${d.capacityGB} GB" : '\u2014'
                        String typ = ([d.mediaType, d.protocol].findAll { it }.join(' / ')) ?: '\u2014'
                        // Append form factor in parens when it's something specific (M.2, EDSFF, etc.)
                        if (d.formFactor && !(d.formFactor in ['Drive2_5', 'Drive3_5'])) {
                            typ += " (${humanizeFormFactor(d.formFactor as String)})"
                        }
                        boolean fp = (d.failurePredicted == true)
                        Integer tempC = d.tempC as Integer
                        String tempStr = formatTemp(tempC)
                        String tempColor = (d.tempHealth == 'Critical') ? '#d9534f' : (d.tempHealth == 'Warning' ? '#ffc107' : '#dbe6ef')
                        Integer life = d.lifeLeftPct as Integer
                        String lifeStr = (life != null) ? "${life}%" : '\u2014'
                        String lifeColor = (life != null && life < 10) ? '#d9534f' : ((life != null && life < 25) ? '#ffc107' : '#dbe6ef')
                        Long pohRaw = d.powerOnHours as Long
                        String poh = (pohRaw != null) ? humanizeHours(pohRaw) : '\u2014'
                        html.append('<tr style="border-top:1px solid rgba(255,255,255,0.04);">')
                        html.append("<td style=\"padding:6px 12px 6px 0;\">${escapeHtml((d.model ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; opacity:0.8;\">${escapeHtml(typ)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; opacity:0.7; font-family:ui-monospace,monospace; font-size:11px;\">${escapeHtml((d.location ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace;\">${escapeHtml(cap)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace; color:${tempColor};\">${escapeHtml(tempStr)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace; color:${lifeColor};\">${escapeHtml(lifeStr)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace; opacity:0.7; font-size:11px;\">${escapeHtml(poh)}</td>")
                        html.append("<td style=\"padding:6px 0; color:${hColor}; font-weight:600;\">${escapeHtml(h)}${fp ? ' \u26a0 failure predicted' : ''}</td>")
                        html.append('</tr>')
                    }
                    html.append('</tbody></table></div>')
                } else if (status.deviceDiscovery && status.deviceDiscovery != 'vMainDeviceDiscoveryComplete') {
                    // v0.1.32 — iLO is still discovering devices; surface this
                    // so users don't think the plugin is broken.
                    html.append('<div style="background:rgba(255,193,7,0.06); border-left:3px solid #ffc107; padding:10px 14px; margin-bottom:14px; font-size:12px;">')
                    html.append("<strong>Drive enumeration:</strong> iLO is still completing device discovery (DeviceDiscoveryComplete: <code>${escapeHtml(status.deviceDiscovery as String)}</code>). Refresh the tab in a minute.")
                    html.append('</div>')
                }

                // ── Volumes / RAID (v0.1.34) ──
                // Logical volumes from /Systems/1/Storage/<n>/Volumes. Skipped
                // entirely on systems without a RAID controller (e.g. Gen11
                // MicroServer without an MR/SR/NS204 card) since the walk
                // returns zero members. When a rebuild/init is in progress,
                // the operation name and percent show in the status column.
                List storVolumes = (status.volumes ?: []) as List
                if (storVolumes) {
                    html.append('<div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                    html.append("<h3 style=\"margin:0 0 12px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;\">Volumes &mdash; ${storVolumes.size()}</h3>")
                    html.append('<table style="width:100%; font-size:12px; border-collapse:collapse;">')
                    html.append('<thead><tr style="opacity:0.55; font-size:10px; text-transform:uppercase; letter-spacing:0.05em;">')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Volume</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Controller</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">RAID</th>')
                    html.append('<th style="text-align:right; padding:0 12px 6px 0;">Capacity</th>')
                    html.append('<th style="text-align:right; padding:0 12px 6px 0;">Drives</th>')
                    html.append('<th style="text-align:left; padding:0 0 6px 0;">Status</th>')
                    html.append('</tr></thead><tbody>')
                    storVolumes.each { Map v ->
                        String hv = (v.health as String) ?: '\u2014'
                        String hColor = (hv == 'OK') ? '#28a745' : (hv == 'Warning' ? '#ffc107' : (hv == 'Critical' ? '#d9534f' : '#7a8a98'))
                        String capStr = v.capacityGB ? "${v.capacityGB} GB" : '\u2014'
                        String raidStr = (v.raidType as String) ?: '\u2014'
                        String nameStr = (v.name ?: v.id ?: '\u2014') as String
                        // Boot-volume marker — small star prefix
                        if (v.bootable) nameStr = "\u2605 ${nameStr}"
                        // Status cell: health pill + optional rebuild/init/encrypted flags
                        String statusCell = "<span style=\"color:${hColor};\">${escapeHtml(hv)}</span>"
                        if (v.opName) {
                            String opStr = humanizeVolumeOp(v.opName as String)
                            if (v.opPct != null) opStr += " ${v.opPct}%"
                            statusCell += " &middot; <span style=\"color:#ffc107;\">${escapeHtml(opStr)}</span>"
                        }
                        if (v.encrypted) {
                            statusCell += ' &middot; <span style="opacity:0.7;">encrypted</span>'
                        }
                        html.append('<tr style="border-top:1px solid rgba(255,255,255,0.04);">')
                        html.append("<td style=\"padding:6px 12px 6px 0;\">${escapeHtml(nameStr)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; opacity:0.7;\">${escapeHtml((v.controller ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; font-family:ui-monospace,monospace;\">${escapeHtml(raidStr)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace;\">${escapeHtml(capStr)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace; opacity:0.8;\">${v.driveCount ?: '\u2014'}</td>")
                        html.append("<td style=\"padding:6px 0; font-weight:600;\">${statusCell}</td>")
                        html.append('</tr>')
                    }
                    html.append('</tbody></table></div>')
                }

                // ── Cooling Zone (v0.1.32) ──
                // All temperature sensors iLO knows about — CPU, ambient, DIMMs,
                // PCIe slots, drive bays, M.2, VRMs, etc. Collapsed by default
                // since most operators only care if something is hot.
                def allTemps = (status.allTemperatures ?: []) as List
                if (allTemps) {
                    html.append('<details style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
                    html.append("<summary style=\"cursor:pointer; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;\">Cooling Zones &mdash; ${allTemps.size()} sensors</summary>")
                    html.append('<table style="width:100%; margin-top:10px; font-size:12px; border-collapse:collapse;">')
                    html.append('<thead><tr style="opacity:0.55; font-size:10px; text-transform:uppercase; letter-spacing:0.05em;">')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Sensor</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Context</th>')
                    html.append('<th style="text-align:right; padding:0 12px 6px 0;">Reading</th>')
                    html.append('<th style="text-align:right; padding:0 12px 6px 0;">Warn @</th>')
                    html.append('<th style="text-align:right; padding:0 0 6px 0;">Crit @</th>')
                    html.append('</tr></thead><tbody>')
                    allTemps.each { t ->
                        Integer c = t.c as Integer
                        Integer warnAt = t.upperWarn as Integer
                        Integer critAt = t.upperCrit as Integer
                        String readColor = '#dbe6ef'
                        if (critAt && c >= critAt) readColor = '#d9534f'
                        else if (warnAt && c >= warnAt) readColor = '#ffc107'
                        html.append('<tr style="border-top:1px solid rgba(255,255,255,0.04);">')
                        html.append("<td style=\"padding:6px 12px 6px 0;\">${escapeHtml((t.name ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; opacity:0.7;\">${escapeHtml((t.physicalCtx ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace; color:${readColor}; font-weight:600;\">${formatTemp(c)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; text-align:right; font-family:ui-monospace,monospace; opacity:0.55;\">${formatTemp(warnAt)}</td>")
                        html.append("<td style=\"padding:6px 0; text-align:right; font-family:ui-monospace,monospace; opacity:0.55;\">${formatTemp(critAt)}</td>")
                        html.append('</tr>')
                    }
                    html.append('</tbody></table></details>')
                }

                // ── Firmware Inventory (v0.1.32) ──
                def fw = (status.firmwareInventory ?: []) as List
                if (fw) {
                    html.append('<details style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
                    html.append("<summary style=\"cursor:pointer; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;\">Firmware Inventory &mdash; ${fw.size()} components</summary>")
                    html.append('<table style="width:100%; margin-top:10px; font-size:12px; border-collapse:collapse;">')
                    html.append('<thead><tr style="opacity:0.55; font-size:10px; text-transform:uppercase; letter-spacing:0.05em;">')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Component</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Version</th>')
                    html.append('<th style="text-align:left; padding:0 0 6px 0;">Updateable</th>')
                    html.append('</tr></thead><tbody>')
                    fw.sort { (it.name as String) }.each { f ->
                        Boolean upd = f.updateable as Boolean
                        html.append('<tr style="border-top:1px solid rgba(255,255,255,0.04);">')
                        html.append("<td style=\"padding:6px 12px 6px 0;\">${escapeHtml((f.name ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 12px 6px 0; font-family:ui-monospace,monospace; opacity:0.85;\">${escapeHtml((f.version ?: '\u2014') as String)}</td>")
                        html.append("<td style=\"padding:6px 0; opacity:0.7;\">${upd == true ? 'Yes' : (upd == false ? 'No' : '\u2014')}</td>")
                        html.append('</tr>')
                    }
                    html.append('</tbody></table></details>')
                }

                // ── Recent events (collapsible) ──
                def events = (status.recentEvents ?: []) as List
                if (events) {
                    html.append('<details style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
                    html.append('<summary style="cursor:pointer; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">Recent Events &mdash; last ' + events.size() + '</summary>')
                    html.append('<table style="width:100%; margin-top:10px; font-size:11px; border-collapse:collapse;">')
                    events.each { e ->
                        String sev = (e.severity as String) ?: 'OK'
                        String sevColor = sev == 'Critical' ? '#d9534f' : (sev == 'Warning' ? '#ffc107' : '#7a8a98')
                        html.append('<tr style="border-top:1px solid rgba(255,255,255,0.04);">')
                        html.append("<td style=\"padding:6px 10px 6px 0; opacity:0.55; white-space:nowrap; font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;\">${escapeHtml((e.created ?: '') as String)}</td>")
                        html.append("<td style=\"padding:6px 10px 6px 0; color:${sevColor}; font-weight:600; white-space:nowrap;\">${escapeHtml(sev)}</td>")
                        html.append("<td style=\"padding:6px 0;\">${escapeHtml((e.message ?: '') as String)}</td>")
                        html.append('</tr>')
                    }
                    html.append('</table></details>')
                }
            }

            // Configure-access card (only if not configured)
            if (!cfg.configured) {
                html.append('<div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.06); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                html.append('<h3 style="margin:0 0 8px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">Configure access</h3>')
                html.append('<p style="margin:0 0 8px; font-size:12px;">This host has no iLO host or credential configured. Add these labels (Actions → Edit → Labels):</p>')
                html.append('<ul style="margin:0; padding-left:18px; font-size:12px; line-height:1.7;">')
                html.append('<li><code>ilo-host:&lt;ip-or-hostname&gt;</code></li>')
                html.append('<li><code>ilo-cred:&lt;credential-id&gt;</code> &mdash; the numeric ID of a credential under Infrastructure &rarr; Trust &rarr; Credentials</li>')
                html.append('<li><code>ilo-verify-ssl:true</code> (optional, default off for self-signed certs)</li>')
                html.append('</ul>')
                if (labelsCsv) {
                    html.append("<p style=\"margin:10px 0 0; opacity:0.6; font-size:11px;\">Labels currently on this host: <code>${escapeHtml(labelsCsv)}</code></p>")
                }
                html.append('</div>')
            }

            // Diagnostics (collapsed by default)
            html.append('<details style="background:rgba(255,255,255,0.01); padding:10px 14px; border-radius:4px; margin-top:14px;">')
            html.append('<summary style="cursor:pointer; font-size:11px; text-transform:uppercase; letter-spacing:0.05em; opacity:0.5;">Diagnostics (v0.1.35)</summary>')
            html.append('<table style="font-family:monospace; font-size:11px; width:100%; margin-top:10px;">')
            diag.each { k, v ->
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px; padding-bottom:2px;\">${escapeHtml(k as String)}</td><td>${escapeHtml(v?.toString())}</td></tr>")
            }
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">labelsCsv</td><td>${escapeHtml(labelsCsv)}</td></tr>")
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">credSource</td><td>${escapeHtml(credSource ?: '')}</td></tr>")
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">readonly</td><td>${cfg.readonly ? 'true (credentials/launch UI suppressed)' : 'false'}</td></tr>")
            // v0.1.25: surface CSP nonce status — if "missing", one-click JS is
            // disabled and the tab falls back to the two-button manual flow.
            String diagNonce = getCspNonce()
            String nonceStr = diagNonce ? "present (length=${diagNonce.length()}, one-click ENABLED)" : 'missing (one-click DISABLED, two-button manual flow active)'
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">cspNonce</td><td>${escapeHtml(nonceStr)}</td></tr>")
            // v0.1.32: surface drive-search diagnostic + device discovery
            // state so we can see at a glance why drives are/aren't showing.
            if (status?.deviceDiscovery) {
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">deviceDiscovery</td><td>${escapeHtml(status.deviceDiscovery as String)}</td></tr>")
            }
            if (status?.amsStatus) {
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">amsStatus</td><td>${escapeHtml(status.amsStatus as String)} <span style=\"opacity:0.55;\">(direct-attached SATA needs AMS running on host)</span></td></tr>")
            }
            Map dDiag = (status?.drivesDiag ?: [:]) as Map
            if (dDiag) {
                String dDiagStr = dDiag.collect { k, v -> "${k}=${v}" }.join(', ')
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">drivesDiag</td><td style=\"font-family:ui-monospace,monospace; font-size:11px;\">${escapeHtml(dDiagStr)}</td></tr>")
            }
            // v0.1.33: surface partial-error info so a failure in one read
            // block is visible (rather than just silently missing cards).
            if (status?.partialError) {
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">partialError</td><td style=\"color:#ffc107; font-family:ui-monospace,monospace; font-size:11px;\">${escapeHtml(status.partialError as String)}</td></tr>")
            }
            // v0.1.34: surface data source/count for the four new cards so
            // we can tell at a glance whether the Power Trend / Network
            // Adapters / Volumes blocks actually got data or hit empty
            // collections.
            if (status?.success) {
                Map ptrendDiag = (status.powerTrend ?: [:]) as Map
                int histSize = ((ptrendDiag.history ?: []) as List).size()
                String ptrendStr = "source=${ptrendDiag.source ?: 'none'}, history=${histSize} samples, current=${ptrendDiag.current ?: 'null'}"
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">powerTrend</td><td style=\"font-family:ui-monospace,monospace; font-size:11px;\">${escapeHtml(ptrendStr)}</td></tr>")
                List naDiag = (status.networkAdapters ?: []) as List
                int totalPortsDiag = (naDiag.sum { ((it.ports ?: []) as List).size() } ?: 0) as Integer
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">networkAdapters</td><td style=\"font-family:ui-monospace,monospace; font-size:11px;\">${naDiag.size()} adapters, ${totalPortsDiag} ports</td></tr>")
                List volDiag = (status.volumes ?: []) as List
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">volumes</td><td style=\"font-family:ui-monospace,monospace; font-size:11px;\">${volDiag.size()} volume${volDiag.size() == 1 ? '' : 's'}</td></tr>")
            }
            html.append('</table></details>')

            html.append('</div>')

            log.info("iLO inline render — configured=${cfg.configured}, iloHost=${cfg.iloHost}, status.success=${status?.success}, errorMsg=${errorMsg}")
            return HTMLResponse.success(html.toString())
        } catch (Throwable t) {
            log.error("iLO renderTemplate threw: ${t.message}", t)
            return HTMLResponse.success(
                    '<div style="padding:20px;color:#c00">iLO tab failed to load: ' +
                            (t.class.simpleName) + ': ' + (t.message ?: '') + '</div>')
        }
    }

    private static String statusItem(String label, String value, String cls) {
        String color
        switch (cls) {
            case 'ok':   color = '#28a745'; break
            case 'warn': color = '#ffc107'; break
            case 'bad':  color = '#d9534f'; break
            // v0.1.31: 'info' = blue, matches the actual color of HPE's UID
            // (chassis identifier) LED on the physical server's front panel.
            case 'info': color = '#3b9eff'; break
            default:     color = '#7a8a98'
        }
        return """<div>\
<div style="font-size:10px; text-transform:uppercase; letter-spacing:0.05em; opacity:0.55; margin-bottom:4px;">${escapeHtml(label)}</div>\
<div><span style="display:inline-block; padding:2px 10px; border-radius:10px; background:rgba(255,255,255,0.05); color:${color}; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.03em;">${escapeHtml(value ?: '?')}</span></div>\
</div>"""
    }

    private static String kvItem(String label, String value) {
        return """<div>\
<div style="font-size:10px; text-transform:uppercase; letter-spacing:0.05em; opacity:0.55; margin-bottom:4px;">${escapeHtml(label)}</div>\
<div style="font-size:13px;">${value ?: '&mdash;'}</div>\
</div>"""
    }

    /**
     * v0.1.35: dual Celsius/Fahrenheit temperature formatter. Redfish
     * always reports temperatures in Celsius (`ReadingCelsius`,
     * `CurrentTemperatureCelsius`), so we keep C as the canonical source
     * and append a rounded F value rather than offer a unit-toggle. Same
     * output everywhere — System card, Cooling Zones table, Drives table —
     * so an international audience never has to do the math and a US-based
     * operator gets the unit they think in without any configuration.
     *
     * Returns the em-dash glyph for null so the column still aligns.
     */
    private static String formatTemp(Integer c) {
        if (c == null) return '\u2014'
        int f = (int) Math.round((c * 9.0d / 5.0d) + 32.0d)
        return "${c} \u00b0C / ${f} \u00b0F"
    }

    /**
     * Turn Redfish's PascalCase BootProgress.LastState values into something
     * readable. Redfish uses values like "OSRunning",
     * "SystemHardwareInitializationComplete", "SetupEntered",
     * "PrimaryProcessorInitializationStarted" etc. We split on capital
     * boundaries to get "OS Running", "System Hardware Initialization
     * Complete", etc.
     */
    private static String humanizeBootProgress(String s) {
        if (!s) return ''
        // Insert a space before each capital that follows a lowercase, but
        // keep runs of capitals together (so "OSRunning" → "OS Running" not
        // "O S Running").
        return s.replaceAll(/([a-z])([A-Z])/, '$1 $2').replaceAll(/([A-Z]+)([A-Z][a-z])/, '$1 $2')
    }

    /**
     * Map an internal AggregateHealthStatus key (e.g. "biosOrHardware",
     * "fanRedundancy", "powerSupplies") to a user-friendly label for the
     * health drill-down banner.
     */
    private static String humanizeHealthKey(String k) {
        switch (k) {
            case 'biosOrHardware':   return 'BIOS/Hardware'
            case 'fans':             return 'Fans'
            case 'fanRedundancy':    return 'Fan Redundancy'
            case 'memory':           return 'Memory'
            case 'network':          return 'Network'
            case 'powerSupplies':    return 'Power Supplies'
            case 'psuRedundancy':    return 'PSU Redundancy'
            case 'processors':       return 'Processors'
            case 'storage':          return 'Storage'
            case 'temperatures':     return 'Temperatures'
            case 'smartStorageBatt': return 'Smart Storage Battery'
            default:                 return k
        }
    }

    /**
     * Turn a Redfish DriveFormFactor enum value into a shorter human label.
     * (See iLO 6 1.77 storage resource definitions for the full enum.)
     */
    private static String humanizeFormFactor(String ff) {
        switch (ff) {
            case 'Drive3_5':           return '3.5"'
            case 'Drive2_5':           return '2.5"'
            case 'M2_2230':            return 'M.2 2230'
            case 'M2_2242':            return 'M.2 2242'
            case 'M2_2260':            return 'M.2 2260'
            case 'M2_2280':            return 'M.2 2280'
            case 'M2_22110':           return 'M.2 22110'
            case 'U2':                 return 'U.2'
            case 'EDSFF_1U_Long':      return 'E1.L'
            case 'EDSFF_1U_Short':     return 'E1.S'
            case 'EDSFF_E3_Short':     return 'E3.S'
            case 'EDSFF_E3_Long':      return 'E3.L'
            case 'PCIeSlotFullLength': return 'PCIe AIC (full)'
            case 'PCIeSlotLowProfile': return 'PCIe AIC (low)'
            case 'PCIeHalfLength':     return 'PCIe AIC (half)'
            default:                   return ff
        }
    }

    /**
     * Render an hour count in a readable form. iLO reports PowerOnHours as
     * an integer count of lifetime hours, which gets big fast (a year is
     * 8760h). Show as "1.2 yr" for >= 1 year, "84d" for >= 30 days, else
     * just hours.
     */
    private static String humanizeHours(long h) {
        if (h <= 0) return '\u2014'
        if (h >= 8760) {
            double years = h / 8760d
            return String.format('%.1f yr', years)
        }
        if (h >= 720) {
            long days = h / 24L
            return "${days}d"
        }
        return "${h}h"
    }

    /**
     * Render an inline SVG sparkline of historical power-draw samples.
     * Input: list of maps each with .avg (preferred) or .max integer fields,
     * oldest at index 0, newest at the end (right edge of the chart).
     * Output: a div containing an SVG ~360x70 px with a polyline + subtle
     * area fill. CSS-only, no JS, no external resources.
     *
     * Returns empty string when there's not enough data to draw (fewer
     * than 2 non-null samples).
     */
    private static String renderPowerSparkline(List samples) {
        if (!samples || samples.size() < 2) return ''
        List<Integer> vals = (samples.collect { (it.avg ?: it.max) as Integer }.findAll { it != null }) as List<Integer>
        if (vals.size() < 2) return ''
        int w = 360
        int h = 70
        int pad = 4
        int vMin = vals.min() as Integer
        int vMax = vals.max() as Integer
        // Flat data: nudge max so we don't divide by zero. Polyline will
        // render as a horizontal line near the bottom edge — visually
        // truthful since "no variation" is the actual signal.
        if (vMax == vMin) vMax = vMin + 1
        int n = vals.size()
        double innerW = w - pad * 2
        double innerH = h - pad * 2
        StringBuilder pts = new StringBuilder()
        StringBuilder areaPts = new StringBuilder()
        for (int i = 0; i < n; i++) {
            double x = pad + (i / (double)(n - 1)) * innerW
            double y = h - pad - ((vals[i] - vMin) / (double)(vMax - vMin)) * innerH
            String xStr = String.format('%.1f', x)
            String yStr = String.format('%.1f', y)
            if (i > 0) { pts.append(' '); areaPts.append(' ') }
            pts.append(xStr).append(',').append(yStr)
            areaPts.append(xStr).append(',').append(yStr)
        }
        // Close the area polygon down to the baseline so the fill has a
        // floor (otherwise just the polyline + its own fill would create
        // a self-intersecting shape).
        double rightX = w - pad
        double leftX = pad
        double bottomY = h - pad
        areaPts.append(' ').append(String.format('%.1f', rightX)).append(',').append(String.format('%.1f', bottomY))
        areaPts.append(' ').append(String.format('%.1f', leftX)).append(',').append(String.format('%.1f', bottomY))

        StringBuilder svg = new StringBuilder()
        svg.append('<div style="flex:1; min-width:240px;">')
        svg.append("<svg viewBox=\"0 0 ${w} ${h}\" preserveAspectRatio=\"none\" style=\"width:100%; height:70px; display:block; background:rgba(255,255,255,0.02); border-radius:3px;\">")
        svg.append("<polygon points=\"${areaPts.toString()}\" fill=\"rgba(1,169,130,0.18)\" stroke=\"none\"/>")
        svg.append("<polyline points=\"${pts.toString()}\" fill=\"none\" stroke=\"#01A982\" stroke-width=\"1.5\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>")
        svg.append('</svg>')
        svg.append('<div style="display:flex; justify-content:space-between; font-size:9px; opacity:0.45; margin-top:4px; padding:0 4px;">')
        svg.append("<span>oldest</span><span>${n} samples</span><span>now</span>")
        svg.append('</div>')
        svg.append('</div>')
        return svg.toString()
    }

    /**
     * Turn a Redfish Volume Operations[].OperationName into a readable
     * label. Common values: Initialize, Format, Rebuild, Encrypt,
     * ChangeRAIDType, Expand, Migrate, Decrypt, Reseal.
     */
    private static String humanizeVolumeOp(String op) {
        if (!op) return ''
        return op.replaceAll(/([a-z])([A-Z])/, '$1 $2').replaceAll(/([A-Z]+)([A-Z][a-z])/, '$1 $2')
    }

    private static Map buildDiagnostics(ComputeServer server) {
        Map d = [
                paramNull       : server == null,
                paramClass      : null,
                directId        : null,
                directName      : null,
                directVendor    : null,
                directModel     : null,
                propsThrew      : null,
                propsKeyCount   : null,
                propsHasId      : null,
                propsHasVendor  : null,
                idFromProps     : null,
                vendorFromProps : null,
                modelFromProps  : null,
                getterIdThrew   : null,
        ]
        if (server == null) return d
        try { d.paramClass = server.getClass()?.name } catch (Throwable ignored) {}
        try { d.directId = server.id } catch (Throwable t) { d.getterIdThrew = t.class.simpleName + ': ' + t.message }
        try { d.directName = server.name } catch (Throwable ignored) {}
        try { d.directVendor = server.hardwareProductVendor } catch (Throwable ignored) {}
        try { d.directModel = server.hardwareProductName } catch (Throwable ignored) {}
        try {
            def props = server.properties
            if (props instanceof Map) {
                d.propsKeyCount = props.size()
                d.propsHasId = props.containsKey('id')
                d.propsHasVendor = props.containsKey('hardwareProductVendor')
                d.idFromProps = props['id']
                d.vendorFromProps = props['hardwareProductVendor']
                d.modelFromProps = props['hardwareProductName']
            } else {
                d.propsThrew = 'getProperties() did not return a Map: ' + (props?.getClass()?.name ?: 'null')
            }
        } catch (Throwable t) {
            d.propsThrew = t.class.simpleName + ': ' + t.message
        }
        return d
    }

    private static String safeId(ComputeServer server) {
        try { return server?.id?.toString() } catch (Throwable t) { return null }
    }
    private static String safeName(ComputeServer server) {
        try { return server?.name as String } catch (Throwable t) { return null }
    }

    /** Minimal HTML escape to keep injected values from breaking the layout. */
    private static String escapeHtml(String s) {
        if (s == null) return ''
        return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }

    /**
     * Escape a string for use as a JSON string literal value (between the
     * surrounding double quotes). Handles backslash, double-quote, and the
     * standard control-character escapes. Used by the v0.1.24 text/plain
     * JSON-injection form trick.
     */
    private static String jsonStringEscape(String s) {
        if (s == null) return ''
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i)
            switch (c) {
                case '"':  sb.append('\\"'); break
                case '\\': sb.append('\\\\'); break
                case '\b': sb.append('\\b'); break
                case '\f': sb.append('\\f'); break
                case '\n': sb.append('\\n'); break
                case '\r': sb.append('\\r'); break
                case '\t': sb.append('\\t'); break
                default:
                    if (c < 0x20) {
                        sb.append(String.format('\\u%04x', (int) c))
                    } else {
                        sb.append(c)
                    }
            }
        }
        return sb.toString()
    }

    /**
     * Read the per-request CSP nonce that Morpheus stamps on its
     * `Content-Security-Policy: script-src 'self' 'nonce-XXX' 'strict-dynamic'`
     * header, so we can emit a nonced inline <script> tag that bypasses the
     * strict-dynamic block on inline JS.
     *
     * Reads the "js-nonce" attribute off the current HttpServletRequest via
     * Spring's RequestContextHolder. We use Class.forName + Groovy dynamic
     * dispatch rather than static imports because the Spring web classes
     * aren't published as a transitive compile-time dependency of
     * morpheus-plugin-api 1.3.1 — they're only present at runtime inside
     * the Morpheus appliance's Spring environment.
     *
     * Returns empty string if anything fails (Spring classes missing, not
     * in a request context, attribute absent). Caller gracefully degrades
     * to the two-button manual launch flow.
     */
    private static String getCspNonce() {
        try {
            Class<?> holderClass = Class.forName('org.springframework.web.context.request.RequestContextHolder')
            def attrs = holderClass.getMethod('getRequestAttributes').invoke(null)
            if (attrs == null) return ''
            // attrs is a ServletRequestAttributes at runtime. Groovy resolves
            // getRequest() dynamically against the concrete instance, even
            // though the compile-time type is Object.
            def req = attrs.getRequest()
            if (req == null) return ''
            def n = req.getAttribute('js-nonce')
            return n ? n.toString() : ''
        } catch (Throwable t) {
            // Spring classes not available, or unexpected error
        }
        return ''
    }
}
