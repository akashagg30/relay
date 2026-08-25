# Contributing to Relay

Thanks for your interest in contributing! This document covers the setup,
conventions, and workflow for working on Relay.

## Development Setup

**Prerequisites**

- JDK 17 (Temurin recommended)
- Android SDK with platform 35 (`ANDROID_HOME` must point at it)
- An Android device/emulator is only needed for manual end-to-end testing

```bash
git clone <your-fork-url>
cd relay
export ANDROID_HOME=~/android-sdk
./gradlew assembleDebug   # builds app/build/outputs/apk/debug/app-debug.apk
```

## Building, Testing, Linting

```bash
./gradlew assembleDebug        # compile + debug APK
./gradlew testDebugUnitTest    # run the JUnit unit tests
./gradlew lint                 # Android lint (CI fails on lint errors)
```

CI runs all three on every push and pull request — please make sure they pass
locally before opening a PR. Unit tests live in `app/src/test/java/`.

## Architecture Overview

```
AgentAccessibilityService → AccessibilityTreeReader → NodeResolver → McpHandler → McpServerService (HTTP :8765)
```

The `mcp/` package is split by concern:

| File | Responsibility |
|------|----------------|
| `McpServerService.kt` | Foreground service + HTTP server |
| `AuthManager.kt` | Bearer token generation/validation |
| `RateLimiter.kt` | Request rate limiting |
| `AuditLogger.kt` | Audit logging |
| `McpHandler.kt` | JSON-RPC protocol dispatch, response framing |
| `ToolRegistry.kt` | Tool definitions/schemas (`tools/list`) |
| `ObservationTools.kt` | Screen observation tools (`get_screen_state`, `observe`, …) |
| `InteractionTools.kt` | Interaction tools (`click_node`, `tap`, `swipe`, …) |
| `AppTools.kt` | App-management tools (`launch_app`, `list_apps`, …) |
| `NodeResolver.kt` | Stale-node resolution against fresh snapshots |
| `SemanticProjector.kt` / `SemanticScene.kt` | Semantic screen projection |
| `AppRegistry.kt` | Installed-app registry, search, launch |

Tool implementations are extension functions on `McpHandler`, grouped by
concern rather than accumulated in one class.

## Adding a New MCP Tool

1. Add its definition/schema in `ToolRegistry.kt` (`buildToolsArray()`).
2. Implement it as an extension function on `McpHandler` in the matching
   focused file (`ObservationTools.kt`, `InteractionTools.kt`, or `AppTools.kt`).
3. Register it in the `handleToolsCall` dispatch in `McpHandler.kt`.
4. Add the tool to the README's **Available MCP Tools** table.
5. Cover any pure logic with a unit test in `app/src/test/`.

## Code Style

- Official Kotlin coding conventions; 4-space indentation.
- Keep tool implementations in their focused files — don't grow `McpHandler.kt`.
- Prefer small, reviewable PRs with a single purpose.
- No new dependencies without prior discussion.

## Submitting Changes

1. Create a topic branch (`feat/my-feature`, `fix/my-bugfix`, `refactor/...`).
2. Make your change with clear commit messages.
3. Verify `assembleDebug`, `testDebugUnitTest`, and `lint` all pass.
4. Open a pull request against `main` describing what changed and why.
5. All CI checks must be green before merge.

## Reporting Bugs & Security Issues

- Bug reports and feature requests: please open a GitHub issue with
  reproduction steps and logs.
- **Security issues**: do NOT open a public issue. Report privately to the
  maintainers (see SECURITY contact in README/repository settings).

## License

By contributing, you agree that your contributions will be licensed under the
Apache License 2.0 that covers this project.
