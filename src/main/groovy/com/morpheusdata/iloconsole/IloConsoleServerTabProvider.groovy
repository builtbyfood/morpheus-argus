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
            // v0.1.38 — UID indicator LED action. Buttons in the System
            // card's UID cell navigate to ?argusUidAction=Off|Lit|Blinking.
            // We pick it up here via the same RequestContextHolder trick
            // we use for the CSP nonce and forward it to collectStatus,
            // which does the PATCH after login and before the bulk reads.
            // Whitelist the value defensively — anything outside the
            // accepted set is ignored (no PATCH, no error banner). The
            // nonce'd JS at the bottom of the tab strips the param from
            // the URL via history.replaceState after render so a browser
            // refresh doesn't re-fire the action.
            String uidAction = null
            try {
                String raw = getRequestParam('argusUidAction')
                if (raw in ['Off', 'Lit', 'Blinking']) uidAction = raw
            } catch (Throwable ignored) {}
            if (cfg.configured) {
                Map credResult = com.morpheusdata.iloconsole.services.IloCredentialService.loadCredential(morpheusContext, cfg.credentialId as Long)
                if (credResult?.error) {
                    errorMsg = credResult.error
                    credSource = credResult.source
                } else {
                    credSource = credResult.source
                    try {
                        RedfishClient client = new RedfishClient(cfg.iloHost as String, cfg.verifySsl as boolean)
                        status = client.collectStatus(credResult.username as String, credResult.password as String, uidAction)
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

            // ── v0.1.36 / v0.1.37 — Theme variables (light-mode fix) ────
            //
            // v0.1.36 bug fixed in 0.1.37: the dark-mode variable
            // declarations were self-referential
            // (`--argus-bg-card: var(--argus-bg-card)`) due to an over-
            // aggressive sed pass during the v0.1.36 light-mode work.
            // The browser ignored the recursive declarations and resolved
            // them to nothing, which made card backgrounds disappear in
            // dark mode (saved by Morpheus's dark page bg showing through).
            // 0.1.37 restores the literal rgba values for dark-mode.
            //
            // v0.1.36 also missed Morpheus 9's actual theme class — none
            // of body.theme-light, body.light-theme, body[data-theme],
            // html.theme-light, html.light, .theme-light matched — so the
            // variable stayed on dark-mode values rendering as pale text
            // on white. v0.1.37 adds a CSP-nonced JS snippet that reads
            // body's computed background brightness and sets
            // data-mode="light"|"dark" on the .argus-tab wrapper. CSS
            // then switches reliably regardless of how Morpheus
            // implements its theme.
            //
            // Light-mode base text is strengthened from v0.1.36's #1a2530
            // to pure black (#000) so the heavily-used opacity:0.55
            // inline styles for label text still read with good contrast.
            // Additional CSS rules below boost specific inline opacity
            // values in light mode via attribute-substring matching.
            //
            // Status colors (#28a745, #ffc107, #d9534f, #01A982, #3b9eff)
            // are intentionally NOT variabilized — they carry semantic
            // meaning and must stay color-coded regardless of theme.
            html.append('<style>')
            // Dark theme (default) — values match v0.1.35 exactly.
            html.append('.argus-tab {')
            html.append('--argus-text:#dbe6ef;')
            html.append('--argus-bg-card:rgba(255,255,255,0.02);')
            html.append('--argus-bg-header:rgba(255,255,255,0.03);')
            html.append('--argus-bg-pill:rgba(255,255,255,0.05);')
            html.append('--argus-border:rgba(255,255,255,0.06);')
            html.append('--argus-border-soft:rgba(255,255,255,0.04);')
            html.append('--argus-bg-diag:rgba(255,255,255,0.01);')
            html.append('--argus-btn-border:rgba(255,255,255,0.15);')
            html.append('}')
            // Light theme overrides — match every Morpheus theme class
            // pattern we have seen, AND the JS-set data-mode="light"
            // attribute (the reliable detection path).
            html.append('body.theme-light .argus-tab,')
            html.append('body.light-theme .argus-tab,')
            html.append('body[data-theme="light"] .argus-tab,')
            html.append('html.theme-light .argus-tab,')
            html.append('html.light .argus-tab,')
            html.append('.theme-light .argus-tab,')
            html.append('.argus-tab[data-mode="light"] {')
            html.append('--argus-text:#000000;')
            html.append('--argus-bg-card:rgba(0,0,0,0.03);')
            html.append('--argus-bg-header:rgba(0,0,0,0.05);')
            html.append('--argus-bg-pill:rgba(0,0,0,0.06);')
            html.append('--argus-border:rgba(0,0,0,0.10);')
            html.append('--argus-border-soft:rgba(0,0,0,0.06);')
            html.append('--argus-bg-diag:rgba(0,0,0,0.025);')
            html.append('--argus-btn-border:rgba(0,0,0,0.20);')
            html.append('}')
            // Light-mode inline-opacity boosts. Many places use
            // style="opacity:0.55" for label text (POWER, HEALTH, iLO,
            // etc.). With pure-black base in light mode the contrast is
            // OK but the labels still look washed out — boosting opacity
            // to ~0.8 makes them clearly legible. CSS attribute-substring
            // selectors target the inline styles and override with
            // !important — that is the only way to win specificity
            // against inline style.
            html.append('.argus-tab[data-mode="light"] [style*="opacity:0.4"]  { opacity:0.70 !important; }')
            html.append('.argus-tab[data-mode="light"] [style*="opacity:0.45"] { opacity:0.72 !important; }')
            html.append('.argus-tab[data-mode="light"] [style*="opacity:0.5"]  { opacity:0.78 !important; }')
            html.append('.argus-tab[data-mode="light"] [style*="opacity:0.55"] { opacity:0.82 !important; }')
            html.append('.argus-tab[data-mode="light"] [style*="opacity:0.6"]  { opacity:0.85 !important; }')
            html.append('.argus-tab[data-mode="light"] [style*="opacity:0.65"] { opacity:0.85 !important; }')
            html.append('.argus-tab[data-mode="light"] [style*="opacity:0.7"]  { opacity:0.88 !important; }')
            html.append('.argus-tab[data-mode="light"] [style*="opacity:0.75"] { opacity:0.90 !important; }')
            html.append('.argus-tab[data-mode="light"] [style*="opacity:0.8"]  { opacity:0.92 !important; }')
            // OS-level light preference as last-resort fallback when both
            // theme-class and JS data-mode detection miss. Skip if the
            // tab is explicitly tagged data-mode="dark".
            html.append('@media (prefers-color-scheme: light) {')
            html.append('.argus-tab:not([data-mode="dark"]) {')
            html.append('--argus-text:#000000;')
            html.append('--argus-bg-card:rgba(0,0,0,0.03);')
            html.append('--argus-bg-header:rgba(0,0,0,0.05);')
            html.append('--argus-bg-pill:rgba(0,0,0,0.06);')
            html.append('--argus-border:rgba(0,0,0,0.10);')
            html.append('--argus-border-soft:rgba(0,0,0,0.06);')
            html.append('--argus-bg-diag:rgba(0,0,0,0.025);')
            html.append('--argus-btn-border:rgba(0,0,0,0.20);')
            html.append('}')
            html.append('}')
            html.append('</style>')

            html.append('<div class="argus-tab" style="padding:0; font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',sans-serif; color:var(--argus-text); font-size:13px;">')

            // ── v0.1.37 — JS theme detection (runs always) ───────────────
            //
            // v0.1.36's CSS-only theme detection missed Morpheus 9's
            // actual theme class. Rather than guess at more class names,
            // v0.1.37 reads the body's computed background color at
            // runtime, computes its luminance, and sets data-mode="light"
            // or data-mode="dark" on every .argus-tab element. The CSS
            // rules above then switch reliably regardless of how Morpheus
            // names its theme.
            //
            // Runs OUTSIDE the readonly/launchData gates so it works for
            // hosts in any state. Defensive try/catch — failure leaves
            // the dark-mode default.
            //
            // Requires the CSP nonce. If the nonce is unavailable (e.g.
            // appliance running without Spring CSP, or version mismatch),
            // theme detection silently falls back to CSS-only matching
            // (which works in Morpheus versions whose theme class
            // matches our list).
            String themeNonce = getCspNonce()
            if (themeNonce) {
                html.append("<script nonce=\"${escapeHtml(themeNonce)}\">")
                html.append("""
(function() {
  try {
    // Wait for body to be available, then detect theme from its bg color.
    var detectTheme = function() {
      try {
        var body = document.body;
        if (!body) return;
        var bg = window.getComputedStyle(body).backgroundColor;
        // Parse "rgb(r, g, b)" or "rgba(r, g, b, a)" — Morpheus always
        // returns one of these for body bg.
        var m = bg.match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)/);
        if (!m) return;
        var r = parseInt(m[1], 10);
        var g = parseInt(m[2], 10);
        var bl = parseInt(m[3], 10);
        // Relative luminance approximation (Rec. 709 coefficients,
        // good enough for a binary light/dark decision).
        var lum = (0.299 * r + 0.587 * g + 0.114 * bl) / 255;
        var mode = lum > 0.5 ? 'light' : 'dark';
        var tabs = document.getElementsByClassName('argus-tab');
        for (var i = 0; i < tabs.length; i++) {
          tabs[i].setAttribute('data-mode', mode);
        }
      } catch (e) { /* failure leaves default theme */ }
    };
    // Run once now; some renders may have body bg already computed.
    detectTheme();
    // Run again after DOM is fully ready in case bg was set by late CSS.
    if (document.readyState !== 'complete') {
      window.addEventListener('load', detectTheme);
    }

    // v0.1.38 — UID indicator LED action wiring.
    //
    // Each UID button in the System card carries data-uid="Off|Lit|Blinking".
    // Clicking navigates the iLO tab to the current URL with
    // ?argusUidAction=<value> appended (preserving other query params like
    // tab=iLO). renderTemplate picks the param up via getRequestParam(),
    // forwards it to RedfishClient.collectStatus, and the System card
    // re-renders with the new badge state.
    //
    // We also strip argusUidAction from the URL on every page load so
    // that a browser refresh doesn't re-fire the action — important
    // since the param sticks around in the address bar after the
    // server-side navigation.
    var stripArgusUidAction = function() {
      try {
        var url = new URL(window.location.href);
        if (url.searchParams.has('argusUidAction')) {
          url.searchParams.delete('argusUidAction');
          window.history.replaceState(null, '', url.toString());
        }
      } catch (e) { /* old browser, no URL constructor — just leave it */ }
    };
    stripArgusUidAction();

    var wireUidButtons = function() {
      try {
        var btns = document.querySelectorAll('.argus-uid-btn');
        for (var i = 0; i < btns.length; i++) {
          (function(btn) {
            btn.addEventListener('click', function(ev) {
              try {
                ev.preventDefault();
                var action = btn.getAttribute('data-uid');
                if (!action) return;
                var url = new URL(window.location.href);
                url.searchParams.set('argusUidAction', action);
                // Small visual cue so the user knows the click registered
                // — the page will reload momentarily.
                btn.style.opacity = '0.5';
                btn.disabled = true;
                window.location.href = url.toString();
              } catch (e) { /* fallback: do nothing, leave click as no-op */ }
            });
          })(btns[i]);
        }
      } catch (e) { /* querySelectorAll not available — nothing to wire */ }
    };
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', wireUidButtons);
    } else {
      wireUidButtons();
    }
  } catch (err) {
    // Theme/UID setup failure is non-fatal — CSS-only matching still
    // applies via prefers-color-scheme; UID buttons just become no-ops.
  }
})();
""")
                html.append('</script>')
                // v0.1.38 — pulse animation for the BLINK badge. Lives in
                // its own <style> tag so we don't bloat the CSS variables
                // block. CSP allows inline <style> by default in Morpheus.
                html.append('<style>@keyframes argusUidPulse { 0%,100%{opacity:1} 50%{opacity:0.25} } .argus-uid-pulse { animation: argusUidPulse 1s ease-in-out infinite; }</style>')
            }

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

            // ── v0.1.36 — Read plugin settings + mint launch token ──────
            //
            // Plugin settings control three behaviors:
            //   - launchMode  : 'auto' (SSO) vs 'link' (no auth, manual login)
            //   - windowMode  : 'popup' vs 'tab'
            //   - authMethod  : 'auto' vs 'sessionkey' vs 'cookie' (legacy)
            //
            // Defaults preserve v0.1.35 behavior so upgrading without touching
            // settings is invisible to existing users.
            //
            // For the sessionkey URL path we mint an extra Redfish session
            // here, capture the token, and DELIBERATELY don't log out — the
            // session is meant to be consumed by the user's click on the
            // launch link. iLO reaps idle sessions after ~30 min if unused.
            // This burns one extra session per tab render, which is fine for
            // a non-polling UI.
            //
            // The mint is skipped when:
            //   - Plugin setting authMethod == 'cookie' (force legacy)
            //   - Plugin setting launchMode == 'link' (no auth needed)
            //   - launchData is null (host not configured / cred unresolved)
            //
            // If the mint fails (firmware quirk, network blip), launchToken
            // stays null and we cleanly fall back to the form-POST path. The
            // setting authMethod == 'sessionkey' (no fallback) is still
            // tolerated — in that case the launch link will be a /irc.html
            // link without a session, which lands the user on iLO's login
            // page; that's the documented behavior for that setting.
            Map argusSettings = [launchMode:'auto', windowMode:'popup', authMethod:'auto']
            try { argusSettings = ((IloConsolePlugin) plugin).readArgusSettings() } catch (Throwable t) {
                log.warn("argus: readArgusSettings failed, using defaults: ${t.message}")
            }
            String launchToken = null
            if (launchData && argusSettings.launchMode != 'link' && argusSettings.authMethod != 'cookie') {
                try {
                    launchToken = com.morpheusdata.iloconsole.services.RedfishClient.acquireLaunchToken(
                            launchData.iloHost as String,
                            cfg.verifySsl as boolean,
                            launchData.username as String,
                            launchData.password as String
                    )
                    if (launchToken) {
                        log.info("argus.launch.method=sessionkey-ready host=${launchData.iloHost}")
                    } else {
                        log.info("argus.launch.method=sessionkey-mint-failed host=${launchData.iloHost} fallback=cookie")
                    }
                } catch (Throwable t) {
                    log.warn("argus: launch token mint threw: ${t.message}")
                }
            }
            String resolvedAuthMethod = launchToken ? 'sessionkey' :
                    (argusSettings.launchMode == 'link' ? 'link-only' :
                            (argusSettings.authMethod == 'sessionkey' ? 'sessionkey-failed-no-fallback' : 'cookie'))

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
  background:transparent; border-color:var(--argus-btn-border); color:var(--argus-text);
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
  background:var(--argus-border); padding:2px 8px; border-radius:3px;
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
                html.append('<div style="display:flex; align-items:center; gap:12px; padding:12px 16px; background:var(--argus-bg-header); border-radius:4px; margin-bottom:14px; flex-wrap:wrap;">')
                html.append('<h2 style="margin:0; font-size:15px; font-weight:600; flex:1; min-width:140px; text-transform:none; display:flex; align-items:center; gap:8px;">')
                html.append('<span>iLO</span>')
                // v0.1.36 — small external-link to the iLO root UI, next to
                // the "iLO" header. Opens iLO's own login page in a new tab.
                // Useful when users want to administer iLO settings outside
                // the Morpheus tab (firmware updates, user management, etc).
                // No auth handoff — the user types creds at iLO's prompt.
                String iloRootHref = "https://${escapeHtml(launchData.iloHost as String)}/"
                html.append("<a href=\"${iloRootHref}\" target=\"_blank\" rel=\"noopener noreferrer\" " +
                        "title=\"Open the iLO management UI in a new tab. iLO login required.\" " +
                        "style=\"display:inline-flex; align-items:center; gap:4px; font-size:11px; " +
                        "font-weight:400; color:var(--argus-text); opacity:0.65; text-decoration:none;\">")
                // Inline SVG external-link icon — no asset dependency.
                html.append('<svg width="12" height="12" viewBox="0 0 24 24" fill="none" ')
                html.append('stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">')
                html.append('<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>')
                html.append('<polyline points="15 3 21 3 21 9"/>')
                html.append('<line x1="10" y1="14" x2="21" y2="3"/>')
                html.append('</svg>')
                html.append('<span>Open iLO UI</span>')
                html.append('</a>')
                html.append('</h2>')
                if (cfg.readonly) {
                    html.append('<span class="ilo-ro-pill">Read-only</span>')
                } else {
                    // ── v0.1.36 — Layered launch ───────────────────────
                    //
                    // Three possible button strategies based on plugin
                    // settings + token-mint success:
                    //
                    //   1. sessionkey URL (preferred): single <a href=
                    //      "https://<host>/irc.html?sessionkey=<TOKEN>">
                    //      One click, no form, no cookie race. Used when
                    //      launchToken is non-null (mint succeeded).
                    //
                    //   2. link-only: simple <a> to /irc.html, no auth.
                    //      User types creds at iLO login. Used when
                    //      plugin setting launchMode == 'link'.
                    //
                    //   3. cookie POST (legacy): the v0.1.35 text/plain
                    //      JSON-injection form-POST + setTimeout JS dance.
                    //      Used when sessionkey mint failed in auto mode,
                    //      OR when authMethod == 'cookie' is forced.
                    //
                    // The "Open Console" companion link always renders —
                    // it lets the user re-open an already-authenticated
                    // session in the same window.
                    String tgt = "iloConsole_${server?.id}"
                    html.append("<label for=\"${pfx}-cred-toggle\" class=\"ilo-btn\" title=\"Reveals the configured iLO username and password for manual copy/paste. Pure HTML/CSS \u2014 nothing transmitted.\">Show credentials</label>")

                    if (launchToken) {
                        // Strategy 1: sessionkey URL — single deterministic
                        // link. iLO accepts the token via URL parameter and
                        // logs the user in without any cookie commit race.
                        String iloIrcWithKey = "https://${escapeHtml(launchData.iloHost as String)}/irc.html?sessionkey=${escapeHtml(launchToken)}"
                        html.append("<a class=\"ilo-btn-primary\" data-argus-launch=\"1\" href=\"${iloIrcWithKey}\" target=\"${tgt}\" rel=\"noopener\" " +
                                "title=\"One-click pre-authenticated console launch (v0.1.36 sessionkey URL).\">" +
                                "&#9658; Launch Console</a>")
                    } else if (argusSettings.launchMode == 'link') {
                        // Strategy 2: link-only mode. No auth attempted.
                        // User logs into iLO with their own credentials at
                        // the prompt — iLO logs the real user identity.
                        String iloIrcOnly = "https://${escapeHtml(launchData.iloHost as String)}/irc.html"
                        html.append("<a class=\"ilo-btn-primary\" data-argus-launch=\"1\" href=\"${iloIrcOnly}\" target=\"${tgt}\" rel=\"noopener\" " +
                                "title=\"Opens the iLO console. iLO login required (Link-only mode).\">" +
                                "&#9658; Launch Console</a>")
                    } else {
                        // Strategy 3: legacy form-POST. This is exactly the
                        // v0.1.35 flow — the JSON-injection trick that posts
                        // credentials to /json/login_session, with a CSP-
                        // nonced inline script for the one-click experience.
                        // Reached when sessionkey mint failed (and we're in
                        // auto mode), or when authMethod=='cookie' forces it.
                        html.append("<form class=\"ilo-launch-form\" method=\"POST\" enctype=\"text/plain\" action=\"${escapeHtml(iloLoginUrl)}\" target=\"${tgt}\" autocomplete=\"off\">")
                        html.append("<input type=\"hidden\" name=\"${escapeHtml(injectName)}\" value=\"${escapeHtml(injectValue)}\">")
                        html.append('<button type="submit" class="ilo-btn-primary" title="Auto-logs into iLO with the stored credentials, then opens the IRC console pre-authenticated. One click does both steps.">&#9658; Launch Console</button>')
                        html.append('</form>')
                    }

                    html.append("<a class=\"ilo-btn-primary\" data-argus-launch=\"1\" href=\"${escapeHtml(iloIrcUrl)}\" target=\"${tgt}\" rel=\"noopener\" title=\"Opens the IRC console using the existing iLO session cookie set by Launch Console. Use after the popup has authenticated, or to re-open the console in the same window.\">&#10142; Open Console</a>")
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
                    // v0.1.36 — windowMode controls window.open features.
                    // 'popup' adds size hints (1280x800). 'tab' uses an EMPTY
                    // features string so the browser opens a normal tab.
                    //
                    // v0.1.39 fix — prior versions passed 'noopener' as the
                    // tab-mode features string. A non-empty features arg to
                    // window.open forces popup-window behavior in Chrome,
                    // Edge, and Firefox regardless of which keywords are
                    // present — 'noopener' is a security flag, not a tab-vs-
                    // window control. Pass an empty string instead. The
                    // `rel="noopener"` already on every <a> tag still gives
                    // us the opener-isolation we wanted from that keyword.
                    String windowFeatures = (argusSettings.windowMode == 'tab')
                            ? ''
                            : 'width=1280,height=800,resizable=yes,scrollbars=no,location=yes,toolbar=no'
                    String jsWindowFeatures = jsonStringEscape(windowFeatures)
                    String jsWindowMode     = jsonStringEscape(argusSettings.windowMode as String ?: 'popup')
                    html.append("<script nonce=\"${escapeHtml(nonce)}\">")
                    html.append("""
(function() {
  try {
    var root = document.getElementById("${jsonStringEscape(rootId)}");
    if (!root) return;
    var ircUrl = "${jsIrcUrl}";
    var target = "${jsTarget}";
    var windowFeatures = "${jsWindowFeatures}";
    var windowMode = "${jsWindowMode}";

    // ── v0.1.39 — choose target by windowMode ──────────────────────────
    //
    // For popup mode: keep the named target so repeated clicks reuse the
    // same popup window (matches the v0.1.36 behavior).
    //
    // For tab mode: switch to '_blank' for link-flow clicks and to a
    // unique-per-click name for the form-POST flow. Why this matters:
    //   1. A non-empty features string forces popup behavior, so we set
    //      features='' in tab mode (done server-side above).
    //   2. But a NAMED target also reuses any existing window with that
    //      name. If the user opened a popup earlier and then switched to
    //      tab mode, every subsequent click would land back in the old
    //      popup. Using '_blank' (link) or a unique name (form) avoids
    //      that reuse and forces a brand-new tab each time.
    //   3. The form-POST flow can't use '_blank' because we need a name
    //      to pin both the pre-opened window and the form.target to the
    //      same browsing context. A timestamped name gives us a fresh
    //      tab AND a window reference for the setTimeout follow-up.
    var linkTarget = (windowMode === 'tab') ? '_blank' : target;

    // ── v0.1.36 — intercept sessionkey / link-only Launch Console <a> ──
    //
    // The sessionkey URL and link-only paths render a plain <a href> link.
    // The browser's default handler ignores window.open features, so a
    // user who selected windowMode=popup would still get a tab. We
    // intercept those clicks and re-open via window.open with the
    // configured features.
    //
    // Marked with data-argus-launch="1" so we don't accidentally hook
    // unrelated links. preventDefault stops the browser's default tab.
    //
    // v0.1.39 — Open Console gained data-argus-launch="1" too, so the
    // selector picks up both Launch and Open Console buttons.
    var launchLinks = root.querySelectorAll('a[data-argus-launch="1"]');
    for (var i = 0; i < launchLinks.length; i++) {
      (function(link) {
        link.addEventListener('click', function(ev) {
          var href = link.getAttribute('href');
          if (!href) return;
          try {
            var win = window.open(href, linkTarget, windowFeatures);
            if (win) {
              ev.preventDefault();
              try { win.focus(); } catch (e) { /* ignore */ }
            }
            // If popup blocked (win == null), don't preventDefault — let
            // the browser navigate the user's existing tab/window normally
            // so they at least land on iLO.
          } catch (e) {
            // window.open threw — let the default <a> behavior run.
          }
        });
      })(launchLinks[i]);
    }

    // ── v0.1.35 legacy — form-POST one-click flow ──────────────────────
    //
    // Only runs when the legacy form-POST launch strategy is in play
    // (sessionkey mint failed in auto mode, OR authMethod=='cookie').
    // If neither form nor button exists (sessionkey/link-only paths),
    // gracefully exits via the if-check below.
    var form = root.querySelector('form.ilo-launch-form');
    var btn  = form ? form.querySelector('button[type="submit"]') : null;
    if (!form || !btn) return;
    var delayMs = 1500;
    var navigated = false;

    btn.addEventListener('click', function() {
      // Open (or refocus) the popup so we have a window reference under
      // the user-activation grant from this click. window.open with an
      // empty URL and an existing name returns a reference to the
      // existing window without navigating it — the form's default
      // submission that follows will then navigate it to the iLO POST.
      // v0.1.36 — also apply windowFeatures here so popup/tab mode is
      // honored on legacy launch path.
      //
      // v0.1.39 — in tab mode, generate a fresh unique target name per
      // click and pin both the form.target AND the pre-opened window to
      // it. This bypasses popup-window reuse from earlier clicks while
      // preserving the JS window reference we need for the iLO->IRC
      // navigation follow-up.
      var formTarget;
      if (windowMode === 'tab') {
        formTarget = target + '_' + Date.now();
        try { form.target = formTarget; } catch (e) { /* ignore */ }
      } else {
        formTarget = target;
      }
      var win;
      try { win = window.open('about:blank', formTarget, windowFeatures); } catch (e) { win = null; }
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
                    html.append('<div class="ilo-cred-panel" style="background:var(--argus-bg-card); border:1px solid rgba(1,169,130,0.3); border-radius:4px; padding:12px 16px; margin-bottom:14px; font-size:12px;">')

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
                html.append('<div style="display:flex; align-items:center; gap:12px; padding:12px 16px; background:var(--argus-bg-header); border-radius:4px; margin-bottom:14px; flex-wrap:wrap;">')
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
            html.append('<div style="background:var(--argus-bg-card); padding:10px 14px; border-radius:4px; margin-bottom:14px; font-size:12px;">')
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
                html.append('<div style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
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
                // v0.1.38 — UID (Unit Identification) LED cell.
                //
                // Always renders, even when the read returned null (we
                // show "Unknown" in that case). The cell contains:
                //   - the current state as a colored badge
                //   - three action buttons: Off / Lit / Blink
                //
                // Button clicks navigate the iLO tab to ?argusUidAction=<value>,
                // which the renderTemplate dispatch path picks up via
                // getRequestParam() and forwards to RedfishClient.collectStatus.
                // collectStatus then PATCHes /Systems/1 (falling back to
                // /Chassis/1) BEFORE the bulk-read, so the System card
                // badge reflects the new state on the same render.
                //
                // Color semantics match the physical front panel:
                //   - Off:      neutral grey badge (no light)
                //   - Lit:      solid blue badge with steady dot
                //   - Blinking: blue badge with a pulsing dot (CSS animation)
                //   - Unknown:  grey badge with em-dash
                //
                // The button corresponding to the current state is styled
                // as disabled-but-still-clickable. Re-sending the current
                // state is a no-op (iLO accepts the PATCH and returns 200)
                // so we don't bother suppressing the click — it's idempotent.
                String led = (status.indicatorLed as String) ?: 'Unknown'
                String ledBadge
                if (led == 'Lit') {
                    ledBadge = '<span style="display:inline-flex; align-items:center; gap:6px; padding:2px 10px; border-radius:11px; background:#1e6fb8; color:#fff; font-size:11px; font-weight:600;"><span style="display:inline-block; width:7px; height:7px; border-radius:50%; background:#9fd5ff;"></span>LIT</span>'
                } else if (led == 'Blinking') {
                    ledBadge = '<span style="display:inline-flex; align-items:center; gap:6px; padding:2px 10px; border-radius:11px; background:#1e6fb8; color:#fff; font-size:11px; font-weight:600;"><span class="argus-uid-pulse" style="display:inline-block; width:7px; height:7px; border-radius:50%; background:#9fd5ff;"></span>BLINK</span>'
                } else if (led == 'Off') {
                    ledBadge = '<span style="display:inline-flex; align-items:center; gap:6px; padding:2px 10px; border-radius:11px; background:rgba(127,127,127,0.18); color:inherit; font-size:11px; font-weight:600;"><span style="display:inline-block; width:7px; height:7px; border-radius:50%; background:rgba(127,127,127,0.55);"></span>OFF</span>'
                } else {
                    ledBadge = '<span style="display:inline-flex; align-items:center; gap:6px; padding:2px 10px; border-radius:11px; background:rgba(127,127,127,0.18); color:inherit; font-size:11px; font-weight:600;">&mdash;</span>'
                }
                String btnBase = 'border:1px solid var(--argus-border); background:transparent; color:inherit; padding:2px 8px; border-radius:3px; font-size:11px; cursor:pointer; line-height:1.4;'
                String btnActive = 'border:1px solid var(--argus-border); background:rgba(127,127,127,0.18); color:inherit; padding:2px 8px; border-radius:3px; font-size:11px; cursor:pointer; line-height:1.4; opacity:0.8;'
                String offStyle  = (led == 'Off')      ? btnActive : btnBase
                String litStyle  = (led == 'Lit')      ? btnActive : btnBase
                String blnkStyle = (led == 'Blinking') ? btnActive : btnBase
                html.append('<div>')
                html.append('<div style="font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.55; margin-bottom:4px;">UID</div>')
                html.append('<div style="display:flex; align-items:center; gap:6px; flex-wrap:wrap;">')
                html.append(ledBadge)
                html.append('<button type="button" class="argus-uid-btn" data-uid="Off"      style="' + offStyle  + '">Off</button>')
                html.append('<button type="button" class="argus-uid-btn" data-uid="Lit"      style="' + litStyle  + '">Lit</button>')
                html.append('<button type="button" class="argus-uid-btn" data-uid="Blinking" style="' + blnkStyle + '">Blink</button>')
                html.append('</div>')
                // Surface a tiny inline status line right under the buttons
                // when the user just performed an action — green check on
                // success, red error message on failure. The line vanishes
                // on the next render (no param → no status).
                if (status.uidActionRequested) {
                    if (status.uidActionSuccess) {
                        html.append('<div style="font-size:10px; opacity:0.65; margin-top:4px;">&#10003; UID set to ' + escapeHtml(status.uidActionRequested as String) + '</div>')
                    } else {
                        html.append('<div style="font-size:10px; color:#c0392b; margin-top:4px;">&#9888; UID change failed: ' + escapeHtml((status.uidActionError as String) ?: 'unknown error') + '</div>')
                    }
                }
                html.append('</div>')
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
                    html.append('<div style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
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
                    html.append('<div style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                    html.append('<h3 style="margin:0 0 12px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">Power Trend</h3>')
                    html.append('<div style="display:flex; align-items:center; gap:20px; flex-wrap:wrap;">')

                    // Left: sparkline (if history) or "no history" placeholder
                    if (ptHaveHist) {
                        html.append(renderPowerSparkline(ptHist))
                    } else {
                        html.append('<div style="flex:1; min-width:240px; padding:18px 12px; background:var(--argus-bg-card); border-radius:3px; font-size:11px; opacity:0.55; text-align:center; line-height:1.5;">')
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
                    html.append('<div style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
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
                    html.append('<details style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
                    html.append("<summary style=\"cursor:pointer; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;\">Network Adapters &mdash; ${netAdapters.size()} adapter${netAdapters.size() == 1 ? '' : 's'}, ${totalPorts} port${totalPorts == 1 ? '' : 's'}</summary>")
                    netAdapters.eachWithIndex { Map a, int idx ->
                        String topPad = (idx == 0) ? '4px' : '12px'
                        String borderTop = (idx == 0) ? '' : 'border-top:1px solid var(--argus-border-soft); '
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
                                html.append('<tr style="border-top:1px solid var(--argus-border-soft);">')
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

                // ── v0.1.36 — Adapter Port LEDs (NIC + HBA split) ──
                //
                // What was a single "NIC Port LEDs" card in v0.1.35 is now
                // split into two cards: "Network Adapter Ports" (Ethernet)
                // and "Host Bus Adapter Ports" (FC / SAS / InfiniBand).
                // Motivation: hosts with FC/SAS HBAs were rendering those
                // ports under the NIC card as confusing dark "—" entries,
                // since HBA ports have no Mbps link rate and Ethernet-only
                // status assumptions don't apply.
                //
                // Classification key:
                //   port.activeTech == 'Ethernet'       → NIC bucket
                //   port.activeTech in [FibreChannel,
                //                       InfiniBand]     → HBA bucket
                //   no activeTech but adapter model     → HBA bucket if
                //     contains FC/HBA/SAS/Fibre markers   model string
                //                                         matches; else NIC
                //
                // HBA ports also display WWPN as monospace subtext (when
                // present) — SAN admins need it for zoning. NIC ports keep
                // Mbps semantics. Status colors (Critical/Warning) are
                // identical across both buckets so a quick scan still
                // surfaces any red lights.
                List portLedAdapters = (status.networkAdapters ?: []) as List
                List<Map> nicLedPorts = []
                List<Map> hbaLedPorts = []
                portLedAdapters.each { Map a ->
                    String adapterModel = ((a.model ?: '') as String).toLowerCase()
                    boolean adapterLooksLikeHba = adapterModel =~ /\b(fc|hba|sas|fibre|fibrechannel|tri-mode|smart array)\b/
                    ((a.ports ?: []) as List).each { Map port ->
                        String tech = ((port.activeTech ?: '') as String)
                        boolean isHba
                        if (tech == 'Ethernet') {
                            isHba = false
                        } else if (tech == 'FibreChannel' || tech == 'InfiniBand') {
                            isHba = true
                        } else {
                            // No activeTech reported (common on SAS HBAs);
                            // fall back to the adapter model heuristic. The
                            // bucket-of-last-resort is NIC so unknown ports
                            // surface in a more familiar card.
                            isHba = adapterLooksLikeHba
                        }
                        if (isHba) {
                            hbaLedPorts << [adapter: a, port: port]
                        } else {
                            nicLedPorts << [adapter: a, port: port]
                        }
                    }
                }
                // Render the NIC card if any Ethernet ports exist.
                if (nicLedPorts) {
                    html.append('<div style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                    html.append('<h3 style="margin:0 0 12px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">Network Adapter Ports</h3>')
                    html.append('<div style="display:flex; flex-wrap:wrap; gap:14px 18px;">')
                    nicLedPorts.each { Map entry ->
                        Map a = entry.adapter as Map
                        Map port = entry.port as Map
                        String link = (port.linkStatus as String) ?: ''
                        String hpeHealth = (port.hpePortHealth as String) ?: ''
                        String baseHealth = (port.health as String) ?: ''
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
                // Render the HBA card only if HBA ports exist — hosts with
                // no HBAs (the common case for MicroServer / hyperconverged
                // nodes) just don't see this card.
                if (hbaLedPorts) {
                    html.append('<div style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
                    html.append('<h3 style="margin:0 0 12px; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">Host Bus Adapter Ports</h3>')
                    html.append('<div style="display:flex; flex-wrap:wrap; gap:14px 18px;">')
                    hbaLedPorts.each { Map entry ->
                        Map a = entry.adapter as Map
                        Map port = entry.port as Map
                        String link = (port.linkStatus as String) ?: ''
                        String hpeHealth = (port.hpePortHealth as String) ?: ''
                        String baseHealth = (port.health as String) ?: ''
                        // HBA semantic differences from NIC:
                        //   - "Online/Offline" instead of "Link Up/Down"
                        //   - Speed shown in Mbps when present (FC links
                        //     report speed similarly to NICs — 8/16/32 Gbps)
                        //   - WWPN shown as monospace subtext when present
                        String dotColor = '#7a8a98'
                        String label
                        if (hpeHealth == 'Critical' || baseHealth == 'Critical') {
                            dotColor = '#d9534f'; label = 'Critical'
                        } else if (hpeHealth == 'Warning' || baseHealth == 'Warning') {
                            dotColor = '#ffc107'; label = 'Warning'
                        } else if (link == 'LinkUp') {
                            dotColor = '#28a745'
                            label = 'Online'
                            if (port.speedMbps) label += " \u00b7 ${port.speedMbps} Mbps"
                        } else if (link == 'LinkDown' || link == 'NoLink') {
                            dotColor = '#5a6a78'
                            label = 'Offline'
                        } else if (link) {
                            label = link
                        } else {
                            label = '\u2014'
                        }
                        String portLabel = (port.portNumber ?: port.name ?: port.id ?: '?') as String
                        String wwpn = (port.wwpn as String)
                        String tip = "${a.name ?: a.id}: Port ${portLabel} \u2014 ${label}"
                        if (wwpn) tip += " (WWPN ${wwpn})"
                        html.append("<div style=\"display:flex; align-items:center; gap:8px;\" title=\"${escapeHtml(tip)}\">")
                        html.append("<span style=\"display:inline-block; width:10px; height:10px; border-radius:50%; background:${dotColor}; box-shadow:0 0 6px ${dotColor}55; flex-shrink:0;\"></span>")
                        html.append('<div style="font-size:11px; line-height:1.3;">')
                        html.append("<div><strong>Port ${escapeHtml(portLabel)}</strong></div>")
                        html.append("<div style=\"opacity:0.65;\">${escapeHtml(label)}</div>")
                        if (wwpn) {
                            html.append("<div style=\"opacity:0.55; font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; font-size:10px; margin-top:2px;\">WWPN ${escapeHtml(wwpn)}</div>")
                        }
                        html.append('</div>')
                        html.append('</div>')
                    }
                    html.append('</div>') // end ports row
                    // Legend (same color semantics as NIC card)
                    html.append('<div style="margin-top:12px; display:flex; flex-wrap:wrap; gap:16px; font-size:10px; opacity:0.55;">')
                    html.append('<span><span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:#28a745; vertical-align:middle; margin-right:5px;"></span>Online</span>')
                    html.append('<span><span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:#5a6a78; vertical-align:middle; margin-right:5px;"></span>Offline</span>')
                    html.append('<span><span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:#ffc107; vertical-align:middle; margin-right:5px;"></span>Warning</span>')
                    html.append('<span><span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:#d9534f; vertical-align:middle; margin-right:5px;"></span>Critical</span>')
                    html.append('</div>')
                    html.append('</div>')
                }

                // ── DIMMs (v0.1.29) ──
                def dimms = (status.dimms ?: []) as List
                if (dimms) {
                    Integer totalGiB = dimms.sum { (it.capacityGiB as Integer) ?: 0 } as Integer
                    html.append('<details style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
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
                        html.append('<tr style="border-top:1px solid var(--argus-border-soft);">')
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
                    html.append('<details style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
                    html.append("<summary style=\"cursor:pointer; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;\">Active iLO Sessions &mdash; ${activeSessions.size()} other ${activeSessions.size() == 1 ? 'user' : 'users'}</summary>")
                    html.append('<table style="width:100%; margin-top:10px; font-size:12px; border-collapse:collapse;">')
                    html.append('<thead><tr style="opacity:0.55; font-size:10px; text-transform:uppercase; letter-spacing:0.05em;">')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">User</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">From IP</th>')
                    html.append('<th style="text-align:left; padding:0 0 6px 0;">Session</th>')
                    html.append('</tr></thead><tbody>')
                    activeSessions.each { s ->
                        html.append('<tr style="border-top:1px solid var(--argus-border-soft);">')
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
                    html.append('<div style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
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
                        html.append('<tr style="border-top:1px solid var(--argus-border-soft);">')
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
                    html.append('<div style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
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
                        html.append('<tr style="border-top:1px solid var(--argus-border-soft);">')
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
                    html.append('<details style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
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
                        html.append('<tr style="border-top:1px solid var(--argus-border-soft);">')
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
                    html.append('<details style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
                    html.append("<summary style=\"cursor:pointer; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;\">Firmware Inventory &mdash; ${fw.size()} components</summary>")
                    html.append('<table style="width:100%; margin-top:10px; font-size:12px; border-collapse:collapse;">')
                    html.append('<thead><tr style="opacity:0.55; font-size:10px; text-transform:uppercase; letter-spacing:0.05em;">')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Component</th>')
                    html.append('<th style="text-align:left; padding:0 12px 6px 0;">Version</th>')
                    html.append('<th style="text-align:left; padding:0 0 6px 0;">Updateable</th>')
                    html.append('</tr></thead><tbody>')
                    fw.sort { (it.name as String) }.each { f ->
                        Boolean upd = f.updateable as Boolean
                        html.append('<tr style="border-top:1px solid var(--argus-border-soft);">')
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
                    html.append('<details style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:10px 14px; margin-bottom:14px;">')
                    html.append('<summary style="cursor:pointer; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.05em; opacity:0.7;">Recent Events &mdash; last ' + events.size() + '</summary>')
                    html.append('<table style="width:100%; margin-top:10px; font-size:11px; border-collapse:collapse;">')
                    events.each { e ->
                        String sev = (e.severity as String) ?: 'OK'
                        String sevColor = sev == 'Critical' ? '#d9534f' : (sev == 'Warning' ? '#ffc107' : '#7a8a98')
                        html.append('<tr style="border-top:1px solid var(--argus-border-soft);">')
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
                html.append('<div style="background:var(--argus-bg-card); border:1px solid var(--argus-border); border-radius:4px; padding:14px 16px; margin-bottom:14px;">')
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
            html.append('<details style="background:var(--argus-bg-diag); padding:10px 14px; border-radius:4px; margin-top:14px;">')
            html.append('<summary style="cursor:pointer; font-size:11px; text-transform:uppercase; letter-spacing:0.05em; opacity:0.5;">Diagnostics (v0.1.43)</summary>')
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
            // v0.1.36 — surface plugin settings + the actual launch path
            // taken this render. `launchAuthResolved` is the most useful row
            // for triage: it tells you which strategy the Launch Console
            // button is wired to right now.
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">launchMode</td><td>${escapeHtml(argusSettings.launchMode as String)}</td></tr>")
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">launchWindowMode</td><td>${escapeHtml(argusSettings.windowMode as String)}</td></tr>")
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">launchAuthMethod</td><td>${escapeHtml(argusSettings.authMethod as String)} <span style=\"opacity:0.55;\">(setting)</span></td></tr>")
            // v0.1.40 — settings-persistence diagnostics. Reports of all
            // three checkboxes appearing inert even after v0.1.38's
            // JsonSlurper fix mean we need to see what Morpheus is
            // actually storing in the pluginConfig blob. Three rows so
            // we can tell (A) Morpheus drops unchecked fields from JSON
            // (v0.1.40 isOn() handles this) from (B) Morpheus persists
            // defaultValue regardless of UI state (which would be a
            // Morpheus bug we can't paper over).
            String parsedKeysStr = (argusSettings._parsedKeys instanceof List)
                    ? ((argusSettings._parsedKeys as List).join(', ') ?: '<empty>')
                    : '<none>'
            String configHasKeysStr = (argusSettings._configHasKeys as Boolean) ? 'yes' : 'no'
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">settingsParsedKeys</td><td>${escapeHtml(parsedKeysStr)} <span style=\"opacity:0.55;\">(hasKeys=${escapeHtml(configHasKeysStr)})</span></td></tr>")
            String rawA = (argusSettings._rawAutoLogin   != null) ? (argusSettings._rawAutoLogin   as String) : '<null>'
            String rawP = (argusSettings._rawPopupWindow != null) ? (argusSettings._rawPopupWindow as String) : '<null>'
            String rawS = (argusSettings._rawSessionkey  != null) ? (argusSettings._rawSessionkey  as String) : '<null>'
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">settingsRaw</td><td>autoLogin=${escapeHtml(rawA)} &middot; popup=${escapeHtml(rawP)} &middot; sessionkey=${escapeHtml(rawS)}</td></tr>")
            if (argusSettings._parseError) {
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">settingsParseError</td><td>${escapeHtml(argusSettings._parseError as String)}</td></tr>")
            }
            // Raw JSON blob is most useful when the rest of the rows
            // don't explain what's going on. Truncate to ~400 chars to
            // avoid overflowing the diagnostics row on hosts with very
            // large pluginConfig blobs (we've never seen big ones here
            // but defensive limit).
            if (argusSettings._rawJson) {
                String rj = argusSettings._rawJson as String
                if (rj.length() > 400) rj = rj.substring(0, 400) + '... (truncated)'
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">settingsJson</td><td><code style=\"font-size:10px; word-break:break-all;\">${escapeHtml(rj)}</code></td></tr>")
            }
            // v0.1.38 — UID indicator LED action trace. Shows nothing on a
            // plain page load (no param), shows the requested value plus
            // success/failure on a render where the user just clicked one
            // of the Off/Lit/Blink buttons in the System card.
            //
            // v0.1.39 — added the per-attempt breakdown (Systems/1 vs
            // Chassis/1, IndicatorLED vs LocationIndicatorActive, plain
            // vs +ETag, with Redfish MessageId on errors) so we can
            // distinguish RBAC (every attempt same code) from firmware
            // quirks (mixed codes) from value/property issues (specific
            // MessageId like PropertyNotWritable). Also surfaces which
            // property the badge READ came from in a separate row.
            if (status?.uidActionRequested) {
                String uidStr = "${status.uidActionRequested}${status.uidActionSuccess ? ' (success via ' + (status.uidActionPropertyUsed ?: '?') + ')' : ' (FAILED: ' + (status.uidActionError ?: 'unknown') + ')'}"
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">uidAction</td><td>${escapeHtml(uidStr)}</td></tr>")
            }
            if (status?.uidActionAttempts) {
                List uidAttempts = status.uidActionAttempts as List
                StringBuilder atSb = new StringBuilder()
                uidAttempts.eachWithIndex { Object item, int idx ->
                    Map at = item as Map
                    if (idx > 0) atSb.append(' &middot; ')
                    String pathStr = (at.path as String) ?: '?'
                    String propStr = (at.property as String) ?: 'IndicatorLED'
                    // Compact label: omit IndicatorLED (it's the default)
                    // and prefix LIA / OEM so the row stays readable on a
                    // single line in the diagnostics table.
                    String propTag
                    if (propStr == 'LocationIndicatorActive') propTag = ' LIA'
                    else if (propStr == 'Oem.Hpe.IndicatorLED') propTag = ' OEM'
                    else propTag = ''
                    String ifm     = (at.ifMatch as Boolean) ? ' +ETag' : ''
                    String outcome
                    if (at.success as Boolean) {
                        outcome = 'ok'
                    } else {
                        String code = at.errorCode ? "HTTP ${at.errorCode}" : 'failed'
                        String msgId = at.redfishMessageId ? " ${at.redfishMessageId}" : ''
                        outcome = "${code}${msgId}"
                    }
                    atSb.append("${pathStr}${propTag}${ifm}: ${outcome}")
                }
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">uidActionAttempts</td><td>${escapeHtml(atSb.toString())}</td></tr>")
            }
            // v0.1.41 — iLO concurrent session pressure indicator. iLO 6
            // typically caps at ~13 concurrent sessions across all clients
            // (web UI, IRC console, REST API, sessionkey launches). Writes
            // start failing with confusing PropertyNotWritableOrUnknown
            // errors before reads do when the pool saturates, so this row
            // gets a colored warning prefix when approaching the cap so
            // operators see it before they spend time chasing fake
            // "property not writable" or RBAC red herrings.
            if (status?.totalSessionCount != null) {
                int sessCount = status.totalSessionCount as int
                String sessHint
                String sessColor
                if (sessCount >= 11) {
                    sessHint = " &mdash; at or near iLO's ~13-session cap; close stale console windows before chasing UID PATCH errors"
                    sessColor = '#c0392b'
                } else if (sessCount >= 7) {
                    sessHint = " &mdash; approaching iLO's ~13-session cap"
                    sessColor = '#d4a017'
                } else {
                    sessHint = ''
                    sessColor = null
                }
                String sessCell = sessColor
                        ? "<span style=\"color:${sessColor};\">${sessCount}</span>${sessHint}"
                        : "${sessCount}${sessHint}"
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">iloSessions</td><td>${sessCell}</td></tr>")
            }
            if (status?.indicatorLed) {
                String src = (status.indicatorLedSource as String) ?: 'IndicatorLED'
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">indicatorLed</td><td>${escapeHtml(status.indicatorLed as String)} <span style=\"opacity:0.55;\">(current, via ${escapeHtml(src)})</span></td></tr>")
            }
            String launchTokenStr = launchToken ? "minted (length=${launchToken.length()})" : 'not minted'
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">launchToken</td><td>${escapeHtml(launchTokenStr)}</td></tr>")
            html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">launchAuthResolved</td><td>${escapeHtml(resolvedAuthMethod)}</td></tr>")
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
                // v0.1.36 — count NIC vs HBA ports for at-a-glance verification
                // that the split classifier picked them up correctly.
                int nicPortsDiag = 0
                int hbaPortsDiag = 0
                int unknownTechDiag = 0
                naDiag.each { Map a ->
                    String mdl = ((a.model ?: '') as String).toLowerCase()
                    boolean adapterHbaLike = mdl =~ /\b(fc|hba|sas|fibre|fibrechannel|tri-mode|smart array)\b/
                    ((a.ports ?: []) as List).each { Map prt ->
                        String tech = ((prt.activeTech ?: '') as String)
                        if (tech == 'Ethernet') nicPortsDiag++
                        else if (tech == 'FibreChannel' || tech == 'InfiniBand') hbaPortsDiag++
                        else if (adapterHbaLike) { hbaPortsDiag++; unknownTechDiag++ }
                        else { nicPortsDiag++; unknownTechDiag++ }
                    }
                }
                String naDiagStr = "${naDiag.size()} adapters, ${totalPortsDiag} ports (NIC=${nicPortsDiag}, HBA=${hbaPortsDiag}"
                if (unknownTechDiag > 0) naDiagStr += ", ${unknownTechDiag} via model fallback"
                naDiagStr += ")"
                html.append("<tr><td style=\"opacity:0.55; padding-right:24px;\">networkAdapters</td><td style=\"font-family:ui-monospace,monospace; font-size:11px;\">${naDiagStr}</td></tr>")
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
<div><span style="display:inline-block; padding:2px 10px; border-radius:10px; background:var(--argus-bg-pill); color:${color}; font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.03em;">${escapeHtml(value ?: '?')}</span></div>\
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
        svg.append("<svg viewBox=\"0 0 ${w} ${h}\" preserveAspectRatio=\"none\" style=\"width:100%; height:70px; display:block; background:var(--argus-bg-card); border-radius:3px;\">")
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

    /**
     * v0.1.38 — Read a query-string parameter off the current
     * HttpServletRequest. Mirrors getCspNonce() exactly: same Spring
     * RequestContextHolder dance, same Groovy dynamic dispatch, same
     * silent-failure semantics. Used by the UID indicator LED control
     * to read argusUidAction from the URL the user just navigated to
     * by clicking a button in the System card.
     *
     * Returns null on any failure (Spring classes absent, no active
     * request context, parameter not present). Caller defaults to "no
     * action" — the tab simply renders normally without doing a PATCH.
     */
    private static String getRequestParam(String name) {
        try {
            Class<?> holderClass = Class.forName('org.springframework.web.context.request.RequestContextHolder')
            def attrs = holderClass.getMethod('getRequestAttributes').invoke(null)
            if (attrs == null) return null
            def req = attrs.getRequest()
            if (req == null) return null
            def v = req.getParameter(name)
            return v ? v.toString() : null
        } catch (Throwable t) {
            // Spring classes not available, or unexpected error
        }
        return null
    }
}
