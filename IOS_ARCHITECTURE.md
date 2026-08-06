# iOS Relay — Architecture Plan

## Executive Summary

**Key Finding**: A direct iOS port of Android Relay is **impossible** due to fundamental iOS security restrictions. However, a hybrid architecture combining iOS App + Mac companion can deliver significant automation capabilities.

**Recommendation**: Build a **Hybrid iOS Relay** that combines limited iOS-native capabilities with a Mac-based XCUITest host for full automation.

## 1. iOS vs Android: Technical Capabilities Assessment

### What Android Relay Can Do (But iOS Cannot)

| Android Capability | iOS Equivalent | Status | Notes |
|-------------------|----------------|---------|-------|
| `AccessibilityService` | None | ❌ **Impossible** | iOS has no equivalent for 3rd-party apps |
| Cross-app UI inspection | None | ❌ **Impossible** | iOS sandboxing prevents this |
| Background HTTP server | Limited | ⚠️ **Restricted** | Only when app is active/backgrounded briefly |
| `performAction()` on other apps | None | ❌ **Impossible** | iOS doesn't allow cross-app interaction |
| Install/launch arbitrary apps | `LSApplicationWorkspace` | ⚠️ **Private API** | Requires jailbreak or enterprise cert |
| System-wide gesture injection | None | ❌ **Impossible** | No equivalent to Android's gesture APIs |

### What iOS Can Do (That Android Relay Uses)

| Android Tool | iOS Equivalent | Feasibility | Implementation |
|--------------|----------------|-------------|----------------|
| `tap(x, y)` | XCUITest only | ⚠️ **Mac Required** | Via XCUITest on Mac host |
| `swipe()` | XCUITest only | ⚠️ **Mac Required** | Via XCUITest on Mac host |
| `wait()` | `DispatchQueue` | ✅ **Possible** | Native iOS implementation |
| `list_apps()` | `LSApplicationWorkspace` | ⚠️ **Private API** | Jailbreak/enterprise only |
| `current_app()` | App state only | ⚠️ **Limited** | Only own app state |

### iOS-Specific Capabilities (Not in Android Relay)

| Capability | API | Notes |
|------------|-----|-------|
| Shortcuts automation | `Intents` framework | Limited to specific app actions |
| Siri integration | `SiriKit` | Voice-triggered shortcuts |
| Widget automation | `WidgetKit` | Limited interaction |
| App Clips | `App Clip` | Lightweight app experiences |
| Screen Time data | `DeviceActivity` | Usage analytics (read-only) |

## 2. Recommended Architecture: Hybrid iOS + Mac

```
AI Agent / MCP Client
        |
        | HTTP POST (JSON-RPC 2.0)
        v
┌─────────────────────────┬─────────────────────────┐
│     iOS Device          │      Mac Host           │
│                         │                         │
│  iOS Relay App          │  Mac Relay Companion    │
│  ├─ Limited MCP Server  │  ├─ Full MCP Server     │
│  ├─ App management      │  ├─ XCUITest Runner     │
│  ├─ Shortcuts bridge    │  ├─ iOS device proxy    │
│  └─ Screen recording    │  └─ UI automation       │
│                         │                         │
└─────────────────────────┴─────────────────────────┘
        |                          |
        └──────── USB/WiFi ────────┘
```

### Architecture Components

#### iOS Relay App (iPhone/iPad)
- **Purpose**: Expose iOS-native capabilities via MCP
- **Runs**: On iOS device
- **Capabilities**: Limited to sandboxed operations
- **Tech Stack**: Swift, SwiftUI, URLSession for HTTP

#### Mac Relay Companion
- **Purpose**: Full automation via XCUITest
- **Runs**: On Mac connected to iOS device
- **Capabilities**: Complete UI automation
- **Tech Stack**: Swift, XCTest, DeviceController

## 3. MCP Tool Mapping: Android → iOS

### ✅ Direct iOS Implementation (iOS App)

| Android Tool | iOS Implementation | Notes |
|-------------|-------------------|-------|
| `wait` | `Task.sleep()` | Simple delay |
| `current_app` | App state tracking | Limited to own app |
| Health endpoint | Native HTTP server | While app is active |

### ⚠️ Mac-Required Implementation (Mac Companion)

