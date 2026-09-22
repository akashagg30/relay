# App Access Policy — implementation report

Commit: `7b37d3a` (build passing)
Feature: user-controlled per-app permissions for the Relay MCP agent.

## What it does

Blocked apps are **both unreadable and untouchable**. Excluding an app but still
letting the agent screenshot and parse its screen would leak exactly the same data
that acting on it would, so reads are gated alongside actions.

## Files changed

| File | Change |
|------|--------|
| `mcp/AppPolicy.kt` | **new** — policy storage, `isAllowed()` decision, invariant sets, gated-tool set, `get_app_policy` handler |
| `mcp/McpHandler.kt` | policy gate inserted in `handleToolsCall` before dispatch; `get_app_policy` dispatch entry |
| `mcp/ToolRegistry.kt` | `get_app_policy` tool schema |
| `ui/DebugScreen.kt` | `AppPolicyCard` + `AppPickerDialog`, rendered in the settings list |

## Enforcement points

One choke point: `McpHandler.handleToolsCall`, before the `when (toolName)` block.
Nothing is enforced inside individual tool implementations, so a tool cannot be
accidentally added to the surface without passing the gate.

On a block the tool returns:

```
policy_blocked: <package> is excluded by your app policy. Change it in the Relay app under App Access.
```

The literal `policy_blocked:` prefix is intentional — it tells the calling agent
this is a user decision rather than a bug, so it stops retrying. The block is also
written to the audit log with method `policy_blocked`.

## Decision order (`AppPolicy.isAllowed`)

1. package null/blank → **allow** (an unknown foreground must not brick the tool)
2. in `alwaysBlocked` → **DENY** (highest priority, wins over everything)
3. in `alwaysAllowed` → allow
4. in `protectedPackages` → deny
5. mode `ALLOWLIST` → allow only if explicitly listed
6. otherwise → allow

## Invariant sets (not user-editable)

- `alwaysBlocked` = `context.packageName` — **Relay itself**
- `alwaysAllowed` = `com.android.systemui` — blocking it breaks the notification shade

**Why Relay blocks itself.** If the agent could act on Relay's own UI it could
escalate privilege: disable the accessibility service, read the auth token off the
debug screen, or tap through and rewrite this policy. That would make
"the agent can never write policy" true on the API surface but false in practice.
Blocking Relay is what actually makes it hold. It is shown in the UI as a
permanently-locked row with no remove control, and "Restore defaults" cannot
remove it. Even if it were absent from the stored set, rule 2 above still blocks it.

## Gated vs never gated

**Gated** (13): `click_node`, `swipe`, `input_text`, `press_key`, `submit`,
`observe`, `find`, `scroll_until`, `get_screen_state`, `take_screenshot`,
`screenshot_with_overlay`, `launch_app`, `open_app`

**Never gated** (15): `back`, `home`, `current_app`, `wait`, `list_apps`,
`search_apps`, `diag_sealed`, `get_audit_log`, `get_audit_summary`,
`get_log_sync`, `set_log_sync`, `set_sync_endpoint`, `get_logs`, `share_logs`,
`get_app_policy`

`back` and `home` are deliberately ungated. If the agent lands inside a blocked
app it must still be able to leave, otherwise the phone is trapped and the user
has to force-stop Relay by hand.

Target resolution: `launch_app` reads its `packageName` argument; `open_app`
resolves its app-name argument to a package via `AppRegistry.findBestMatch`;
every other gated tool checks the current foreground package via
`findForegroundRoot`. An unresolvable target is allowed through rather than
blocked, so a lookup failure never bricks a tool.

## Seeded sensitive defaults

Enabled by default. Seeded lazily on first policy read; if the user later empties
the list it is **not** silently repopulated.

- **Password managers:** `com.x8bit.bitwarden`, `com.onepassword.android`,
  `com.agilebits.onepassword`, `com.lastpass.lpandroid`, `com.keepassdroid`
- **Authenticators:** `com.google.android.apps.authenticator2`, `com.authy.authy`,
  `com.microsoft.authenticator`, `com.azure.authenticator`,
  `org.fedorahosted.freeotp`, `com.duosecurity.duomobile`
- **UPI / payments (India):** `com.google.android.apps.nbu.paisa.user`,
  `com.phonepe.app`, `net.one97.paytm`, `com.dreamplug.androidapp`, `com.downy.app`
- **Banking (India):** `com.sbi.lotusintouch`, `com.snapwork.hdfc`,
  `com.csam.icici.bank.imobile`, `com.axis.mobile`, `com.msf.kbank.mobile`,
  `com.idfcfirstbank.optimus`, `com.bankofbaroda.mconnect`

Deliberately **excluded**: `com.google.android.gms`. Play Services backs a large
number of unrelated apps, so blocking it would break far more than it protects.

## Read-only guarantee

There is **no** `set_app_policy` tool. `get_app_policy` is read-only. Neither an
agent nor anyone holding the bearer token can widen its own access — policy
changes happen only in the phone UI.

## Verification status — read this honestly

**Verified:**
- Compiles; GitHub Actions build passes (run on commit `7b37d3a`).
- `get_app_policy` is registered in the tool registry and dispatched.
- Gate is placed before the dispatch `when`, so all 13 gated tools route through it.

**NOT verified — requires the device:**
- No on-device test was run. I could not reach the phone; the MCP server at
  `192.168.1.8:8765` requires a bearer token I do not hold.
- Not yet confirmed on hardware: that the gate actually returns
  `policy_blocked:` for a protected app, that `back`/`home` still escape a
  blocked app, that the UI cards render correctly, and that the seeded list
  appears on first launch.

Those are the next checks, and they need the APK installed.
