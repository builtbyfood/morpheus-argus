# Troubleshooting

Symptom-driven guide. Each section names what the user sees, then explains
what's actually happening underneath and what to change.

---

## The iLO Console tab doesn't appear on the host detail page

**What `show()` checks.** `IloConsoleServerTabProvider.show(server, …)`
returns `true` if any of these are true:

1. The server's `name` contains `ilo` (case-insensitive).
2. The server's `description` contains `ilo` (case-insensitive).
3. The server has a label starting with `ilo-host:`.
4. The server's `serverType.code` matches a known HPE type.

If none match, the tab is hidden. Add an `ilo-host:` label to force it
visible (see *Configuration* in the README).

**Verify the plugin loaded.** Tail the Morpheus log while uploading:

```
tail -F /var/log/morpheus/morpheus-ui/current | grep -i ilo
```

You should see:

```
INFO  c.m.i.IloConsolePlugin - iLO Console plugin 0.1.34 initialized
   (custom HandlebarsRenderer, no controller routes)
```

If you don't, the plugin failed to load — check for stack traces in the
log around the upload time, and confirm the JAR was actually placed in
`/var/opt/morpheus/plugins/`.

**Stale plugin.** Morpheus's plugin loader has historically been fussy
about hot-replacing a JAR. If you uploaded a new version on top of an
old one and behavior looks stale, do a full uninstall: **Administration
→ Integrations → Plugins → iLO Console → Uninstall**, then upload the
new JAR fresh.

---

## Status panel shows "iLO error: 404" or "401 Unauthorized"

The Redfish status pull is failing on the appliance → iLO HTTPS connection.
Common causes:

| Error | Likely cause | Fix |
| --- | --- | --- |
| `404` on `/redfish/v1/SessionService/Sessions/<id>` | Normal — happens during the cleanup `DELETE`. iLO already expired the session. Not actually an error. | None; the WARN-level log line is cosmetic. |
| `401 Unauthorized` on the login | Wrong username/password in the credential. | **Infrastructure → Trust → Credentials**, edit the credential set on the `ilo-cred:` label. |
| `Connection refused` or `Connect timed out` | iLO is unreachable from the appliance (network ACL, wrong IP in `ilo-host:` label, iLO powered off). | Check `ilo-host:` label value matches the iLO's actual IP/hostname; ping/curl from the appliance to confirm reachability. |
| `PKIX path building failed` | iLO has a self-signed cert and the appliance's JVM trust store rejects it. | Add `ilo-verify-ssl:false` label, or import iLO's cert into the appliance's trust store. |

The full error is in the tab's red error box and also in the Diagnostics
block as `errorMsg`. The credential lookup path is in `credSource` —
useful for confirming the right credential was loaded.

---

## Launch Console popup shows "Malformed object, expected '{' at start of object"

You're on a version before 0.1.24. The form was sending
`application/x-www-form-urlencoded` (the HTML form default) but iLO's
`/json/login_session` expects JSON. Upgrade to 0.1.24 or newer, which
uses `enctype="text/plain"` with a JSON-injection trick to ship a
valid JSON body without inline JavaScript.

If you're already on 0.1.24+ and seeing this, check the Diagnostics
block in the tab — `cspNonce` should be `present` for one-click mode or
`missing` for the two-button manual flow. Either way, the form body
should be JSON-shaped. Capture the request in the browser DevTools
Network tab → Request → Payload to confirm the body looks like
`{"method":"login","user_login":"…","password":"…","x":"="}`.

---

## Launch Console popup shows iLO's login page, not the JSON session response

Something is keeping the form from reaching iLO at all. Check:

1. **HTTPS cert** — first time you hit iLO from this browser, you need
   to accept its self-signed certificate. Open `https://<ilo>/` directly
   once, click through the cert warning, then try Launch Console again.
2. **Mixed content** — if your Morpheus appliance is `https://` and your
   iLO is also `https://`, no issue. If Morpheus is `http://`, modern
   browsers may block the cross-origin POST. Use HTTPS on Morpheus.
3. **Network reachability from the browser** — the form-POST happens
   from your browser, not the appliance. The appliance can talk to iLO
   (status pull works), but your browser must also be able to reach
   the iLO IP. If you're behind a VPN that only routes traffic to
   the appliance, the browser can't reach the iLO directly.

