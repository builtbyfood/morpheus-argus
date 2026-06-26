# Changelog

All notable changes to the `morpheus-ilo-console` plugin. The version numbers
trace the actual iteration history; lessons learned at each step are summarized
in the `Notes` rows because they explain why the next version exists.

## 0.1.35 — Dual Celsius / Fahrenheit temperature display

A small unit-display change so the plugin reads naturally for an
international audience without needing a config knob.

### What changed

- **Added.** `formatTemp(Integer c)` helper in
  `IloConsoleServerTabProvider`. Returns `"${c} °C / ${f} °F"` with `f`
  rounded to the nearest whole degree, or an em-dash for null. Single
  source of truth — no other code touches temperature formatting.
- **Switched.** All five temperature display sites now call
  `formatTemp()`: System card *CPU Temp* pill, System card *Ambient*
  field, Drives table *Temp* column, Cooling Zones table *Reading*
  column, Cooling Zones *Warn @* / *Crit @* columns. Same C source, just
  with F appended.
- **No data path change.** Redfish reports temperatures only in Celsius
  (`ReadingCelsius`, `CurrentTemperatureCelsius`). The C value is still
  the canonical source — F is derived at render time. No new fields
  collected, no new HTTP calls.

### Why dual display rather than a toggle

A unit-toggle (label / option-type / UI button) was considered and
rejected:

- A toggle is a config surface that has to be discovered, documented,
  and remembered per-host or per-user. Dual display works for everyone
  with zero configuration.
- The dense layouts of the temperature tables can afford the extra
  characters — `53 °C / 127 °F` is six characters longer than `53 °C`
  and reads cleanly in mono columns. There's no real-estate reason to
  hide one or the other.
- It's consistent with how other monitoring tooling (Grafana, iDRAC,
  IPMI sensors) tends to present temperatures: both, side-by-side, no
  toggle.

### Repo hygiene also in this release

- README example label values use generic IP ranges rather than the
  author's home-lab IPs.
- TROUBLESHOOTING.md no longer references a specific iLO IP — just
  "the iLO IP" as a placeholder concept.

---

## 0.1.34 — Four new cards: Power Trend, Network Adapters, NIC Port LEDs, Volumes / RAID

Pulls four more data surfaces out of Redfish, each in its own card with its
own defensive try-catch so a missing endpoint just hides one card rather
than blanking the tab. None of the existing v0.1.33 cards were touched.

### Power Trend card

Inline SVG sparkline of historical power draw, side-by-side with a
"current draw" gauge.

- **Added.** `/Chassis/1/EnvironmentMetrics` read for the current
  `PowerWatts.Reading` plus `ReadingRangeMin/Max` when present.
- **Added.** `/Chassis/1/Power/PowerMeter` read for HPE's historical
  sample buffer (`Samples[]` — a rolling per-minute / per-five-minute
  buffer iLO 6 maintains on supported hardware). Capped at the last 60
  samples for the sparkline — denser polylines just look noisy at the
  card's width.
- **Added.** Cross-fill from the existing `/Power` read so the card has
  *something* useful even when EnvironmentMetrics and PowerMeter are
  both empty — `powerMin/Max/AvgWatts` and `powerIntervalMin` flow in
  from the Power & Cooling block.
- **Added.** `renderPowerSparkline()` helper. Pure inline SVG — polyline
  + translucent area fill, viewBox-normalized so it scales to the card
  width. No JS, no external assets — CSP-safe.
- **Honored v0.1.31 convention.** A reading of `0` is treated as "not
  reported" rather than "genuinely 0 W draw", since iLO 6 on entry-tier
  hardware (e.g. MicroServer Gen11) returns 0 when the sensor isn't
  populated. The gauge shows "Current reading not reported" rather than
  a misleading "0 W now".
- **Empty state.** When `PowerMeter.Samples` is empty, the sparkline
  slot becomes a centered "No history available — iLO didn't return
  /Power/PowerMeter samples on this hardware tier" note, and the gauge
  half still renders whatever stats are available. When *everything* is
  empty/zero (the Gen11 MicroServer case), the card is suppressed
  entirely.

### Network Adapters card (collapsible)

Per-adapter detail from `/Chassis/1/NetworkAdapters` — distinct from the
existing "Network" summary card, which only reports the primary host NIC
IP/MAC/link.

