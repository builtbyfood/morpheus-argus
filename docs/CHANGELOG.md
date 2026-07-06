# Changelog

All notable changes to the `morpheus-ilo-console` plugin. The version numbers
trace the actual iteration history; lessons learned at each step are summarized
in the `Notes` rows because they explain why the next version exists.

## 0.1.49 — Tighten HPE-only gate: label opt-in no longer overrides vendor check

v0.1.48's `hasIloOptInLabel` path was too permissive. As shipped, an
`ilo-host:<ip>` label on ANY host — HPE, Dell iDRAC, Cisco CIMC,
Supermicro IPMI, anything — would show the iLO tab. That defeated
the point of the vendor gate: the plugin is specifically for HPE
iLO, and a broken iLO tab on a Dell PowerEdge is worse than no tab
at all.

### What changed

- **Fixed.** `matchesHpeIlo()` now enforces a strict two-gate
  check:
    1. Must have an HPE signal — vendor field contains an HPE
       variant OR model field contains a known HPE identifier.
       Non-HPE hardware never passes this gate regardless of
       labels.
    2. Given HPE is confirmed, either the model contains a known
       iLO-family token (auto-detect), OR the host has an
       `ilo-host:X` label (opt-in for HPE families we haven't
       listed yet). The label bypasses the family-token check but
       not the HPE check.
- **Updated.** README and TROUBLESHOOTING wording clarified. The
  label is described as "for an HPE host that isn't auto-detected"
  and calls out explicitly that "non-HPE hardware (Dell iDRAC,
  Cisco CIMC, Supermicro IPMI, etc.) never gets the iLO tab,
  regardless of what labels are attached." No wiggle room in the
  docs about that.

### Notes

- **A label alone should never bypass a category check.** The
  category here is "hardware family we intend to support" —
  specifically HPE iLO. The label's purpose is to help pinpoint
  *which* iLO within that category, not to override the category
  itself. Same rule applies to any capability gate: labels can
  specialize a category match, not substitute for one.
- **"Cluttering someone else's host detail page with a broken tab"
  is a real cost.** An empty iLO tab on a Dell iDRAC host would
  confuse operators, generate support tickets, and undermine trust
  in the plugin's intent. Absence of harm on the working case
  (label helps HPE families not yet in our token list) does not
  license harm on the broken case (label breaks non-HPE hosts).
  The strict two-gate check keeps both properties: helpful on HPE,
  invisible on everything else.
- **v0.1.48 shipped with this bug in the code and in the docs.**
  I described the label as an unconditional override in both the
  class-level Javadoc and the README, and implemented it that way.
  The right question ("does this show up on Dell too?") caught it
  before wider testing. Worth internalizing: any time an escape
  hatch is added to a filter, immediately ask "what's the failure
  mode when someone uses the escape hatch on the wrong side of the
  filter?"

## 0.1.48 — Broadened hardware recognition beyond ProLiant; documented iLO 6 v1.76 sessionkey behavior

Two additions from field feedback. First, the tab's `show()` gate was
matching only hosts whose Morpheus-reported model contained
`proliant`. iLO ships on many more HPE server families than ProLiant
alone — Synergy compute modules, Apollo HPC, BladeSystem, Edgeline,
Alletra 4000/5000/6000 compute, Cray XD, Superdome Flex/X nodes,
plus MicroServer variants that some Morpheus discovery paths report
without the ProLiant prefix. Users running these families were
seeing the plugin loaded but no tab.

Second, we now have a field-confirmed answer for the iLO 6 v1.76
sessionkey URL failure that surfaced during v0.1.47 testing: HPE
silently dropped support for `/irc.html?sessionkey=<X-Auth-Token>`
in that firmware revision, likely as a security hardening step
(URL-embedded credentials leak into browser history, referer
headers, and proxy logs). The plugin's existing cookie POST fallback
continues to work on this firmware — users just need to uncheck the
sessionkey URL setting. Documented that with a browser-devtools
signature so the next person hits diagnosis in seconds.

### What changed

- **Added.** `IloDetectionService.matchesHpeIlo()` replaces
  `matchesHpeProliant()`. The old method is retained as a
  `@Deprecated` alias so anything calling it externally still works.
  The new method matches any of nine model tokens (`proliant`,
  `synergy`, `apollo`, `bladesystem`, `edgeline`, `microserver`,
  `alletra`, `cray xd`, `superdome`) in addition to the existing
  HPE vendor check.

- **Added.** Explicit label-based opt-in. Any host with an
  `ilo-host:<ip>` label now passes the `show()` gate regardless of
  what Morpheus's vendor/model fingerprint reports. This covers
  future HPE families we haven't listed yet, hosts where Morpheus's
  hardware detection is missing (e.g. bare imports without an SMBIOS
  probe), and edge cases where the model string doesn't quite fit
  any token. Users opt in explicitly by attaching the label they'd
  attach anyway to point the plugin at their iLO — no extra
  configuration step.

- **Added.** New TROUBLESHOOTING section
  *"Launch Console opens iLO's login page instead of the console
  (iLO 6 v1.76+)"*. Walks through the browser-devtools signature
  (`X-Auth-Token: null` on requests after landing on `/irc.html`,
  iLO loading `/html/login_session` instead of the console) that
  identifies this failure mode versus a generic auth problem.
  Explains the fix: uncheck **Use Sessionkey URL Authentication** in
  plugin settings, clear browser cache, refresh. Notes that cookie
  POST continues to work on this firmware.

- **Improved.** README opens the tested-against list beyond
  ProLiant. New "Auto-detected HPE server families" section lists
  each family, what it corresponds to (rack, blade, dense compute,
  etc.), and calls out that the `ilo-host:` label handles anything
  outside the list. Install snippet updated to v0.1.48.

- **Improved.** Plugin description in the Morpheus admin UI now
  reads *"HPE iLO console and Redfish status panel for
  iLO-managed HPE servers (ProLiant, Synergy, Apollo, and more)"*
  instead of "for ProLiant hosts."

- **Improved.** TROUBLESHOOTING "iLO Console tab doesn't appear"
  section rewritten to match v0.1.48's actual gating logic — two
  paths (label opt-in wins; auto-detect via vendor + model token)
  rather than the older four-check list which described earlier
  behavior. Includes a workaround suggestion for HPE families we
  haven't listed yet (add `ilo-host:`, file an issue with the
  vendor/model strings).

### Notes

- **Auto-detect is a helpful default, not a security gate.** The
  plugin's actual power (reading iLO, launching console, PATCHing
  UID) only activates once the user attaches `ilo-cred:<id>` and
  `ilo-host:<ip>` labels; without those, an incorrectly-shown tab
  just renders an "unconfigured" state. That means the vendor/model
  match is best-effort — being too permissive is much less costly
  than being too restrictive. A user who has to add a label to see
  the tab has done more work than a user who sees an unconfigured
  tab.
- **Explicit label opt-in beats fingerprint matching for future-
  proofing.** Every time HPE adds a server family or renames a
  product line, our fingerprint list would need updating. The
  `ilo-host:` label sidesteps that entirely — the user telling us
  "this host has iLO at 192.168.x.x" is a more reliable signal
  than any hardware string HPE might rebrand. Fingerprinting stays
  as the zero-config default; labels are the escape hatch.
- **iLO 6 v1.76's sessionkey drop is a case where HPE's docs are
  silent but browser devtools tells the whole story.** No changelog
  entry, no deprecation notice — just a firmware upgrade after
  which URL-embedded sessionkeys stop working. The signature
  (`X-Auth-Token: null` on the follow-up XHR) is unambiguous once
  you know to look for it. That's the pattern to document loudly:
  when a black-box firmware change breaks a feature, the
  troubleshooting page should include the specific network-trace
  signature that identifies it, so the next person doesn't burn
  time on cleanup / credentials / cookies theories.

## 0.1.47 — Session cleanup fix: normalize path comparison so we don't delete our own session

v0.1.46 introduced `cleanupOwnStaleSessions()` to keep the iLO session
pool from saturating. Field testing on behemoth surfaced a bad
regression: the tab rendered with `SYSTEM: POWER=? · MEMORY=? GiB`,
every card empty, and diagnostics showing
`iloSessions: 0 (cleaned 1 stale on login)`, `powerTrend: current=null`,
every drives-counter zero. Cleanup had deleted our own current
session along with the stale ones, and every subsequent GET was
returning 401 from an invalidated token.

