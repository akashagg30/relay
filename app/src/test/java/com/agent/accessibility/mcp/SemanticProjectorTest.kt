package com.agent.accessibility.mcp

import com.agent.accessibility.model.AccessibilityNodeData
import com.agent.accessibility.model.AccessibilityTreeData
import com.agent.accessibility.model.RectData
import org.junit.Assert.*
import org.junit.Test

class SemanticProjectorTest {

    private fun makeNode(
        id: Int,
        text: String? = null,
        desc: String? = null,
        className: String? = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        longClickable: Boolean = false,
        selected: Boolean = false,
        checked: Boolean = false,
        enabled: Boolean = true,
        visibleToUser: Boolean = true,
        left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 50,
        children: List<AccessibilityNodeData> = emptyList(),
        viewId: String? = null
    ): AccessibilityNodeData {
        return AccessibilityNodeData(
            nodeId = id,
            text = text,
            contentDescription = desc,
            className = className,
            packageName = "com.test.app",
            viewIdResourceName = viewId,
            boundsInScreen = RectData(left, top, right, bottom),
            clickable = clickable,
            longClickable = longClickable,
            scrollable = scrollable,
            editable = editable,
            focusable = true,
            focused = false,
            enabled = enabled,
            selected = selected,
            checked = checked,
            visibleToUser = visibleToUser,
            childCount = children.size,
            children = children
        )
    }

    private fun makeTree(root: AccessibilityNodeData): AccessibilityTreeData {
        return AccessibilityTreeData(
            foregroundPackage = "com.test.app",
            totalNodeCount = root.flatten().size,
            root = root,
            timestamp = System.currentTimeMillis()
        )
    }

    @Test
    fun `empty tree produces empty scene`() {
        val root = makeNode(0, className = "android.widget.FrameLayout")
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals("com.test.app", scene.app)
        assertEquals(0, scene.elements.size)
    }

