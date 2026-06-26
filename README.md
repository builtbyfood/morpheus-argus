# Argus — HPE iLO integration for Morpheus

> **Internal codename:** Argus (Argos Panoptes), the 100-eyed all-seeing Greek
> watchman. **User-facing name:** **iLO** — the tab, the plugin display name in
> Morpheus's admin list, and the page header all read "iLO". The "Argus"
> name lives in the repo, the JAR file, and the docs. See *Naming* below.

A Morpheus 9.0 plugin that adds an **iLO** tab to HPE ProLiant host detail
pages. It reads live status from the iLO via Redfish — power, health,
thermals, fans, PSU, network identity, drives, DIMMs, recent events,
active sessions — exposes the configured credentials for copy/paste, and
launches the IRC HTML5 console in a new window — pre-authenticated, in
one click.

## Naming

| Surface | Name | Why |
| --- | --- | --- |
| GitHub repo, build artifact | `morpheus-argus` | The plugin watches servers through iLO; Argos Panoptes was the mythological all-seeing watchman. |
| Tab title, header `<h2>`, plugin display name in admin UI | **iLO** | What the user actually wants to see — they're looking at iLO data. |
| Internal Morpheus plugin code (`getCode()`) | `morpheus-ilo-console` | Unchanged from earlier versions so installed plugins upgrade cleanly without needing an uninstall/reinstall. |
| Java package | `com.morpheusdata.iloconsole` | Same reason — class identity preserved. |

If you build the source, the JAR comes out as `morpheus-argus-0.x.x-all.jar`.

---

## What you get

| Surface | Where |
| --- | --- |
| **iLO** tab | Infrastructure → Compute → Hosts → *any HPE ProLiant host* |
| **System** card | Power state · Health · iLO model + firmware · BIOS version · CPU · Memory · Serial · Asset Tag · SKU · **Indicator LED** state · **TPM** · **Boot Progress** · **Boot Override** · **Hostname** |
| **Power & Cooling** card | Current power draw (with average fallback) · PSU count + redundancy · hottest CPU temperature · ambient temperature · fan summary. Temperatures shown as °C / °F. |
| **Network** card | Host MAC + IP + link status · iLO's own MAC + IP + hostname/FQDN · **iLO date/time** · **iLO license type** |
| **Drives** card | Per-drive model · media type · protocol · capacity · health (with ⚠ on predicted failure). |
| **Power Trend** card | Inline SVG sparkline of historical power draw from `/Power/PowerMeter` + current/min/avg/max gauge from `/EnvironmentMetrics`. Gracefully empty on hardware that doesn't track power. |
| **Network Adapters** (collapsible) | Per-adapter model · manufacturer · serial · firmware · health. Per-port table with link state · speed · MAC. |
| **NIC Port LEDs** card | At-a-glance colored dot per port: green LinkUp · gray LinkDown · yellow Warning · red Critical. Native hover tooltip with adapter + MAC. |
| **Volumes / RAID** card | Per-volume RAID type · capacity · drive count · health · in-progress operation (rebuild / init pct) · encryption · boot-volume star. Skipped when no RAID controller present. |
| **DIMMs** (collapsible) | Per-slot capacity · speed · type · manufacturer · part number. |
| **Active iLO Sessions** (collapsible) | Other users currently logged into this iLO (useful before launching IRC). |
| **Recent Events** (collapsible) | Last 5 IML entries with severity coloring |
| **▶ Launch Console** button | Posts iLO credentials, opens the IRC console pre-authenticated. One click when CSP nonce is available; two clicks otherwise. |
| **→ Open Console** button | Manual companion to Launch — navigates the popup to `/irc.html`. |
| **Show credentials** toggle | CSS-only reveal of the configured username + password, single-click select for paste. |
| **Read-only mode** (label-driven) | When `ilo-readonly:true` is set on a host, hides credentials and launch UI entirely; status cards still render. |
| **Diagnostics** (collapsible) | Property-access trace, label values, CSP nonce status, readonly state. |