- **Added.** Walk of `/Chassis/1/NetworkAdapters`, fetching each adapter
  plus its `NetworkPorts` collection (with a fallback to the newer
  `Ports` schema for iLO 7+ forward compatibility).
- **Captures per adapter.** `Model`, `Manufacturer`, `SerialNumber`,
  `PartNumber`, `Firmware.Current.VersionString` (with
  `FirmwarePackageVersion` fallback), `Status.Health`.
- **Captures per port.** `LinkStatus`, speed (in `CurrentLinkSpeedMbps`
  → `LinkSpeedMbps` → `CurrentSpeedGbps×1000` priority order),
  `ActiveLinkTechnology`, MACs from `AssociatedNetworkAddresses` (with
  newer `Ports.Ethernet.AssociatedMACAddresses` fallback),
  `PhysicalPortNumber`, base `Status.Health` + state.
- **Sort stability.** Ports sorted by physical port number (iLO returns
  them in arbitrary `Members` order, which made for an ugly table);
  adapters sorted by name. Both sort closures null-safe per the v0.1.33
  discipline.
- **UI.** Collapsible `<details>` block — header summary shows adapter
  and port counts, expanding reveals per-adapter sections each with
  identity strip + ports sub-table.

### NIC Port LEDs card

Compact at-a-glance per-port health using HPE `Oem.Hpe.PortHealth` plus
base `Status.Health` + `LinkStatus`. Kept separate from Network Adapters
(not folded in) — useful for quick "any red lights?" scans during
incident triage without expanding the larger details block.

- **Added.** Capture of `Oem.Hpe.PortHealth`, `Oem.Hpe.LinkStatus`,
  `Oem.Hpe.PortStatus`, `Oem.Hpe.FlexLOM`, `Oem.Hpe.NicCapacity` on each
  NetworkPort entry (alongside the standard fields above).
- **LED color logic.** Most-specific-wins priority — HPE OEM critical
  or base critical → red; HPE OEM warning or base warning → yellow;
  `LinkStatus == 'LinkUp'` → green (with speed in the sublabel);
  `LinkUp` is `LinkDown` / `NoLink` → dim gray; unknown → gray.
- **Native tooltips.** Each LED dot has a `title=` attribute with the
  full "Adapter: Port N — state (MACs)" string — hover for detail
  without needing JS.
- **Compact legend** beneath the dot row.

### Volumes / RAID card

Logical volumes from `/Systems/1/Storage/<n>/Volumes`.

- **Added.** Walk of `/Systems/1/Storage` controllers, fetching each
  controller's Volumes collection.
- **Captures per volume.** `RAIDType` (`RAID0/1/5/6/10/50/60`),
  `CapacityBytes` (converted to GB decimal), `Status.Health/State`,
  `Links.Drives[].size()` as stripe width, in-progress
  `Operations[0].{OperationName, PercentComplete}` (for rebuilds /
  initializations / expansions), encryption flag from base `Encrypted`
  or `Oem.Hpe.LogicalDriveEncryption`, write/read cache policy,
  `Oem.Hpe.BootVolume` (star-marked in UI).
- **Skipped entirely** on systems without a RAID controller (Gen11
  MicroServer without an MR/SR/NS204 card) — `/Systems/1/Storage`
  returns zero members, the `result.volumes` list is empty, the card
  doesn't render. Same graceful-empty pattern as the Drives card.
- **Added.** `humanizeVolumeOp()` helper turns `ChangeRAIDType` →
  `Change RAID Type`, `Rebuild` stays `Rebuild`, etc.

### Diagnostics

- **Added.** `powerTrend`, `networkAdapters`, `volumes` rows summarizing
  source + sample counts. Lets you see at a glance whether the new
  collectors actually got data or hit empty collections.
- **Bumped.** Diagnostics heading to `v0.1.34`.

### Notes

- All four cards follow the v0.1.33 partial-data discipline: each read
  block is its own try-catch in `RedfishClient.collectStatus()`, and
  on exception the block's result field is initialized to an empty
  list / map so the renderer's `if (data)` check correctly skips the
  card. A failure in one new block can't blank the others.
- All sort closures are null-safe per the v0.1.33 discipline
  (`(it.name ?: '') as String`, `((it.c ?: 0) as Integer)`).
- Caught and fixed a Groovy precedence bug in the speedMbps coercion
  during development: `?:` (Elvis) binds tighter than `? :` (ternary),
  so `(a ?: b ?: c ? d : null)` evaluates as `((a ?: b ?: c) ? d : null)`,
  not the intended `a ?? b ?? (c ? d : null)`. Rewrote as an explicit
  if-chain.
