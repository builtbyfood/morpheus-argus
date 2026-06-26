# Architecture

This document explains the **shape** of the plugin and **why** each major
piece is the way it is. The README is for users; this is for the next
developer who has to extend or fork it.

> **Status — v0.1.35.** The plugin reads live Redfish status from the iLO,
> exposes credentials with a CSS-only reveal panel, and launches the IRC
> HTML5 console. Launch is one click when Morpheus's CSP nonce is readable
> (most cases), two clicks when it isn't (graceful degradation). No
> controller routes are registered; the plugin declares no permissions.
> All work happens in the tab provider and the Redfish client.

---

## Design constraints we couldn't move

Every shape in this plugin exists because of one of these constraints.
They're listed first because most of the code's apparent strangeness is
direct consequence.

**Morpheus 9.0 CSP is `script-src 'self' 'nonce-XXX' 'strict-dynamic'`.**
Under `strict-dynamic`, the literal source-list values (`'self'`,
`'unsafe-inline'`) are ignored — only scripts carrying the per-request
nonce execute, and they can dynamically load additional scripts. Critically:
**inline event-handler attributes cannot carry a nonce** by browser spec,
so `onclick="…"` is dead in this environment regardless of what the source
list looks like. The CSS-only Show credentials toggle and the nonced
inline script in 0.1.25/0.1.26 are both responses to this.

**iLO sends `X-Frame-Options: SAMEORIGIN` and `frame-ancestors 'self'`.**
The IRC console can't be embedded in an iframe from a different origin.
Period. The only way around this would be a WebSocket-aware reverse
proxy in the appliance — out of scope.

**iLO's `/json/login_session` is JSON-only.** It parses the request body
as JSON regardless of `Content-Type` and returns
"Malformed object, expected '{' at start of object" if the body doesn't
start with `{`. Plain `<form>` POSTs default to
`application/x-www-form-urlencoded`, which doesn't satisfy this.

