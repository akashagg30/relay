# Relay — Project Context

## What This Is

Relay is an MCP server that lets AI agents explore, interact with, and test Android apps. No test scripts needed — just natural language instructions to an AI agent. Relay runs as an Android AccessibilityService and exposes phone inspection and control as MCP tools, allowing any AI agent to relay actions to the device via HTTP.

## Target Market

- **QA teams** — Automate regression testing with AI agents instead of brittle UI scripts
- **AI testing startups** — Build AI-powered mobile testing platforms on top of Relay
- **Mobile dev teams** — Integrate AI-driven app exploration into CI/CD pipelines
- **Accessibility consultants** — Use AI agents to validate app accessibility in real-time

## Architecture

```
AI Agent / MCP Client
        |
        | HTTP POST (JSON-RPC 2.0)
        v
  McpServerService (:8765)
        |
        v
  McpHandler (tool dispatch, 14 tools)
        |
        v
  NodeResolver (fresh traversal per action)
        |
        v
  AgentAccessibilityService (Android AccessibilityService)
        |
        v
   Other Android Apps
```

## Current Phase

Phase 3 — Security hardening complete. MCP server with authentication, rate limiting, consent prompts, and audit logging.

## Tech Stack

- Kotlin, Jetpack Compose, Android SDK 35
- No external dependencies beyond AndroidX/Compose
- Raw `java.net.ServerSocket` HTTP (no NanoHTTPD, no OkHttp)
- JSON built with `org.json` (Android built-in)
- Pre-commit hook runs `./gradlew lint`
- GitHub Actions CI builds APK on push

## Key Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/agent/accessibility/mcp/McpServerService.kt` | Foreground service, HTTP server on port 8765 |
| `app/src/main/java/com/agent/accessibility/mcp/McpHandler.kt` | JSON-RPC tool dispatch, 14 tools |
| `app/src/main/java/com/agent/accessibility/mcp/NodeResolver.kt` | Fresh traversal resolution, snapshot manager, scoring |
| `app/src/main/java/com/agent/accessibility/mcp/AuthManager.kt` | Token auth (random 32-byte token, regeneratable) |
| `app/src/main/java/com/agent/accessibility/mcp/RateLimiter.kt` | Rate limiting (30 req/sec per IP, sliding window) |
| `app/src/main/java/com/agent/accessibility/mcp/AuditLogger.kt` | Request logging (last 100 entries, SharedPreferences) |
| `app/src/main/java/com/agent/accessibility/mcp/AppRegistry.kt` | App discovery (list, search, open, current) |
| `app/src/main/java/com/agent/accessibility/mcp/SemanticProjector.kt` | Raw tree → semantic scene for observe() |
| `app/src/main/java/com/agent/accessibility/mcp/SemanticScene.kt` | Semantic data model (roles, elements, sections) |
| `app/src/main/java/com/agent/accessibility/service/AgentAccessibilityService.kt` | AccessibilityService, captures trees |
| `app/src/main/java/com/agent/accessibility/service/AccessibilityTreeReader.kt` | Reads AccessibilityNodeInfo into models |
| `app/src/main/java/com/agent/accessibility/model/AccessibilityNodeData.kt` | Data models: nodes, trees, snapshots |
| `app/src/main/java/com/agent/accessibility/controller/AccessibilityController.kt` | UI-facing controller (click, tap, swipe, etc.) |
| `app/src/main/java/com/agent/accessibility/ui/DebugScreen.kt` | Compose debug UI with all controls |
| `app/src/main/AndroidManifest.xml` | Permissions, service declarations, `<queries>` |

## MCP Tools (14)