Root cause was a path-vs-URL mismatch that never fires on happy-path
testing:

- `sessionLocation` comes from the `Location` header on `POST
  /Sessions`. On HPE iLO 6/7 firmware this is an **absolute URL**
  like `https://192.168.0.248/redfish/v1/SessionService/Sessions/xxx`.
- `Members[].@odata.id` from `/SessionService/Sessions` is a
  **relative path** like `/redfish/v1/SessionService/Sessions/xxx`.

v0.1.46's `if (sPath == sessionLocation) continue` compared the two
strings directly. They never matched, cleanup deleted every session
for our user, and the current render's session went with them.

The launchToken row still read `minted (length=32)` because
`acquireLaunchToken()` uses an independent RedfishClient instance
with its own login — it was untouched.

### What changed

- **Fixed.** `cleanupOwnStaleSessions()` now normalizes both sides to
  path form before comparing. New private helper
  `normalizeSessionPath(String loc)` uses `java.net.URI` to extract
  the path from an absolute URL, falls back to manual scheme parsing
  if URI construction throws, and strips any trailing slash so
  `/Sessions/xxx/` and `/Sessions/xxx` compare equal. Applied to
  both our own `sessionLocation` and each `Members[].@odata.id`
  before the equality check.

- **Added.** Belt-and-suspenders secondary check. A new helper
  `tailSegment(String pathOrUrl)` extracts the last path segment
  (the session id). We compare both the normalized paths AND the
  tail segments — so even if some future firmware quirk breaks the
  path normalization, a session whose id matches ours is still
  never touched.

- **Added.** Safety cap. Cleanup will never delete more than
  `(memberCount - 1)` sessions in one pass — at least one session
  always survives, no matter what the filters do. Logged as
  `cleanupOwnStaleSessions hit safety cap of N deletes; stopping`
  if it fires.

- **Added.** Bail-out guard. If `normalizeSessionPath(sessionLocation)`
  returns null or empty (should be impossible after a successful
  login, but be paranoid), cleanup is skipped entirely with a
  `WARN`-level log rather than proceeding into a wildcard delete.

### Notes

- **HPE's `Location` header format differs between firmware
  releases**. Some return absolute URLs; some return paths. Any
  Redfish client code that compares Location-derived strings against
  `@odata.id` values needs a normalizer, not a `==`. Same rule as
  the property-name-drift lessons (v0.1.42 `getSettings` vs
  `getPluginConfig`, v0.1.45 `PowerDetail` vs `Samples`, v0.1.46
  `Integer` where a `List` was expected): vendor responses evolve
  faster than call sites assuming a specific shape.
- **A "destructive" operation needs a belt-AND-suspenders invariant,
  not a single filter**. v0.1.46's filter was correct in intent
  (skip the current session) but relied on one string comparison
  being right. v0.1.47 layers three independent checks — normalized
  path match, id-tail match, and a hard cap on delete count. Even
  if two of the three break in a future firmware quirk, no
  wildcard-delete is possible. Cost: a dozen extra lines. Payoff:
  the class of "we deleted our own credential and the tab shows
  nothing" bugs is now closed regardless of what iLO does next.
- **Every visible failure is easier to trace than a silent one.**
  The v0.1.46 regression showed up as `iloSessions: 0 (cleaned 1
  stale on login)` — the "cleaned 1" made the cause obvious in one
  screenshot. If we hadn't been logging the cleaned-count, the
  empty-tab symptom would have looked like a network or credential
  failure and taken much longer to diagnose. Same forensics-first
  pattern applies to every destructive operation: report what you
  did, even when it "worked".

## 0.1.46 — Stale session cleanup on login; power probe crash fix

Two things. First, field testing on behemoth (DL320 Gen11, iLO 6
v1.76) showed v0.1.45's `readMeter` closure throwing
`java.lang.Integer.isEmpty()` on both probe endpoints — one of the two
Redfish properties resolved to a non-List type (likely an integer
count companion) and the downstream `if (pd && sm)` truthy check
invoked `.isEmpty()` on it. Fully defensive `instanceof` checks
close the door on that whole class of type-mismatch crash.

Second, per the v0.1.41 documentation, each tab render creates two
iLO sessions — one for `collectStatus` (properly torn down at
end-of-render) and one via `acquireLaunchToken` (intentionally leaks
so the sessionkey stays valid for the user's console click). The
leaked sessions accumulate until iLO's ~30-minute idle reap kicks
in. Combined with human operator activity on the same iLO account,
the ~13-session pool saturates in an afternoon, and PATCH writes
start failing with misleading `PropertyNotWritableOrUnknown` errors
that look like RBAC problems but aren't. v0.1.46 clears our own
stale sessions at login time so the pool never fills up from the
plugin's side.

### What changed

- **Added.** `RedfishClient.cleanupOwnStaleSessions(String username)`
  method. On collectStatus login, lists all iLO sessions, filters to
  those whose `UserName` matches the credential we just authenticated
  as, excludes the session we just created (matched by `@odata.id`
  against `sessionLocation`), and issues `DELETE` on each remaining
  match. Best-effort per-session — a single failed DELETE doesn't
  abort cleanup of the rest, iLO's idle-reap will catch what we
  couldn't. Returns the number of sessions deleted, which
  collectStatus stashes on `result.sessionsCleanedUp` for
  diagnostics. Critically, other users' sessions (iLO web UI,
  administrators, other integrations) are NEVER touched — the filter
  matches on `UserName == the credential we authenticated as`, so an
  operator with a live IRC console up won't get bumped by a Morpheus
  tab render.

- **Fixed.** `readMeter` closure regression from v0.1.45 that threw
  `java.lang.Integer.isEmpty()`. v0.1.46 replaces Groovy truthy-check
  patterns (`if (pd && sm)`, `pd ?: sm`) with explicit `instanceof
  List` guards and `.isEmpty()` calls on values we've confirmed are
  Lists. Same for the inner sample-entry loop: `entry instanceof
  Map` before any property access. The concrete shape of the iLO
  response body no longer matters — anything we can't cleanly walk
  as List-of-Maps is treated as absent and skipped.

- **Added.** Diagnostics visibility on the meterProbes row now
  includes the top-level keys of the response body when the probe
  found 0 entries. Renders as `keys=[AveragePowerReading,
  PowerDetail, ...]` right after the count. When a firmware
  version returns a different shape than PowerDetail / Samples, this
  row shows what we DID get in one render without a redeploy. Same
  forensics-first pattern that unblocked settings (v0.1.40) and UID
  (v0.1.39).

- **Improved.** Diagnostics `iloSessions` row appends `(cleaned N
  stale on login)` when v0.1.46 cleanup actually deleted sessions.
  Silent when no cleanup happened, so unchanged wording on typical
  renders. Makes the fix visible without needing to compare
  side-by-side.

### Notes

- **Session leaks by design need explicit cleanup, not "iLO will
  reap them".** The launch-token session leak was intentional in
  v0.1.36 (sessionkey URL launch requires the session to outlive the
  render), and iLO's ~30-minute idle reap covers the case where the
  user never clicks Launch. But that reap is far too slow to keep up
  with active use — multiple tab refreshes in a short window can
  stack sessions faster than iLO clears them. The right pattern is
  explicit best-effort cleanup on every fresh login: we already know
  our own username, we can list sessions, we can filter to ones we
  own, we can DELETE the ones that aren't the current session.
  Nothing tricky and no risk to other users. Cost: two extra GETs
  and a handful of DELETEs at the start of each render.
- **Never touch other users' sessions.** The temptation when
  cleaning up a shared iLO would be to kill every stale session
  regardless of owner — but Morpheus is one integration among many,
  and killing a live IRC console for an operator who happens to
  authenticate as a different iLO user would be a much worse
  outcome than a saturated pool. The filter has to be strictly on
  `UserName == the credential we authenticated as`, and it has to be
  visible in the code so a future maintainer can't accidentally
  loosen it.
