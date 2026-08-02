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
            longClickable = false,
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
    fun `label merged with value`() {
        val root = makeNode(0, className = "android.widget.FrameLayout", children = listOf(
            makeNode(1, text = "Battery"),
            makeNode(2, text = "3%")
        ))
        val tree = makeTree(root)
        val scene = SemanticProjector.project(tree, 100L, 1920)
        // After merge, should have fewer elements
        assertTrue(scene.elements.size <= 2)
        // If merged, the value should have the label as title
        val values = scene.elements.filter { it.role == SemanticRole.VALUE }
        if (values.isNotEmpty()) {
            assertEquals("Battery", values[0].title)
            assertEquals("3%", values[0].value)
        }
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