Tested against:

- Morpheus 9.0.0 / plugin-api 1.3.1
- HPE ProLiant MicroServer Gen11, iLO 6 v1.74
- Chrome 130+, Firefox 130+, Brave 1.70+

---

## Install by building or using the presented JAR file located here: https://github.com/builtbyfood/morpheus-argus/releases

1. **Build** the JAR locally:
   ```bash
   ./gradlew clean shadowJar
   # output: build/libs/morpheus-argus-0.1.35-all.jar
   ```
   Requires JDK 17 (produces Java 11 bytecode).

2. **Upload** to your Morpheus appliance:
   **Administration → Integrations → Plugins → Upload Plugin** →
   select the JAR.

3. **Confirm load** in the morpheus-ui log:
   ```
   tail -F /var/log/morpheus/morpheus-ui/current | grep -i ilo
   ```
   You should see:
   ```
   INFO  c.m.i.IloConsolePlugin - iLO Console plugin 0.1.35 initialized
                                  (custom HandlebarsRenderer, no controller routes)
   ```

4. **No role/permission grants are required.** The plugin declares no
   custom permissions and registers no controller routes. The tab and
   launch flow inherit whatever permissions the host detail page itself
   requires.

5. **Upgrading from an earlier version**: do a full uninstall first
   (**Plugins → iLO Console → Uninstall**), then upload the new JAR.
   Morpheus's plugin hot-replace can be flaky and leave stale state.

---

## Configuration

Configure each iLO-managed host via labels (free-form key:value tags in
Morpheus). On the host detail page, **Edit → Labels**, add:

| Label | Required? | Example | Notes |
| --- | --- | --- | --- |
| `ilo-host:<ip-or-host>` | yes | `ilo-host:10.0.10.50` | iLO management IP or DNS name (no scheme, no port). |
| `ilo-cred:<credential-id>` | yes | `ilo-cred:1` | Numeric ID of a Morpheus credential set (Infrastructure → Trust → Credentials). Must be a `username-password` type credential. |
| `ilo-verify-ssl:<true\|false>` | no | `ilo-verify-ssl:false` | Defaults to `true`. Set to `false` for iLOs with self-signed certs. |
| `ilo-readonly:<true\|false>` | no | `ilo-readonly:true` | Defaults to `false`. When `true`, hides the credentials panel and the Launch/Open Console buttons — only the status cards render. Useful for hosts you want operators to be able to *see* but not be able to launch a console for. Note this is configuration, not real authorization (anyone who can edit labels can flip it). |

After saving the labels, refresh the host page — the iLO Console tab
should appear and the status panel should populate within ~2 seconds.

### Finding a credential ID

Go to **Infrastructure → Trust → Credentials**, hover the credential's
name, and check the URL or page title — there's a numeric `id` field.
The plugin's Diagnostics block also shows `credSource` once a
credential has been loaded, which can confirm you've got the right one.

---

## Using the tab

### Buttons at a glance

The header row has three buttons. Each one also has a hover tooltip
explaining what it does, in case you forget:

| Button | What it does |
| --- | --- |
| **Show credentials** | Reveals the configured iLO username and password in a panel below the header, for manual copy/paste. Pure HTML/CSS — nothing is transmitted anywhere by clicking this. Single-click any value to select the whole string for Ctrl+C. |
| **▶ Launch Console** | Auto-logs into iLO with the stored credentials, then opens the IRC console pre-authenticated in a new window. **One click does both steps.** Used when you want to go from Morpheus to an active iLO console in the fastest possible way. |
| **→ Open Console** | Opens the IRC console using the existing iLO session cookie that Launch Console established. Use this if the auto-navigate in Launch Console didn't complete (you see iLO's JSON response and nothing else), or to re-open the console in the same popup window later without re-authenticating. |

### Status panel