- **Groovy truthy semantics on unknown types are dangerous.** The
  v0.1.45 `if (pd && sm)` crash happened because `pm.PowerDetail`
  resolved to something other than a List on this firmware, and
  Groovy's `.asBoolean()` fallback path called `.isEmpty()` on it —
  which Integer doesn't have. Defensive patterns matter more than
  concise code when parsing untrusted vendor responses:
  `instanceof` and explicit `.isEmpty()` on values we've confirmed
  are Collections. Same rule applies to Redfish property access
  broadly — vendor responses evolve across firmware revisions
  without notice, and a "harmless" `if (thing)` becomes a runtime
  crash the moment a scalar sneaks into what used to be an array.

## 0.1.45 — Power Trend sparkline reads PowerDetail (iLO 6/7 property name)

v0.1.44's FastPowerMeter fallback didn't fix the empty sparkline on
DL320 Gen11 with iLO 6 v1.76. The field diagnostic (`powerTrend:
source=EnvironmentMetrics, history=0 samples`) showed both probes
were hitting the endpoints and returning zero samples. Turns out the
plugin was reading the wrong property inside the response body:

- iLO 5 uses `HpePowerMeter.Samples[]` with `Time`, `Average`, `Maximum`.
- iLO 6 / iLO 7 renamed the array to `HpePowerMeter.PowerDetail[]`
  with `Time`, `Average`, `Peak` (replacement for `Maximum`),
  `Minimum` (new), plus per-component fields `CpuWatts`,
  `DimmWatts`, `GpuWatts`, `SharedFanWatts`.

Reference: iLO 7 changelog `HpePowerMeter.v2_1_0` → `v2_2_0` adds
`PowerDetail[{item}].SharedFanWatts`. Confirmed against a real-world
PowerShell script from HPE's community forum that pulls `powerdetail`
from `/redfish/v1/chassis/1/power/fastpowermeter`.

The plugin was reading `pm.Samples` on iLO 6, where the field is
`pm.PowerDetail`. Groovy's map access returned null, closure returned
null, both probes recorded zero samples, sparkline stayed blank.

### What changed

- **Fixed.** `readMeter` closure now reads both property shapes:
  `PowerDetail` (iLO 6/7, preferred) with `Samples` (iLO 5) as
  fallback. Per-entry field mapping: `Average` unchanged, `Peak` or
  `Maximum` → trend max, `Minimum` (PowerDetail only) → trend min.
  When PowerDetail carries a per-entry `Minimum` it takes precedence
  over the derived min-of-averages we've been computing on iLO 5.
- **Improved.** New `meterProbes` Diagnostics row shows each probe's
  URL and entry count (or -1 for HTTP null). Format:
  `/Power/PowerMeter: 0 entries · /Power/FastPowerMeter: 60 entries`.
  Row only appears when at least one probe was attempted so it stays
  out of the way on hardware where the first-hit probe succeeds. The
  short-path rewrite drops the redundant `/redfish/v1/Chassis/1`
  prefix so the row fits on a single line.
- **Improved.** Empty-state message on the Power Trend card is now
  driven by whether ANY meterProbe returned entries — if all probes
  returned 0 or -1, the card explicitly says which URLs were tried,
  which turns future variance into a one-render diagnostic.

### Notes

- **HPE renamed a Redfish property between iLO 5 and iLO 6.** Not
  deprecated, not aliased — the old name (`Samples`) is just gone on
  iLO 6/7, replaced by a differently-named array (`PowerDetail`)
  that carries additional per-component fields. Plugin code that
  worked on iLO 5 silently returns no data on iLO 6 without any HTTP
  error. Always read the target firmware's actual resource
  definition, not just an earlier version's — the resource map lists
  the type name but not the field names inside, and the field names
  are where the version drift lives.
- **This is the same shape as v0.1.42's `getSettings` fix.** In both
  cases the previous release "found the right endpoint" but "read
  the wrong property" — different Morpheus / iLO version conventions
  than the plugin was written against. The forensics-first pattern
  (surface what the probe returned, not just what the caller decided)
  caught both in one round after adding the diagnostic. The
  `meterProbes` row is the direct analog of the `settingsRaw` row.
- **HPE community forums are a valid source of ground truth for
  Redfish property names.** The PowerShell snippet using
  `.powerdetail` in a working script confirmed the property name
  faster than parsing HPE's schema docs. When HPE's own docs are
  ambiguous about which properties are populated on which firmware,
  code samples from actual field use are worth searching for.

## 0.1.44 — Power Trend sparkline reads FastPowerMeter as fallback

Field report from a DL320 Gen11 with iLO 6 v1.76: the Power Trend
card showed "No history available" even though iLO's own web UI had
a fully-populated 20-Minute History Graph with CPU/Fan/GPU/DIMM
breakdowns. HPE's iLO 6 v1.68+ resource map documents TWO peer
endpoints returning the same `HpePowerMeter` type:

- `/redfish/v1/Chassis/1/Power/PowerMeter` — longer-term history
  (24-hour on firmware that populates it)
- `/redfish/v1/Chassis/1/Power/FastPowerMeter` — 20-minute
  high-resolution samples (what the iLO web UI's "20-Minute History
  Graph" reads from)

The plugin's Power Trend card only read the first. On firmware /
hardware combinations that only populate FastPowerMeter — which
includes DL320 Gen11 on iLO 6 v1.76 — the sparkline stayed empty
even though the data existed.

### What changed

- **Fixed.** Power Trend sparkline reads `/Power/FastPowerMeter` as
  a fallback when `/Power/PowerMeter` doesn't return samples. Both
  endpoints emit the same `HpePowerMeter` type per HPE's own resource
  map, so the parsing code is identical — only the URL differs. The
  `trend.source` field tags the diagnostics with which endpoint the
  data came from (`PowerMeter` vs `FastPowerMeter` vs
  `EnvironmentMetrics+PowerMeter` combos), so it's obvious from the
  tab which source populated the card. Empty-state message reworded
  to mention both endpoints so future 0-sample cases don't send
  people looking at the wrong URL.

### Notes

- **HPE ships peer Redfish endpoints returning the same OEM type with
  different retention windows.** The `HpePowerMeter` type is
  documented once but instantiated at two URIs
  (`/Power/PowerMeter` and `/Power/FastPowerMeter`), and which one
  is populated depends on firmware/hardware tier. Always probe both
  when reading HPE OEM history data — the pattern generalizes to
  other paired endpoints in the resource map.
- **iLO web UI is a spec: if the value shows there, some Redfish
  endpoint returns it.** The DL320 Gen11 report followed the same
  pattern as the earlier UID debugging — the iLO web UI displayed
  the data, so the property/endpoint existed somewhere, we just
  weren't reading the right one. Whenever the plugin fails to read
  something that iLO's own UI shows, the first debugging step is to
  find the alternate endpoint in HPE's resource map rather than
  assume the value isn't available.

## 0.1.43 — Release prep: privilege docs corrected, README freshened, posting artifacts added

Polish-only release ahead of the GitHub push. Field testing on the same
iLO that originally returned 403 on UID PATCH (v0.1.38) now succeeds
without any extra iLO privilege beyond Login + Remote Console. The
v0.1.38 → v0.1.39 hypothesis that "Configure iLO Settings" privilege
was required appears to have been wrong — the original 403 was almost
certainly session-pressure (v0.1.41 documented the failure mode), and
the privilege change was coincident rather than causal. Correcting the
docs so future users don't grant write privileges they don't actually
need.

### What changed

- **Fixed (docs).** Removed the "Configure iLO Settings privilege
  required" claim from TROUBLESHOOTING.md and from the inline 403
  hint in `setIndicatorLed()`. Field testing on iLO 5 / iLO 6 / iLO 7
  with an account that has only Login + Remote Console privileges
  confirmed UID writes succeed via `LocationIndicatorActive` and
  `Oem.Hpe.IndicatorLED`. The new docs section emphasizes checking
  the `iloSessions` row first, then escalating iLO RBAC as a
  diagnostic only if session pressure isn't the cause.
- **Updated (docs).** README refreshed for the mature feature set —
  System card now mentions UID control buttons, the diagnostics row
  list includes plugin settings forensics and concurrent-session
  pressure, the install snippet references v0.1.43, and tested-
  against now lists MicroServer Gen10 Plus, MicroServer Gen11, and
  DL325 Gen12. Cooling Zones and Firmware Inventory rows added to
  the feature table (they shipped earlier but were missing from the
  table).
- **Added.** `docs/RELEASE_NOTES.md` — consolidated GitHub release
  page content covering v0.1.35 → v0.1.43 highlights, install
  instructions, compatibility matrix, and a "what's NOT in this
  release" section to set expectations on deferred work.
- **Added.** `docs/LINKEDIN_POST.md` — two variants of the
  announcement post (short ~150-word headline, long ~400-word deep
  dive), first-comment text, hashtag pool, and posting-strategy
  notes including image scrubbing requirements.

### Notes

- **Operators should grant privileges that the plugin actually
  requires, not privileges the plugin documentation guessed at.**
  v0.1.38 docs over-specified by claiming a write privilege that
  isn't actually needed for the modern write path (the OEM
  property). The correction matters because least-privilege is the
  right default for BMC accounts — granting "Configure iLO Settings"
  to a Morpheus-managed account opens up much more than just UID
  control. v0.1.43 docs say "Login + Remote Console works in our
  testing" and direct users to confirm via the diagnostics rows
  before escalating.