- The sparkline is pure inline SVG, so it's CSP-safe regardless of the
  appliance's nonce situation — no script needed.

## 0.1.33 — Regression fix + richer drive data from iLO 6 1.77 spec

This release fixes the v0.1.32 regression where most cards disappeared, and
extracts the much richer drive data that HPE's iLO 6 1.77 storage spec
exposes (drive temperature, lifetime hours, location, form factor, etc.).

### Regression fix (critical)

- **Fixed.** v0.1.32 silently lost the Power & Cooling, Network, DIMMs,
  Drives, Cooling Zones, Firmware Inventory, Active iLO Sessions, and
  Recent Events cards. Root cause: the temperatures block at line 180-202
  of `RedfishClient.collectStatus()` was **not wrapped in try-catch**,
  and the `.sort { (it.name as String) }` call threw `NullPointerException`
  when any temperature sensor returned a null `Name`. The throw escaped
  past every subsequent block (Chassis, Power, Network, EthernetInterfaces,
  Memory, Drives, FirmwareInventory, Sessions), leaving the result map
  populated only with the early system reads. The outer catch set
  `result.error` but **kept `result.success = true`** (set early on line 110),
  so the tab still rendered, just with most data missing.
- **Fixed.** Wrapped the thermal block in try-catch. Sort closures now use
  `(it.name ?: '') as String` so a null name doesn't break sorting, and
  `((it.c ?: 0) as Integer)` for temperature sorting.
- **Added.** Wrapped the `Oem.Hpe.AggregateHealthStatus` /
  `DeviceDiscoveryComplete` reads (added in v0.1.32) in try-catch as well —
  the same hazard applied if the OEM payload was malformed.
- **Added.** `partialError` row in Diagnostics that surfaces the outer
  catch's exception class + message when partial-failure recovery kicks
  in. If a block fails silently in future, this row tells you exactly
  which exception was swallowed.

### Drive data enrichment (iLO 6 1.77 spec)

Reading the iLO 6 1.77 Storage resource definitions, the Drive schema
exposes a lot more data than we were extracting. New `buildDriveEntry()`
now also captures (when iLO provides them):

- **`Oem.Hpe.CurrentTemperatureCelsius`** — per-drive temperature
- **`Oem.Hpe.PowerOnHours`** — lifetime hours, humanized to years/days
- **`Oem.Hpe.NVMeId`** — full NVMe identifier string
- **`Oem.Hpe.DriveStatus.Health`** + **`Oem.Hpe.TemperatureStatus.Health`** —
  HPE OEM status that's sometimes more accurate than base `Status.Health`
  (which is a roll-up)
- **`Oem.Hpe.HealthUpdated`** — `Boot` or `Dynamic`, tells us if drive
  health properties are updated at runtime or only at POST
- **`Location[].Info`** — physical location string ("Box 1 Bay 3",
  "Bay 5", "Slot 21" for NVMe AICs)
- **`PhysicalLocation.PartLocation.ServiceLabel`** — DMTF-standard location
  fallback
- **`DriveFormFactor`** — `M2_2280`, `Drive2_5`, `EDSFF_E3_Short`, etc.,
  humanized for display
- **`EncryptionStatus`** — `Unencrypted` / `Unlocked` / `Locked` / `Foreign`
- **`PredictedMediaLifeLeftPercent`** — wear leveling status (color-coded
  red below 10%, yellow below 25%)
- **`HotspareType`** — `None` / `Global` / `Chassis` / `Dedicated`

### Drives card layout change

The card now shows 8 columns instead of 4: Model, Type (with form factor
for M.2/EDSFF/PCIe AIC drives), Location, Capacity, Temp, Life Left,
Power-On, Health. Color coding:

- **Temp**: yellow when iLO reports `TemperatureStatus.Health=Warning`,
  red when `Critical`.
- **Life Left**: red below 10%, yellow below 25%.
- **Health**: green OK / yellow Warning / red Critical, with `⚠ failure
  predicted` suffix when `FailurePredicted=true`.

### New display helpers

- `humanizeFormFactor()` — turns `M2_2280` → `M.2 2280`, `EDSFF_E3_Short` → `E3.S`, etc.
- `humanizeHours()` — `8760+` hours → `1.2 yr`; `720+` → `84d`; else hours.

