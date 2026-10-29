// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Akash Agarwal
//
// This file is part of Relay, licensed under the GNU Affero General Public
// License v3.0 or later. See the LICENSE file for details.

package com.agent.accessibility.mcp

import com.agent.accessibility.model.AccessibilityNodeData
import com.agent.accessibility.model.AccessibilityTreeData

/**
 * Projects the raw accessibility tree into the semantic scene that
 * `observe`/`find` expose (R-15: tree completeness — vision-free operation).
 *
 * ## Retention contract
 *
 * The founder's goal is that the tree alone is enough to act: a caller must
 * never need a screenshot to find a row title or a button. The rules that
 * follow are the whole contract, and `TreeCompletenessTest` enforces them
 * against committed raw fixtures:
 *
 * 1. **A node with identity is retained.** Identity = non-blank `text`,
 *    `contentDescription` or `viewId`. Its element carries `id`, `role`,
 *    `bounds` (when visible and measurable) and `viewId` when the node has one.
 * 2. **An actionable node is always retained**, even with no identity:
 *    `clickable`, `scrollable`, `editable` or `longClickable`. When such a
 *    node also has no text/description/viewId it is retained *and* reported in
 *    `treeIncomplete` as `actionable_without_identity` — never silently
 *    nameless.
 * 3. **Nothing is dropped without a record.** Nodes folded into another
 *    element (a label supplying a button title, a text child folded into a
 *    summary) are listed in that element's `mergedFrom`. A visible node with
 *    no usable rect cannot be a tap target, so it is reported as
 *    `degenerate_bounds` in `treeIncomplete` — and its subtree is still
 *    projected, because an unrenderable container must never take its
 *    children down with it.
 * 4. **No cross-node merging.** The 1.0.115 projector merged consecutive
 *    VALUE nodes ("Sponsored" + the row title) into one element, keeping the
 *    first node's id and rect and demoting the second node's text to a
 *    `value` field. On Play Store results that is exactly how row titles
 *    ("Claude by Anthropic") vanished from `observe` while a screenshot
 *    showed them plainly. Distinct nodes now stay distinct: their ids and
 *    rects are what `click_node` needs.
 * 5. **`focusable` alone is not identity.** It is true on nearly every
 *    container; a focusable leaf with nothing else is reported as
 *    `focusable_without_identity` (bounded to [MAX_GAPS] entries) instead of
 *    flooding the scene with nameless elements.
 */
object SemanticProjector {

    /** Upper bound on `treeIncomplete` entries, so one broken screen cannot
     *  blow up the observe payload. */
    const val MAX_GAPS = 40

    fun project(tree: AccessibilityTreeData, snapshotId: Long, screenHeight: Int, screenWidth: Int = 1080): SemanticScene {
        val root = tree.root
        if (root == null) {
            return SemanticScene(
                app = tree.foregroundPackage,
                screenTitle = "Unknown",
                scrollable = false,
                elements = emptyList()
            )
        }

        val ctx = ProjectionContext(snapshotId, screenHeight, screenWidth)
        val screenTitle = extractScreenTitle(root)
        val isScrollable = hasScrollableDescendant(root)
        val elements = projectChildren(root, ctx)

        return SemanticScene(
            app = tree.foregroundPackage,
            screenTitle = screenTitle,
            scrollable = isScrollable,
            elements = elements,
            treeIncomplete = ctx.gaps.toList()
        )
    }

    /**
     * Lenient projection for the observe() sparse fallback (R-13).
     *
     * Kept as the last-resort path when the strict walk yields nothing at all
     * (dialogs/popups whose nodes carry little text). It keeps every node with
     * any signal — text, description, viewId or interactivity — so the scene
     * is non-empty whenever the tree has a root.
     */
    fun projectSparse(
        tree: AccessibilityTreeData,
        snapshotId: Long,
        screenHeight: Int,
        screenWidth: Int = 1080
    ): SemanticScene {
        val root = tree.root
        val elements = mutableListOf<SemanticElement>()
        if (root != null) {
            collectSparse(root, snapshotId, screenHeight, screenWidth, elements)
            if (elements.isEmpty()) {
                elements.add(
                    SemanticElement(
                        elementId = ElementId(snapshotId, root.nodeId).encode(),
                        role = SemanticRole.VALUE,
                        title = root.text ?: root.contentDescription
                            ?: root.className?.substringAfterLast('.'),
                        viewId = root.viewIdResourceName
                    )
                )
            }
        }
        return SemanticScene(
            app = tree.foregroundPackage,
            screenTitle = if (root != null) extractScreenTitle(root) else "Unknown",
            scrollable = root != null && hasScrollableDescendant(root),
            elements = elements
        )
    }