- **Session pressure was almost certainly the cause of the original
  v0.1.38 403.** Closing other console windows fixed the same PATCH
  call without any RBAC change. The pattern from this whole
  iteration sequence: when a failure looks like RBAC but isn't
  reproducible after a state reset (closed sessions, fresh tab
  render), it's almost certainly not RBAC. RBAC failures are
  deterministic; session-pressure failures aren't.

## 0.1.42 — Settings actually load (RxJava 3 blockingGet, not RxJava 2 toBlocking)

Fourth iteration on the same bug. v0.1.41 fixed the method name from
`getPluginConfig()` to `getSettings(this)` per the Morpheus docs but
called `.toBlocking().firstOrDefault(null)` on the return value — the
RxJava 2 idiom. Morpheus is running on RxJava 3, and `getSettings(this)`
returns a `Single<String>` which has `blockingGet()` instead of
`toBlocking()`. The v0.1.41 forensics row surfaced the exact
exception:

```
No signature of method:
io.reactivex.rxjava3.internal.operators.single.SingleJust.toBlocking()
is applicable for argument types: () values: []
Possible solutions: toString(), toString()
```

### What changed

- **Fixed.** `readArgusSettings()` uses `morpheus.getSettings(this).blockingGet()`,
  the RxJava 3 equivalent. Falls back to the doc-snippet `subscribe()`
  pattern if `blockingGet()` is missing for any reason (defensive
  against future RxJava version changes). Combined with v0.1.41's
  correct method name and v0.1.40's defensive `isOn()`, the settings
  page now actually changes runtime behavior. Save the page with a
  checkbox unchecked, refresh the iLO tab, and the corresponding
  `launchMode` / `launchWindowMode` / `launchAuthMethod` row in
  Diagnostics flips immediately.

### Notes

- **The forensics diagnostic is now the most important debugging
  feature in this plugin.** Three of the last four releases have been
  bug-fix iterations on the same area, and each one converged on the
  real bug in one round because the visible exception message named
  the actual problem. The lesson generalizes: a `try/catch` that
  swallows an exception should always surface that exception in a
  diagnostic row, even if the user-facing output doesn't show it. The
  cost is one HTML row and the upside is not paying for four
  iterations to find a one-line bug.
- **The Morpheus docs snippet uses `subscribe()` precisely because it
  works on any RxJava version.** The doc-snippet pattern is verbose
  but version-agnostic. `blockingGet()` (RxJava 3) and
  `toBlocking().value()` (RxJava 2) are both shorter but tied to a
  specific RxJava major version. Defensive plugin code should either
  use the docs snippet verbatim or do what v0.1.42 does — try the
  shorter idiom first, fall back to subscribe() on
  MissingMethodException. The shortcut isn't worth a release of churn
  for someone discovering the same incompatibility.
- **Plugin-API version is not RxJava version.** Morpheus's
  `morpheus-plugin-api` Maven artifact is at 1.3.1 — but that's the
  plugin contract version, not the runtime RxJava version. The
  runtime can swap RxJava 2 for RxJava 3 without bumping the plugin
  API version, because the plugin contract just specifies "returns
  some reactive Single<String>" without naming the package. Don't
  assume API version pins runtime library versions.

## 0.1.41 — Settings API actually called correctly; iLO session-limit failure mode documented

Two findings from v0.1.40 field testing. First, v0.1.40's settings-
forensics diagnostic surfaced the actual exception that had been
silently swallowed since v0.1.37: `No signature of method:
com.morpheus.plugin.MorpheusContextImplService.getPluginConfig() is
applicable for argument types: () values: []`. The method we'd been
calling for three releases (`morpheus.getPluginConfig()`) doesn't
exist on the Morpheus runtime context. The correct method per
Morpheus's official plugin docs is `morpheus.getSettings(plugin)`.

Second, field-confirmed that UID PATCH failures with
`iLO.2.37.PropertyNotWritableOrUnknown` on otherwise-functional iLO
firmware are a session-pressure symptom. Closing other open console
windows (which were holding iLO sessions) made the same PATCH start
working. Explains why the same plugin version flipped working/broken
between defender and scorcher across recent test cycles.

### What changed

- **Fixed.** Settings persistence actually works. v0.1.41 calls the
  correct Morpheus API: `morpheus.getSettings(this)` instead of the
  nonexistent `morpheus.getPluginConfig()`. The defensive isOn() from
  v0.1.40 is preserved as a safety net for cases where Morpheus
  drops unchecked CHECKBOX fields from the persisted JSON. Combined,
  toggling any of the three checkboxes and saving now actually
  changes the runtime behavior on the next tab render — the
  `launchMode`, `launchWindowMode`, and `launchAuthMethod`
  diagnostics rows flip in real time, providing a trivial way to
  verify the fix.

- **Documented.** iLO concurrent-session limit interaction with the
  plugin. iLO 6 typically allows up to 13 concurrent sessions across
  all clients (web UI, IRC console, REST API, sessionkey-based
  launches). When at or near the limit, the plugin's PATCH operations
  start returning `iLO.2.37.PropertyNotWritableOrUnknown` even
  though reads keep working — the symptom looks identical to the
  RBAC-misconfigured case from v0.1.38, but is actually a transient
  session-pressure failure. The fix is operational, not in code:
  close stale console windows, or wait for sessions to time out.
  New TROUBLESHOOTING section walks through the symptom and the
  diagnostic signals (the `iloSessions` row in Diagnostics tells you
  how many sessions are currently active).

### Notes

- **Always read the official plugin API docs to confirm method
  signatures.** v0.1.37 → v0.1.40 spent four releases iterating on a
  method that didn't exist. The actual API is documented in
  Morpheus's "Getting Started with Custom Instance Tabs" guide
  (docs.morpheusdata.com), and the snippet there is one of the
  shortest sections of the page — three lines of Groovy. Web search
  ahead of guess-and-check would have caught this at v0.1.37.
- **Diagnostic rows that surface the exception text are worth their
  weight in gold.** The v0.1.40 forensics diagnostic existed solely
  to confirm what Morpheus was persisting — but it also surfaced
  the never-before-seen MissingMethodException because the `try`
  block stored the error message in `parseError`. Without that row,
  v0.1.41 would still be guessing about a "Morpheus persistence
  bug" that doesn't exist. Lesson: catch blocks that silently
  swallow exceptions should always surface the exception message
  in a debug diagnostic somewhere, even if the rendered output
  doesn't show it by default.
- **iLO has a hard cap on concurrent sessions (typically 13 on iLO
  6). Writes start failing before reads do.** The plugin opens an
  iLO session on every tab render (to mint a sessionkey for console
  launch), and console launches via sessionkey URL open another
  short-lived session. Repeated tab loads + active console windows
  can stack 8-10 sessions on a busy operational workflow. PATCH
  operations seem to fail first when the session pool is saturated,
  returning misleading "property not writable" errors. Reads
  continue to succeed, which makes the failure look like a code/RBAC
  problem rather than a session-pressure problem. The
  `iloSessions` diagnostics row is the tell.

## 0.1.40 — HPE OEM UID property + settings-persistence forensics