## 0.1.32 — Drive diagnostics + Health drill-down + Cooling Zones + Firmware

This release focuses on visibility — both into more iLO data and into
*why* certain data isn't showing up.

- **Fixed.** Removed the `HpeSmartStorage` fallback path entirely.
  Per HPE's iLO 6 adaptation guide, the legacy
  `/Systems/1/SmartStorage/ArrayControllers` URI was **removed in
  iLO 6** — calling it on Gen11 hardware was always going to return 404.
- **Added.** Two new drive-enumeration fallback paths to try harder on
  hosts without a Smart Array controller (e.g. MicroServer Gen11):
  - `/redfish/v1/Chassis/1/Drives` (direct chassis Drives collection)
  - Walk of the full `/redfish/v1/Chassis` collection — RDE storage
    devices register their own chassis IDs (e.g. `/Chassis/DE040000`),
    so checking each one's `Drives` collection catches drives the
    `/Systems/1`-based walk misses.
- **Added.** Drive-search diagnostic in the Diagnostics block. Shows
  the count returned at each step of the lookup chain
  (`sysStorageMembers`, `simpleStorageMembers`, `chassis1DrivesMembers`,
  `chassisCollectionMembers`, etc.) so you can see exactly where the
  enumeration is stopping. Surfaced as `drivesDiag` row.
- **Added.** `deviceDiscovery` row in Diagnostics — reads HPE's
  `Oem.Hpe.DeviceDiscoveryComplete.DeviceDiscovery` from `/Systems/1`.
  When this isn't `vMainDeviceDiscoveryComplete`, iLO is still probing
  attached hardware and drives/storage may legitimately not appear yet.
- **Added.** `amsStatus` row in Diagnostics — shows whether HPE's
  Agentless Management Service is detected on the host. AMS is
  **required** to enumerate direct-attached SATA drives via the
  chipset (no Smart Array). When `amsStatus` is empty or `Critical`,
  drives on chipset SATA controllers (typical for MicroServer Gen11)
  will not be visible to iLO Redfish.
- **Added.** **Health drill-down banner** — when overall Health is not
  `OK`, a red-bordered banner appears above the System card showing
  *which* subsystems are reporting the issue. Driven by HPE's
  `Oem.Hpe.AggregateHealthStatus` (Fans, Memory, Network, PowerSupplies,
  PSU Redundancy, Processors, Storage, Temperatures, Smart Storage
  Battery). Hidden when overall Health is OK.
- **Added.** **Cooling Zones** collapsible section listing all
  temperature sensors iLO knows about — not just the hottest CPU and
  ambient. Includes physical context (CPU, Memory, Intake, Exhaust,
  PowerSupply, etc.), reading, warning threshold, and critical
  threshold for each sensor. Reading is colored yellow when ≥ warn
  threshold and red when ≥ crit threshold.
- **Added.** **Firmware Inventory** collapsible section listing
  every firmware component iLO reports (System BIOS, iLO, network
  adapters, storage controllers, etc.) with version and whether it
  can be updated. Reads from `/redfish/v1/UpdateService/FirmwareInventory`.
- **Improved.** Power Draw now includes min/max from
  `PowerMetrics.MinConsumedWatts`/`MaxConsumedWatts` when present —
  e.g. `42 W · avg 38 W` with a small "range 32–55 W over last 24 min"
  line below.

## 0.1.31 — Argus rename + drive enumeration fallbacks + LED color match + power "Not reported"

- **Renamed (external).** Repo and JAR artifact name changed from
  `morpheus-ilo-console` to **`morpheus-argus`** — Argos Panoptes, the
  100-eyed all-seeing Greek watchman, fits a plugin that watches servers
  via iLO. `settings.gradle`'s `rootProject.name` now produces
  `morpheus-argus-0.1.31-all.jar`. The internal plugin code stays
  `morpheus-ilo-console` and the Plugin/ServerTabProvider class names
  are unchanged, so Morpheus identifies it as the same plugin during
  upgrades — no uninstall required. All user-facing surfaces (tab title,
  display name in admin list, header `<h2>`) stay **"iLO"**.
- **Fixed.** Power Draw showed a misleading `0 W` on hardware that
  doesn't track power consumption (e.g. iLO 6 on MicroServer Gen11,
  where both `PowerConsumedWatts` and `PowerMetrics.AverageConsumedWatts`
  come back zero/null). Now displays a muted **"Not reported"** when
  both fields are empty. When either field has a positive value, shows
  it normally (instant, or instant + avg if they differ).