    @Test
    fun `single clickable text node becomes action`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Wi-Fi", clickable = true, className = "android.widget.LinearLayout")
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals(SemanticRole.ACTION, scene.elements[0].role)
        assertEquals("Wi-Fi", scene.elements[0].title)
        assertEquals("100:1", scene.elements[0].elementId)
    }

    @Test
    fun `text node without click becomes value`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Android version", clickable = false)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals(SemanticRole.VALUE, scene.elements[0].role)
        assertEquals("Android version", scene.elements[0].title)
    }

    @Test
    fun `editable node becomes input`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Search", editable = true, className = "android.widget.EditText")
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals(SemanticRole.INPUT, scene.elements[0].role)
        assertEquals("Search", scene.elements[0].placeholder)
    }

    @Test
    fun `checked node becomes toggle`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Airplane mode", checked = true, className = "android.widget.Switch")
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals(SemanticRole.TOGGLE, scene.elements[0].role)
        assertEquals("Airplane mode", scene.elements[0].title)
        assertEquals(true, scene.elements[0].checked)
    }

    @Test
    fun `selected node with text becomes tab`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Primary", selected = true, clickable = true)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals(SemanticRole.TAB, scene.elements[0].role)
        assertEquals("Primary", scene.elements[0].title)
        assertEquals(true, scene.elements[0].selected)
    }

    @Test
    fun `scrollable node becomes list`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, scrollable = true, className = "android.widget.RecyclerView",
                children = listOf(
                    makeNode(2, text = "Item 1", clickable = true),
                    makeNode(3, text = "Item 2", clickable = true)
                ))
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals(SemanticRole.LIST, scene.elements[0].role)
        assertTrue(scene.elements[0].scrollable)
        assertEquals(2, scene.elements[0].children.size)
    }

    @Test
    fun `invisible nodes are kept as offscreen`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Hidden", visibleToUser = false),
            makeNode(2, text = "Visible", clickable = true)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        // Invisible nodes with text are kept as offscreen elements
        assertEquals(2, scene.elements.size)
        assertEquals("Hidden", scene.elements[0].title)
        assertEquals(PresentationState.OFFSCREEN, scene.elements[0].presentation)
        assertEquals("Visible", scene.elements[1].title)
        assertEquals(PresentationState.VISIBLE, scene.elements[1].presentation)
    }

    @Test
    fun `decorative images are skipped`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, className = "android.widget.ImageView"),
            makeNode(2, text = "Real content", clickable = true)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals("Real content", scene.elements[0].title)
    }

    @Test
    fun `images with contentDescription are kept`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, desc = "Profile picture", className = "android.widget.ImageView"),
            makeNode(2, text = "Content", clickable = true)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(2, scene.elements.size)
        assertEquals(SemanticRole.IMAGE, scene.elements[0].role)
        assertEquals("Profile picture", scene.elements[0].title)
    }

    @Test
    fun `degenerate bounds nodes are skipped`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Zero width", left = 0, top = 0, right = 0, bottom = 50),
            makeNode(2, text = "Valid", clickable = true, left = 0, top = 0, right = 100, bottom = 50)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals("Valid", scene.elements[0].title)
    }

    @Test
    fun `element IDs are reused from raw tree`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(42, text = "Wi-Fi", clickable = true)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals("100:42", scene.elements[0].elementId)
    }

    @Test
    fun `action with summary from child text`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Wi-Fi", clickable = true, className = "android.widget.LinearLayout", children = listOf(
                makeNode(2, text = "Connected to Airtel")
            ))
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals(SemanticRole.ACTION, scene.elements[0].role)
        assertEquals("Wi-Fi", scene.elements[0].title)
        assertEquals("Connected to Airtel", scene.elements[0].summary)
    }

    @Test
    fun `disabled action has enabled=false`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Back", clickable = true, enabled = false)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals(false, scene.elements[0].enabled)
    }

    @Test
    fun `header from toolbar class`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Settings", className = "android.widget.Toolbar")
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals(SemanticRole.HEADER, scene.elements[0].role)
        assertEquals("Settings", scene.elements[0].title)
    }

    @Test
    fun `adjacent text nodes are not merged away`() {
        // R-15: 1.0.115 merged two consecutive VALUE nodes into one, keeping
        // the first node's id and rect and demoting the second node's text to
        // `value`. On Play Store results that is how row titles disappeared
        // ("Sponsored" swallowed "Claude by Anthropic"). Both nodes must now
        // survive as first-class elements with their own ids and rects.
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Sponsored", left = 0, top = 0, right = 200, bottom = 40),
            makeNode(2, text = "Claude by Anthropic", left = 0, top = 50, right = 700, bottom = 120)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(2, scene.elements.size)
        assertEquals("Sponsored", scene.elements[0].title)
        assertEquals("Claude by Anthropic", scene.elements[1].title)
        assertEquals("100:2", scene.elements[1].elementId)
        assertEquals(50, scene.elements[1].bounds?.top)
        assertEquals(120, scene.elements[1].bounds?.bottom)
        assertTrue(scene.treeIncomplete.isEmpty())
    }

    @Test
    fun `degenerate container keeps its subtree and reports the gap`() {
        // R-15/R-5: a visible container with a 0x0 rect used to return null and
        // take every child with it, which is how a whole Play/Wikipedia screen
        // collapsed to one or two elements.
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, className = "android.view.View", left = 0, top = 0, right = 0, bottom = 0,
                children = listOf(
                    makeNode(2, text = "For you", clickable = true),
                    makeNode(3, text = "Top charts", clickable = true)
                ))
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(2, scene.elements.size)
        assertEquals("For you", scene.elements[0].title)
        assertEquals(1, scene.treeIncomplete.size)
        assertEquals(TreeGap.DEGENERATE_BOUNDS, scene.treeIncomplete[0].reason)
        assertEquals("100:1", scene.treeIncomplete[0].elementId)
    }

    @Test
    fun `actionable node without identity is kept and reported`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, className = "android.widget.ImageButton", clickable = true)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals(SemanticRole.ACTION, scene.elements[0].role)
        assertNotNull(scene.elements[0].bounds)
        assertEquals(1, scene.treeIncomplete.size)
        assertEquals(TreeGap.ACTIONABLE_WITHOUT_IDENTITY, scene.treeIncomplete[0].reason)
    }

    @Test
    fun `clickable card keeps nested actionable children`() {
        // A clickable card used to swallow its descendants entirely, so a
        // nested Install button inside a result row was unreachable.
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, className = "android.widget.LinearLayout", clickable = true,
                left = 0, top = 0, right = 1000, bottom = 400,
                children = listOf(
                    makeNode(2, text = "Claude by Anthropic"),
                    makeNode(3, text = "Install", clickable = true,
                        left = 800, top = 100, right = 990, bottom = 200)
                ))
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        val card = scene.elements[0]
        assertEquals(SemanticRole.ACTION, card.role)
        assertEquals("Claude by Anthropic", card.title)
        assertEquals(2, card.children.size)
        assertEquals("Claude by Anthropic", card.children[0].title)
        assertEquals("Install", card.children[1].title)
        assertEquals("100:3", card.children[1].elementId)
    }

    @Test
    fun `viewId survives projection`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Wi-Fi", clickable = true, viewId = "com.android.settings:id/wifi_row")
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals("com.android.settings:id/wifi_row", scene.elements[0].viewId)
    }

    @Test
    fun `longClickable row stays actionable`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, className = "android.widget.LinearLayout", longClickable = true,
                left = 0, top = 0, right = 1000, bottom = 200,
                children = listOf(
                    makeNode(2, text = "Display", left = 80, top = 20, right = 600, bottom = 90)
                ))
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        // A long-pressable row is a target (context menu), not a decorative
        // section: it must keep its own id and rect and carry the label.
        assertEquals(1, scene.elements.size)
        assertEquals(SemanticRole.ACTION, scene.elements[0].role)
        assertEquals("Display", scene.elements[0].title)
        assertEquals("100:1", scene.elements[0].elementId)
        assertEquals("Display", scene.elements[0].children[0].title)
    }

    @Test
    fun `label merged with titleless action records the absorbed id`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, desc = "Menu"),
            makeNode(2, clickable = true, className = "android.widget.ImageButton")
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        assertEquals("Menu", scene.elements[0].title)
        assertTrue(scene.elements[0].mergedFrom.contains("100:1"))
    }

    @Test
    fun `layout with children becomes section`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, className = "android.widget.LinearLayout", children = listOf(
                makeNode(2, text = "Wi-Fi", clickable = true),
                makeNode(3, text = "Bluetooth", clickable = true)
            ))
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        // The layout with children should become a section
        val sections = scene.elements.filter { it.role == SemanticRole.SECTION }
        assertTrue(sections.isNotEmpty())
        // Section should contain the action children
        val section = sections.first()
        assertEquals(2, section.children.size)
    }

    @Test
    fun `scrollable flag propagated to scene`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", scrollable = true, children = listOf(
            makeNode(1, text = "Item", clickable = true)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertTrue(scene.scrollable)
    }

    @Test
    fun `bounds hint computed correctly`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Top item", clickable = true, top = 0, bottom = 50),
            makeNode(2, text = "Bottom item", clickable = true, top = 1800, bottom = 1920)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(2, scene.elements.size)
        // Top item should have vertical=top
        assertEquals("top", scene.elements[0].bounds?.vertical)
        // Bottom item should have vertical=bottom
        assertEquals("bottom", scene.elements[1].bounds?.vertical)
    }

    @Test
    fun `scene JSON serialization`() {
        // JSONObject is not available in unit tests (Android-only)
        // This is tested via integration tests on device
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Wi-Fi", clickable = true)
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        // Just verify the scene has correct structure
        assertEquals(1, scene.elements.size)
        assertEquals("com.test.app", scene.app)
    }

    @Test
    fun `multiple sections preserved`() {
        // With flatten logic, single-child layouts without text are flattened
        // So we get the actions directly, not wrapped in sections
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, className = "android.widget.LinearLayout", children = listOf(
                makeNode(2, text = "Wi-Fi", clickable = true)
            )),
            makeNode(3, className = "android.widget.LinearLayout", children = listOf(
                makeNode(4, text = "Bluetooth", clickable = true)
            ))
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        // Single-child layouts are flattened, so we get 2 actions
        val actions = scene.elements.filter { it.role == SemanticRole.ACTION }
        assertEquals(2, actions.size)
        assertEquals("Wi-Fi", actions[0].title)
        assertEquals("Bluetooth", actions[1].title)
    }

    @Test
    fun `image with description and clickable`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, desc = "Menu icon", clickable = true, className = "android.widget.ImageView")
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        assertEquals(1, scene.elements.size)
        // Clickable image with description should be action
        assertEquals(SemanticRole.ACTION, scene.elements[0].role)
        assertEquals("Menu icon", scene.elements[0].title)
    }
}