| Tool | Input | Description |
|------|-------|-------------|
| `get_screen_state` | — | Current foreground accessibility tree as JSON |
| `observe` | — | Semantic scene (roles, sections, elements) — use instead of get_screen_state |
| `click_node` | `elementId` | Click element, fresh traversal + clickable ancestor walk |
| `tap` | `x`, `y` | Gesture tap at coordinates |
| `swipe` | `startX`, `startY`, `endX`, `endY`, `durationMs` | Gesture swipe |
| `input_text` | `elementId`, `text` | ACTION_SET_TEXT on editable element |
| `back` | — | GLOBAL_ACTION_BACK |
| `home` | — | GLOBAL_ACTION_HOME |
| `launch_app` | `packageName` | Launch app via package manager |
| `wait` | `milliseconds` | Wait (max 10s) |
| `list_apps` | — | Returns launchable applications |
| `search_apps` | `query` | Search installed apps by name |
| `open_app` | `name` | Open app by human-readable name |
| `current_app` | — | Returns currently foreground app |

## MCP Protocol

- JSON-RPC 2.0 over HTTP POST
- Endpoint: `POST http://<PHONE_IP>:8765/mcp`
- Health: `GET http://<PHONE_IP>:8765/health`
- Auth: `Authorization: Bearer <32-char-hex-token>`
- Methods: `initialize`, `tools/list`, `tools/call`

## Auth Token

**Development:** `11223344` (hardcoded in `AuthManager.kt`)
**Production:** Will revert to random 32-byte token stored in SharedPreferences.

## Security Features

- **Authentication:** 32-byte random hex token, regeneratable via debug UI
- **Rate Limiting:** 30 requests/second per IP, sliding window (429 Too Many Requests)
- **Consent Prompts:** New client IPs require approval via notification (403 Forbidden)
- **Audit Logging:** All requests logged with timestamp, IP, auth status, method, response code
- **Client Management:** Approved clients stored in SharedPreferences, removable via debug UI

## Node Resolution Strategy (Sealed Node Fix)

**Architecture**: Fresh traversal per action. No `AccessibilityNodeInfo` survives beyond the traversal that acquired it.

1. `get_screen_state` captures tree, stores **descriptors** (viewId, text, class, bounds) — NOT live nodes
2. `click_node(elementId)` calls `resolveFresh()`:
   - Acquires fresh `rootInActiveWindow`
   - Traverses tree, collects ALL nodes in a list (no recycling during traversal)
   - Finds best match by descriptor scoring
   - Recycles all non-matched nodes
   - Returns `FreshTraversalResult` with sealed nodes
3. Performs `performAction()` on the live node — **no IllegalStateException**
4. `recycleAll()` in `finally` block — every node recycled exactly once

**Element ID format**: `snapshotId:nodeId` (e.g., `"24:86"`)

**Descriptor scoring**: viewId match (+50), text match (+40), contentDescription match (+40), className match (+5). Minimum score > 5 to match.

## How the Agent Uses This

```
loop:
  state = call observe()         # or get_screen_state
  agent reasons about state
  agent picks action (click_node, tap, input_text, etc.)
  agent calls action
  wait 1-2 seconds
  repeat
```

## Testing

- **105 unit tests** in `app/src/test/` (NodeResolverTest, AppRegistryTest, SemanticProjectorTest)
- All tests passing, pre-commit lint clean
- Verified on: Xiaomi MIUI 14.0.5, Motorola

## Building

```bash
export ANDROID_HOME=~/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
cd ~/akash/relay
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Installing

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
# Or wireless:
adb -s <PHONE_IP>:41959 install app/build/outputs/apk/debug/app-debug.apk
```

## What NOT To Change

- `AgentAccessibilityService` — handles lifecycle correctly, don't break it
- `AccessibilityTreeReader` — works, tested with real apps
- Node ID assignment — sequential, stable within one `get_screen_state` call
- Foreground service notification — required by Android for background HTTP
- `resolveFresh()` lifecycle — must recycle all non-matched nodes, use `finally` for cleanup

## What's NOT Implemented Yet

- Screenshots / MediaProjection
- OCR
- Autonomous planning inside the app
- Multiple simultaneous MCP clients
- Encrypted transport (TLS)

## CI/CD

- `.github/workflows/build.yml` — lint + build on push to main
- Pre-commit hook runs `./gradlew lint` locally
- APK uploaded as GitHub Actions artifact
