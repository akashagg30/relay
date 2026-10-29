# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.116] - 2026-10-30

**Tree completeness — the accessibility tree must give ALL the info (vision-free operation).**

`observe`/`find` used to lose information that was present in the raw accessibility
tree, which forced callers to fall back to screenshots and vision. On Play Store
search results, row titles ("Claude by Anthropic") never appeared in `observe`
while a screenshot showed them plainly.

### Fixed
- **No node merging across elements.** The projector merged consecutive text nodes
  into one element (`mergeConsecutiveLabels`), keeping the first node's id and rect
  and demoting the second node's text to a `value` field. On Play results the
  "Sponsored" chip swallowed the row title, so the title was invisible to `observe`
  and unfindable by `find`. Distinct nodes now stay distinct, each with its own id
  and rect.
- **A container with no usable rect no longer erases its subtree.** A visible node
  reporting a 0x0 rect returned `null` and took every descendant with it; that is
  how Play home and Wikipedia collapsed to one or two elements (R-5). The subtree is
  projected and the container is reported in `treeIncomplete`.
- **Controls no longer swallow their children.** `click_node` targets (actions,
  toggles, tabs, inputs, players) dropped all descendants, so a nested "Install"
  button inside a clickable result card was unreachable. Every element keeps its
  children.
- **`find` searches every field**, not just title/summary: text, contentDescription,
  value, placeholder, viewId and element id, across all retained elements including
  nested ones. Exact title matches rank first. Response gains `count` and `matches[]`.
- **`longClickable` rows stay actionable** instead of degrading to a nameless section.

### Added
- Every element now carries `viewId` (the Android resource id) when the view exposes
  one — a handle that survives snapshot churn, unlike the per-snapshot element id.
- `treeIncomplete` — an optional top-level key in the `observe` envelope, present only
  when the projection had to leave something out: `{id, nodeId, reason, text?,
  className?}` with reason `degenerate_bounds`, `actionable_without_identity` or
  `focusable_without_identity` (bounded to 40 entries). A complete tree keeps the
  exact 1.0.113 key set.
- `mergedFrom` on elements that absorbed another node (label → icon button), so a
  merge is never silent.
- **Raw-vs-projection diff harness** (`TreeCompletenessTest`) over committed raw
  `get_screen_state` fixtures for Play results, Play home, Wikipedia article and MIUI
  Settings: it fails if an actionable node is dropped, if a visible text is not
  findable, if a retained visible element has no pixel rect, or if a viewId is lost.
- `get_screen_state` no longer pre-filters longClickable / focusable-leaf nodes out of
  the raw dump it is judged on.

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
