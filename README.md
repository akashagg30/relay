# Android Agent — MCP Server

An Android AccessibilityService that exposes phone inspection and control as MCP tools for external AI agents.

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

1. Open **Android Agent** app
2. Tap **OPEN ACCESSIBILITY SETTINGS**
3. Find **Android Agent** → toggle **ON**
4. Confirm the permission dialog

## Start MCP Server

1. In the app, tap **START SERVER**
2. The app shows: Status, Port, Auth Token, Endpoint
3. The server runs as a foreground service (persistent notification)

## Find Phone IP

- **Settings → Wi-Fi → tap connected network → IP address**
- Or the app displays it in the MCP Server card

## Authentication

- A random 32-byte token is generated on first launch
- Stored in app SharedPreferences
- Displayed in the debug UI — tap **COPY TOKEN**
- Required for all MCP requests via `Authorization: Bearer <token>`

## MCP Endpoint

```
POST http://<PHONE_IP>:8765/mcp
```

## Health Check

```bash
curl http://<PHONE_IP>:8765/health
```

Response:
```json
{
  "status": "ok",
  "accessibility": true,
  "foregroundPackage": "com.android.settings",
  "mcpServer": true,
  "port": 8765
}
```

## Available MCP Tools

| Tool | Input | Description |
|------|-------|-------------|
| `get_screen_state` | — | Returns current foreground app accessibility tree as JSON |
| `click_node` | `nodeId` | Clicks a node (walks up to clickable ancestor if needed) |
| `tap` | `x`, `y` | Taps at screen coordinates |
| `swipe` | `startX`, `startY`, `endX`, `endY`, `durationMs` | Swipes between two points |
| `input_text` | `nodeId`, `text` | Types text into an editable node |
| `back` | — | Performs GLOBAL_ACTION_BACK |
| `home` | — | Performs GLOBAL_ACTION_HOME |
| `launch_app` | `packageName` | Launches an app by package name |
| `wait` | `milliseconds` | Waits (max 10s) for UI transitions |

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

### Get Screen State

```bash
curl -X POST http://<PHONE_IP>:8765/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"jsonrpc":"2.0","id":"3","method":"tools/call","params":{"name":"get_screen_state","arguments":{}}}'
```

### Click a Node

```bash
curl -X POST http://<PHONE_IP>:8765/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"jsonrpc":"2.0","id":"4","method":"tools/call","params":{"name":"click_node","arguments":{"nodeId":27}}}'
```

## Example MCP Client Configuration

For Claude Desktop or similar MCP clients, add to your config:

```json
{
  "mcpServers": {
    "android-agent": {
      "url": "http://<PHONE_IP>:8765/mcp",
      "headers": {
        "Authorization": "Bearer <YOUR_TOKEN>"
      }
    }
  }
}
```

## Node Resolution

Node IDs are stable within a single `get_screen_state` response. Between calls:

1. `get_screen_state` captures the tree and stores node descriptors (viewId, text, class, bounds)
2. `click_node(id)` resolves the descriptor against the **current** `rootInActiveWindow`
3. If the screen changed and the node can't be matched → returns `stale_node` error
4. The agent should call `get_screen_state` again to get fresh IDs

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
│   ├── AuthManager.kt          # Token generation & validation
│   ├── McpHandler.kt           # JSON-RPC tool dispatch
│   ├── McpServerService.kt     # Foreground service + HTTP server
│   └── NodeResolver.kt         # Stale node resolution
├── model/
│   └── AccessibilityNodeData.kt
├── service/
│   ├── AccessibilityTreeReader.kt
│   └── AgentAccessibilityService.kt
└── ui/
    └── DebugScreen.kt
```