    private fun collectSparse(
        node: AccessibilityNodeData,
        snapshotId: Long,
        screenHeight: Int,
        screenWidth: Int,
        out: MutableList<SemanticElement>
    ) {
        val hasSignal = !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank() ||
            !node.viewIdResourceName.isNullOrBlank() ||
            node.clickable || node.editable || node.scrollable ||
            node.checked || node.selected
        if (hasSignal) {
            val role = when {
                node.editable -> SemanticRole.INPUT
                node.checked -> SemanticRole.TOGGLE
                node.clickable -> SemanticRole.ACTION
                node.scrollable -> SemanticRole.LIST
                !node.text.isNullOrBlank() -> SemanticRole.VALUE
                else -> SemanticRole.LABEL
            }
            val bounds = if (hasSaneBounds(node)) {
                BoundsHint.fromBounds(
                    node.boundsInScreen.left, node.boundsInScreen.top,
                    node.boundsInScreen.right, node.boundsInScreen.bottom,
                    screenHeight, screenWidth
                )
            } else {
                null
            }
            out.add(
                SemanticElement(
                    elementId = ElementId(snapshotId, node.nodeId).encode(),
                    role = role,
                    title = node.text ?: node.contentDescription,
                    enabled = node.enabled,
                    checked = if (role == SemanticRole.TOGGLE) node.checked else null,
                    selected = if (node.selected) true else null,
                    scrollable = role == SemanticRole.LIST,
                    bounds = bounds,
                    viewId = node.viewIdResourceName
                )
            )
        }
        for (child in node.children) {
            collectSparse(child, snapshotId, screenHeight, screenWidth, out)
        }
    }

    // ---------------------------------------------------------------- context

    private class ProjectionContext(
        val snapshotId: Long,
        val screenHeight: Int,
        val screenWidth: Int
    ) {
        val gaps = mutableListOf<TreeGap>()

        fun gap(node: AccessibilityNodeData, reason: String, text: String?) {
            if (gaps.size >= MAX_GAPS) return
            gaps.add(
                TreeGap(
                    elementId = ElementId(snapshotId, node.nodeId).encode(),
                    nodeId = node.nodeId,
                    reason = reason,
                    text = text,
                    className = node.className?.substringAfterLast('.')
                )
            )
        }
    }

    // -------------------------------------------------------------- traversal

    private fun projectChildren(parent: AccessibilityNodeData, ctx: ProjectionContext): List<SemanticElement> {
        val out = mutableListOf<SemanticElement>()
        for (child in parent.children) {
            out.addAll(projectNode(child, ctx))
        }
        return mergeLabelsIntoTitlelessActions(out)
    }