---

## Open Console lands on iLO's login page (Launch Console worked)

The form POST succeeded — you saw iLO's JSON response — but the session
cookie didn't follow. Almost always one of:

**Browser cookie policy.** Some browsers (notably Brave and Firefox with
strict tracking protection) treat the iLO origin as third-party in this
context and drop the `Set-Cookie`. Check the browser's shield/tracker
settings:

- **Brave**: click the Brave shield icon in the address bar; under
  "Cross-site cookies blocked" or similar, toggle off for this site.
- **Firefox**: enhanced tracking protection might be flagging it. Try
  with tracking protection lowered for this domain.
- **Chrome**: third-party cookie blocking can fire if you've enabled
  "Block third-party cookies" globally. Add an exception for the iLO
  IP.

**iLO firmware with `SameSite=Strict`.** Some recent iLO 6 builds set
`SameSite=Strict` on `sessionKey`. That's incompatible with cross-origin
launches by design; there's no way to override it from the plugin side.
Falls back to **Show credentials** for copy/paste.

**Verify which it is.** Open browser DevTools, switch to the Application
(Chrome) or Storage (Firefox) tab, look under Cookies for the iLO IP.
After clicking Launch Console:

- Cookie present → SameSite isn't the issue; check that Open Console's
  target matches Launch's exactly (both should be `iloConsole_<server.id>`).
- Cookie absent → either SameSite or browser policy is dropping it.

---

## One-click flow doesn't work — popup just shows the JSON and stops

Check the **Diagnostics** block in the tab:

- `cspNonce: present (length=N, one-click ENABLED)` — the JS loaded.
  Most likely the 1500 ms timeout is too short for your network. The
  delay is currently a compile-time constant in
  `IloConsoleServerTabProvider.groovy`; rebuild with `delayMs = 2500`
  (or higher) to give iLO more time before navigation.

- `cspNonce: missing (one-click DISABLED, two-button manual flow active)` —
  the nonce attribute Morpheus stamps on the request isn't where we
  expect it. Possible causes:
  - Morpheus version older than expected — `js-nonce` is the attribute
    name in 9.0 / plugin-api 1.3.1; older or newer Morpheus may use a
    different name.
  - The tab is being rendered outside a normal HTTP request context for
    some reason (cached prerender? scheduled job? unlikely but
    possible).
  - The fall-through two-click flow still works; this is annoying but
    not blocking.

If you're stuck on the manual two-click flow and want the one-click to
work, capture a `grep -i nonce /var/log/morpheus/morpheus-ui/current`
right after a tab render. There should be a line like
`iLO tab render — CSP nonce: missing (one-click JS disabled)`.

---

## Show credentials reveals the wrong account

The plugin loads whichever credential is referenced by the host's
`ilo-cred:` label. Check the label value:

```
ilo-cred:1234   # credential ID 1234
```

In Morpheus, go to **Infrastructure → Trust → Credentials**, find the
credential by ID, and verify its username and password match the iLO.

If you change the credential's password elsewhere, the next tab render
picks it up automatically — there's no plugin-side cache.

---

## Drives card is missing or shows no drives

The plugin tries five different Redfish paths to enumerate drives:

1. `/Systems/1/Storage/<n>/Drives/<n>` — modern, used when a Smart Array, MR, SR, or NS204 controller is present
2. `/Systems/1/SimpleStorage/<n>/Devices/<n>` — legacy (iLO 5 and earlier)
3. `/Chassis/1/Drives` — direct chassis Drives collection
4. `/Chassis/1.Links.Drives` — chassis Links array
5. Walk of `/redfish/v1/Chassis` collection — catches RDE storage devices that register their own chassis IDs (e.g. `/Chassis/DE040000`)

Open the **Diagnostics** block and look at the `drivesDiag` row. It shows
how many members each step returned. If all counts are zero, iLO genuinely
isn't enumerating drives.

### Why this happens on ProLiant Gen11 entry-level (e.g. MicroServer Gen11)