- **Fixed.** **Indicator LED (UID) color** now matches the real LED on
  the server's front panel — blue when `Lit` or `Blinking`, grey when
  `Off`. Previously displayed yellow/warning when lit, which didn't
  match what an operator sees physically in the rack. New `info` (blue,
  `#3b9eff`) class added to the `statusItem()` palette to support this.
- **Added.** Two more drive-enumeration fallback paths for ProLiant
  Gen11 hosts without a Smart Array RAID controller (e.g. MicroServer
  Gen11), where the standard `/Systems/1/Storage` returns empty
  Members. New order:
  1. `/Systems/1/Storage/<n>/Drives/<n>` — modern Redfish (Gen10+ with controller)
  2. `/Systems/1/SimpleStorage/<n>/Devices/<n>` — legacy fallback
  3. `/Systems/1/SmartStorage/ArrayControllers/<n>/PhysicalDrives/<n>`
     (HPE OEM) — Smart Array, alternate enumeration
  4. `/Chassis/1.Links.Drives` (canonical Redfish chassis-level) —
     last resort for enclosures without a system-level controller
- **Refactored.** New private `buildDriveEntry()` helper in
  `RedfishClient` normalizes the disparate Redfish vs HPE-OEM drive
  shapes (`CapacityBytes` vs `CapacityGB` vs `CapacityMiB`; `Protocol`
  vs `InterfaceType`) into one consistent Map for the tab provider.

## 0.1.30 — Button tooltips + Boot Progress color fix

- **Added.** Hover tooltips (`title` attribute) on all three header
  buttons:
  - **Show credentials** — "Reveals the configured iLO username and
    password for manual copy/paste. Pure HTML/CSS — nothing transmitted."
  - **▶ Launch Console** — "Auto-logs into iLO with the stored
    credentials, then opens the IRC console pre-authenticated. One click
    does both steps."
  - **→ Open Console** — "Opens the IRC console using the existing iLO
    session cookie set by Launch Console. Use after the popup has
    authenticated, or to re-open the console in the same window."
  - These use the native HTML `title` attribute, so they work in every
    browser without any JS, and respect each browser's tooltip styling.
- **Added.** README has a new "Buttons at a glance" subsection at the
  top of "Using the tab" — three-row table explaining what each button
  does and when to use it. Matches the tooltip text.
- **Fixed.** Boot Progress in the System card showed `OSBootStarted` as
  yellow ("warn") even though that's a normal, healthy state during a
  successful boot. The class-mapping list now treats `OSBootStarted`,
  `OSRunning`, `SystemHardwareInitializationComplete`, and
  `PrimaryProcessorInitializationStarted` all as green ("ok"). Other
  values (e.g., `SetupEntered`) stay yellow.

## 0.1.29 — H2 case fix + more read-only data + power-control documented

- **Fixed.** The tab header rendered as "ILO" (all caps) because
  Morpheus's parent stylesheet applies `text-transform: uppercase` to
  `<h2>` elements on host detail pages. Added `text-transform: none` to
  the inline style on both h2 occurrences so the literal source
  ("iLO") renders as written.
- **Added.** **TPM** in the System card — Module type (TPM2_0 / TPM1_2)
  with an Enabled/Disabled pill.
- **Added.** **Boot Progress** in the System card — humanized form of
  Redfish's `BootProgress.LastState` ("OS Running",
  "System Hardware Initialization Complete", etc.). Green pill when in
  a normal running state, yellow otherwise.
- **Added.** **Boot Override** in the System card — when
  `Boot.BootSourceOverrideTarget` is set to anything other than "None"
  (i.e., next-boot will be redirected to PXE/Hdd/Cd/etc.).
- **Added.** **Hostname** in the System card — `Systems/1.HostName`.
- **Added.** **iLO Date/Time** in the Network card — iLO's clock with
  its local offset.