    /**
     * Projects one raw node to zero or more elements.
     *
     * Returns a list because a transparent container (no identity, no
     * interactivity) is spliced into its parent's element list rather than
     * being represented by a node of its own.
     */
    private fun projectNode(node: AccessibilityNodeData, ctx: ProjectionContext): List<SemanticElement> {
        val shortClass = node.className?.substringAfterLast('.') ?: ""
        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        val hasViewId = !node.viewIdResourceName.isNullOrBlank()
        val hasIdentity = hasText || hasDesc || hasViewId
        val actionable = isActionable(node)
        val presentation = determinePresentation(node, ctx.screenHeight)
        val saneBounds = hasSaneBounds(node)
        val isContainer = isLayoutClass(shortClass) && node.children.isNotEmpty()

        // (3) Visible but unmeasurable: cannot be a tap target. Report it, and
        //     still project the subtree — a bad parent rect must not erase it.
        if (!saneBounds && presentation == PresentationState.VISIBLE) {
            if (hasIdentity || actionable || node.children.isNotEmpty()) {
                ctx.gap(node, TreeGap.DEGENERATE_BOUNDS, node.text ?: node.contentDescription)
            }
            return projectChildren(node, ctx)
        }

        // Transparent container / decorative leaf: nothing of its own to show.
        if (!hasIdentity && !actionable && !isContainer) {
            if (node.children.isEmpty() && node.focusable && saneBounds &&
                presentation == PresentationState.VISIBLE
            ) {
                ctx.gap(node, TreeGap.FOCUSABLE_WITHOUT_IDENTITY, null)
            }
            return projectChildren(node, ctx)
        }

        val elementId = ElementId(ctx.snapshotId, node.nodeId).encode()
        val bounds = if (saneBounds) {
            BoundsHint.fromBounds(
                node.boundsInScreen.left, node.boundsInScreen.top,
                node.boundsInScreen.right, node.boundsInScreen.bottom,
                ctx.screenHeight, ctx.screenWidth
            )
        } else {
            null  // offscreen elements may have degenerate bounds
        }

        val role = determineRole(node)
        val childElements = projectChildren(node, ctx)

        // A layout container with exactly one child and no identity of its own
        // is a pass-through wrapper: splice its child instead of adding a
        // nameless level to the tree.
        if (role == SemanticRole.SECTION && isContainer && !hasIdentity && !actionable &&
            childElements.size == 1
        ) {
            return childElements
        }

        var element = createElement(node, role, elementId, bounds, childElements, presentation)

        // Every element keeps its descendants: a control's nested buttons and
        // text must never be swallowed by the control itself.
        if (element.children.isEmpty() && childElements.isNotEmpty()) {
            element = element.copy(children = childElements)
        }
        if (element.title.isNullOrBlank() && childElements.isNotEmpty() &&
            isControlRole(role)
        ) {
            element = element.copy(title = childElements.first().title ?: childElements.first().value)
        }

        // (2) Actionable but nameless *after* derivation: keep it (it is
        //     reachable by id and rect) and tell the caller it has no name. A
        //     clickable row whose text lives in a child is NOT a gap — the row
        //     carries the derived title.
        if (actionable && !hasIdentity &&
            element.title.isNullOrBlank() && element.summary.isNullOrBlank() &&
            element.value.isNullOrBlank()
        ) {
            ctx.gap(node, TreeGap.ACTIONABLE_WITHOUT_IDENTITY, null)
        }

        return listOf(element.copy(viewId = node.viewIdResourceName))
    }

    // ----------------------------------------------------------- role helpers

    private fun isActionable(node: AccessibilityNodeData): Boolean =
        node.clickable || node.scrollable || node.editable || node.longClickable

    private fun isControlRole(role: SemanticRole): Boolean = when (role) {
        SemanticRole.ACTION, SemanticRole.TOGGLE, SemanticRole.TAB,
        SemanticRole.INPUT, SemanticRole.PLAYER -> true
        else -> false
    }

    private fun determinePresentation(node: AccessibilityNodeData, screenHeight: Int): PresentationState {
        if (!node.visibleToUser) {
            val hasContent = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() ||
                !node.viewIdResourceName.isNullOrBlank()
            val isInteractive = isActionable(node)
            if (hasContent || isInteractive) {
                return PresentationState.OFFSCREEN
            }
            return PresentationState.HIDDEN
        }
        return PresentationState.VISIBLE
    }

    private fun determineRole(node: AccessibilityNodeData): SemanticRole {
        val shortClass = node.className?.substringAfterLast('.') ?: ""
        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        val hasContent = hasText || hasDesc

        if (node.editable) return SemanticRole.INPUT

        if (node.checked || shortClass.contains("Switch") || shortClass.contains("CheckBox") ||
            shortClass.contains("RadioButton") || shortClass.contains("Toggle")
        ) {
            return SemanticRole.TOGGLE
        }

        if (node.selected && hasContent) return SemanticRole.TAB

        if (node.clickable) return SemanticRole.ACTION

        // A long-pressable row is actionable even when it is not clickable
        // (context menus on list rows) — it must not degrade to a label.
        if (node.longClickable) return SemanticRole.ACTION

        if (node.scrollable) return SemanticRole.LIST

        if (shortClass.contains("Toolbar") || shortClass.contains("ActionBar") ||
            shortClass.contains("AppBar")
        ) {
            return SemanticRole.HEADER
        }

        if (hasText) return SemanticRole.VALUE

        if (isImageClass(shortClass) && hasDesc) return SemanticRole.IMAGE

        if (hasDesc) return SemanticRole.LABEL

        if (isLayoutClass(shortClass) && node.children.isNotEmpty()) {
            return SemanticRole.SECTION
        }

        // Anything else that survived retention (a viewId-only node, say) is
        // still worth showing as a value rather than being invented away.
        return SemanticRole.VALUE
    }

