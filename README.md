# Relay — Let AI Agents Test Your Android Apps

[![License](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)

Relay is an MCP server that lets AI agents explore, interact with, and test Android apps. No test scripts needed — just natural language instructions to an AI agent.

**Open Source (AGPL-3.0)** — Free to use, modify, and self-host. If you offer Relay as a hosted or managed service to others, you must publish your source under the same license. Premium features (Relay Cloud, enterprise, managed hosting) will be proprietary.

**Commercial licensing** — If AGPL doesn't work for your use case, a commercial license is available. Contact: agarwal.akash30@gmail.com

## Use Cases

- **AI-driven regression testing** — Let AI agents re-run test flows after every build
- **Automated app exploration and bug finding** — Agents navigate apps to discover crashes and UX issues
- **Natural language test cases (no code)** — Write test scenarios in plain English, execute via AI
- **CI/CD integration for mobile QA** — Plug into existing pipelines for automated mobile testing
- **Accessibility testing via AI** — Agents validate app accessibility with real device interactions

## Why Relay?

Relay is **built specifically for testing**, not general-purpose remote control. While other MCP servers offer 50+ tools for controlling any app, Relay focuses on 28 tools optimized for QA workflows.

**Key differentiators:**
- **Testing-first design** — Every tool is designed for QA use cases
- **Semantic understanding** — `observe()` tool provides higher-level scene analysis for smarter test automation
- **A documented contract** — the 28-tool surface is the same list in `tools/list`, the README and the dispatch table (enforced by `ToolRegistryContractTest`)
- **CI/CD ready** — Built for automated testing pipelines

If you need general-purpose phone control, there are other options. If you need **reliable, AI-powered testing**, Relay is built for you.

## Architecture

```
MCP Client / AI Agent
        |
        | HTTP + JSON-RPC
        v
  McpServerService (port 8765)
        |
        v
  McpHandler (tool dispatch)
        |
        v
  NodeResolver (stale-node resolution)
        |
        v
  AccessibilityController
        |
        v
  AgentAccessibilityService
        |
        v
   Android Apps
```

## How to Build

```bash
# Local (requires Android SDK)
export ANDROID_HOME=~/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew assembleDebug

# Or push to GitHub — Actions builds automatically
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## How to Install

```bash
# USB
adb install app/build/outputs/apk/debug/app-debug.apk

# Wireless (after setup)
adb connect <PHONE_IP>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Enable Accessibility Service

1. Open **Relay** app
2. Tap **OPEN ACCESSIBILITY SETTINGS**
3. Find **Relay** → toggle **ON**
4. Confirm the permission dialog

## Start MCP Server

1. In the app, tap **START SERVER**
2. The app shows: Status, Port, Auth Token, Endpoint
3. The server runs as a foreground service (persistent notification)

## Find Phone IP

- **Settings → Wi-Fi → tap connected network → IP address**
- Or the app displays it in the MCP Server card

## Authentication

- A random 128-bit token (32 hex characters) is generated on first launch
- Stored in app SharedPreferences, displayed in the debug UI — tap **COPY TOKEN**
- Required on **every** `/mcp` request, `GET` included: `Authorization: Bearer <token>`
  (a GET without the token returns `401`, exactly like POST — it is the same data)
- Rotating the token in the app takes effect on the next request; `/mcp/status`
  shows the rotation counter and a token fingerprint so a client can tell
  "my token is old" apart from "I am rate limited"

## MCP Endpoint

```
POST http://<PHONE_IP>:8765/mcp   # JSON-RPC (SSE when Accept: text/event-stream)
GET  http://<PHONE_IP>:8765/mcp   # legacy HTTP+SSE transport — token required
```

## Health Check

```bash
curl http://<PHONE_IP>:8765/health
```

Response:
```json
{
  "status": "ok",
  "version": "1.0.117",
  "accessibility": true,
  "accessibilityRunning": true,
  "foregroundPackage": "com.android.settings",
  "mcpServer": true,
  "port": 8765,
  "statusEndpoint": "/mcp/status"
}
```

## Status / Diagnosis

`GET /mcp/status` is public on purpose: when a client cannot authenticate it
still needs to know **why**. It never returns the token — only a sha-256
fingerprint (first 12 hex) that a client can compare against its own copy.

```bash
curl http://<PHONE_IP>:8765/mcp/status
```

```json
{
  "status": "degraded",
  "version": "1.0.117",
  "protocolVersion": "2025-06-18",
  "uptimeSec": 4210,
  "server": { "running": true, "port": 8765 },
  "accessibility": { "enabled": true, "running": true },
  "auth": {
    "required": true,
    "tokenProvided": true,
    "authenticated": false,
    "tokenFingerprint": "3f9c1a0b7d22",
    "rotationCount": 1,
    "lastRotatedAt": 1761000000000
  },
  "rateLimit": { "requestsPerSecond": 30, "blockedClients": 0 },
  "clients": { "approved": 2 },
  "session": { "mode": "stateless", "lock": "none" },
  "tunnel": { "running": false, "url": null },
  "causes": ["token_mismatch_or_rotated"],
  "hint": "hash the token you hold and compare with auth.tokenFingerprint; a different value means the phone rotated it (rotationCount=1) — re-read it from the Relay app"
}
```

`causes` is the part to branch on: `server_stopped`, `accessibility_not_running`,
`token_not_provided`, `token_mismatch_or_rotated`, `rate_limited`. There is no
server-side session to be stuck on — every request authenticates on its own.

## Available MCP Tools

`tools/list` advertises exactly 28 tools. This table is the same list — if the
two ever diverge, `ToolRegistryContractTest` fails the build.

| Tool | Input | Description |
|------|-------|-------------|
| `click_node` | `id` | Clicks a node (walks up to clickable ancestor if needed). Legacy aliases `elementId`/`nodeId` also accepted |
| `swipe` | `startX`, `startY`, `endX`, `endY`, `durationMs` | Swipes between two points |
| `input_text` | `id`, `text` | Types text into an editable node. Legacy aliases `elementId`/`nodeId` also accepted for the id |
| `press_key` | `key` | Presses a key (`enter`, `back`, `home`, `tab`, ...) |
| `submit` | — | Submits the focused field via the IME action, then a submit button, then the input's trailing edge |
| `back` | — | Performs GLOBAL_ACTION_BACK |
| `home` | — | Performs GLOBAL_ACTION_HOME |
| `launch_app` | `packageName` | Launches an app by package name |
| `wait` | `milliseconds` | Waits (max 10s) for UI transitions |
| `list_apps` | — | Returns launchable applications installed on the device |
| `search_apps` | `query` | Searches installed applications by name |
| `open_app` | `name` | Opens an installed application by human-readable name (returns `ambiguous_app` instead of guessing) |
| `current_app` | — | Returns the currently foreground application |
| `take_screenshot` | — | Base64 PNG of the current screen; two bounded attempts, typed `screenshot_failed` envelope on failure |
| `screenshot_with_overlay` | — | Screenshot with element bounding boxes and ids drawn on top |
| `get_app_policy` | — | Read-only view of the user's app access policy (`protected` / `alwaysBlocked` are unreadable and unactionable) |
| `get_audit_log` | `limit` | Recent tool calls with success/failure, method and timing |
| `get_audit_summary` | — | Aggregate success rate and average timing per tool |
| `get_logs` | `limit` | Recent local log entries |
| `share_logs` | — | Opens the Android share sheet with the log file |
| `set_log_sync` | `enabled` | Opt in/out of cloud log sync |
| `get_log_sync` | — | Current sync state and endpoint |
| `set_sync_endpoint` | `url` | Sets the cloud sync endpoint |
| `observe` | — | Returns a semantic representation of the current screen (roles, ids, pixel bounds, `viewId`). Top-level keys are always `app`, `screenTitle`, `scrollable`, `screenHash`, `elements`, `screenState`. One optional key, `treeIncomplete`, appears **only** when the tree could not represent something (a node with no usable rect, or an actionable node with no text/description/viewId) — so a gap is never silent. `screenTitle` is `"Unknown"` when no title candidate (TextView/Toolbar/ActionBar) was found within depth 10 of the window root; normal for full-screen apps/games |
| `find` | `text`, `role` | Searches the last `observe()` result for a matching element. Matches text, contentDescription, value, placeholder, `viewId` and element id across every retained element, nested ones included; exact title matches rank first. Returns `found`, `count`, `matches[]` (id/role/title/viewId/bounds, up to 10) and the best `element` |
| `scroll_until` | `text`, `direction`, `maxScrolls` | Scrolls until an element with matching text is visible |
| `diag_sealed` | `elementId` | Diagnostic: tests node sealed lifecycle (development aid) |
| `act_toward_goal` | `goal`, `maxSteps`, `confidenceThreshold`, `pauseBeforeKeywords` | Bounded on-device perceive-decide-act loop; returns one of `done\|escalate\|paused\|stuck\|blocked\|max_steps` (`escalate` includes fresh element IDs ready for immediate use) |

### Legacy compatibility

`get_screen_state` is the pre-`observe` tool. It is **not** advertised in
`tools/list` anymore, but it still answers for old clients; it is declared in
`COMPAT_ALIAS_TOOL_NAMES` so it can never drift back into the advertised
surface unnoticed. New code should call `observe`.

`tap` (raw x/y tap) is not part of the server surface; use `click_node` with an
element id, which resolves against the live tree instead of tapping pixels that
may have moved.

## Example MCP Request

### Initialize

```bash
curl -X POST http://<PHONE_IP>:8765/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"jsonrpc":"2.0","id":"1","method":"initialize","params":{}}'
```

### List Tools

```bash
curl -X POST http://<PHONE_IP>:8765/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/list","params":{}}'
```

### Observe The Screen

```bash
curl -X POST http://<PHONE_IP>:8765/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ***" \
  -d '{"jsonrpc":"2.0","id":"3","method":"tools/call","params":{"name":"observe","arguments":{}}}'
```

### Click a Node

```bash
curl -X POST http://<PHONE_IP>:8765/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"jsonrpc":"2.0","id":"4","method":"tools/call","params":{"name":"click_node","arguments":{"id":"1:7"}}}'
```

## Example MCP Client Configuration

For Claude Desktop or similar MCP clients, add to your config:

```json
{
  "mcpServers": {
    "relay": {
      "url": "http://<PHONE_IP>:8765/mcp",
      "headers": {
        "Authorization": "Bearer <YOUR_TOKEN>"
      }
    }
  }
}
```

## Node Resolution

Node IDs are stable within a single `observe` response. Between calls:

1. `observe` captures the tree and stores node descriptors (viewId, text, class, bounds)
2. `click_node(id)` resolves the descriptor against the **current** `rootInActiveWindow`
3. If the screen changed and the node can't be matched → returns `stale_node` error
4. The agent should call `observe` again to get fresh IDs

## Network Setup

For another device on the same Wi-Fi to connect:

1. Phone and client must be on the same network
2. Some routers isolate Wi-Fi clients — disable "AP Isolation" if present
3. Firewall on the phone should allow port 8765

## Security Notes

- Server defaults to local/private network only
- No internet-facing exposure
- Token required for all requests
- Token not logged
- Server must be manually started
- Foreground notification shows when server is running

## Project Structure

```
app/src/main/java/com/agent/accessibility/
├── MainActivity.kt
├── controller/
│   └── AccessibilityController.kt
├── mcp/
│   ├── AppRegistry.kt          # Installed-app registry, search & launch
│   ├── AuditLogger.kt          # Audit logging of MCP requests
│   ├── AuthManager.kt          # Token generation & validation
│   ├── McpHandler.kt           # JSON-RPC protocol dispatch & response framing
│   ├── McpServerService.kt     # Foreground service + HTTP server
│   ├── NodeResolver.kt         # Stale node resolution
│   ├── RateLimiter.kt          # Per-request rate limiting
│   ├── ServerDiagnostics.kt    # /mcp/status document + one auth policy for all routes
│   ├── AuthManager.kt          # Token generation, rotation & validation
│   ├── SemanticProjector.kt    # Projects UI tree to semantic scene
│   ├── SemanticScene.kt        # Semantic scene data model
│   ├── ToolRegistry.kt         # MCP tool definitions / schemas (tools/list)
│   ├── ObservationTools.kt     # get_screen_state, observe, find, scroll_until, diag_sealed
│   ├── InteractionTools.kt     # click_node, tap, swipe, input_text, back, home
│   └── AppTools.kt             # launch_app, list_apps, search_apps, open_app, current_app, wait
├── model/
│   └── AccessibilityNodeData.kt
├── service/
│   ├── AccessibilityTreeReader.kt
│   └── AgentAccessibilityService.kt
└── ui/
    └── DebugScreen.kt
```
