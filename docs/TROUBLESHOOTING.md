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

### Why this also happens during boot

If `deviceDiscovery` in Diagnostics shows anything other than
`vMainDeviceDiscoveryComplete`, iLO is still discovering hardware
(usually during/just after POST). A yellow info banner appears in the
tab in this case. Wait a minute and refresh.

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