- **Added.** **iLO License Type** in the Network card — from
  `Managers/1.Oem.Hpe.License.LicenseType` ("iLO Advanced", "iLO
  Standard", etc.).
- **Added.** **DIMMs** collapsible section — per-slot detail (slot,
  capacity, speed, type, manufacturer, part number). Filters out empty
  slots so a 16-slot motherboard with 4 DIMMs populated shows just the
  4 populated ones.
- **Added.** **Active iLO Sessions** collapsible section — other users
  currently logged into this iLO (their name, source IP, session type).
  Useful for "is the console already in use?" before launching IRC.
  Skips the session our own status pull created. Reads from
  `/redfish/v1/SessionService/Sessions`.
- **Documented.** Power control intentionally not in this plugin —
  the IRC console exposes power buttons, and they require the iLO
  user's `reset_priv:1` privilege anyway. README now has a "Power
  control" section explaining this trade-off.

## 0.1.28 — Rendering fixes + Drives, Indicator LED, iLO network

- **Fixed.** `&deg;`, `&middot;`, etc. inside `statusItem()` values were
  being HTML-escaped (`&` → `&amp;`) and rendering as the literal text
  `&DEG;` / `&MIDDOT;` in the status pills. Switched to Unicode characters
  (`°`, `·`) directly in the source — they don't need escaping and render
  correctly through the existing `escapeHtml()` pipeline.
- **Fixed.** Power draw showed `0 W` on iLO 6 / MicroServer Gen11 even
  though the server was running at non-trivial load. The firmware doesn't
  update `PowerConsumedWatts` frequently on lower-tier ProLiant, but does
  track `PowerMetrics.AverageConsumedWatts`. The Power & Cooling card now
  prefers the average if the current reading is zero, and shows both
  when they differ (e.g. `0 W · avg 38 W`).
- **Added.** **Drives** card — pulls from `/Systems/1/Storage` (iLO 6
  modern path) with fallback to `/Systems/1/SimpleStorage` (legacy).
  Shows model, type (SSD/HDD + protocol), capacity in GB, and health.
  When `FailurePredicted: true` is set, shows a ⚠ warning next to the
  health status.
- **Added.** **Indicator LED** in the System card — color-coded pill
  showing current state (`Off`/`Lit`/`Blinking`). Display only for now;
  a toggle button is a future power-action item.
- **Added.** **iLO network info** in the Network card — iLO's own MAC,
  IP, and hostname/FQDN (from `/Managers/1/EthernetInterfaces`). Useful
  for confirming the iLO IP in the host's `ilo-host:` label is the right
  one and seeing the iLO's DNS name.
- **Improved.** Recent Events fetch — some iLO 6 firmware doesn't honor
  the `?$top=5` OData filter on `/LogServices/IML/Entries`, returning
  empty. The fetch now tries with the filter first, falls back to the
  unfiltered endpoint and takes the last 5 entries by reverse-chronological
  order if needed.

## 0.1.27 — Rename, expanded status, readonly mode, button styling fix

- **Renamed.** The tab and plugin display name dropped the "Console" suffix
  and now read simply **iLO**. The internal plugin code stays
  `morpheus-ilo-console` for backwards compatibility (so reinstalls behave).
- **Button styling fix.** The Launch Console `<button>` and Open Console
  `<a>` now render pixel-identically. The old CSS missed `appearance: none`
  on the `<button>` element, so the user agent's native button styling
  (slightly rounded corners, different font metrics, different padding)
  was overriding our rules. Unified rule with `appearance: none`,
  `font: inherit`, explicit `border`, `border-radius`, `line-height`, and
  `box-sizing: border-box` makes both render identical regardless of
  underlying element type.
- **Expanded status display.** Three new status cards alongside the
  existing System card:
  - **Power & Cooling** — current power draw / capacity, PSU count and
    redundancy state, hottest CPU temperature, ambient temperature, fan
    summary (count + average %).
  - **Network** — host MAC, primary IPv4 address, link status with
    speed.
  - **Recent Events** (collapsible) — last 5 entries from iLO's
    Integrated Management Log (IML), color-coded by severity.
  - Each card is conditional on the underlying Redfish endpoint
    responding; missing data omits the card rather than failing the whole
    tab. New endpoints called from `RedfishClient.collectStatus()`:
    `GET /Chassis/1/Power`, `GET /Chassis/1`,
    `GET /Systems/1/EthernetInterfaces` + members, and
    `GET /Managers/1/LogServices/IML/Entries?$top=5`.
- **Read-only mode.** New label `ilo-readonly:true` on a host. When set:
  - The credentials panel is **not** rendered in the DOM at all (so a
    dev-tools peek shows nothing sensitive).
  - The Launch Console form and Open Console link are suppressed.
  - The Show credentials toggle is suppressed.
  - A small yellow "Read-only" pill appears next to the iLO title.
  - The status cards still render normally. This is configuration, not
    authorization — anyone who can edit labels can flip it back — but it
    solves the "I don't want this host's password sitting in the DOM"
    problem for hosts where you set the label.
- **Diagnostics block** now also shows the `readonly` state alongside
  the existing `cspNonce` row.

## 0.1.26 — Reflection-based nonce reader (compile fix for 0.1.25)

- **Fixed.** 0.1.25's `getCspNonce()` used static imports for
  `org.springframework.web.context.request.RequestContextHolder` and
  `ServletRequestAttributes`, which broke the build —
  `morpheus-plugin-api:1.3.1` doesn't expose `spring-web` as a transitive
  compile-time dependency, even though Spring is present at runtime
  inside the Morpheus webapp.
- **Approach.** Switched to `Class.forName("…RequestContextHolder")` +
  Groovy dynamic dispatch (`attrs.getRequest()`, `req.getAttribute(…)`),
  so the Spring classes are looked up at runtime only. The plugin
  compiles against just morpheus-plugin-api, and the reflection
  resolves cleanly inside the appliance.
- **Behavior unchanged from 0.1.25.** Nonce present → one-click launch.
  Nonce missing (or Spring classes absent for any reason) → two-button
  manual flow. Diagnostics still surfaces `cspNonce: present/missing`.

## 0.1.25 — One-click launch via nonced inline JS

- **Added.** When Morpheus's per-request CSP nonce is readable (Spring
  `RequestContextHolder` → `js-nonce` request attribute), the tab emits a
  `<script nonce="…">` block that hooks Launch Console's click event,
  pre-opens the popup target under user activation, and schedules a
  `setTimeout` (1500 ms) to navigate that popup to `/irc.html` after the
  form-POST completes. One click → IRC console loaded and authenticated.
