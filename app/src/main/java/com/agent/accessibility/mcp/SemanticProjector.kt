package com.agent.accessibility.mcp

import com.agent.accessibility.model.AccessibilityNodeData
import com.agent.accessibility.model.AccessibilityTreeData

object SemanticProjector {

    private const val TAG = "SemanticProjector"

    fun project(tree: AccessibilityTreeData, snapshotId: Long, screenHeight: Int): SemanticScene {
        val root = tree.root
        if (root == null) {
            return SemanticScene(
                app = tree.foregroundPackage,
                screenTitle = "Unknown",
                scrollable = false,
                elements = emptyList()
            )
        }

        val screenTitle = extractScreenTitle(root)
        val isScrollable = hasScrollableDescendant(root)
        val elements = projectChildren(root, snapshotId, screenHeight)

        return SemanticScene(
            app = tree.foregroundPackage,
            screenTitle = screenTitle,
            scrollable = isScrollable,
            elements = elements
        )
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
                shortClass.contains("ActionBar")) {
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

    private fun projectChildren(
        parent: AccessibilityNodeData,
        snapshotId: Long,
        screenHeight: Int
    ): List<SemanticElement> {
        val result = mutableListOf<SemanticElement>()

        for (child in parent.children) {
            val projected = projectNode(child, snapshotId, screenHeight)
            if (projected != null) {
                result.add(projected)
            }
        }

        return mergeConsecutiveLabels(result)
    }

    private fun projectNode(
        node: AccessibilityNodeData,
        snapshotId: Long,
        screenHeight: Int
    ): SemanticElement? {
        // Skip decorative images (no text, no description, not clickable)
        if (isDecorativeImage(node)) return null

        // Determine presentation state
        val presentation = determinePresentation(node, screenHeight)

        // Skip nodes with degenerate bounds UNLESS they're off-screen (planning info)
        if (!hasSaneBounds(node) && presentation == PresentationState.VISIBLE) return null

        val elementId = ElementId(snapshotId, node.nodeId).encode()
        val bounds = if (hasSaneBounds(node)) {
            BoundsHint.fromBounds(
                node.boundsInScreen.left, node.boundsInScreen.top,
                node.boundsInScreen.right, node.boundsInScreen.bottom,
                screenHeight
            )
        } else {
            null  // Off-screen elements may have degenerate bounds
        }

        // Determine role
        val role = determineRole(node)
        val children = projectChildren(node, snapshotId, screenHeight)

        // Skip empty nodes with no text, no description, not clickable, no children
        if (node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank() &&
            !node.clickable && !node.scrollable && !node.editable && children.isEmpty()) {
            return null
        }

        // Single-child layout: flatten by returning the single child
        val shortClass = node.className?.substringAfterLast('.') ?: ""
        if (isLayoutClass(shortClass) && children.size == 1 && node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank() && !node.clickable) {
            return children[0]
        }

        return when (role) {
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
    }

    private fun determinePresentation(node: AccessibilityNodeData, screenHeight: Int): PresentationState {
        if (!node.visibleToUser) {
            // Check if it's a semantic element worth keeping (has text/description or is clickable)
            val hasContent = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            val isInteractive = node.clickable || node.editable || node.scrollable
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
        val clickable = node.clickable
        val editable = node.editable
        val scrollable = node.scrollable
        val selected = node.selected
        val checked = node.checked

        // Input fields
        if (editable) return SemanticRole.INPUT

        // Toggles (checked state)
        if (checked || shortClass.contains("Switch") || shortClass.contains("CheckBox") ||
            shortClass.contains("RadioButton") || shortClass.contains("Toggle")) {
            return SemanticRole.TOGGLE
        }

        // Tabs (selected state with text)
        if (selected && hasContent) return SemanticRole.TAB

        // Actions (clickable with content)
        if (clickable && hasContent) return SemanticRole.ACTION

        // Clickable without content: empty action (icon button)
        if (clickable && !hasContent) return SemanticRole.ACTION

        // Scrollable containers (lists)
        if (scrollable) return SemanticRole.LIST

        // Headers: toolbar/actionbar-like classes
        if (shortClass.contains("Toolbar") || shortClass.contains("ActionBar") ||
            shortClass.contains("AppBar")) {
            return SemanticRole.HEADER
        }

        // Values: text nodes
        if (hasText) return SemanticRole.VALUE

        // Images with description
        if (isImageClass(shortClass) && hasDesc) return SemanticRole.IMAGE

        // Labels: nodes with description but no direct text
        if (hasDesc) return SemanticRole.LABEL

        // Layouts with children: section
        if (isLayoutClass(shortClass) && node.children.isNotEmpty()) {
            return SemanticRole.SECTION
        }

        // Skip everything else
        return SemanticRole.SECTION  // Will be skipped if empty
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

    private fun isDecorativeImage(node: AccessibilityNodeData): Boolean {
        val shortClass = node.className?.substringAfterLast('.') ?: ""
        if (!isImageClass(shortClass)) return false
        return node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank() && !node.clickable
    }

    private fun hasSaneBounds(node: AccessibilityNodeData): Boolean {
        val b = node.boundsInScreen
        return b.right > b.left && b.bottom > b.top
    }

    private fun hasChildrenWithContent(node: AccessibilityNodeData): Boolean {
        for (child in node.children) {
            if (!child.text.isNullOrBlank() || !child.contentDescription.isNullOrBlank()) {
                return true
            }
            if (hasChildrenWithContent(child)) return true
        }
        return false
    }

    private fun mergeConsecutiveLabels(elements: List<SemanticElement>): List<SemanticElement> {
        if (elements.size < 2) return elements

        val result = mutableListOf<SemanticElement>()
        var i = 0

        while (i < elements.size) {
            val current = elements[i]

            // Merge label + value into value with label
            if (current.role == SemanticRole.LABEL && i + 1 < elements.size) {
                val next = elements[i + 1]
                if (next.role == SemanticRole.VALUE) {
                    result.add(next.copy(title = current.title ?: current.value))
                    i += 2
                    continue
                }
            }

            // Merge two consecutive VALUE nodes: first becomes title, second becomes value
            if (current.role == SemanticRole.VALUE && i + 1 < elements.size) {
                val next = elements[i + 1]
                if (next.role == SemanticRole.VALUE) {
                    // Merge: title from first, value from second
                    result.add(current.copy(value = next.title))
                    i += 2
                    continue
                }
            }

            // Merge label + action into action with title
            if (current.role == SemanticRole.LABEL && i + 1 < elements.size) {
                val next = elements[i + 1]
                if (next.role == SemanticRole.ACTION && next.title == null) {
                    result.add(next.copy(title = current.title ?: current.value))
                    i += 2
                    continue
                }
            }

            result.add(current)
            i++
        }

        return result
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
        val title = node.text ?: node.contentDescription
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.SECTION,
            title = title,
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
        val title = node.text ?: node.contentDescription
        val summary = extractSummaryFromChildren(node)
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.TOGGLE,
            title = title,
            summary = summary,
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
            title = node.contentDescription,
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
        val title = node.text ?: node.contentDescription
        return SemanticElement(
            elementId = elementId,
            role = SemanticRole.LIST,
            title = title,
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
        // Look for summary-like text in children
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