Entry-level ProLiant Gen11 hosts often don't have a Smart Array, MR, SR,
or NS204 storage controller — drives are attached directly to the
chipset's AHCI SATA ports. **iLO 6 does not enumerate drives on chipset
SATA controllers via Redfish unless HPE's Agentless Management Service
(AMS) is running on the host operating system.** This is documented in
HPE's iLO 6 User Guide:

> *"To view a full set of data on the Storage Information page, ensure
> that AMS is installed and running. SAS/SATA controller information is
> displayed only if AMS is installed and running on the server."*

The plugin surfaces this via the `amsStatus` row in Diagnostics. If
it's empty or shows `Critical`, install the appropriate HPE AMS package
on the host OS:

- **Linux** (RHEL/SLES/Ubuntu): https://www.hpe.com/support → Search "amsd"
- **Windows Server**: https://www.hpe.com/support → Search "HPE Agentless Management Service Windows"
- **VMware ESXi**: bundled in the HPE Customized ESXi image

After AMS is installed and running, the next tab render should populate
the Drives card. iLO 6 1.60+ also moves direct-attached NVMe drives out
of the Storage Controllers list and into Drives only — this is normal
and the plugin handles it.

> **v0.1.40 confirmation.** Field-confirmed on a Gen11 MicroServer
> (iLO 6 v1.74, KVM hypervisor host running `amsd`): installing AMS
> took the Drives card from empty to populating with the on-chassis
> SATA drive details (model, capacity, health) on the next render,
> no plugin restart required.

### Why this also happens during boot

If `deviceDiscovery` in Diagnostics shows anything other than
`vMainDeviceDiscoveryComplete`, iLO is still discovering hardware
(usually during/just after POST). A yellow info banner appears in the
tab in this case. Wait a minute and refresh.

---

## `hpilo` kernel log noise after AMS install

After installing AMS, the host's kernel log (`dmesg`, `journalctl -k`)
fills with messages like:

```
[123456.7890] hpilo 0000:02:00.2: Open could not dequeue a packet
[123456.7891] hpilo 0000:02:00.2: Open could not dequeue a packet
[123456.7892] hpilo 0000:02:00.2: Closing, but controller still active
```

**This is normal operational noise from the HPE CHIF (Channel Interface)
driver, not a fault.** The `hpilo` kernel driver provides the userspace
channel that AMS uses to talk to iLO over PCIe. The "could not dequeue
a packet" warnings happen when AMS opens a channel slot, polls for
data, and finds none waiting yet — which is common during normal AMS
operation. The "Closing, but controller still active" message happens
when AMS closes a channel slot while iLO still has the corresponding
mailbox flagged as active.

These messages do **not** indicate:
- An iLO failure or instability
- A driver bug
- A plugin malfunction (the plugin talks Redfish over the network, not
  through `hpilo` — these messages happen whether or not the plugin
  is installed)
- A reason to remove or stop AMS

If the volume of messages is operationally annoying, you can filter
them out at the rsyslog or journald level:

```
# Filter hpilo messages from rsyslog
:msg, contains, "hpilo" stop
```

Or rate-limit them in dmesg:
```
echo 'kernel.printk_ratelimit = 5' >> /etc/sysctl.d/99-hpilo-quiet.conf
sysctl -p /etc/sysctl.d/99-hpilo-quiet.conf
```

Filing a bug with HPE about the warning verbosity is the long-term
path; the messages serve no operational purpose and the driver could
demote them to debug level.

---

## Power Trend card is missing or says "No history available"

The Power Trend card (v0.1.34) pulls from two sources:

1. `/Chassis/1/EnvironmentMetrics` for the current `PowerWatts.Reading`
2. `/Chassis/1/Power/PowerMeter` for the historical `Samples[]` array

Open Diagnostics and look at the `powerTrend` row. It shows
`source=...`, sample count, and the current reading.

- **Card absent entirely.** Both sources returned null/empty AND the
  existing `/Power` read returned 0 — i.e. nothing useful to display.
  Common on entry-tier ProLiant Gen11 (e.g. MicroServer Gen11), where
  the hardware doesn't track power. Working as designed.
- **Card present, sparkline says "No history available".** EnvironmentMetrics
  returned a current reading but PowerMeter has no samples. Some iLO 6
  firmware revisions don't populate the historical buffer until the
  server has been running for a while — wait 15+ minutes and refresh.