Renders within ~1–2 seconds of opening the tab. Pulled via a Redfish
session that's created with `POST /SessionService/Sessions`, used for a
handful of `GET`s on `/Systems/1`, `/Managers/1`, `/Processors/1`,
`/Memory`, and torn down with `DELETE /SessionService/Sessions/<id>` in
a `finally` block (the HPE-recommended lifecycle). No long-lived state
on the iLO; each tab render is a fresh session.

If you see a red error box instead of values, check the **Status panel
shows an error** section in `docs/TROUBLESHOOTING.md`.

### Launch Console (one-click)

Click **▶ Launch Console**. A new browser window opens, navigates to
iLO's `/json/login_session`, briefly displays iLO's JSON response, then
~1.5 seconds later auto-navigates to `https://<ilo>/irc.html` —
authenticated, no login prompt.

Behind the scenes:

1. The button is a `<form>` submit with `enctype="text/plain"` and a
   single hidden input whose name+value concatenate to a valid JSON
   body that iLO's parser accepts.
2. An inline `<script nonce="…">` (using Morpheus's per-request CSP
   nonce) pre-opens the popup window during the click's user-activation
   grant and schedules a `setTimeout` that navigates the popup to
   `/irc.html` after the form-POST has had time to set iLO's session
   cookie.
3. iLO's `sessionKey` cookie is `SameSite=Lax` by default, which means
   it sticks through the cross-origin form-POST and is sent on the
   subsequent navigation to `/irc.html`.

If the CSP nonce isn't readable for any reason — older Morpheus, edge
configuration, etc. — the tab silently falls back to the two-button
manual flow. Open the **Diagnostics** block to see `cspNonce: present`
or `cspNonce: missing`.

### Launch Console (two-click fallback)

If you only see one-click happening with the popup landing on iLO's
JSON response and stopping there, the nonce-based JS isn't loading.
The plugin still works in two clicks:

1. Click **▶ Launch Console** → popup opens with iLO's JSON response,
   cookie set on iLO's origin.
2. Click **→ Open Console** → popup navigates to `/irc.html` with the
   cookie attached. IRC loads authenticated.

### Show credentials

The deepest fallback. Click **Show credentials** to reveal the
configured username and (masked) password in a panel below the header.
Click **Reveal** next to the password to unmask it. Single-click any
value to select the whole string, then Ctrl+C / Cmd+C to copy.

This works regardless of any browser policy, CSP setting, SameSite
attribute, or iLO firmware quirk — it's pure HTML/CSS with no
JavaScript and no network calls.

---

## Troubleshooting

The most common issues:

- **Tab doesn't appear** → host doesn't match the show() criteria; add an `ilo-host:` label.
- **Status panel shows 404 on `/SessionService/Sessions/<id>`** → cosmetic, normal session-cleanup race.
- **Status panel shows 401** → wrong credential.
- **Launch Console shows "Malformed object"** → you're on a version before 0.1.24; upgrade.
- **Open Console lands on iLO's login page** → browser dropped the cookie; usually Brave shield, Firefox tracking protection, or iLO's `SameSite=Strict`. Use Show credentials.
- **One-click doesn't fire** → CSP nonce wasn't readable; check Diagnostics for `cspNonce: missing`. Two-button flow still works.

For the full symptom-driven guide, see [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md).

---

## Architecture

A short component map (full version in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)):

```
IloConsolePlugin                    (entry point)
└── registerProvider(IloConsoleServerTabProvider)

IloConsoleServerTabProvider         (the tab)
├── show()                          → matches HPE iLO hosts
└── renderTemplate()
    ├── IloConfigStore              → reads ilo-host / ilo-cred / ilo-verify-ssl labels
    ├── IloCredentialService        → loads username/password (uses the working credential API)
    ├── RedfishClient               → POST session, several GETs, DELETE session
    └── builds launch UI:
        ├── <form enctype="text/plain"> + JSON-injection input → posts to iLO
        ├── <a> → manual /irc.html companion
        ├── <script nonce="…">     → one-click bridge (when nonce readable)
        └── CSS-only Show credentials reveal
```