| Android Tool | iOS Implementation | Notes |
|-------------|-------------------|-------|
| `get_screen_state` | XCUITest hierarchy | Mac + connected device |
| `observe` | XCUITest semantic parsing | Mac + connected device |
| `click_node` | XCUITest tap | Mac + connected device |
| `tap` | XCUITest coordinate tap | Mac + connected device |
| `swipe` | XCUITest swipe | Mac + connected device |
| `input_text` | XCUITest text input | Mac + connected device |
| `back` | XCUITest navigation | Mac + connected device |
| `home` | Device button simulation | Mac + connected device |

### ❌ Impossible on iOS

| Android Tool | iOS Status | Alternative |
|-------------|------------|-------------|
| `launch_app` | No public API | User must launch manually |
| `list_apps` | Private API only | Shortcuts app list (limited) |
| `search_apps` | Private API only | Shortcuts search (limited) |
| `open_app` | No cross-app launch | URL schemes only (limited) |

### 🆕 iOS-Only Additions

| New Tool | Purpose | Implementation |
|----------|---------|----------------|
| `run_shortcut` | Execute iOS Shortcuts | `INVoiceShortcutCenter` |
| `trigger_siri` | Voice command | `INVoiceShortcutCenter` |
| `get_widgets` | Widget information | `WidgetCenter` queries |
| `screen_recording_start/stop` | Capture sessions | `ReplayKit` |
| `get_usage_data` | App usage stats | `DeviceActivity` (if permitted) |

## 4. Implementation Phases

### Phase 1: iOS App Foundation (2-3 weeks)
**Goal**: Basic iOS MCP server with limited tools

**Deliverables**:
- Swift iOS app with HTTP server
- MCP JSON-RPC 2.0 implementation
- Tools: `wait`, `current_app`, health endpoint
- Basic auth (bearer token)
- Foreground/background lifecycle management

**Technical Challenges**:
- iOS background execution limits
- HTTP server lifecycle
- App Store compliance

### Phase 2: Mac Companion (4-5 weeks)
**Goal**: XCUITest-based full automation

**Deliverables**:
- Mac Swift app with XCUITest integration
- Connected device management
- Tools: `get_screen_state`, `tap`, `click_node`, `swipe`, `input_text`
- iOS ↔ Mac communication bridge
- Element resolution strategy (similar to Android)

**Technical Challenges**:
- XCUITest reliability
- Device connection management
- Cross-platform MCP coordination

### Phase 3: iOS-Native Features (2-3 weeks)
**Goal**: Leverage iOS-specific capabilities

**Deliverables**:
- Shortcuts integration (`run_shortcut`)
- Screen recording (`ReplayKit`)
- Siri integration basic framework
- Widget enumeration
- Usage data (if possible)

**Technical Challenges**:
- Shortcuts security model
- ReplayKit permissions
- Widget API limitations

### Phase 4: Hybrid Coordination (3-4 weeks)
**Goal**: Seamless iOS+Mac operation

**Deliverables**:
- Intelligent tool routing (iOS vs Mac)
- Unified MCP interface
- Failover mechanisms
- Performance optimization
- Error handling across devices

**Technical Challenges**:
- Latency management
- State synchronization
- Connection reliability

## 5. How iOS Version Complements Android

### Cross-Platform AI Agent Benefits
- **Device Choice**: Agents can target iOS or Android based on app availability
- **Ecosystem Testing**: Test workflows across both mobile platforms
- **User Preference**: Support users' preferred mobile platform
- **Feature Parity**: Similar MCP interface for consistent agent development

### iOS-Specific Value Adds
- **Shortcuts Integration**: Leverage iOS's powerful automation framework
- **Siri Integration**: Voice-activated agent workflows
- **Screen Recording**: Better session capture than Android
- **Mac Ecosystem**: Native integration with Mac-based AI workflows

### Combined Capabilities
- **Multi-Device Workflows**: Agent controls both iPhone and Mac
- **Cross-Platform Testing**: Validate app behavior on both platforms
- **Ecosystem Automation**: Combine mobile + desktop automation

## 6. What NOT to Build (Realistic Limitations)

### ❌ Don't Attempt
1. **Pure iOS Automation**: Without Mac, automation is extremely limited
2. **Jailbreak-Required Features**: Not viable for mainstream adoption
3. **Private API Usage**: Will break with iOS updates, App Store rejection
4. **Background-Always Server**: iOS will kill it, not sustainable
5. **Cross-App UI Inspection**: Technically impossible, don't try
6. **System-Level Gestures**: iOS prevents this for security