- **Card present, "Current reading not reported".** PowerMeter returned
  history but EnvironmentMetrics returned 0/null for current. The
  sparkline still renders. Treated as "not reported" rather than
  "genuinely 0 W" per the v0.1.31 convention.

## Network Adapters card is missing

The plugin walks `/Chassis/1/NetworkAdapters`. Open Diagnostics and
look at the `networkAdapters` row.

- **`0 adapters, 0 ports`.** iLO didn't expose any adapters at this
  path. Some older iLO 6 firmware (pre-1.50) and most iLO 5 firmware
  only populated network data under `/Systems/1/EthernetInterfaces`,
  not `/Chassis/1/NetworkAdapters` — in which case the simpler
  "Network" card (v0.1.28+) is the right place to look for host NIC
  details. The Network Adapters card is iLO 6 1.50+ territory.
- **Adapters listed but no ports.** The adapter's `NetworkPorts`
  collection returned empty. Check whether iLO has finished POST
  discovery (the `deviceDiscovery` row).

## NIC Port LEDs all show the same color (gray)

The LED logic prefers HPE's `Oem.Hpe.PortHealth` first, falls back to
base `Status.Health`, and finally uses `LinkStatus` for green/gray.
If every dot is gray, the most likely cause is that all ports are
`LinkDown` and have no health field populated. Confirm in the Network
Adapters card above — the per-port table shows the same data.

## UID cell shows "Unknown" or buttons don't do anything (v0.1.38+)

The plugin reads `IndicatorLED` from `/Chassis/1` first, then falls
back to `/Systems/1`. If both come back null, the cell shows "Unknown".
This is rare — every iLO 5 / iLO 6 / iLO 7 firmware we've seen
populates one or both. If it persists, check the Diagnostics row
`indicatorLed` at the bottom of the tab; if that row is missing entirely
the read failed at the HTTP layer (auth or network issue).

If the buttons render but clicking them does nothing — no URL change,
no badge change — the CSP nonce is missing. The same nonce drives
theme detection and console launch, so check the Diagnostics row
`cspNonce` — if it reads `missing`, no JS runs in the tab, including
UID. This is the same condition that disables one-click console
launch; see "One-click flow doesn't work" above.

The action only fires for the exact values `Off`, `Lit`, `Blinking`
(case-sensitive). The buttons emit those values directly; if you
construct a `?argusUidAction=` URL by hand with a different value
it is ignored silently — no PATCH, no diagnostic row.

## UID change failed: iLO returned 400 PropertyNotWritableOrUnknown intermittently

This symptom looks identical to the firmware-property-readonly case from
v0.1.40 but is actually a transient session-pressure failure when other
clients (web UI tabs, open IRC console windows, ongoing REST API
clients) are holding iLO sessions.

iLO 6 typically caps concurrent sessions at around 13 across **all**
clients. The plugin opens an iLO session on every tab render to mint
the sessionkey for console launch, and every Launch Console click
opens another short-lived session that doesn't close until the IRC
window does. A normal operator workflow (open the iLO tab a few times,
launch console once or twice, leave a console window open while you
work, then come back to the tab) can stack 6-10 sessions easily.

Once the pool saturates, iLO refuses NEW operations with confusing
errors. PATCH operations (UID writes, BIOS settings, anything mutable)
fail first. Reads continue to succeed, so the symptom looks code-
related rather than session-related: the tab renders fine, but
clicking UID buttons returns the misleading
`iLO.2.37.PropertyNotWritableOrUnknown` error on every probe step.

### How to confirm session pressure is the cause

Open the **Diagnostics** block and look for the `iloSessions` row
(v0.1.41+). It shows the current concurrent session count. v0.1.41
color-codes it:

- `< 7` sessions → black/normal, no concern.
- `7-10` sessions → yellow, approaching the cap.
- `≥ 11` sessions → red, at or near the cap. The inline hint reads:
  *"at or near iLO's ~13-session cap; close stale console windows
  before chasing UID PATCH errors."*

If `iloSessions` reads ≥ 11 and your UID PATCH is failing with
`PropertyNotWritableOrUnknown`, you've found the cause.

### How to recover