Two follow-ups from v0.1.39 field testing. First, after the iLO account
gained "Configure iLO Settings" privilege, the UID PATCH started
returning `iLO.2.37.PropertyNotWritableOrUnknown` on all six v0.1.39
probe steps — both DMTF `IndicatorLED` and `LocationIndicatorActive`
are read-only on this firmware. HPE's iLO 6 changelog confirms they
moved the writable Lit/Blinking/Off property to `Oem.Hpe.IndicatorLED`
as an explicit fallback for clients that don't want to switch to the
boolean DMTF property. Second, every plugin-settings checkbox reads
as "on" regardless of UI state even after v0.1.38's JsonSlurper fix —
forensics diagnostics added so we can see exactly what Morpheus
persists, plus a defensive smarter `isOn()` that handles the most
likely cause (Morpheus omitting unchecked fields from the JSON blob).

### What changed

- **Improved.** UID PATCH probe chain extended from 6 → 8 steps with
  HPE's OEM property as the final fallback:
    7. `PATCH /Systems/1 {Oem: {Hpe: {IndicatorLED: <value>}}}`.
    8. `PATCH /Chassis/1 {Oem: {Hpe: {IndicatorLED: <value>}}}`.
  Per HPE's iLO 6 Redfish changelog: *"Added Oem.Hpe.IndicatorLED:
  ... This is a fallback added for clients that want to continue to
  use IndicatorLED."* On iLO 6 firmware where DMTF `IndicatorLED` is
  read-only and `LocationIndicatorActive` also rejects writes (the
  v0.1.39 field-report scenario), this OEM property is the writable
  one. Body shape is the nested-OEM form. The diagnostics row tags
  these attempts with ` OEM` so they're visually distinct from the
  DMTF attempts.

- **Improved.** UID badge READ now falls back to `Oem.Hpe.IndicatorLED`
  when both `IndicatorLED` and `LocationIndicatorActive` are
  null/Unknown. Full read order: DMTF `IndicatorLED` (Chassis then
  System) → `LocationIndicatorActive` (Chassis then System) →
  `Oem.Hpe.IndicatorLED` (Chassis then System). On firmware where
  only the OEM property is populated, the badge now shows the actual
  Lit/Blinking/Off state instead of an em-dash.

- **Improved.** UID 400 hint now distinguishes "all 8 attempts failed
  with PropertyNotWritable" (genuinely no writable UID property on
  this firmware) from the v0.1.39 "may have moved to an HPE OEM
  endpoint" hint (which is now what step 7/8 actually probes). The
  new message points users straight at filing a GitHub issue with
  firmware version, because the plugin has exhausted everywhere we
  know to look.

- **Improved.** Plugin-settings diagnostics. New rows in the
  Diagnostics block surface the raw JSON Morpheus persists in
  pluginConfig, plus what we parsed out of it:
    - `settingsParsedKeys` — comma-separated keys present in the
      parsed Map, plus a `hasKeys` boolean for first-install
      detection.
    - `settingsRaw` — each setting's raw value as Morpheus stored
      it, separately for each of the three checkboxes. `<null>` means
      the key is entirely absent.
    - `settingsJson` — the raw JSON blob from
      `morpheus.getPluginConfig()`, truncated to 400 chars.
    - `settingsParseError` — present only when JsonSlurper threw.
  Saving the Settings page with different checkbox combinations now
  shows immediately whether Morpheus is dropping unchecked fields
  from the JSON (the v0.1.40 defensive `isOn()` handles this) or
  persisting `defaultValue` regardless of UI state (a Morpheus bug
  no plugin-side fix can paper over).

- **Improved.** `readArgusSettings()` smarter `isOn(null)`. Previous
  behavior treated every missing key as "default-on", which is
  correct only on a truly fresh install. v0.1.40 looks at whether
  the config Map has ANY saved keys: if yes, missing keys are
  treated as `false` (user explicitly unchecked and Morpheus dropped
  the field); if no, missing keys are treated as `true` (fresh
  install, defaults apply). This correctly handles the most likely
  cause of the v0.1.39 reports — assuming Morpheus is in fact
  dropping unchecked fields from JSON, which the new diagnostics
  will confirm or refute.

### Notes

- **HPE iLO 6 has THREE UID-related properties.** DMTF top-level
  `IndicatorLED` (read-only on modern firmware, kept for backward
  compat), DMTF `LocationIndicatorActive` (boolean, may also be
  read-only on some firmware), and HPE OEM `Oem.Hpe.IndicatorLED`
  (Lit/Blinking/Off enum, explicitly the writable fallback per HPE's
  own changelog). Modern firmware writes go through the OEM property.
  Don't assume DMTF properties are the "right" answer — HPE moved
  the writable bit by design.
- **Morpheus plugin-settings CHECKBOX persistence is opaque.** We
  can't tell from the plugin side what Morpheus stores when a user
  unchecks a CHECKBOX with `defaultValue: 'on'`. Possibilities:
  field dropped, field set to `false`, field set to defaultValue
  regardless. Adding diagnostics that show the raw JSON is the only
  way to find out without source access to Morpheus's form handler.
  Lesson for future settings work: always emit a raw-config
  diagnostics row from the first version that introduces settings —
  the cost is one HTML row and it pays off the moment anyone reports
  a "settings don't work" issue.
- **HPE AMS agent is required for drive enumeration on KVM
  hypervisor hosts.** Drives populate the iLO Storage collection
  only when the AMS agent runs on the host OS and reports drive
  inventory back to iLO via the CHIF channel. Without AMS, the
  Drives card is empty even on hosts with healthy drives — this is
  a documented HPE behavior, not a plugin bug. Documented in
  TROUBLESHOOTING.md.
- **The `hpilo` kernel driver emits `Open could not dequeue a packet`
  warnings as expected noise.** These appear in `dmesg` or
  `journalctl -k` on hosts where AMS or other userspace tools open
  the iLO CHIF channel. The messages are informational and do not
  indicate a plugin or hardware fault. Documented in TROUBLESHOOTING.md.

## 0.1.39 — Window-mode actually opens tabs; UID PATCH probes harder before blaming RBAC

Two follow-ups from v0.1.38 testing. The "Open in Popup Window" checkbox
was being read correctly, but unchecking it still opened a popup window
because of a bad features-string choice on the JS side. And the new UID
buttons were returning 403 on every attempt, which v0.1.38 surfaced as
the unhelpful banner "UID change failed: iLO returned 403" with no
guidance about why or what to do.

### What changed

- **Fixed.** Window mode setting now actually takes effect. v0.1.38 passed
  `'noopener'` as the `window.open` features string for tab mode. That's
  a non-empty features string, and a non-empty features string forces
  popup-window behavior in Chrome, Edge, and Firefox regardless of
  which keywords it contains — `noopener` is a security flag, not a
  tab-vs-window control. The fix is to pass `''` for tab mode. While
  fixing that, also addressed a secondary issue: even with empty
  features, a NAMED target (`iloConsole_${server.id}`) was being reused
  across clicks, so a previously-opened popup window would still get
  reused on subsequent clicks even after the user switched to tab mode.
  Tab mode now uses `_blank` for the link flow (sessionkey URL,
  link-only) and a fresh `iloConsole_<id>_<timestamp>` per click for
  the form-POST flow (which needs a named target to pin form.target +
  pre-opened window together). Each click opens a brand-new tab; no
  reuse. The `rel="noopener"` on every `<a>` tag preserves the
  opener-isolation we wanted from the `noopener` keyword.

- **Fixed.** Open Console button now honors window mode too. Previously
  it was a plain `<a target rel="noopener">` link with no JS
  interception, so the browser's default click handler ignored the
  windowMode setting and just opened wherever the browser preferred.
  Added `data-argus-launch="1"` so the existing JS handler picks it up
  alongside Launch Console.

- **Improved.** UID PATCH now walks a 6-step probe chain instead of the
  2-step v0.1.38 chain (Systems/1 plain → Chassis/1 plain). The new
  chain adds an `If-Match: *` retry on each IndicatorLED endpoint AND
  two `LocationIndicatorActive` fallback attempts on the DMTF newer
  boolean property — some iLO 6 firmware (notably 1.5+ on Gen11) made
  IndicatorLED read-only and only accepts writes via the new property:
    1. `PATCH /Systems/1 {IndicatorLED}` (no If-Match).
    2. `PATCH /Systems/1 {IndicatorLED} + If-Match: *`.
    3. `PATCH /Chassis/1 {IndicatorLED}` (no If-Match).
    4. `PATCH /Chassis/1 {IndicatorLED} + If-Match: *`.
    5. `PATCH /Systems/1 {LocationIndicatorActive: bool}` (Off→false,
       Lit/Blinking→true).
    6. `PATCH /Chassis/1 {LocationIndicatorActive: bool}`.
  The If-Match retry is there because some Redfish implementations
  require ETag concurrency on PATCH and return 403 (rather than the
  spec-correct 412) when it's missing. The wildcard `*` matches any
  current ETag, so it's safe to add unconditionally; servers that
  don't require If-Match ignore the header. The six attempts and
  their HTTP outcomes are now recorded in a `uidActionAttempts`
  diagnostics row so it's clear from the tab whether everything
  returned the same code (RBAC) or different codes (firmware quirk)
  or specific Redfish errors (property/value issues).