**iLO's IRC session uses a Web UI cookie, not the Redfish token.**
The `X-Auth-Token` we get from `POST /redfish/v1/SessionService/Sessions`
authorizes Redfish API calls and lives only on the appliance. It can't
authenticate `/irc.html`, which checks the `sessionKey` cookie that
`/json/login_session` sets. Different session pools.
[HPE's session-management blog](https://servermanagementportal.ext.hpe.com/docs/references_and_material/blogposts/etc/managingilosessions/managingilosessionswithredfish)
documents this explicitly.

**Browser cookies can't be transferred between origins or clients.**
A cookie iLO sends in response to a request from the appliance is stored
in the appliance's HTTP client cookie jar. There's no primitive to "hand
it" to the user's browser. The browser has to make its own request to
iLO to receive its own cookie. That's why the auto-login has to happen
in the browser (form-POST from the tab), not on the appliance.

**Plugin controller routes return 403 in this appliance with no
diagnostic.** Six versions tried different permission shapes (`"admin-cm"`,
`"compute-cm"`, `"admin"`, plugin-declared `"ilo-console"`, both single
and list forms of `Route.build`, both `setPermissions()` and the
controller's `getPermissions()` override). All 403. No entries in
`morpheus-ui/current` to indicate why. We don't know if the route never
registered, if a permission lookup failed, or if a CSRF check rejected
the GET — and we couldn't get diagnostic data out of the appliance.
0.1.23+ takes the controller out of the design entirely.

**Morpheus 9.0 / plugin-api 1.3.1 quirks.** Three specific gotchas:

1. **`accountCredential.get(Long)` throws** `GroovyCastException
   Single→Maybe`. Work around with `accountCredential.listById([id])`
   + `loadCredentialConfig([id: credentialId, type: 1], [:])`.
2. **`hasCustomRenderer()` must be a `Boolean` field**, not an
   `@Override` method returning `boolean`. Without it set true *and*
   `this.renderer = new HandlebarsRenderer()` in `initialize()`,
   `renderTemplate()` is bypassed.
3. **Handlebars model binding is empty on this appliance.** Variables
   passed via `ViewModel.put(…)` aren't accessible as `{{name}}` in the
   template. Templates can still render, but you can only get data in
   via inline-stringification. Inline-HTML via
   `HTMLResponse.success(html.toString())` is the working path.

---

## Component map

```
IloConsolePlugin                      (the entry point)
├── initialize()
│   ├── new HandlebarsRenderer()      (set as this.renderer, required
│   │                                  even though we don't use it now)
│   ├── new IloConfigStore()           (reads ilo-host / ilo-cred / ilo-verify-ssl
│   │                                  labels from a ComputeServer)
│   ├── new IloDetectionService()      (does the host vendor/serverType
│   │                                  matching for ServerTabProvider.show)
│   ├── new AuditLogger()              (Slf4j wrapper; placeholder for
│   │                                  future security audit pipe)
│   └── registerProvider(new IloConsoleServerTabProvider(this, morpheus))
│
└── (NOT registered)
    new IloConsoleController(this, morpheus)
        — six-version 403 saga; source kept for future Redfish actions

IloConsoleServerTabProvider           (the meat)
├── show(ComputeServer, ...)
│   └── true if name/description/labels/serverType match HPE iLO
│
├── renderTemplate(ViewModel<Map>)
│   ├── IloConfigStore.loadConfig(server)              → iloHost, credId, verifySsl
│   ├── IloCredentialService.loadCredential(credId)    → username, password
│   ├── new RedfishClient(host, user, pass, verifySsl)
│   │   └── collectStatus()                            → power/health/cpu/mem/etc
│   ├── builds the launch UI:
│   │   ├── <form enctype="text/plain" action=".../login_session" target="iloConsole_N">
│   │   │   └── <input name='{"...JSON open..."' value='"}'>
│   │   │   └── <button type="submit">▶ Launch Console</button>
│   │   ├── <a href=".../irc.html" target="iloConsole_N">→ Open Console</a>
│   │   ├── (if CSP nonce available) <script nonce="...">click handler that
│   │   │     pre-opens popup window and setTimeouts navigation to /irc.html</script>
│   │   ├── CSS-only Show credentials panel (checkbox + sibling-selector)
│   │   └── status panel (power/health/bios/cpu/mem)
│   └── HTMLResponse.success(html)
│
└── private helpers
    ├── escapeHtml(String)            (& < > " escaping for HTML attributes)
    ├── jsonStringEscape(String)      (\\ " \b \f \n \r \t \uXXXX for JSON literals)
    └── getCspNonce()                 (reads js-nonce from Spring request context)

RedfishClient                         (status-side I/O)
├── login()                           POST /redfish/v1/SessionService/Sessions
│                                     → captures X-Auth-Token, Location
├── collectStatus()                   wraps login + several GETs + finally-delete
│   ├── GET /redfish/v1/Systems/1
│   ├── GET /redfish/v1/Managers/1
│   ├── GET /redfish/v1/Systems/1/Processors/1
│   └── GET /redfish/v1/Systems/1/Memory
└── deleteSession()                   DELETE the session in finally block
                                     (the canonical lifecycle per HPE docs)
```

The dotted lines: `IloConsoleController` is in the source tree but never
registered, and Handlebars is configured but not used for the tab. Both
are kept as scaffolding — controller for future Redfish-backed actions,
Handlebars in case a future feature needs templates.

---

## The launch flow in full

This is the hardest part to keep in your head, so a sequence:

```
User clicks ▶ Launch Console
│
├── (one-click path, when CSP nonce was readable)
│       browser fires 'click' on <button type="submit">
│       └── our nonced inline JS click handler:
│             window.open('', 'iloConsole_<id>')      ← user-activation grants popup
│             setTimeout(navigateToIrc, 1500)
│
├── browser then fires the form's default submit action:
│       POST https://<ilo>/json/login_session
│         Content-Type: text/plain
│         body: {"method":"login","user_login":"…","password":"…","x":"="}
│         target: iloConsole_<id>            ← lands in the named popup
│
├── iLO parses body as JSON, authenticates, responds:
│       200 OK
│         Content-Type: application/json
│         Set-Cookie: sessionKey=…; Path=/   ← cookie lands on iLO origin
│         body: {"session_key":"…","user_name":"…","login_priv":1,…}
│
├── popup briefly shows iLO's JSON response (raw text)
│
├── ~1500 ms after click, setTimeout fires:
│       win.location.href = "https://<ilo>/irc.html"
│       popup navigates to /irc.html on iLO origin
│       sessionKey cookie is sent on the request (SameSite=Lax default)
│
└── iLO recognizes session, /irc.html renders the IRC console
```

In the no-nonce fallback (`getCspNonce()` returned empty), there's no
inline script. The user clicks Launch (popup shows JSON), then clicks
**→ Open Console** (popup navigates manually). Two clicks, same end.

The form-POST body is JSON because of the `enctype="text/plain"`
JSON-injection trick — see *Why the launch form uses `enctype="text/plain"`*
below.

---

## Why the launch form uses `enctype="text/plain"`

iLO's `/json/login_session` expects JSON. HTML `<form>` can produce
exactly three encodings, none of which is `application/json`:

- `application/x-www-form-urlencoded` (default): `key=value&key=value`
- `multipart/form-data`: MIME multipart, big and not JSON
- `text/plain`: literally `name=value\r\n` per field, no URL-encoding

The `text/plain` format is rarely used and has a peculiar property: it
joins each field's name and value with a single `=` character. By
crafting a single hidden input whose **name** is the JSON document up to
a placeholder and whose **value** is the close of that placeholder, the
resulting `name=value` concatenation comes out as valid JSON:

```html
<input type="hidden"
       name='{"method":"login","user_login":"u","password":"p","x":"'
       value='"}'>
```

Body produced:
```
{"method":"login","user_login":"u","password":"p","x":"="}
```

The `=` from text/plain's separator lives safely as the value of the
`"x"` key, which iLO ignores. Verified across passwords containing `"`,
`\`, `&`, `<`, `>`, `'`, and spaces — `jsonStringEscape` + `escapeHtml`
round-trip all of them correctly.

This trick is documented as a CSRF technique for sites that fail to
validate JSON Content-Type. iLO doesn't validate; it just parses
whatever it receives as JSON. So it works for our (legitimate) use.

---

## Why the one-click JS is structured the way it is

The constraints are subtle:

1. **The popup must be opened during the click handler's synchronous
   execution** because the browser revokes user-activation when control
   returns to the event loop. `window.open(...)` inside a `setTimeout`
   would be blocked by popup blockers.
2. **We can't read the popup's location after it navigates cross-origin
   to iLO.** So we can't tell when iLO's response has actually arrived.
   A fixed delay is the practical answer; 1500 ms is safe for LAN.
3. **We CAN write to the popup's `location.href` from the opener even
   cross-origin** (per HTML spec — opener-side `location` writes are
   exempted from the same-origin policy). So `win.location.href = ircUrl`
   from our setTimeout works.
4. **We don't `preventDefault()` on the click**. The form's default
   action (POST to iLO) needs to happen. We just add the popup-prep and
   the deferred navigation alongside it.

The handler:

```javascript
btn.addEventListener('click', function() {
  var win = window.open('', target);   // ← under user-activation, returns ref
  if (!win) return;
  setTimeout(function() {
    if (!win.closed) {
      try { win.location.href = ircUrl; } catch (e) {}
    }
  }, 1500);
  // form submit happens after handler returns
});
```

If `window.open` returns null (popup blocker fired even though we had
user activation), we silently exit — the form still submits and the
user falls back to the two-click flow.

---

## The Redfish session lifecycle

`RedfishClient.collectStatus()` follows the pattern HPE recommends in
their session-management blog:

```
1. POST /redfish/v1/SessionService/Sessions
       body: {"UserName":"...","Password":"..."}
   → 201 Created
     X-Auth-Token: <opaque>
     Location: /redfish/v1/SessionService/Sessions/<id>

2. (one or more authenticated GETs, all with X-Auth-Token header)

3. DELETE /redfish/v1/SessionService/Sessions/<id>      ← always, in finally
```

The `finally` block is the important part. iLO has a finite session
table; if we leak sessions, eventually iLO refuses new logins. The
DELETE returning 404 is normal — iLO sometimes expires the session
between create and delete, and the WARN-level log line is cosmetic.

We use `services.accountCredential.listById([id])` +
`loadCredentialConfig([id: credentialId, type: 1], [:])` rather than the
broken `accountCredential.get(Long)`; the broken one throws
`GroovyCastException Single→Maybe` in plugin-api 1.3.1.

---

## The Show credentials panel is CSS-only

Two hidden checkboxes drive sibling-selector CSS:

```css
#root .ilo-cred-panel { display:none; }
#root #cred-toggle:checked ~ .ilo-cred-panel { display:block; }
#root .ilo-pass-mask { display:inline; }
#root .ilo-pass-real { display:none; }
#root #pass-toggle:checked ~ .ilo-cred-panel .ilo-pass-mask { display:none; }
#root #pass-toggle:checked ~ .ilo-cred-panel .ilo-pass-real { display:inline; }
```

The `<label for="cred-toggle">` and `<label for="pass-toggle">` elements
trigger the checkboxes when clicked, which flips the `:checked` state,
which the sibling selectors respond to. Zero JavaScript.

`user-select: all` on each credential value makes a single click select
the entire value — then Ctrl+C copies it. The user never sees the
checkboxes (they're positioned off-screen with `left: -9999px`).

This whole design exists because in 0.1.x's CSP, inline `onclick="…"`
attributes can never carry a nonce, so a JS-based reveal can't ever
work without solving the nonce problem. Once we have the nonce (0.1.25),
we *could* migrate the reveal to JS, but the CSS approach has zero
maintenance cost and works in environments where the nonce isn't
available — so it stays.

---

## What's not in the plugin (and why)

| Feature | Why it isn't here |
| --- | --- |
| Power on/off/reset actions | Will be in 0.2.x via Redfish (`POST /Systems/1/Actions/ComputerSystem.Reset`). Needs no controller route; the appliance can fire it directly from a tab button using a small form to a Morpheus AJAX endpoint or via the same nonce-based JS approach we now have for launch. |
| Per-server config UI | Currently driven by labels (`ilo-host:`, `ilo-cred:`, `ilo-verify-ssl:`). Building a real config UI requires a `OptionTypeProvider` or `InputProvider` integration; deferred until labels feel limiting. |
| Multi-iLO listings / inventory page | Out of scope for a server-tab plugin. Could be a separate report or dashboard provider in the future. |
| iLO 4 support | iLO 4's IRC URL is `/html5/keyboard.html` (or similar), different from iLO 5/6's `/irc.html`. Add a firmware-version check in `RedfishClient.collectStatus()` and branch the launch URL based on `Managers/1`'s `FirmwareVersion`. |
| Reverse-proxied iframe console | iLO's `X-Frame-Options: SAMEORIGIN` blocks iframe embedding; only fixable with a WebSocket-aware proxy in the appliance. Big project. |
| Auto-detection of iLO IP from the host | Requires querying Morpheus for the host's IPMI/BMC address or the iLO's discovered network info; possible via the SDK but adds dependencies on how the host was added to Morpheus. |

---

## File map

```
src/main/groovy/com/morpheusdata/iloconsole/
├── IloConsolePlugin.groovy               — entry point; initialize/destroy/getCode/getName
├── IloConsoleServerTabProvider.groovy    — the tab; renderTemplate + show + helpers
├── IloConsoleController.groovy           — UNUSED in 0.1.23+; kept for future actions
└── services/
    ├── AuditLogger.groovy                 — Slf4j-backed audit log placeholder
    ├── IloConfigStore.groovy              — parses ilo-host / ilo-cred / ilo-verify-ssl labels
    ├── IloCredentialService.groovy        — credential lookup (works around the broken API)
    ├── IloDetectionService.groovy         — host vendor/serverType matching for show()
    └── RedfishClient.groovy               — POST session, GETs, DELETE session

src/main/resources/
└── i18n/messages.properties              — i18n strings (mostly tab title)

docs/
├── ARCHITECTURE.md                       — this file
├── CHANGELOG.md                          — version history
└── TROUBLESHOOTING.md                    — symptom-driven guide

build.gradle                              — morpheus-plugin-api 1.3.1, shadow 8.1.1,
                                            Groovy 3.0.21, asset-pipeline (configured but
                                            no assets currently shipped)
gradle.properties                         — version=0.1.34
README.md                                 — install + quick reference
LICENSE                                   — Apache 2.0
```