### ❌ Don't Promise
1. **Feature Parity with Android**: iOS is fundamentally more restrictive
2. **No Mac Required**: Full automation requires Mac + XCUITest
3. **App Store Distribution**: Automation apps face rejection risk
4. **Enterprise-Only Features**: Most users don't have enterprise certificates
5. **Real-Time Performance**: XCUITest has inherent latency

### ❌ Don't Overspend On
1. **Complex Workarounds**: Accept iOS limitations rather than fighting them
2. **Perfect UI Inspection**: XCUITest hierarchy is good enough
3. **Background Persistence**: Work within iOS app lifecycle model
4. **Advanced Computer Vision**: OCR/screenshot analysis (future phase)

## 7. Technical Architecture Details

### iOS App Structure
```swift
// Core MCP server
class IOSMcpServer {
    let httpServer: HTTPServer
    let toolHandler: ToolHandler
    let authManager: AuthManager
    
    // Limited iOS-native tools
    func handleWait(_ milliseconds: Int)
    func handleCurrentApp() -> AppInfo
    func handleRunShortcut(_ name: String)
}

// Mac companion bridge
class MacCompanionBridge {
    func proxyToMac(_ tool: String, _ params: [String: Any])
    func isConnected() -> Bool
}
```

### Mac Companion Structure
```swift
// XCUITest automation
class XCUITestRunner {
    let device: XCUIDevice
    
    func getScreenState() -> AccessibilityTree
    func tapElement(_ elementId: String)
    func tapCoordinates(_ x: Int, _ y: Int)
    func swipe(_ startX: Int, _ startY: Int, _ endX: Int, _ endY: Int)
}

// iOS device proxy
class IOSDeviceProxy {
    func forwardToDevice(_ tool: String, _ params: [String: Any])
    func executeOnDevice(_ tool: String, _ params: [String: Any])
}
```

### Tool Routing Logic
```swift
enum ToolExecutionTarget {
    case iOS        // Execute on iOS device
    case mac        // Execute on Mac via XCUITest
    case both       // Coordinate both devices
}

func routeTool(_ toolName: String) -> ToolExecutionTarget {
    switch toolName {
    case "wait", "current_app", "run_shortcut":
        return .iOS
    case "get_screen_state", "tap", "click_node", "swipe":
        return .mac
    case "screen_recording_start":
        return .both  // iOS records, Mac controls
    default:
        return .mac
    }
}
```

## 8. Success Metrics & Realistic Expectations

### Success Metrics
- **Tool Coverage**: 8-10 of 14 Android tools working (57-71%)
- **Latency**: <2s for XCUITest operations (vs <500ms Android)
- **Reliability**: 90%+ success rate for common operations
- **Setup Complexity**: <10 minutes for iOS app + Mac companion setup

### Realistic Expectations
- **Performance**: 2-4x slower than Android due to XCUITest overhead
- **Setup**: More complex (requires Mac) vs Android (single device)
- **Reliability**: Lower than Android due to XCUITest limitations
- **Coverage**: ~60% of Android's automation capability

### User Experience
- **Best Case**: Smooth automation for iOS-native apps with Mac setup
- **Typical Case**: Workable automation with some latency/reliability issues
- **Worst Case**: Complex setup, limited iOS-only functionality

## 9. Development Timeline

**Total Estimate**: 12-15 weeks for MVP (3-4 months)

- Phase 1 (iOS Foundation): Weeks 1-3
- Phase 2 (Mac Companion): Weeks 4-8
- Phase 3 (iOS Features): Weeks 9-11
- Phase 4 (Hybrid Polish): Weeks 12-15

**Team Requirements**:
- 1 iOS developer (Swift, iOS SDK)
- 1 Mac developer (Swift, XCUITest)
- 1 integration developer (both platforms)

## 10. Conclusion

iOS Relay is **feasible but limited**. The hybrid iOS+Mac approach can deliver meaningful automation capabilities while being honest about iOS constraints. The value proposition is iOS ecosystem integration and Mac-based workflows, not feature parity with Android.

**Recommendation**: Proceed with hybrid architecture, market as "iOS ecosystem automation" rather than "iOS phone automation," and be transparent about Mac dependency for full functionality.