    private fun isLayoutClass(className: String): Boolean {
        return className.contains("Layout") || className.contains("ViewGroup") ||
            className.contains("LinearLayout") || className.contains("FrameLayout") ||
            className.contains("RelativeLayout") || className.contains("ConstraintLayout") ||
            className.contains("CoordinatorLayout")
    }

    private fun isImageClass(className: String): Boolean {
        return className.contains("ImageView") || className.contains("Image") ||
            className.contains("Icon")
    }

    private fun hasSaneBounds(node: AccessibilityNodeData): Boolean {
        val b = node.boundsInScreen
        return b.right > b.left && b.bottom > b.top
    }

    private fun extractScreenTitle(root: AccessibilityNodeData): String {
        val candidate = findTitleCandidate(root, maxDepth = 10)
        return candidate ?: "Unknown"
    }

    private fun findTitleCandidate(node: AccessibilityNodeData, maxDepth: Int): String? {
        if (maxDepth < 0) return null

        if (!node.text.isNullOrBlank() && !node.clickable) {
            val shortClass = node.className?.substringAfterLast('.') ?: ""
            if (shortClass.contains("TextView") || shortClass.contains("Toolbar") ||
                shortClass.contains("ActionBar")
            ) {
                return node.text
            }
        }

        for (child in node.children) {
            val result = findTitleCandidate(child, maxDepth - 1)
            if (result != null) return result
        }

        return null
    }

    private fun hasScrollableDescendant(node: AccessibilityNodeData): Boolean {
        if (node.scrollable) return true
        for (child in node.children) {
            if (hasScrollableDescendant(child)) return true
        }
        return false
    }

    /**
     * Folds a description-only label into the immediately following
     * title-less action (the "icon button + label" pattern).
     *
     * This is the ONLY merge the projector performs, and it is lossless: the
     * label's element id is recorded in the action's `mergedFrom`, and the
     * label is only merged when it carries no viewId of its own and absorbed
     * nothing else.
     */
    private fun mergeLabelsIntoTitlelessActions(elements: List<SemanticElement>): List<SemanticElement> {
        if (elements.size < 2) return elements
        val result = mutableListOf<SemanticElement>()
        var i = 0
        while (i < elements.size) {
            val current = elements[i]
            val next = if (i + 1 < elements.size) elements[i + 1] else null
            val mergeable = current.role == SemanticRole.LABEL &&
                current.viewId == null &&
                current.mergedFrom.isEmpty() &&
                next != null &&
                next.role == SemanticRole.ACTION &&
                next.title.isNullOrBlank() &&
                next.mergedFrom.isEmpty()
            if (mergeable && next != null) {
                result.add(
                    next.copy(
                        title = current.title ?: current.value,
                        mergedFrom = listOf(current.elementId)
                    )
                )
                i += 2
                continue
            }
            result.add(current)
            i++
        }
        return result
    }

    // -------------------------------------------------------------- builders

    private fun createElement(
        node: AccessibilityNodeData,
        role: SemanticRole,
        elementId: String,
        bounds: BoundsHint?,
        children: List<SemanticElement>,
        presentation: PresentationState
    ): SemanticElement = when (role) {
        SemanticRole.HEADER -> createHeader(node, elementId, bounds, presentation)
        SemanticRole.SECTION -> createSection(node, elementId, bounds, children, presentation)
        SemanticRole.ACTION -> createAction(node, elementId, bounds, presentation)
        SemanticRole.INPUT -> createInput(node, elementId, bounds, presentation)
        SemanticRole.TOGGLE -> createToggle(node, elementId, bounds, presentation)
        SemanticRole.VALUE -> createValue(node, elementId, bounds, presentation)
        SemanticRole.LABEL -> createLabel(node, elementId, bounds, presentation)
        SemanticRole.IMAGE -> createImage(node, elementId, bounds, presentation)
        SemanticRole.LIST -> createList(node, elementId, bounds, children, presentation)
        SemanticRole.TAB -> createTab(node, elementId, bounds, presentation)
        SemanticRole.MENU -> createMenu(node, elementId, bounds, children, presentation)
        SemanticRole.PLAYER -> createPlayer(node, elementId, bounds, presentation)
    }

