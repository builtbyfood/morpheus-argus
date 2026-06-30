# Plugin Settings

Three plugin-level settings under
**Administration → Plugins → iLO Console → Settings**.

All three are plugin-wide (not per-host). Per-host configuration stays
label-driven so it remains scriptable. Defaults preserve the v0.1.37
behavior (sessionkey URL launch, popup window) — which itself preserves
v0.1.35 user-visible behavior beyond making launches more reliable.

> **Note on version history.** v0.1.36 shipped these as SELECT dropdowns
> that rendered "No options available" because plugin-api 1.3.1's
> `optionSource` lookup expects the method on a registered
> `OptionSourceProvider` rather than the Plugin class. v0.1.37 reworked
> them as CHECKBOX inputs, which work cleanly. The underlying behavior
> space is the same; only the UI surface changed.

---

## Console: Auto-login (SSO)

**Default:** Checked (on)

Controls whether the plugin pre-authenticates the iLO session before
opening the console.

### Checked — the default

The plugin establishes an iLO Redfish session server-side using the
credential referenced by the host's `ilo-cred:` label, then opens the
iLO HTML5 console pre-authenticated. The user lands on the console
without seeing a login page.

**Use when:**

- You want frictionless one-click console access (the main reason to
  install this plugin).
- A shared / service iLO account is acceptable for audit purposes.

### Unchecked

The plugin opens `/irc.html` without authentication. The user types iLO
credentials manually on the login page.

**Use when:**

- Your organization requires iLO logs to record the actual user
  identity, not a shared service account.
- You want to give users a faster path to iLO than copy-pasting URLs
  but don't want credentials traversing Morpheus on launch.
- You're testing — useful when diagnosing whether an issue is in the
  auth path or elsewhere.

In unchecked mode, the credential label (`ilo-cred:`) is still
recommended (so the Diagnostics panel can show resolution status, and
so the credentials panel renders with values for manual copy/paste) but
not strictly required for the launch button to work.

---

## Console: Open in Popup Window

**Default:** Checked (on)

Controls whether the console opens in a sized popup window or a standard
browser tab.

### Checked — the default

Console opens in a window sized 1280×800 with browser chrome minimized.
The popup must be triggered by a user click (synchronous). If a popup
blocker interferes, the launch link gracefully falls back to opening as
a tab.

**Caveat:** some Firefox configurations override window-size hints based
on user preference ("open new windows in a new tab"). When that's set,
Firefox opens a tab regardless. No way for the plugin to detect or
override — works as the user has configured.

### Unchecked

Console opens as a standard browser tab.

**Use when:**

- Popup blockers are aggressive across your user base and the
  popup-blocked fallback is annoying.
- You prefer keeping all browser context (history, back button, address
  bar) on the console window.
- You're using a tab manager extension and want consoles to integrate
  with it.

> **v0.1.39 note.** Versions 0.1.36–0.1.38 read this setting correctly
> but the unchecked path still produced a popup window. The features
> string passed to `window.open` was non-empty (`'noopener'`), which
> all current browsers treat as a popup signal regardless of which
> keywords are present. v0.1.39 passes an empty features string and
> uses `_blank` (or a unique target name for the form-POST flow) so
> the unchecked path now actually opens a tab. If you saw a popup
> with this box unchecked, upgrade.

---

## Console: Use Sessionkey URL Authentication

**Default:** Checked (on)

Controls which authentication path the plugin uses when launching the
console. Only takes effect when **Console: Auto-login (SSO)** is checked
— has no effect on link-only launches (which don't authenticate).

### Checked — the default

The plugin mints a fresh Redfish session at tab render time, captures
the token, and renders the Launch Console button as a plain
`<a href="https://<host>/irc.html?sessionkey=<TOKEN>">`. iLO accepts the
token via URL parameter — deterministic, no cookie commit race, one
click.

If the session mint fails (firmware quirk, network blip, iLO
unreachable), the plugin transparently falls back to the v0.1.35
form-POST + setTimeout dance. The transition is invisible to the user —
the Launch Console button still works, just via the slower path.

**Use when:** always, unless you've identified a firmware-specific
reason to force the legacy path.

### Unchecked

Force the v0.1.35 form-POST + setTimeout path. The plugin posts
credentials to `/json/login_session` via the text/plain JSON-injection
trick, then navigates the popup to `/irc.html` ~1.5 s later.

**Use when:**

- You've hit a firmware quirk where sessionkey URLs don't work and you
  want a stable workaround.
- You want to keep the exact v0.1.35 launch behavior without
  downgrading the plugin.

---

## Setting combinations

Defaults (all three checked) reproduce the new v0.1.36/v0.1.37 behavior:

| Auto-login | Popup window | Sessionkey | Behavior |
| :-: | :-: | :-: | --- |
| ✓ | ✓ | ✓ | **Default.** Sessionkey URL launch in a sized popup. Falls back to cookie POST if mint fails. |
| ✓ | ✓ | — | Cookie POST launch in a sized popup. v0.1.35 behavior, no race fix. |
| ✓ | — | ✓ | Sessionkey URL launch in a new tab. |
| ✓ | — | — | Cookie POST launch in a new tab. |
| — | ✓ | (n/a) | Link-only launch in a sized popup. No auth; user types creds at iLO. |
| — | — | (n/a) | Link-only launch in a new tab. |

---

## Verifying settings

After changing any setting, verify it took effect:

1. **iLO tab → Diagnostics panel.** Five rows show the resolved settings
   and runtime state:
   - `launchMode` — `auto` if Auto-login is checked, else `link`
   - `launchWindowMode` — `popup` if Popup Window is checked, else `tab`
   - `launchAuthMethod` — `auto` if Sessionkey URL is checked, else
     `cookie`
   - `launchToken` — `minted (length=N)` or `not minted`
   - `launchAuthResolved` — the actual strategy this render is wired to
     (`sessionkey`, `cookie`, `link-only`, etc.)
2. **Click Launch Console.** Behavior should match what you selected.
3. **Tail the appliance log** to confirm which path actually ran:
   ```bash
   sudo tail -f /var/log/morpheus/morpheus-ui/current | grep argus.launch.method
   ```
   Expected formats:
   - `argus.launch.method=sessionkey-ready ...` — mint succeeded, button
     wired to sessionkey URL
   - `argus.launch.method=sessionkey-mint-failed ...` — mint failed, tab
     fell back to legacy cookie path

## What if I change my mind?

Settings are read on every tab render — no plugin reload required.
Change values, hit **Save Changes**, refresh the iLO tab. New behavior
takes effect immediately.

## How this interacts with `ilo-readonly:true`

The `ilo-readonly:true` label suppresses the entire launch UI (no
buttons, no credentials panel) on a per-host basis. Plugin settings
don't override this — readonly hosts stay readonly regardless of the
Settings page. Useful for audit-locked hosts where you want operators
to be able to *see* status but not be able to launch a console.