- **Preserved.** If the nonce is missing for any reason (Spring context
  unavailable, attribute renamed in a future Morpheus version, the script is
  CSP-blocked anyway), the 0.1.24 two-button manual flow is still present
  and works.
- **Surfaced.** The Diagnostics block shows `cspNonce: present (length=N, one-click ENABLED)`
  or `missing (one-click DISABLED, two-button manual flow active)` so the
  state is visible without grepping logs.

## 0.1.24 — text/plain JSON-injection for the form POST

- **Fixed.** 0.1.23 sent the form body as `application/x-www-form-urlencoded`
  (the HTML default), but iLO's `/json/login_session` is JSON-only — it
  responded `Malformed object, expected '{' at start of object.` HTML forms
  can't natively send `application/json` and CSP forbids inline `fetch()`
  calls. 0.1.24 abuses `enctype="text/plain"`: a single hidden `<input>`
  whose name carries the JSON open (`{"method":"login","user_login":"…","password":"…","x":"`)
  and whose value carries the close (`"}`). The browser joins them with
  `=`, producing a body like `{"method":"login","user_login":"…","password":"…","x":"="}`
  — valid JSON with the stray `=` safely inside an `"x"` key iLO ignores.
- **Added.** `jsonStringEscape()` helper for safe interpolation of the
  username/password into the JSON literal (escapes `\\`, `"`, `\b`, `\f`,
  `\n`, `\r`, `\t`, and `\\u00XX` for other control chars).

## 0.1.23 — Two-button form-POST launch, controller route abandoned

- **Removed.** `setPermissions(...)` declaration and the `controllers.add(...)`
  registration. After six versions of trying to make `/iloConsole/launch`
  return anything other than 403, the controller route approach was
  abandoned entirely. No plugin permission is declared, no controller
  route is registered.
- **Added.** A `<form method="POST" action="https://<ilo>/json/login_session" target="iloConsole_<id>">`
  in the tab header carries the credentials in hidden inputs and uses
  the named-popup target for top-level cross-origin navigation (which
  honors `SameSite=Lax`, iLO's default for its session cookie). A
  companion `<a href="https://<ilo>/irc.html" target="iloConsole_<id>">`
  navigates the same popup.
- **UX.** Two visible buttons in the tab header: **▶ Launch Console**
  (form submit) and **→ Open Console** (link). User clicks Launch (popup
  briefly shows iLO's JSON response), then Open Console (popup navigates
  to IRC). Two clicks.

## 0.1.22 — `setPermissions()` + list-form `Route.build`

- **Added.** `IloConsolePlugin.initialize()` declared a custom permission
  via `setPermissions([Permission.build('iLO Console', 'ilo-console',
  [Permission.AccessType.none, Permission.AccessType.full])])`.
- **Changed.** `IloConsoleController.getRoutes()` used the list form of
  `Route.build(path, method, [Permission.build("ilo-console", "full")])`
  (matching the pattern in `wabbas-morpheus/morpheus-plugins` and
  `martezr/morpheus-datadog-instance-tab-plugin`).
- **Result.** Still 403. The 403 didn't log to `/var/log/morpheus/morpheus-ui/current`,
  so we couldn't diagnose which layer was failing (permission not declared
  vs permission not granted to role vs route not registered vs CSRF).

