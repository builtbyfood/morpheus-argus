# Credential Troubleshooting

This guide covers credential resolution failures specifically. If the iLO
tab loads but you see `credSource` empty in the Diagnostics panel, or the
error banner says credential lookup failed, walk through this in order.

For general install / render issues, see [`TROUBLESHOOTING.md`](../TROUBLESHOOTING.md).

## Setup

All `curl` examples assume these environment variables are set:

```bash
export MORPHEUS_URL="https://morpheus.example.com"
export TOKEN="<bearer-token>"   # Admin → Integrations → API Access, or POST /oauth/token
```

If you don't have a token yet:

```bash
curl -sk -X POST "$MORPHEUS_URL/oauth/token?grant_type=password&scope=write" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=morph-api&username=<user>&password=<pass>" \
  | jq -r '.access_token'
```

Stash the result in `$TOKEN`.

## Step 0 — Read the Diagnostics panel

Open the iLO tab on the affected host, scroll to the **Diagnostics** panel
at the bottom (collapsed by default — click to expand). Key fields:

| Field | Healthy value | Meaning if not healthy |
| --- | --- | --- |
| `paramNull` | `false` | If `true`, the plugin received no server reference — tab routing issue, not credential. |
| `directId` | numeric | Server resolved successfully. |
| `labelsCsv` | comma-separated labels | If you see a single token with no commas → labels were typed as one big string. See [Section 5](#section-5--labels-typed-as-a-single-string). |
| `credSource` | non-empty (e.g. `id=7,name=ilo-svc,type=username-password`) | **Empty = credential not resolved.** Walk through Sections 1–4. |
| `launchAuthResolved` | `sessionkey` (best) or `cookie` (fallback) | If `sessionkey-failed-no-fallback`, the mint failed and the `Console Authentication Method` setting is `Sessionkey only`. Either fix the cred or change the setting to `Auto`. |
| `cspNonce` | `present (length=...)` | If absent, CSP wiring is broken — not credential-related. |

`credSource` empty is the most common failure path; the rest of this doc
focuses on it.

---

## Section 1 — Does the credential ID actually exist?

Most common cause: a typo or mismatched ID in the `ilo-cred:<n>` label.

```bash
# List all credentials and find the right ID
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/credentials?max=100" \
  | jq '.credentials[] | {id, name, type: .type.code, enabled}'
```

Then inspect the specific ID referenced in the host's `ilo-cred:` label:

```bash
# Replace 7 with whatever ID is in the host's ilo-cred label
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/credentials/7" \
  | jq '.credential | {id, name, type: .type.code, enabled, accountId: .account.id}'
```

If this returns `404` or empty, the ID doesn't exist. Common causes:

- User read a row number from the Credentials UI list instead of the ID
  column. The ID column may not be visible by default — click "Show
  columns" and enable it.
- Credential was deleted but the label wasn't updated on the host.
- Credential was created in a different tenant (see [Section 3](#section-3--cross-tenant-mismatch)).

**Fix:** correct the label via **Actions → Edit → Labels** on the host.

---

## Section 2 — Is the credential the right type?

The plugin only accepts `username-password` credentials. Other types
(access-key-secret, certificate, ssh-key) won't resolve through
`accountCredential.listById()` + `loadCredentialConfig()`.

```bash
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/credentials/7" \
  | jq '.credential.type.code'
```

Expected output:

```
"username-password"
```

If it's anything else, create a new `username-password` credential and
update the host's `ilo-cred:` label to reference the new ID.

To filter the credentials list to only valid types:

```bash
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/credentials?type=username-password&max=100" \
  | jq '.credentials[] | {id, name}'
```

---

## Section 3 — Cross-tenant mismatch

Cross-tenant credential references silently return null from
`accountCredential.listById()`. Both objects must be in the same tenant.

```bash
# Host's tenant
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/servers/<SERVER_ID>" \
  | jq '.server.account.id'

# Credential's tenant (from Section 1)
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/credentials/7" \
  | jq '.credential.account.id'
```

These two `id` values must match.

**Common scenario:** A platform admin creates the credential in the master
tenant, but the host lives under a sub-tenant. The credential is visible
in the sub-tenant's UI (cross-tenant read works), but the plugin's
resolution path doesn't surface it.

**Fix:** create the credential in the same tenant as the host. If you
need to share a credential across tenants, create it once per tenant —
Morpheus doesn't have true cross-tenant credential sharing for plugin
consumption in plugin-api 1.3.1.

---

## Section 4 — Is the credential enabled?

A disabled credential also returns null through `loadCredentialConfig()`
even though it appears in the listing.

```bash
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/credentials/7" \
  | jq '.credential.enabled'
```

Expected: `true`. If `false`, enable via **Infrastructure → Trust →
Credentials → (edit row) → Enabled**.

---

## Section 5 — Labels typed as a single string

**Visual indicator:** look at the host detail page header. Properly
entered labels render as **separate pills**:

```
[ilo-host:10.10.10.42]  [ilo-cred:7]  [ilo-verify-ssl:false]
```

If you see **one big pill** containing the whole string:

```
[ilo-host:10.10.10.42 ilo-cred:7 ilo-verify-ssl:false]
```

…the user typed all three labels as a single value. Morpheus stored it as
one label, not three. The plugin's label parser splits on commas; it sees
one token starting with `ilo-host:`, never finds a discrete `ilo-cred:`
token, and `credSource` ends up empty even though the credential ID might
be perfectly valid.

**Fix:** re-enter labels via **Actions → Edit → Labels**, pressing Enter
(or comma) between each label so each one becomes its own pill.

This is user error, not a plugin bug — the UI's pill rendering already
signals the problem. But it accounts for a meaningful share of support
questions, so it's documented here for first-line support.

---

## Section 6 — Validate the credential directly against iLO Redfish

If the credential resolves cleanly in Morpheus (`credSource` populated)
but the iLO tab still shows errors, validate against iLO directly. This
isolates "bad credential value" from "plugin can't read it."

```bash
curl -sk -u "<ilo-username>:<ilo-password>" \
  "https://<ilo-ip>/redfish/v1/Systems/1" \
  | jq '{Name, Model, PowerState, Status}'
```

Outcomes:

- **200 with data:** credential is valid, iLO reachable. Issue is on the
  Morpheus/plugin side.
- **401 Unauthorized:** username/password stored in the credential is
  wrong. Update the credential in Morpheus.
- **403 Forbidden:** credential is valid but lacks privileges. Make sure
  the iLO user has at least the "User" role + "Read" permission.
- **Connection refused / timeout:** network reachability issue. Check from
  the Morpheus appliance directly: `ssh morpheus-appliance` then run the
  same `curl`.

---

## Section 7 — Tail the appliance UI log

While reproducing the failure, tail the UI log on the Morpheus appliance:

```bash
sudo tail -f /var/log/morpheus/morpheus-ui/current | grep -iE 'argus|ilo|credential'
```

Common log patterns to look for:

| Log line | Meaning |
| --- | --- |
| `argus.launch.method=sessionkey-ready ...` | Sessionkey mint succeeded; Launch Console wired to the new path. |
| `argus.launch.method=sessionkey-mint-failed ...` | Mint failed; tab fell back to the v0.1.35 cookie path. |
| `argus: launch token mint threw: ...` | Unexpected exception during mint — credential or network issue. |
| `argus: readArgusSettings failed, using defaults: ...` | Plugin settings access broke. Verify plugin-api version. |
| `loadConfig threw for server ...` | Label collection blew up. Probably a Morpheus-version compatibility issue. |
| `iLO ${iloHost}: session login failed` | Direct Redfish auth fail. Same as a `401` in [Section 6](#section-6--validate-the-credential-directly-against-ilo-redfish). |

---

## Section 8 — Plugin API quirks to know about

Non-obvious behaviors documented for maintainers:

- **`accountCredential.get(Long)` is broken** in plugin-api 1.3.1 —
  returns null even for valid IDs. The plugin uses
  `accountCredential.listById(id)` + `loadCredentialConfig(cred)` instead.
  Mentioned here so any workaround scripts don't try `.get()` and confuse
  the diagnosis.
- **Morpheus CSP nonce.** All Launch / Open buttons use `addEventListener`,
  never inline `onclick=`. The plugin reads the nonce via
  `Class.forName('org.springframework.web.context.request.RequestContextHolder')`.
- **Correct iLO 6 Gen11 console URL is `/irc.html`**, not `/html5/irc.html`
  (which 404s on iLO 6).
- **After re-uploading the plugin, fully uninstall the prior version
  first** (Admin → Plugins → Uninstall) to avoid duplicate iLO tabs on
  hosts. See `TROUBLESHOOTING.md` for the symptom.

---

## Quick reference — the full command set

```bash
# Env
export MORPHEUS_URL="https://morpheus.example.com"
export TOKEN="<bearer-token>"

# 1. List all credentials
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/credentials?max=100" \
  | jq '.credentials[] | {id, name, type: .type.code, enabled}'

# 2. Inspect single credential
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/credentials/<ID>" \
  | jq '.credential | {id, name, type: .type.code, enabled, accountId: .account.id}'

# 3. Filter to username-password type only
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/credentials?type=username-password&max=100" \
  | jq '.credentials[] | {id, name}'

# 4. Get host's labels and tenant
curl -sk -H "Authorization: Bearer $TOKEN" \
  "$MORPHEUS_URL/api/servers/<SERVER_ID>" \
  | jq '.server | {id, name, labels, accountId: .account.id}'

# 5. Validate credential against iLO directly
curl -sk -u "<ilo-user>:<ilo-pass>" \
  "https://<ilo-ip>/redfish/v1/Systems/1" \
  | jq '{Name, Model, PowerState, Status}'

# 6. Tail UI log
sudo tail -f /var/log/morpheus/morpheus-ui/current | grep -iE 'argus|ilo|credential'
```

---

## When to file a bug

After walking through Sections 1–7, if:

- Credential exists, is `username-password`, enabled, in the same tenant
  as the host
- Labels are three separate pills (not one big string)
- `curl` against iLO Redfish with the same credentials succeeds
- `credSource` is still empty in the Diagnostics panel

…that's a real plugin bug. File an issue at
[github.com/builtbyfood/morpheus-argus/issues](https://github.com/builtbyfood/morpheus-argus/issues)
with:

1. Diagnostics panel screenshot (scrub IPs/serials first per
   `SCREENSHOT_GUIDE.md`)
2. Morpheus version + plugin-api version (Admin → Plugins → details
   panel)
3. Plugin version (Diagnostics header)
4. iLO firmware version (visible in the System card)
5. Relevant UI log lines (command 6 above)