The unusual shapes — text/plain JSON injection, CSS-only credentials
toggle, no controller routes — exist because of Morpheus's CSP
(`strict-dynamic` blocks unnonced inline JS), iLO's mixed
session-types-and-cookie-policies model, and a six-version saga of
plugin controller routes returning 403 with no diagnostic. The
architecture doc explains each in detail.

---

## Power control

The plugin does **not** ship buttons for power on / off / reset, and there
are no plans to add them. Two reasons:

1. **The IRC console already exposes them.** Once you click **▶ Launch
   Console** and the IRC window loads, iLO's own toolbar at the top has
   Power → Momentary Press / Press and Hold / Cold Boot / Reset. Plus
   the **Power** dropdown on iLO's main dashboard offers the same set
   with graceful-shutdown variants. Adding a parallel set of buttons in
   the Morpheus tab would just be a thinner duplicate of what iLO's own
   UI does better.
2. **It depends on the iLO user's permissions anyway.** Redfish
   `POST /Systems/1/Actions/ComputerSystem.Reset` requires the iLO
   account to have the **Virtual Power and Reset** privilege bit
   (`reset_priv`). The session JSON we get on launch includes this:

   ```json
   {"session_key":"…", "user_name":"morpheus",
    "login_priv":1, "remote_cons_priv":1, "virtual_media_priv":1,
    "reset_priv":1, "config_priv":0, "user_priv":0}
   ```

   Even if the plugin offered power buttons, an account without
   `reset_priv:1` would get HTTP 403 on the action — meaning the
   feature would only work for accounts that already have it via iLO's
   own UI. Configuring privileges happens in iLO's
   **Administration → User Administration**; manage power access there,
   not in the Morpheus tab.

If you want to grant or revoke power control for a specific operator,
do it on the iLO user account directly, then they can do power actions
via the IRC console.

---

## Roadmap

Not yet implemented, in priority order:

1. **iLO 4 support** — check `Managers/1.FirmwareVersion` and switch
   the IRC URL from `/irc.html` to the iLO 4 path.
2. **Configuration UI** — replace label-driven config with a real
   plugin option-type form.
3. **Multi-iLO inventory view** — a separate dashboard provider that
   lists all iLO-managed hosts with summary status.
4. **Indicator LED toggle** — single button in the System card to flip
   the chassis identifier LED on/off. (Not power control per se, but
   uses the same `PATCH /Chassis/1` mechanism that power actions would.
   Useful when you're physically in the rack identifying a specific
   server.)

---

## Security notes

- The plugin never logs credentials. The audit logger in `services/AuditLogger.groovy`
  records action attempts (e.g. "user X launched console for server Y")
  but not the credential payload.
- Credentials in the tab DOM (Show credentials + the form's hidden
  input) are visible to any browser extension or page-inspector
  running in the user's session — same threat model as any password
  manager autofill. If your security model requires server-side-only
  credential handling, this plugin isn't a fit.
- The form-POST to iLO crosses origins (Morpheus appliance → iLO IP).
  Browsers honor `SameSite=Lax` (iLO's default) for top-level
  cross-origin form submissions, which is why the auto-login works.
  On iLOs configured with `SameSite=Strict`, the cookie is dropped
  and only the two-click + manual paste fallback works.

---

## Building

```bash
git clone https://github.com/builtbyfood/morpheus-argus.git
cd morpheus-argus
./gradlew clean shadowJar
# output: build/libs/morpheus-argus-0.1.35-all.jar
```

JDK 17 required (produces Java 11 bytecode for plugin-api 1.3.1 / Morpheus 9.0).

Project layout:

- `src/main/groovy/com/morpheusdata/iloconsole/` — Plugin + ServerTabProvider + Controller + services/
- `src/main/resources/i18n/` — message bundle
- `docs/ARCHITECTURE.md` — design rationale
- `docs/CHANGELOG.md` — version history
- `docs/TROUBLESHOOTING.md` — symptom-driven troubleshooting

---

## License

Apache 2.0. See [`LICENSE`](LICENSE).