## 0.1.21 — `Permission.build("admin", "full")`

- **Changed.** Per the Morpheus plugin docs' canonical example
  (`developer.morpheusdata.com`), switched the route permission to the
  bare `"admin"` code rather than the previously guessed `"admin-cm"`.
- **Result.** Still 403.

## 0.1.20 — Controller route abandoned (first time), CSS-only credentials reveal

- **Removed.** `controllers.add(...)` — gave up on the controller route
  after 0.1.16–0.1.19 all returned 403.
- **Changed.** Launch Console reverted to a plain `<a href="https://<ilo>/irc.html" target>`
  link. User pastes credentials into iLO's own login page.
- **Added.** **CSS-only Show credentials toggle** that bypassed the CSP
  inline-JS block. Two hidden checkbox inputs drive sibling-selector
  show/hide on the credentials panel and password mask. Adding
  `user-select: all` on each value makes single-click select the whole
  string, ready to Ctrl+C. Zero JavaScript in the tab.
- **Why no JS.** Morpheus's CSP is
  `script-src 'self' 'nonce-XXX' 'unsafe-inline' 'strict-dynamic' …`. Under
  `'strict-dynamic'`, both `'unsafe-inline'` and `'self'` are ignored —
  only scripts carrying the per-request nonce execute. Inline event
  handler attributes (`onclick="…"`) can never carry a nonce by browser
  spec, so every inline JS we'd written was being blocked.

## 0.1.19 — Plugin-controller permission attempt #4 (`compute-cm`)

- Tried `Permission.build("compute-cm", "full")`. Result: 403.

## 0.1.16 — 0.1.18 — Controller-served auto-login bridge (initial attempts)

- **Added.** `IloConsoleController` with `/iloConsole/launch?serverId=N`
  route that resolved the server, loaded credentials, and served a
  complete HTML document with an auto-submitting form to
  `/json/login_session`, hidden iframe for the JSON response, and inline
  JS to navigate to `/irc.html` on iframe load.
- **Tried.** `Permission.build("admin-cm", "full")` in 0.1.16, then a
  list form, then variations.
- **Result.** All 403. No actionable diagnostic in the appliance log.

## 0.1.15 — Handlebars templating (abandoned)

- **Tried.** Render the tab via Handlebars at template path `hbs/serverTab`.
- **Found.** Template path collided with framework's own templates;
  namespaced to `hbs/iloConsoleServerTab` to work around. Then discovered
  that the model binding was empty when the template rendered on this
  appliance — variables passed in didn't reach the template scope.
- **Decided.** Fall back to inline HTML via `HTMLResponse.success(html.toString())`.
  Handlebars stays available in the plugin for any future templates that
  need it, but the tab itself doesn't use it.

## 0.1.7–0.1.14 — Render plumbing

- Worked through the Morpheus 9.0 / plugin-api 1.3.1 surface:
  `hasCustomRenderer()` must return a `Boolean` field, not an
  `@Override` method. Without it, `renderTemplate()` is bypassed and
  the framework's own placeholder renders instead. `this.renderer = new HandlebarsRenderer()`
  must be set in `initialize()` alongside the field.

## 0.1.0–0.1.6 — Initial ServerTabProvider scaffold

- Set up the project from `builtbyfood/morpheus-vm-folders` as reference.
- Got the Redfish status pull working: `POST /redfish/v1/SessionService/Sessions`
  with credential lookup via `services.accountCredential.listById([id])` +
  `loadCredentialConfig([id: credentialId, type: 1], [:])` (the simpler
  `accountCredential.get(Long)` API throws `GroovyCastException
  Single→Maybe` in plugin-api 1.3.1).
- Confirmed `try { … } finally { DELETE /SessionService/Sessions/<id> }`
  pattern from `ha-silo` is the right session lifecycle for the iLO 6
  firmware we're targeting.
