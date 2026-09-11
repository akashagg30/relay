# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

**License: Apache 2.0 → AGPL-3.0**

Relay is now licensed under the GNU Affero General Public License v3.0 or later.

- Free to use, modify, and self-host for any purpose, including commercial.
- If you offer Relay (or a modified version) as a hosted or managed service to
  third parties, you must make your source available under the same license.
- A commercial license is available for organizations that cannot comply with
  AGPL terms. Contact: agarwal.akash30@gmail.com

Releases **v1.1.0 and earlier remain Apache 2.0** — that grant is irrevocable for
anyone who obtained those versions. AGPL-3.0 applies from this release forward.

- Split the ~63KB `McpHandler.kt` god-file into focused modules under
  `app/src/main/java/com/agent/accessibility/mcp/`:
  - `McpHandler.kt` — JSON-RPC protocol dispatch and response framing
  - `ToolRegistry.kt` — tool definitions / schemas served by `tools/list`
  - `ObservationTools.kt` — `get_screen_state`, `observe`, `find`, `scroll_until`, `diag_sealed`
  - `InteractionTools.kt` — `click_node`, `tap`, `swipe`, `input_text`, `back`, `home`
  - `AppTools.kt` — `launch_app`, `list_apps`, `search_apps`, `open_app`, `current_app`, `wait`

  Tool implementations moved verbatim as extension functions; no behavior change.

### Fixed
- CI (`.github/workflows/build.yml`) now runs the unit test suite
  (`./gradlew testDebugUnitTest`) on every push and pull request, and uploads
  test reports as artifacts. Previously only lint and the APK build ran.
- README documented 14 tools but listed only 9. The table now lists all 17
  tools and the prose count matches the code (`tools/list` is the source of truth).

## [1.0.0] - 2026-08-06

### Added
- Initial release: an MCP server that lets AI agents explore, interact with,
  and test Android apps over HTTP + JSON-RPC (port 8765).
- 17 MCP tools covering screen observation, UI interaction, and app management.
- Accessibility-service automation with stale-node resolution and re-snapshot retry.
- Bearer-token authentication, rate limiting, and audit logging.
- Semantic screen projection (`observe`) for higher-level scene reasoning.