- **Improved.** UID PATCH errors now capture the Redfish error
  `MessageId` from iLO's response body (`error.@Message.ExtendedInfo[0].MessageId`)
  and surface it both on the inline banner and the diagnostics row.
  Standard Redfish servers return identifiers like
  `Base.1.0.PropertyNotWritable` or `Base.1.0.PropertyValueNotInList`
  in error responses — these are immediately actionable signals about
  which fallback the plugin should try next. v0.1.38 threw the
  response body away and only retained the HTTP code, which made 400
  responses uniformly unhelpful.

- **Improved.** UID 400 banner now identifies the most likely root
  cause based on the Redfish MessageId. `PropertyNotWritable` or
  `PropertyReadOnly` → "IndicatorLED is read-only on this firmware and
  LocationIndicatorActive was also rejected — property may have moved
  to an HPE OEM endpoint. File a GitHub issue." `PropertyValueNotInList`
  or `PropertyValueTypeError` → "The value '<value>' isn't accepted by
  this firmware — try a different button (some firmware supports only
  Off/Blinking, not Lit)." 401, 403, 404, 405 also get tailored
  messages. The full per-attempt list still goes into the Diagnostics
  row for debugging firmware-specific weirdness.

- **Fixed.** Read-side fallback for the UID badge. v0.1.38 read only
  `IndicatorLED` from Chassis/1 and Systems/1; when both came back
  null or "Unknown", the badge showed an em-dash. iLO 6 1.74+ on
  Gen11 populates `LocationIndicatorActive` (boolean) instead, so the
  badge stayed "Unknown" even on systems where UID was clearly active.
  v0.1.39 falls back to LocationIndicatorActive when IndicatorLED
  doesn't yield a usable value, mapping `true` → 'Lit' and `false` →
  'Off'. The badge now reflects reality on these firmware revisions.
  A new `indicatorLedSource` diagnostics annotation shows which
  property the badge value came from (IndicatorLED or
  LocationIndicatorActive). Caveat: LocationIndicatorActive is a
  boolean, so when the badge comes from there we can't distinguish
  Lit (steady) from Blinking — both report as 'Lit'.

### Notes

- **A non-empty `window.open` features string forces popup behavior in
  every modern browser.** This includes strings containing ONLY
  security keywords like `noopener` or `noreferrer`. Don't use those
  in the features string to convey intent; put them in `rel=""` on
  the `<a>` tag instead. For tab behavior, the features string must
  be the empty string `''`, full stop.
- **Named `window.open` targets reuse existing windows of that name,
  including their original type.** If a popup-style window with the
  given name is already open, calling `window.open(url, name,
  emptyFeatures)` will reuse the popup window, not open a fresh tab.
  Per-click unique names (timestamp suffix) are the workaround when
  you need a named target but also want fresh windows.
- **iLO sometimes returns 403 where the Redfish spec says 412 should
  fire.** Some HPE firmware enforces ETag concurrency on PATCH but
  uses 403 for "missing If-Match" instead of 412 ("If-Match did not
  match"). Always retry with `If-Match: *` once on 403 before
  concluding RBAC is the cause — and even when RBAC is the actual
  cause, you want the diagnostic data to prove it rather than guess.
- **The most common cause of UID PATCH 403 is the iLO user account
  lacking the "Configure iLO Settings" privilege.** Login and Remote
  Console (which are enough for reads and console launch) are not
  enough to write IndicatorLED. Documented in TROUBLESHOOTING.md.
- **HPE iLO 6 firmware revisions vary on which property is writable
  for the UID LED.** Some accept `IndicatorLED` on `/Systems/1`; some
  only on `/Chassis/1`; some have made `IndicatorLED` read-only and
  require the DMTF newer `LocationIndicatorActive` (boolean) property.
  v0.1.39's 6-step probe chain covers all four combinations of
  endpoint × property to find one that works without requiring a
  firmware-version lookup table. The trade-off: when the working
  combination is `LocationIndicatorActive`, the badge can't
  distinguish Lit (steady) from Blinking — both display as Lit.
- **Always extract the Redfish error MessageId from PATCH error
  bodies, not just the HTTP code.** Redfish's `error.@Message.ExtendedInfo[0].MessageId`
  carries identifiers like `Base.1.0.PropertyNotWritable` that turn
  "400 Bad Request" (useless) into "the property is read-only, try
  the LocationIndicatorActive fallback" (immediately actionable). The
  Morpheus HttpApiClient surfaces this via `resp.data` on non-2xx
  responses — pull it out, even though every other 2xx response
  treats the body as a successful payload.

## 0.1.38 — Settings persistence fix + UID indicator LED control

Two things: the v0.1.37 settings page rendered correctly as checkboxes but
nothing the user saved actually took effect, and we added a long-deferred
operational feature — front-panel UID light control from inside the tab,
for finding a server in the datacenter.

### What changed

- **Fixed.** Settings persistence. v0.1.37's `readArgusSettings()` used
  `(morpheus.getPluginConfig().toBlocking().firstOrDefault([:]) ?: [:]) as Map`,
  which compiled fine and looked sensible, but `getPluginConfig()` returns
  `Observable<String>` (a JSON-encoded blob), not `Observable<Map>`. The
  `as Map` coercion against a non-empty String throws `GroovyCastException`,
  the outer catch attempted a `metaClass.getProperty(this, 'settings')`
  fallback that also failed, and the config Map ended up `[:]` every
  render. Then the `isOn(null)` default of `true` made every setting
  appear "on" regardless of what the user actually saved. Symptom: Travis
  unchecked "Open in Popup Window", saved, Launch Console still opened a
  popup. v0.1.38 parses the JSON properly with `JsonSlurper.parseText()`
  and treats anything outside that successful-parse path as "use
  defaults". The launchMode / launchWindowMode / launchAuthMethod rows in
  the Diagnostics table flip in real time as the checkboxes toggle now,
  making this trivially verifiable.

- **New.** UID indicator LED control. The System card now always renders
  a `UID` cell containing the current state as a colored badge
  (Off / Lit / Blinking / Unknown — blue for active states matching the
  physical front panel; pulse animation for Blinking) plus three small
  action buttons (Off / Lit / Blink). Clicks navigate the iLO tab to
  `?argusUidAction=<value>`, `renderTemplate` reads the param via the
  Spring `RequestContextHolder` trick we already use for the CSP nonce,
  whitelists the value against `['Off','Lit','Blinking']`, and forwards
  it to `RedfishClient.collectStatus`. The new collectStatus signature
  does the `PATCH /redfish/v1/Systems/1 {"IndicatorLED": value}` after
  login but BEFORE the bulk-read, so the same render's badge reflects
  the new state. iLO commit lag is handled with an optimistic override —
  on successful PATCH we trust the requested value rather than the
  subsequent GET. iLO firmware variance (Gen10 / iLO 5 expose
  IndicatorLED on /Chassis/1 only, Gen11 / iLO 6 on /Systems/1 only) is
  handled by falling back to /Chassis/1 if the /Systems/1 PATCH returns
  4xx. The IndicatorLED READ also tries both endpoints in turn — that's
  why v0.1.37's UID row never appeared on "defender" (Gen11 only reports
  IndicatorLED on /Systems/1; v0.1.37 only read /Chassis/1). A small
  inline success/failure line appears below the buttons on action
  renders, then disappears on the next render. A nonce'd JS snippet
  strips `argusUidAction` from the URL via `history.replaceState`
  immediately on every load so a browser refresh doesn't re-fire the
  action.

