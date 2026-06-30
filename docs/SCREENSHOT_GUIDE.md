# Screenshot Guide

Reference for capturing release screenshots consistently across versions.
Mostly an internal / contributor doc — but the **scrub list** is critical
for anything public-facing (releases, LinkedIn announcements, blog posts,
README).

## Shots required for a v0.1.36 release

| # | Shot | Theme | Source host | Why |
| --- | --- | --- | --- | --- |
| 1 | Hero — full iLO tab, top section | Dark | DL325 Gen12 (production-looking) | Primary release / LinkedIn image — shows System, Power & Cooling, Power Trend |
| 2 | Hero — full iLO tab, top section | **Light** | Same as #1 | **NEW in v0.1.36** — proves the light-mode color fix |
| 3 | Network + HBA cards visible together | Either | DL325 Gen12 (host with HBAs) | **NEW in v0.1.36** — proves NIC / HBA separation, HBA WWPN visible |
| 4 | Network card only (no HBA card) | Either | MicroServer Gen11 (no HBAs) | Proves HBA card is omitted, not shown empty |
| 5 | Plugin Settings page | Either | n/a (admin UI) | **NEW in v0.1.36** — three new settings visible with defaults |
| 6 | Launch Console popup window | Dark | Any | Proves sized popup behavior (1280×800) |
| 7 | Diagnostics panel — healthy | Either | defender | Reference for users to compare against; v0.1.36 header + new rows |
| 8 | Diagnostics panel — empty credSource | Either | Mock or test scenario | For credential troubleshooting doc |

Shots #1, #2, and #3 are the most important — they go on the LinkedIn
announcement, GitHub release, and (optionally) the top of the README.

## Crop and sizing guidance

### Hero shots (#1, #2)

- Capture full browser viewport, then crop to the iLO tab content area
  only — strip the browser chrome and Morpheus left nav.
- Target output: ~1600×900 or 2:1 ratio. Width matters more than height
  for LinkedIn.
- Include the page tabs bar (`Summary | Wiki | ... | iLO`) so iLO is
  visible as the active tab.
- Include the System card and either Power & Cooling or Power Trend
  below.