1. Close any IRC console windows you have open in your browser. Each
   one holds an iLO session for the duration of the window's lifetime.
2. Close any iLO web UI tabs.
3. In iLO web UI: navigate to **Administration → Active Sessions** and
   manually disconnect any stale sessions.
4. Wait. iLO times out idle sessions after ~30 minutes by default; the
   pool will recover on its own if you stop adding to it.

Refresh the Morpheus iLO tab. `iloSessions` should drop. UID PATCH
should start working again.

### Why this is so confusing

The error message iLO returns —
`iLO.2.37.PropertyNotWritableOrUnknown` — is genuinely misleading.
"Unknown" suggests the property doesn't exist, not that the system is
under session pressure. HPE could fix this by returning a 503 Service
Unavailable with a session-pressure-specific MessageId, but the
current behavior dates back to early iLO firmware. The
`iloSessions` row is the cheapest way to tell the difference without
filing a ticket with HPE.

### Long-term plugin behavior

The plugin currently opens a session per tab render and lets iLO
reap idle sessions. A future version could explicitly delete the
session at the end of each render (we have `sessionLocation` saved
and a logout path in `RedfishClient`), trading slight latency on
each render for never contributing to session pressure. Filed as a
to-do; happy to accept PRs for it.

## UID change failed: iLO returned 403 / 401 / 400 / 404 / 405 (v0.1.40+)

The plugin walks an 8-step PATCH probe chain when you click a UID button:

1. `PATCH /redfish/v1/Systems/1 {IndicatorLED}` with no `If-Match`.
2. Same, but with `If-Match: *` (rules out firmware that returns 403
   instead of the spec-correct 412 for missing-ETag concurrency).
3. `PATCH /redfish/v1/Chassis/1 {IndicatorLED}` with no `If-Match`.
4. Same, but with `If-Match: *`.
5. `PATCH /redfish/v1/Systems/1 {LocationIndicatorActive: bool}` — DMTF's
   newer boolean replacement for IndicatorLED. Off → `false`, Lit/Blinking
   → `true`.
6. `PATCH /redfish/v1/Chassis/1 {LocationIndicatorActive: bool}`.
7. `PATCH /redfish/v1/Systems/1 {Oem: {Hpe: {IndicatorLED: value}}}` —
   HPE OEM nested property. HPE's iLO 6 changelog calls this out
   explicitly as the writable fallback for clients that want to keep
   using Lit/Blinking/Off rather than the boolean LocationIndicatorActive.
   On modern iLO 6 firmware (1.5+) where the DMTF properties are
   read-only, this is the one that actually works.
8. `PATCH /redfish/v1/Chassis/1 {Oem: {Hpe: {IndicatorLED: value}}}`.

If any step succeeds, you see the green check banner with the property
name that worked, like `UID set to Lit (success via Oem.Hpe.IndicatorLED)`.
If all eight fail, the inline banner names the most likely cause based on
the HTTP code and (for 400) the Redfish MessageId from iLO's response
body. The per-step outcome lives in the Diagnostics row
`uidActionAttempts`, which looks like:

```
/Systems/1: HTTP 400 iLO.2.37.PropertyNotWritableOrUnknown ·
/Systems/1 +ETag: HTTP 400 iLO.2.37.PropertyNotWritableOrUnknown ·
/Chassis/1: HTTP 400 iLO.2.37.PropertyNotWritableOrUnknown ·
/Chassis/1 +ETag: HTTP 400 iLO.2.37.PropertyNotWritableOrUnknown ·
/Systems/1 LIA: HTTP 400 iLO.2.37.PropertyNotWritableOrUnknown ·
/Chassis/1 LIA: HTTP 400 iLO.2.37.PropertyNotWritableOrUnknown ·
/Systems/1 OEM: ok
```