    private fun createHeader(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.HEADER,
            title = node.text ?: node.contentDescription,
            presentation = presentation,
            bounds = bounds
        )
    }

    private fun createSection(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        children: List<SemanticElement>,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.SECTION,
            title = node.text ?: node.contentDescription,
            scrollable = node.scrollable,
            presentation = presentation,
            bounds = bounds,
            children = children
        )
    }

    private fun createAction(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        presentation: PresentationState
    ): SemanticElement {
        var title = node.text ?: node.contentDescription
        var summary = extractSummaryFromChildren(node)

        // If no direct text, derive from descendants (like NodeResolver.deriveSemanticText)
        if (title.isNullOrBlank()) {
            val derived = deriveTextFromDescendants(node)
            title = derived.first
            if (summary.isNullOrBlank()) {
                summary = derived.second
            }
        }

        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.ACTION,
            title = title,
            summary = summary,
            enabled = node.enabled,
            presentation = presentation,
            bounds = bounds
        )
    }

    private fun createInput(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.INPUT,
            title = node.contentDescription,
            placeholder = node.text,
            value = if (node.text.isNullOrBlank()) "" else node.text,
            enabled = node.enabled,
            focused = node.focused,
            presentation = presentation,
            bounds = bounds
        )
    }

    private fun createToggle(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.TOGGLE,
            title = node.text ?: node.contentDescription,
            summary = extractSummaryFromChildren(node),
            checked = node.checked,
            enabled = node.enabled,
            presentation = presentation,
            bounds = bounds
        )
    }

    private fun createValue(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.VALUE,
            title = node.text ?: node.contentDescription,
            presentation = presentation,
            bounds = bounds
        )
    }

    private fun createLabel(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.LABEL,
            title = node.contentDescription ?: node.text,
            presentation = presentation,
            bounds = bounds
        )
    }

    private fun createImage(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.IMAGE,
            title = node.contentDescription ?: node.text,
            presentation = presentation,
            bounds = bounds
        )
    }

    private fun createList(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        children: List<SemanticElement>,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.LIST,
            title = node.text ?: node.contentDescription,
            scrollable = true,
            presentation = presentation,
            bounds = bounds,
            children = children
        )
    }

    private fun createTab(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.TAB,
            title = node.text ?: node.contentDescription,
            selected = node.selected,
            enabled = node.enabled,
            presentation = presentation,
            bounds = bounds
        )
    }

    private fun createMenu(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        children: List<SemanticElement>,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.MENU,
            title = node.text ?: node.contentDescription,
            presentation = presentation,
            bounds = bounds,
            children = children
        )
    }

    private fun createPlayer(
        node: AccessibilityNodeData,
        elementId: String,
        bounds: BoundsHint?,
        presentation: PresentationState
    ): SemanticElement {
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.PLAYER,
            title = node.text ?: node.contentDescription,
            presentation = presentation,
            bounds = bounds
        )
    }

    private fun extractSummaryFromChildren(node: AccessibilityNodeData): String? {
        for (child in node.children) {
            if (!child.text.isNullOrBlank() && child.text != node.text) {
                return child.text
            }
            if (!child.contentDescription.isNullOrBlank() && child.contentDescription != node.contentDescription) {
                return child.contentDescription
            }
        }
        return null
    }

    private fun deriveTextFromDescendants(node: AccessibilityNodeData, maxDepth: Int = 3): Pair<String?, String?> {
        var title: String? = null
        var summary: String? = null
        deriveFromDescendants(node, maxDepth) { child ->
            if (title.isNullOrBlank() && !child.text.isNullOrBlank()) {
                title = child.text
            } else if (summary.isNullOrBlank() && !child.text.isNullOrBlank() && child.text != title) {
                summary = child.text
            }
            if (summary.isNullOrBlank() && !child.contentDescription.isNullOrBlank() && child.contentDescription != title) {
                summary = child.contentDescription
            }
        }
        return Pair(title, summary)
    }

    private fun deriveFromDescendants(
        node: AccessibilityNodeData,
        maxDepth: Int,
        visitor: (AccessibilityNodeData) -> Unit
    ) {
        if (maxDepth <= 0) return
        for (child in node.children) {
            visitor(child)
            deriveFromDescendants(child, maxDepth - 1, visitor)
        }
    }
}
