# Android Agent — Project Context

## What This Is

An Android app that turns a phone into an MCP-controlled device for AI agents. An external AI can inspect the screen and perform actions (tap, swipe, type, navigate) via HTTP.

## Architecture

```
AI Agent / MCP Client
        |
        | HTTP POST (JSON-RPC 2.0)
        v
  McpServerService (:8765)
        |
        v
  McpHandler (tool dispatch)
        |
        v
  NodeResolver (stale-node matching)
        |
        v
  AgentAccessibilityService (Android AccessibilityService)
        |
        v
   Other Android Apps
```

## Current Phase

Phase 2 — MCP server implemented. No screenshots, no OCR, no autonomous planning.

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
| `app/src/main/java/com/agent/accessibility/mcp/McpHandler.kt` | JSON-RPC tool dispatch, 9 tools |
| `app/src/main/java/com/agent/accessibility/mcp/NodeResolver.kt` | Resolves node IDs against current hierarchy |
| `app/src/main/java/com/agent/accessibility/mcp/AuthManager.kt` | Token auth (dev token: `11223344`) |
| `app/src/main/java/com/agent/accessibility/service/AgentAccessibilityService.kt` | AccessibilityService, captures trees |
| `app/src/main/java/com/agent/accessibility/service/AccessibilityTreeReader.kt` | Reads AccessibilityNodeInfo into models |
| `app/src/main/java/com/agent/accessibility/model/AccessibilityNodeData.kt` | Data models: nodes, trees, snapshots |
| `app/src/main/java/com/agent/accessibility/controller/AccessibilityController.kt` | UI-facing controller (click, tap, swipe, etc.) |
| `app/src/main/java/com/agent/accessibility/ui/DebugScreen.kt` | Compose debug UI with all controls |
| `app/src/main/AndroidManifest.xml` | Permissions, service declarations |

## MCP Tools

| Tool | Input | Description |
|------|-------|-------------|
| `get_screen_state` | — | Current foreground accessibility tree as JSON |
| `click_node` | `nodeId` | Click node, walks to clickable ancestor |
| `tap` | `x`, `y` | Gesture tap at coordinates |
| `swipe` | `startX`, `startY`, `endX`, `endY`, `durationMs` | Gesture swipe |
| `input_text` | `nodeId`, `text` | ACTION_SET_TEXT on editable node |
| `back` | — | GLOBAL_ACTION_BACK |
| `home` | — | GLOBAL_ACTION_HOME |
| `launch_app` | `packageName` | Launch app via package manager |
| `wait` | `milliseconds` | Wait (max 10s) |

## MCP Protocol

- JSON-RPC 2.0 over HTTP POST
- Endpoint: `POST http://<PHONE_IP>:8765/mcp`
- Health: `GET http://<PHONE_IP>:8765/health`
- Auth: `Authorization: Bearer 11223344`
- Methods: `initialize`, `tools/list`, `tools/call`

## Auth Token

**Development:** `11223344` (hardcoded in `AuthManager.kt`)
**Production:** Will revert to random 32-byte token stored in SharedPreferences.

## Node Resolution Strategy

1. `get_screen_state` captures tree, stores descriptors (viewId, text, class, bounds) per node
2. `click_node(id)` resolves descriptor against **current** `rootInActiveWindow`
3. If screen changed → returns `stale_node` error, agent must re-fetch
4. Descriptors match by: viewIdResourceName first, then text+class, then contentDescription+class

## How the Agent Uses This

```
loop:
  state = call get_screen_state
  agent reasons about state
  agent picks action (click_node, tap, input_text, etc.)
  agent calls action
  wait 1-2 seconds
  repeat
```

## Building

```bash
export ANDROID_HOME=~/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
cd ~/akash/android-agent
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Installing

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
# Or wireless:
adb -s <PHONE_IP>:5555 install app/build/outputs/apk/debug/app-debug.apk
```

## What NOT To Change

- `AgentAccessibilityService` — handles lifecycle correctly, don't break it
- `AccessibilityTreeReader` — works, tested with real apps
- Node ID assignment — sequential, stable within one `get_screen_state` call
- Foreground service notification — required by Android for background HTTP

## What's NOT Implemented Yet

- Screenshots / MediaProjection
- OCR
- Autonomous planning inside the app
- Multiple simultaneous MCP clients
- Encrypted transport (TLS)
- Tool call rate limiting

## CI/CD

- `.github/workflows/build.yml` — lint + build on push to main
- Pre-commit hook runs `./gradlew lint` locally
- APK uploaded as GitHub Actions artifact