- **Added.** New helper `getRequestParam(String name)` mirroring
  `getCspNonce()` exactly — same Spring `RequestContextHolder` lookup,
  same Groovy dynamic dispatch, same silent-failure semantics. Reads
  query-string parameters off the current `HttpServletRequest` from
  inside `renderTemplate`. Returns null on any failure so the UID
  feature degrades to "no action this render" rather than throwing.

- **Added.** Two new RedfishClient methods. `patchJson(path, body)` is
  the generic PATCH wrapper, mirroring the existing `getJson(path)` GET
  wrapper — uses the same X-Auth-Token header pattern, returns
  `[success, errorCode?, errorMessage?]` instead of throwing.
  `setIndicatorLed(value)` is the UID-specific helper that validates the
  value against the whitelist, attempts /Systems/1 first, then falls
  back to /Chassis/1, and returns the more informative of the two
  failures when both fail.

- **Added.** New diagnostic rows in the Diagnostics table:
  `uidAction` (only present on render where an action was attempted —
  shows the requested value and success/failure inline) and
  `indicatorLed` (current state from the read). Useful for triaging any
  firmware that doesn't behave like the Gen11 / Gen12 fleet.

- **Bumped.** Diagnostics header to `(v0.1.38)`.

### Why this design

The render-time-PATCH-via-URL-param pattern was chosen over the
alternatives discussed: a controller endpoint would need to clear the
unresolved 403 issue from v0.1.35 first, and exposing the X-Auth-Token
to client-side JS for a direct browser → iLO PATCH has the security
profile of a session-hijack vulnerability waiting to happen. URL-param
dispatch keeps the auth token server-side, requires no controller
routing, and reuses the existing tab-render lifecycle. The browser
back-button / refresh case is handled cleanly by `history.replaceState`
stripping the param on load.

The trade-off: clicking a UID button forces a full tab re-render
(re-fetch of the whole Redfish status set), which is overkill for what
should be a 100-byte PATCH. In practice it's a few hundred ms and feels
fine; if it becomes annoying we can add a "UID-only render" fast path
later that skips the bulk-read.

### Notes

The same plugin re-upload caching that bit us on v0.1.37 (where the
OptionType registrations stayed v0.1.36 until a full uninstall+reinstall)
applies here too. To pick up the new UID cell, do the full Admin →
Plugins → uninstall before re-uploading the v0.1.38 JAR.

## 0.1.37 — Light-mode contrast fix + settings rebuilt as checkboxes

Two follow-ups from real-world v0.1.36 testing. The light-mode work in
v0.1.36 only got us halfway there: the variable framework was right but
the theme detection missed the actual Morpheus 9 theme class, so the
variable stayed on dark-mode values rendering as pale gray text on white.
Separately, the v0.1.36 settings dropdowns rendered as "No options
available" because plugin-api 1.3.1's `optionSource` lookup expects the
method on a registered OptionSourceProvider — not on the Plugin class
itself.

### What changed