- **Light hero (#2)** is the key proof point for the v0.1.36 release —
  same scope as the dark hero, just with Morpheus's theme toggled to
  light.

### NIC + HBA shot (#3)

- Scroll so both cards are visible together (or capture two adjacent
  screens and stitch if needed).
- Make sure at least one port shows `Link Up` (NIC) or `Online` (HBA)
  with speed (proves live data) and at least one HBA port shows WWPN.

### Plugin Settings shot (#5)

- Navigate to **Admin → Plugins → iLO Console → Settings**.
- Show all three dropdowns with their defaults visible.
- Optional: capture a second shot with one dropdown expanded to show
  the options.

### Diagnostics panel shot (#7)

- Scroll to the bottom of the iLO tab.
- Capture the full Diagnostics table including the header
  `DIAGNOSTICS (v0.1.36)`.
- Verify the five new v0.1.36 rows are visible: `launchMode`,
  `launchWindowMode`, `launchAuthMethod`, `launchToken`,
  `launchAuthResolved`. Also confirm the `networkAdapters` row shows
  the `NIC=N, HBA=N` split.

## **PII scrub list — critical for any public shot**

Before publishing **anywhere** (GitHub, LinkedIn, blog, docs, support
tickets), scrub:

- [ ] **IP addresses** — both Morpheus appliance and iLO IPs. Replace
      with RFC1918 placeholders like `10.10.10.42` or use solid black
      rectangles.
- [ ] **MAC addresses** — host MAC and iLO MAC. Replace with
      `XX:XX:XX:XX:XX:XX` or solid-rect.
- [ ] **Hostnames** — replace production hostnames with anonymized
      values (`HOST01`, `defender`, `HVM1` are fine — they're not
      identifying).
- [ ] **iLO hostnames** — `ILO-CN01` style values often encode location
      or cluster info. Replace or block.
- [ ] **Serial numbers** — `CZUD3X01NT`, `CZ123ABCXYZ`. Service-tag-
      style identifiers can be used for warranty lookups. Block or
      replace.
- [ ] **Asset tags** — anything in the Asset Tag field is likely
      sensitive (internal inventory codes).
- [ ] **WWPNs** — `10:00:XX:XX:XX:XX:XX:XX`. These are SAN identifiers
      and shouldn't leak.
- [ ] **FQDNs** — both Morpheus appliance URL and any iLO FQDNs visible
      in tooltips or URL bars.
- [ ] **Active iLO sessions** — the Active Sessions card shows real
      usernames + source IPs. Either omit the card from the screenshot
      or block each row.
- [ ] **IML events** — may contain serial numbers, internal IDs, or
      detailed error messages that hint at infrastructure. Omit or
      trim.
- [ ] **URL bar** — full appliance URL is fine if generic
      (`morpheus.example.com`); scrub if it's a real domain.

**Tools:** Snagit, Greenshot, or built-in OS screenshot tool with
annotation. Use solid black rectangles for IPs / MACs / serials, not
blur — blur can sometimes be reversed.

## Standard placeholder values

When you need to replace real data with realistic-looking placeholders:

| Field | Placeholder |
| --- | --- |
| Morpheus URL | `morpheus.example.com` |
| iLO IP | `10.10.10.42` |
| iLO FQDN | `ilo01.example.lan` |
| Host MAC | `00:11:22:33:44:55` |
| iLO MAC | `7C:A6:2A:00:00:00` (HPE OUI preserved, suffix zeroed) |
| Host serial | `CZXXXXXXXX` |
| WWPN | `10:00:XX:XX:XX:XX:XX:XX` |

## File naming convention

For files going into the repo `docs/screenshots/` directory:

```
v0_1_36-hero-dark.png
v0_1_36-hero-light.png
v0_1_36-nic-hba-cards.png
v0_1_36-plugin-settings.png
v0_1_36-console-popup.png
v0_1_36-diagnostics.png
```

Snake case, version prefix, descriptor suffix. Makes it easy to swap
shots per version without breaking links from prior CHANGELOG entries.

## Where to use each shot

| Shot | LinkedIn | GitHub release | README | docs/ |
| --- | --- | --- | --- | --- |
| #1 hero dark | Primary image | Top of body | Optional top | — |
| #2 hero light | Secondary image | Below #1 | Optional | — |
| #3 NIC + HBA | First comment image | Highlight section | — | — |
| #4 NIC only | — | — | — | Optional |
| #5 Plugin Settings | — | Settings section | — | `PLUGIN_SETTINGS.md` |
| #6 Console popup | — | — | — | — |
| #7 Diagnostics healthy | — | — | — | `CREDENTIAL_TROUBLESHOOTING.md` |
| #8 Diagnostics empty credSource | — | — | — | `CREDENTIAL_TROUBLESHOOTING.md` |

## LinkedIn-specific tips

From the v0.1.35 announcement playbook:

- Drop the GitHub link in the **first comment**, not the post body —
  LinkedIn de-prioritizes posts with external links in the body.
- Use 5–7 hashtags. Same set across releases for consistency: e.g.
  `#HPE #iLO #ProLiant #Morpheus #Redfish #DevOps #Homelab`.
- Post early in the work week (Tuesday–Thursday morning US time).
- Reply to early commenters in the first hour — engagement signal
  boost.

## What goes in the CHANGELOG screenshot reference

Travis's CHANGELOG style doesn't currently embed images, but if a
release benefits from one, link rather than embed:

```markdown
![Hero dark](docs/screenshots/v0_1_36-hero-dark.png)
```

Keep the markdown rendering portable across GitHub and `pandoc`-
rendered PDFs.
