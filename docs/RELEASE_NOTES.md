# morpheus-argus v0.1.48

> Drop the JAR into Morpheus, get a live iLO panel on every HPE
> ProLiant host detail page plus one-click pre-authenticated console
> launch.

This release consolidates ten iterations of operational hardening on
top of the v0.1.34 feature complete (Power Trend / Network Adapters /
NIC Port LEDs / Volumes & RAID). v0.1.35 added user-facing polish,
v0.1.36 added pre-authenticated sessionkey URL console launch,
v0.1.38 added UID indicator LED control, and v0.1.39 → v0.1.48 hardened
that control across HPE firmware variance and Morpheus plugin-api
quirks based on direct field testing. The result is a single drop-in
JAR that "just works" on Morpheus 9.0 with iLO 5 / iLO 6 / iLO 7
hardware.

## Highlights

### Live iLO status panel

A new **iLO** tab on every HPE ProLiant host detail page, rendered
server-side from raw Redfish data. Cards: System (power, health, iLO
firmware, BIOS, CPU, RAM, UID, TPM, boot progress) · Power & Cooling
(draw + sparkline trend, PSU, CPU temp, ambient temp, fans) · Network
(host MAC/IP, iLO MAC/IP, iLO date/time, license) · Drives (per-drive
model/capacity/health) · Power Trend (inline SVG sparkline) · Network
Adapters (per-port link state, speed, MAC) · NIC Port LEDs (at-a-glance
colored dots) · Volumes / RAID (per-volume type, capacity, drive count)
· DIMMs (per-slot inventory) · Cooling Zones (every temp sensor) ·
Firmware Inventory (every component iLO reports) · Active iLO Sessions
(other users currently connected) · Recent Events (last 5 IML
entries). Temperatures shown in °C / °F.

### One-click pre-authenticated console launch (v0.1.36)

The **▶ Launch Console** button mints a Redfish session at tab render,
embeds the sessionkey in iLO's IRC URL, and opens the HTML5 console
pre-authenticated. No login prompt, no cookie-commit race, no
SameSite-cookie surprises. Falls back to the v0.1.35 form-POST cookie
flow automatically if the sessionkey mint fails. The
**→ Open Console** button is a manual companion that re-opens an
already-authenticated session in the same window.

Three plugin-wide settings under **Administration → Plugins → iLO
Console → Settings** control the launch UX without per-host
configuration: Auto-login on/off, popup window vs browser tab, and
sessionkey vs cookie auth. Plugin settings persistence is fully
working as of v0.1.42.

### UID indicator LED control (v0.1.38 → v0.1.40)

The System card includes UID indicator LED state plus three action
buttons (Off / Lit / Blink). Clicks fire a Redfish PATCH from the
plugin and update the badge optimistically. HPE moved the writable
UID property around across firmware revisions, so the plugin probes
an 8-step chain to find one that works:

1. DMTF `IndicatorLED` on `/Systems/1` (plain)
2. Same, with `If-Match: *` (handles strict ETag firmware)
3. DMTF `IndicatorLED` on `/Chassis/1`
4. Same, with `If-Match: *`
5. DMTF `LocationIndicatorActive` (boolean) on `/Systems/1`
6. DMTF `LocationIndicatorActive` (boolean) on `/Chassis/1`
7. HPE OEM `Oem.Hpe.IndicatorLED` on `/Systems/1`
8. HPE OEM `Oem.Hpe.IndicatorLED` on `/Chassis/1`

The same fallback chain runs on the read side, so the badge populates
correctly even on firmware where iLO only exposes the value through
the newer property.

### Operational diagnostics (v0.1.40 → v0.1.41)

The Diagnostics block at the bottom of the tab surfaces every
property-access trace, label values, CSP nonce status, plugin
settings forensics (raw JSON + parsed keys + per-key value), iLO
concurrent-session count with color-coded warning when approaching
iLO's ~13-session cap, and a per-attempt breakdown of every UID
PATCH probe step including HTTP code and Redfish MessageId. Triaging
"why isn't this working" takes seconds rather than tickets.

### Themed UI (v0.1.37)

Light-mode CSS variables and JS-based theme detection so the tab
reads cleanly against both Morpheus's dark and light themes
(luminance-based detection, no theme-class guessing).

## Install

1. Build: `./gradlew clean shadowJar` → `build/libs/morpheus-argus-0.1.48-all.jar`
2. Upload: **Administration → Integrations → Plugins → Upload Plugin**
3. Confirm in log: `tail -F /var/log/morpheus/morpheus-ui/current | grep -i ilo`
4. Configure per-host via labels: `ilo-host:<ip>`, `ilo-cred:<credential-id>`,
   `ilo-verify-ssl:<true|false>`, `ilo-readonly:<true|false>`
5. Open any HPE ProLiant host detail page → "iLO" tab

**Upgrading**: do a full uninstall first
(**Plugins → iLO Console → Uninstall**), then upload the new JAR.
Morpheus's plugin hot-replace can leave stale state, particularly
around OptionType registrations and plugin metadata.

## Compatibility

- Morpheus 9.0.0 with plugin-api 1.3.1
- HPE ProLiant MicroServer Gen10 Plus (iLO 5), MicroServer Gen11
  (iLO 6 v1.74), Compute DL325 Gen12 (iLO 7 v1.22.00)
- Chrome 130+, Firefox 130+, Brave 1.70+

## What's NOT in this release (deferred)

- **Per-port traffic counters.** Network Adapters card surfaces link
  state, speed, and MAC but not byte/packet counters. Filed for a
  future release.
- **Multi-iLO inventory view.** No top-level "all iLOs at once"
  dashboard. The per-host tab is intentional — operators triage one
  host at a time.
- **Power actions.** No power-on / power-off / reset / graceful
  shutdown buttons. Filed; needs iLO permission model thought.
- **Server-side controller route for the launch flow.** Console
  launch currently relies on per-render sessionkey minting; a proper
  server-side endpoint would simplify the JS dance, but is blocked
  on an unresolved Morpheus controller-403 issue documented in
  `docs/ARCHITECTURE.md`.

## Detailed changelog

See `docs/CHANGELOG.md` for the full version-by-version trace
including every operational lesson learned in the v0.1.36 → v0.1.48
hardening sequence. Highlights:

- **v0.1.42**: settings actually load (RxJava 3 `blockingGet()`, not
  the v0.1.41 RxJava 2 `toBlocking()`)
- **v0.1.41**: correct Morpheus API method (`getSettings(this)`, not
  the v0.1.38 `getPluginConfig()`); iLO session-limit failure mode
  documented and surfaced as a diagnostics row
- **v0.1.40**: HPE OEM UID property added as probe steps 7-8;
  settings-persistence forensics row added
- **v0.1.39**: UID PATCH probe chain expanded to 6 steps with
  `If-Match: *` retry and `LocationIndicatorActive` fallback; window
  mode actually opens tabs
- **v0.1.38**: UID indicator LED control feature; light-mode theme
  detection
- **v0.1.37**: plugin settings reworked from SELECT dropdowns to
  CHECKBOXES (the SELECT path didn't work in plugin-api 1.3.1)
- **v0.1.36**: sessionkey URL launch + cookie-fallback; plugin
  settings page
- **v0.1.35**: dual °C / °F temperature display; GitHub repo
  preparation; LinkedIn announcement content

## License

MIT. See `LICENSE`.