- **Fixed.** Light-mode contrast — the v0.1.36 text was still too pale to
  read against a white background. Root cause was a two-layer miss:
  1. The dark-mode CSS variable declarations were self-referential
     (`--argus-bg-card: var(--argus-bg-card)`) after an over-aggressive
     sed pass in v0.1.36 replaced the variable definitions alongside the
     uses. Browsers ignored the recursive declarations, which is why
     dark-mode card backgrounds went transparent (mostly invisible
     because Morpheus's dark page bg blends through). v0.1.37 restores
     the literal `rgba(255,255,255,0.0X)` values for dark-mode.
  2. The light-mode CSS selectors (`body.theme-light`, `body.light-theme`,
     `body[data-theme="light"]`, `html.theme-light`, `html.light`,
     `.theme-light`) didn't match Morpheus 9's actual theme class, and
     `@media (prefers-color-scheme: light)` only fires when the user's
     OS preference matches — which doesn't track Morpheus's theme
     setting. v0.1.37 adds a CSP-nonced inline JS snippet that reads
     `window.getComputedStyle(document.body).backgroundColor`, computes
     its luminance via the Rec. 709 coefficients
     (`0.299*r + 0.587*g + 0.114*b`), and sets `data-mode="light"` or
     `data-mode="dark"` on every `.argus-tab` element. The CSS rules
     then switch reliably regardless of how Morpheus implements its
     theme.
- **Fixed.** Plugin settings showing "No options available" in v0.1.36.
  Plugin-api 1.3.1's `optionSource: 'methodName'` lookup expects the
  method on a registered `OptionSourceProvider`, not on the Plugin
  class. v0.1.37 sidesteps the OptionSourceProvider registration (and
  its own permission cascade — see the controller route history in
  0.1.16-0.1.23) by switching from three SELECT dropdowns to three
  CHECKBOX inputs. The behavior space is the same; the settings are
  now:
  - **Console: Auto-login (SSO)** — checked (default) = pre-authenticate;
    unchecked = link-only (open `/irc.html`, user types creds at iLO).
  - **Console: Open in Popup Window** — checked (default) = sized popup
    (1280×800); unchecked = standard browser tab.
  - **Console: Use Sessionkey URL Authentication** — checked (default) =
    sessionkey URL with cookie fallback on mint failure; unchecked =
    force the v0.1.35 cookie POST path.

  The v0.1.36 "Sessionkey only — no fallback" forced mode is dropped;
  with Auto + cookie fallback as the default, the no-fallback variant
  added no operational value. If we need it later it becomes a fourth
  checkbox.
- **Strengthened.** Light-mode base text color from v0.1.36's `#1a2530`
  to pure black (`#000000`) so the heavily-used `opacity:0.55` inline
  styles for label text (POWER, HEALTH, iLO, etc.) still read with good
  contrast. Added a row of attribute-substring CSS selectors that boost
  inline opacity values in light mode via `!important` — the only way to
  win specificity against inline `style` attributes.
- **Bumped.** Diagnostics header to `(v0.1.37)`.

### Things that didn't change

- The downstream rendering path. `readArgusSettings()` still returns the
  same `launchMode` / `windowMode` / `authMethod` keys with the same
  string values. The CHECKBOX migration is invisible to every code path
  that consumes the settings.
- The v0.1.36 launch logic (sessionkey URL + cookie fallback) — that
  side of v0.1.36 worked; only the settings UI surface was broken.
- The NIC / HBA card split. Travis hadn't tested it against a host with
  HBAs yet but the logic is unchanged from v0.1.36.

### Notes

- The JS theme detection requires the CSP nonce (read via Spring's
  `RequestContextHolder` per 0.1.25). If the nonce is unavailable, the
  tab silently falls back to CSS-only theme matching — works on
  Morpheus versions whose theme class is in our list. v0.1.37's `data-
  mode` attribute is the canonical signal; theme classes are just
  extra coverage.
- CHECKBOX defaults in plugin-api 1.3.1 are stringly-typed (`'on'` /
  `'off'`) but some setups store them as Booleans. `readArgusSettings`
  handles both via a tolerant `isOn` closure that treats anything not
  unambiguously false-y as checked.
- The `--argus-bg-card: var(--argus-bg-card)` self-reference bug in
  v0.1.36 was a sed mistake — the sed pattern matched both the variable
  USES (inline `style="background:rgba(255,255,255,0.02)"` → `var(...)`)
  and the variable DEFINITIONS (CSS rule `--argus-bg-card:
  rgba(255,255,255,0.02)` → `var(...)`). The fix is mechanical (restore
  literal values in the CSS rule); the lesson is to be more surgical
  about sed boundaries when the same substring means different things
  in different contexts.

---

## 0.1.36 — Sessionkey URL launch, NIC/HBA split, light-mode theme, plugin settings

Three problem reports drove this release: the tab was nearly unreadable in
Morpheus's light theme; hosts with FC/SAS HBAs lumped them into the NIC
Port LEDs card as confusing dark "—" entries; and the form-POST launch dance
sometimes landed users on the iLO login page (cookie commit race) requiring
an extra refresh.

The launch fix is the headline. v0.1.35's launch posts credentials to
`/json/login_session` then JS-schedules a navigation to `/irc.html` 1.5 s
later, trusting iLO to commit the session cookie in time. Under load,
sometimes it doesn't. v0.1.36 reuses the existing `RedfishClient` to mint a
fresh session at tab render, then renders the Launch Console button as a
plain `<a href="https://<host>/irc.html?sessionkey=<TOKEN>">`. iLO accepts
the token via URL param, no cookie race, one click. The v0.1.35 form-POST
path is preserved as automatic fallback for when the mint fails, and three
new plugin settings let users tune launch / window / auth behavior.

### What changed

- **Fixed.** Light-mode color regression. The entire tab's color values
  (text, card backgrounds, borders, dividers) are now driven by a single
  set of CSS variables (`--argus-text`, `--argus-bg-card`,
  `--argus-bg-header`, `--argus-bg-pill`, `--argus-border`,
  `--argus-border-soft`, `--argus-bg-diag`, `--argus-btn-border`) defined
  at the top of the render output. Dark-theme values match v0.1.35
  exactly. Light-theme values override via multiple likely Morpheus theme
  class patterns (`body.theme-light`, `body.light-theme`,
  `body[data-theme="light"]`, `html.theme-light`, `html.light`,
  `.theme-light`), plus `@media (prefers-color-scheme: light)` as a
  last-resort fallback. Status colors (ON / WARNING / CRITICAL, link-up
  green, etc.) are intentionally NOT variabilized — they carry semantic
  meaning and must stay color-coded regardless of theme.
- **Fixed.** Console launch race condition. New sessionkey-URL path:
  `RedfishClient.acquireLaunchToken()` mints a fresh Redfish session at
  tab render and intentionally does NOT log out — the session is
  consumed by the user's click on the rendered `?sessionkey=<TOKEN>` URL.
  Deterministic, no cookie commit race, single click. iLO 6 reaps the
  session after ~30 min idle if unused, so a stale long-open tab might
  see a login page on click — refreshing the iLO tab re-mints. The
  legacy form-POST + setTimeout flow is kept as automatic fallback for
  when the mint fails (firmware quirk, network blip) or when the new
  `consoleAuthMethod` setting is set to `cookie`.
- **Fixed.** NIC / HBA conflation. The "NIC Port LEDs" card in v0.1.35
  rendered every port from `/Chassis/1/NetworkAdapters` regardless of
  port technology, which surfaced FC and SAS HBA ports as dark "—"
  entries with no Mbps speed. v0.1.36 classifies each port by
  `ActiveLinkTechnology` (Ethernet → NIC bucket; FibreChannel /
  InfiniBand → HBA bucket; null → fall back to adapter-model string for
  FC / HBA / SAS / Fibre / TriMode / SmartArray markers, default NIC).
  The card is split into **Network Adapter Ports** (Ethernet) and
  **Host Bus Adapter Ports** (FC / SAS / IB). HBA ports use "Online /
  Offline" semantics instead of "Link Up / Link Down" and display
  WWPN as monospace subtext under the port label. The HBA card is
  omitted entirely on hosts with no HBA ports, so the common case
  (MicroServer / hyperconverged nodes) doesn't gain an empty card.
- **Added.** Three plugin-level settings under
  **Administration → Plugins → iLO Console → Settings**:
  - `Console Launch Mode`: `Auto-login (SSO)` (default — matches v0.1.35)
    or `Link only` (open `/irc.html` without auth, user types credentials
    at iLO's prompt; useful when an org wants iLO to log the actual user
    identity rather than a shared service account).
  - `Console Window Mode`: `Popup` (default — 1280×800 sized window) or
    `New tab` (standard browser tab). Popup is more ergonomic for console
    work; tab mode is the safer choice for users with popup blockers or
    browsers that override window-size hints by user preference.
  - `Console Authentication Method`: `Auto` (default — try sessionkey,
    fall back to cookie on mint failure), `Sessionkey only` (force
    sessionkey, no fallback), or `Cookie only (legacy)` (force the
    v0.1.35 form-POST path). Provides an explicit escape hatch for any
    firmware quirks where one method fails consistently.
- **Added.** iLO web UI link next to the "iLO" header. A small
  external-link icon with the text "Open iLO UI" opens `https://<host>/`
  in a new tab. No authentication handoff — opens iLO's login page
  directly. Useful when users want to administer iLO settings (firmware
  updates, user management, license) outside the Morpheus tab.
- **Added.** WWPN capture in `RedfishClient`. Each port now records
  `wwpn` from `port.FibreChannel?.WWPN` with fallback to
  `port.Oem?.Hpe?.WWPN`. NIC ports keep WWPN as `null` (Ethernet doesn't
  use WWPNs); HBA ports surface it in the new Host Bus Adapter Ports
  card as monospace subtext for SAN admins doing zoning.
- **Added.** Five new Diagnostics panel rows:
  - `launchMode` — current `Console Launch Mode` setting
  - `launchWindowMode` — current `Console Window Mode` setting
  - `launchAuthMethod` — current `Console Authentication Method` setting
  - `launchToken` — `minted (length=N)` or `not minted`
  - `launchAuthResolved` — actual strategy in use this render
    (`sessionkey` / `cookie` / `link-only` /
    `sessionkey-failed-no-fallback`)

  The existing `networkAdapters` row breaks out adapter / port count plus
  `NIC=N, HBA=N` split (and flags ports classified via model fallback
  rather than `ActiveLinkTechnology`) so the classifier is debuggable
  at a glance.
- **Added.** New docs: `docs/CREDENTIAL_TROUBLESHOOTING.md`,
  `docs/PLUGIN_SETTINGS.md`, `docs/SCREENSHOT_GUIDE.md`. Credential doc
  walks through every credential resolution failure mode with `curl`
  examples (wrong ID, wrong type, cross-tenant mismatch, disabled
  credential, labels-as-single-string visual indicator, direct iLO
  Redfish validation). Settings doc explains each plugin setting. Screenshot
  doc is contributor reference for capturing consistent release shots
  with a thorough PII scrub list.

### Things that didn't change

- Default launch behavior. Auto-login SSO remains the default; users
  upgrading from 0.1.35 without touching the new settings see no
  behavioral change beyond the sessionkey URL replacing the form-POST
  (which is invisible to the user — just more reliable).
- Label schema. Existing hosts continue to work with `ilo-host:`,
  `ilo-cred:`, `ilo-verify-ssl:`, `ilo-readonly:` labels unchanged.
- Plugin internal name (`ilo-console-plugin`) and package
  (`com.morpheusdata.iloconsole`). Renaming would break upgrade-in-place
  compatibility; user-facing surfaces use "iLO" / "Argus" naming while
  internal code retains `iloconsole`.

### Why CSS variables rather than a wholesale rewrite

The tab renders inline HTML with `style="…"` attributes — 1441 lines of
the stuff at v0.1.35. Refactoring to external classes would be a
multi-release effort. The CSS-variable approach is mechanical: define the
variables once at the top of the render output, do a find-and-replace
through the file (40+ rgba color sites), and theme-aware values fall out
for free. Inline styles still win specificity battles, but `var(...)` in
an inline style resolves against whichever theme is active at render
time. No DOM detection, no `prefers-color-scheme` complexity for the
common case — Morpheus's theme class drives it. The `prefers-color-scheme`
media query is there only as a fallback for cases where we missed the
real Morpheus theme class.

### Why mint at tab render rather than on click

A click-time mint would need a server-side endpoint to receive the click
and respond with the sessionkey URL. The plugin abandoned registered
controllers in v0.1.23 after 0.1.16-0.1.22 fought permission 403s
across every documented declaration pattern with no clean resolution.
Re-opening that question would gate v0.1.36 on solving it; minting at
render time sidesteps the whole controller question. The cost is one
extra Redfish session per tab view (iLO 6 default max is 10 concurrent;
idle reap is ~30 min) and a slightly longer tab render. Acceptable for a
non-polling UI.

### Notes

- Sessionkey URL has been validated against iLO 5 v2.78+ and iLO 6 v1.74.
  Older firmware untested; users on older firmware can switch
  `Console Authentication Method` to `Cookie only`.
- Popup window features are honored by Chrome and Edge. Firefox may
  ignore size hints if the user has set "open new windows in a new tab"
  in their preferences — the link falls back to a tab gracefully in
  that case.
- The "labels typed as a single string" issue Travis observed during
  v0.1.35 testing (one big pill instead of three separate ones) is user
  error visible from the Morpheus UI's pill rendering, not a plugin bug.
  Documented in `docs/CREDENTIAL_TROUBLESHOOTING.md` as a known failure
  mode so first-line support can recognize it quickly.

---

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