That format tells you both *which paths failed and how* and *which path
finally worked*. The `LIA` and `OEM` tags mark which property the
attempt used (DMTF IndicatorLED is unlabeled because it's the default).

**All eight returned 403.** Almost always RBAC — the iLO user account
lacks the privilege to write `IndicatorLED`. See *iLO user privileges
required for write operations* below.

**All eight returned 401.** The X-Auth-Token expired between login and
PATCH. We've only seen this when another admin session was active on
iLO and the plugin's session got bumped.

**All eight returned 400 with `PropertyNotWritable`, `PropertyReadOnly`,
or `iLO.*.PropertyNotWritableOrUnknown` (HPE's variant).** Every
writable UID property we know about — `IndicatorLED`,
`LocationIndicatorActive`, and `Oem.Hpe.IndicatorLED` — is read-only
on this firmware/hardware combination. This shouldn't happen on
supported iLO firmware; file a GitHub issue with the iLO version
string from the Diagnostics row `iloFirmware` and the full
`uidActionAttempts` row so we can hunt for whatever new OEM action
HPE may have introduced.

**All eight returned 400 with `PropertyValueNotInList`,
`PropertyValueNotInAllowableValues`, or `PropertyValueTypeError`.** The
value you sent isn't in the firmware's allowed list. Some firmware
supports only `Off` and `Blinking` (no `Lit` steady state). Try a
different button.

**All eight returned 404.** None of the three properties is exposed on
this firmware/hardware combination. File a GitHub issue.

**All eight returned 405.** PATCH method not allowed. Likely a firmware
quirk. Workaround: click "Open iLO UI" in the header card and set UID
from iLO's own UI.

**Mixed codes across the eight attempts.** Usually means firmware quirks
rather than RBAC. Capture the exact `uidActionAttempts` row and file
an issue.

**Steps 1-6 fail but step 7 or 8 succeeds.** This is the expected path
on iLO 6 v1.5+ firmware that has made DMTF IndicatorLED read-only AND
also locks down LocationIndicatorActive. HPE moved the writable bit
to `Oem.Hpe.IndicatorLED` explicitly as a Lit/Blinking/Off fallback.
Everything works correctly; the OEM property round-trips faithfully
(Lit and Blinking are distinguishable, unlike the LocationIndicatorActive
boolean fallback).

**Steps 1-4 fail but step 5 or 6 succeeds.** Older firmware path where
DMTF IndicatorLED is deprecated and LocationIndicatorActive is the
writable property. Caveat: the badge will read `Lit` for any "on"
state (boolean can't distinguish Lit from Blinking), and the diagnostics
row `indicatorLed` will append `(via LocationIndicatorActive)`.

## Plugin settings checkboxes appear to do nothing (v0.1.40+)

Symptom: unchecking "Console: Open in Popup Window" still opens a
popup; unchecking "Console: Auto-login (SSO)" still pre-authenticates.
Same for the third checkbox. v0.1.40 added diagnostics rows so you can
see what's actually going on without source access to Morpheus's form
handler.

Open the **Diagnostics** block at the bottom of the tab and look for
five rows:

- `launchMode`, `launchWindowMode`, `launchAuthMethod` — what the
  plugin ultimately decided after reading + interpreting the saved
  settings.
- `settingsParsedKeys` — list of keys present in the JSON Morpheus
  persisted, plus a `hasKeys=yes/no` boolean. `hasKeys=no` means
  Morpheus is returning an empty config blob (you've never saved, or
  Morpheus dropped it somehow).
- `settingsRaw` — each individual setting's raw value as Morpheus
  stored it: `autoLogin=on · popup=<null> · sessionkey=on`. `<null>`
  means the field is entirely absent from the JSON (Morpheus dropped
  it when you unchecked); a value like `on` or `off` is the literal
  string Morpheus persisted.
- `settingsJson` — the raw JSON Morpheus returned from
  `getPluginConfig()`, truncated to ~400 chars.

### Three possible diagnoses, by `settingsRaw` row content

**Unchecked field shows as `<null>` in `settingsRaw`.** Morpheus is
dropping unchecked CHECKBOX fields from the persisted JSON. v0.1.40's
defensive `isOn()` should handle this correctly — when other keys are
present (`hasKeys=yes`), missing keys are treated as `false`. If
you're seeing this AND the corresponding `launchMode` /
`launchWindowMode` / `launchAuthMethod` row still reads the "on"
value, file a GitHub issue with the full diagnostics block.

**Unchecked field shows as `off`, `false`, `0`, or empty string.**
Morpheus is persisting the unchecked state explicitly. The defensive
`isOn()` should handle this. If the resolved row (`launchMode` etc.)
still reads the "on" value, file a GitHub issue.

**Unchecked field shows as `on` regardless of UI state.** This is the
nasty case: Morpheus is persisting the OptionType's `defaultValue` on
form submit regardless of UI checkbox state. The plugin can't tell
this apart from the user genuinely leaving the box checked, and there's
no plugin-side fix that doesn't break the fresh-install UX of
"defaults are sensible without visiting Settings". File a Morpheus
issue with the diagnostics row attached.

In all three cases the diagnostics row is the source of truth. Don't
guess what's saved — read it.

## iLO user privileges

The plugin reads a lot from iLO and writes very little. In practice, an
iLO user account with just **Login** and **Remote Console** privileges
has been sufficient for every plugin feature in field testing — reads,
console launch (sessionkey-pre-authenticated), AND UID indicator LED
control via `LocationIndicatorActive` and `Oem.Hpe.IndicatorLED`. You
don't necessarily need to grant additional write privileges for UID
control specifically; both DMTF's newer boolean property and HPE's
OEM property route around the older `Configure iLO Settings`
requirement that the deprecated top-level `IndicatorLED` had.

If UID PATCH fails with HTTP 403 on every probe step, RBAC is one
possible cause but not the only one — concurrent session pressure on
iLO (see *UID change failed intermittently* above) more commonly
produces this symptom than missing privileges. Verify the session
count in the `iloSessions` diagnostic row first; if it's near or over
13, close stale console windows and retry before touching iLO user
privileges.

If UID PATCH genuinely returns 403 after sessions have been cleared
and a retry, the iLO user may need a higher privilege level — HPE's
RBAC is firmware-version-specific and not consistently documented.
Try escalating the iLO user to administrator temporarily as a
diagnostic; if writes succeed, you can narrow down which specific
privilege is needed by re-removing them one at a time. The plugin
itself makes no specific privilege demand beyond Login + Remote
Console for everything that currently works.

## Volumes / RAID card is missing

The plugin walks `/Systems/1/Storage` and for each controller fetches
the Volumes collection.

- **No RAID controller present.** Gen11 MicroServer without an
  MR/SR/NS204 card returns zero members from `/Systems/1/Storage`,
  the `result.volumes` list is empty, the card doesn't render.
  Working as designed — same pattern as the absent Drives card on
  this hardware. Open Diagnostics and look at the `volumes` row to
  confirm.
- **Controller present but no volumes defined.** A controller in
  HBA / JBOD mode (no virtual disks configured) will still appear
  in `/Systems/1/Storage` but with an empty `Volumes` collection.
  The card stays hidden. Create a volume in iLO's storage
  configuration UI to populate it.

---

## Tab shows "iLO Console tab failed to load: NullPointerException: …"

`renderTemplate()` threw. The full message after the colon should point
at what was null. Common cases:

| Message | Cause |
| --- | --- |
| `Cannot get property 'iloHost' on null object` | `configStore.loadConfig(server)` returned null — usually because `ilo-host:` label is missing. |
| `Cannot invoke method getCredentialId on null object` | The `ilo-cred:` label is malformed (e.g. has spaces, wrong format). |

Open Diagnostics in the tab — `labelsCsv` shows the actual labels the
plugin saw on the server, which is the fastest way to confirm whether
the label was applied at all and what value it has.

---

## "Plugin upload" succeeds, but the new version doesn't take effect

Morpheus's hot-reload of plugins is inconsistent across versions. The
reliable fix: do a full uninstall before re-uploading.

1. **Administration → Integrations → Plugins** → click the iLO Console
   row → **Uninstall**.
2. Wait for the row to disappear (a few seconds).
3. **Upload Plugin**, select the new JAR.
4. Confirm in the morpheus-ui log:
   `iLO Console plugin <new-version> initialized`
   `iLO Console plugin <old-version> destroyed`

If you skip the uninstall and just re-upload, you'll sometimes get the
old controller routes still registered, stale singletons, or worse —
duplicate tabs.

---

## I want to disable the plugin without uninstalling

Toggle it off in **Administration → Integrations → Plugins → iLO Console
→ Disable**. The tab disappears from host pages until you re-enable.
The plugin's JAR and credentials remain in place.
