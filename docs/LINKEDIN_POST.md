# LinkedIn announcement — morpheus-argus v0.1.43

Two variants below. **Variant A** is the short, punchy post you said
you wanted to use as the actual headline. **Variant B** is the longer
"deep dive" you can use for a blog post or a follow-up reply with more
technical detail.

Posting strategy from earlier conversations:
- **First-comment link placement** to avoid LinkedIn's link-suppression
  on the main post.
- **5-7 hashtags** (listed at the bottom of each variant).
- **Primary image**: full iLO-tab screenshot showing System / Power &
  Cooling / Network / Drives / Power Trend / Network Adapters / NIC
  Port LEDs in one frame.
- **Secondary image (optional carousel)**: NIC Port LEDs card crop +
  Diagnostics block showing settings/sessions/UID forensics.
- See `docs/SCREENSHOT_GUIDE.md` for what to scrub before posting:
  IPs, hostnames, MAC addresses, asset tags, serials, and the
  full iLO IP from any URL bar visible in the browser frame.

---

## Variant A — short (~150 words)

> **The HPE iLO panel I wanted my Morpheus to have**
>
> Bare-metal operators know the moment: a host won't boot. You SSH'd
> to it dozens of times last week. Today, you need iLO — but the URL
> is a bookmark on your laptop, the credentials are in a vault, and
> the team chat is asking why you're not on call.
>
> So I built it into Morpheus. `morpheus-argus` is a Morpheus 9.0
> plugin that adds a live iLO tab to every HPE ProLiant host detail
> page. Power, health, thermals, fans, PSU, drives, DIMMs, network
> adapters, NIC port LEDs, RAID volumes, firmware inventory, recent
> events, active sessions — pulled live from Redfish.
>
> One click pre-authenticates a console session and opens iLO's
> HTML5 IRC console. UID indicator LED control from inside the tab
> for the datacenter tech walking the rack. Read-only mode for
> hosts where you want visibility without launch access.
>
> Open source, MIT licensed. Link in comments.
>
> #HPE #iLO #Morpheus #BareMetal #DataCenter #OpenSource #SRE

---

## Variant B — long (~400 words)

> **The HPE iLO panel I wanted my Morpheus to have**
>
> Every bare-metal operator knows the moment. A host won't boot. You
> SSH'd to it twenty times last week. Today, you need iLO — but the
> URL is a bookmark on your laptop, the credentials live in a vault
> three clicks deep, the team chat is asking why you're not on call,
> and you still have to find the right BMC IP among forty-eight
> identical-looking ProLiants in the rack.
>
> The bookmark-and-vault workaround scales to about a dozen servers.
> Past that, you're burning minutes per incident on context-switching
> that the platform should be solving. So I built it into Morpheus.
>
> `morpheus-argus` is a Morpheus 9.0 plugin that adds a live "iLO"
> tab to every HPE ProLiant host detail page. It reads live status
> via Redfish — power state, health, iLO firmware version, BIOS
> version, CPU, memory, drives, DIMMs, network adapters, NIC port
> LEDs, RAID volumes, cooling zones, firmware inventory, recent IML
> events, and active iLO sessions. Temperatures in °C and °F because
> half your team isn't in the US.
>
> Two buttons that matter most:
>
> **▶ Launch Console** mints a Redfish session, embeds the
> sessionkey in the IRC URL, and opens iLO's HTML5 console
> pre-authenticated in one click. No login prompt. No "find the
> bookmark" step. The popup-vs-tab decision is a plugin setting.
>
> **UID indicator LED control** lets you toggle the front-panel UID
> LED from inside the tab — Off / Lit / Blink — for the datacenter
> tech walking the rack trying to find the failing host. The plugin
> probes DMTF IndicatorLED, LocationIndicatorActive, and HPE's OEM
> Oem.Hpe.IndicatorLED in sequence so it works across iLO 5 / iLO 6
> / iLO 7 firmware regardless of which property HPE made writable
> on your specific version.
>
> A read-only mode (label-driven) hides credentials and console
> launch on hosts where you want visibility but not launch access.
> Diagnostics block at the bottom of the tab surfaces every
> property access trace, plugin settings forensics, and iLO
> concurrent-session pressure indicator so triaging "why isn't this
> working" takes seconds rather than tickets.
>
> Open source under MIT. Built against Morpheus 9.0 and tested
> across MicroServer Gen10 Plus, MicroServer Gen11, and DL325 Gen12.
> Link in comments.
>
> #HPE #iLO #Morpheus #BareMetal #DataCenter #Redfish #OpenSource

---

## First-comment text (post separately right after the main post)

> Repo: https://github.com/builtbyfood/morpheus-argus
>
> Release notes for v0.1.43:
> https://github.com/builtbyfood/morpheus-argus/releases/tag/v0.1.43

---

## Hashtag pool (pick 5-7 per post)

Primary (always include):
- `#HPE`
- `#iLO`
- `#Morpheus`

Operational angle:
- `#BareMetal`
- `#DataCenter`
- `#SRE`
- `#DevOps`

Technical angle (pick one or two):
- `#Redfish`
- `#OpenSource`
- `#Groovy`
- `#ProLiant`

Avoid: `#Cloud` (misleading — this is bare-metal), `#AI` (irrelevant
and noisy), generic `#Tech` (low signal).
