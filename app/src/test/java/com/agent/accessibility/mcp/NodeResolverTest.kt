package com.agent.accessibility.mcp

import android.view.accessibility.AccessibilityNodeInfo
import com.agent.accessibility.model.AccessibilityNodeData
import com.agent.accessibility.model.RectData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class NodeResolverTest {

    private lateinit var snapshotManager: SnapshotManager

    @Before
    fun setUp() {
        snapshotManager = SnapshotManager()
    }

    private fun makeDescriptor(
        id: Int = 1,
        packageName: String? = "com.example.app",
        viewIdResourceName: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        className: String? = null,
        clickable: Boolean = false,
        scrollable: Boolean = false,
        semanticText: String? = null,
        semanticDescription: String? = null
    ): NodeDescriptor {
        return NodeDescriptor(
            id = id,
            parentId = null,
            packageName = packageName,
            viewIdResourceName = viewIdResourceName,
            text = text,
            contentDescription = contentDescription,
            className = className,
            bounds = listOf(0, 0, 100, 50),
            clickable = clickable,
            scrollable = scrollable,
            semanticText = semanticText,
            semanticDescription = semanticDescription
        )
    }

    private fun makeNodeData(
        nodeId: Int = 1,
        text: String? = null,
        contentDescription: String? = null,
        viewIdResourceName: String? = null,
        className: String? = "android.widget.LinearLayout",
        packageName: String? = "com.android.settings",
        clickable: Boolean = false,
        scrollable: Boolean = false,
        bounds: RectData = RectData(0, 0, 100, 50),
        children: List<AccessibilityNodeData> = emptyList()
    ): AccessibilityNodeData {
        return AccessibilityNodeData(
            nodeId = nodeId,
            text = text,
            contentDescription = contentDescription,
            className = className,
            packageName = packageName,
            viewIdResourceName = viewIdResourceName,
            boundsInScreen = bounds,
            clickable = clickable,
            longClickable = false,
            scrollable = scrollable,
            editable = false,
            focusable = false,
            focused = false,
            enabled = true,
            selected = false,
            checked = false,
            visibleToUser = true,
            childCount = children.size,
            children = children
        )
    }

    private fun makeMockNode(
        packageName: String = "com.android.settings",
        viewId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        className: String? = "android.widget.LinearLayout",
        clickable: Boolean = false,
        isVisibleToUser: Boolean = true
    ): AccessibilityNodeInfo {
        val node = mock(AccessibilityNodeInfo::class.java)
        `when`(node.packageName).thenReturn(packageName)
        `when`(node.viewIdResourceName).thenReturn(viewId)
        `when`(node.text).thenReturn(text)
        `when`(node.contentDescription).thenReturn(contentDescription)
        `when`(node.className).thenReturn(className)
        `when`(node.isClickable).thenReturn(clickable)
        `when`(node.isVisibleToUser).thenReturn(isVisibleToUser)
        `when`(node.getChild(0)).thenReturn(null)
        `when`(node.childCount).thenReturn(0)
        return node
    }

    // ============================================================
    // A. Clickable parent + title child: parent inherits semantic title
    // ============================================================

    @Test
    fun `clickable parent inherits semantic title from title child`() {
        val titleChild = makeNodeData(
            nodeId = 2,
            text = "Battery",
            viewIdResourceName = "android:id/title",
            bounds = RectData(203, 325, 402, 321)
        )
        val parent = makeNodeData(
            nodeId = 1,
            clickable = true,
            bounds = RectData(0, 325, 1272, 434),
            children = listOf(titleChild)
        )

        val (semanticText, semanticDescription) = NodeResolver.deriveSemanticText(parent)

        assertEquals("Battery", semanticText)
        assertNull(semanticDescription)
    }

    @Test
    fun `enriched descriptor stores semantic text`() {
        val titleChild = makeNodeData(
            nodeId = 2,
            text = "Battery",
            viewIdResourceName = "android:id/title"
        )
        val parent = makeNodeData(
            nodeId = 1,
            clickable = true,
            children = listOf(titleChild)
        )

        val tree = com.agent.accessibility.model.AccessibilityTreeData(
            foregroundPackage = "com.android.settings",
            totalNodeCount = 2,
            root = parent
        )

        val descriptors = NodeResolver.buildDescriptors(tree)
        val parentDescriptor = descriptors[1]!!

        assertEquals("Battery", parentDescriptor.semanticText)
        assertNull(parentDescriptor.semanticDescription)
        assertNull(parentDescriptor.text)
    }

    // ============================================================
    // B. Clickable parent + title + summary: correct primary/secondary semantics
    // ============================================================

    @Test
    fun `clickable parent inherits both title and summary semantics`() {
        val titleChild = makeNodeData(
            nodeId = 2,
            text = "Network & Internet",
            viewIdResourceName = "android:id/title"
        )
        val summaryChild = makeNodeData(
            nodeId = 3,
            text = "Mobile, Wi-Fi, hotspot",
            viewIdResourceName = "android:id/summary"
        )
        val parent = makeNodeData(
            nodeId = 1,
            clickable = true,
            children = listOf(titleChild, summaryChild)
        )

        val (semanticText, semanticDescription) = NodeResolver.deriveSemanticText(parent)

        assertEquals("Network & Internet", semanticText)
        assertEquals("Mobile, Wi-Fi, hotspot", semanticDescription)
    }

    @Test
    fun `enriched descriptor stores both semantic text and description`() {
        val titleChild = makeNodeData(
            nodeId = 2,
            text = "Network & Internet",
            viewIdResourceName = "android:id/title"
        )
        val summaryChild = makeNodeData(
            nodeId = 3,
            text = "Mobile, Wi-Fi, hotspot",
            viewIdResourceName = "android:id/summary"
        )
        val parent = makeNodeData(
            nodeId = 1,
            clickable = true,
            children = listOf(titleChild, summaryChild)
        )

        val tree = com.agent.accessibility.model.AccessibilityTreeData(
            foregroundPackage = "com.android.settings",
            totalNodeCount = 3,
            root = parent
        )

        val descriptors = NodeResolver.buildDescriptors(tree)
        val parentDescriptor = descriptors[1]!!

        assertEquals("Network & Internet", parentDescriptor.semanticText)
        assertEquals("Mobile, Wi-Fi, hotspot", parentDescriptor.semanticDescription)
    }

    @Test
    fun `summary without title only yields semantic description`() {
        val summaryChild = makeNodeData(
            nodeId = 2,
            text = "Some summary",
            viewIdResourceName = "android:id/summary"
        )
        val parent = makeNodeData(
            nodeId = 1,
            clickable = true,
            children = listOf(summaryChild)
        )

        val (semanticText, semanticDescription) = NodeResolver.deriveSemanticText(parent)

        assertNull(semanticText)
        assertEquals("Some summary", semanticDescription)
    }

    // ============================================================
    // C. Nested clickable child: parent does NOT inherit nested child's semantics
    // ============================================================

    @Test
    fun `clickable parent does not inherit from nested independently-clickable child`() {
        val innerTitle = makeNodeData(
            nodeId = 4,
            text = "Inner Button Text",
            viewIdResourceName = "android:id/title"
        )
        val innerClickable = makeNodeData(
            nodeId = 3,
            clickable = true,
            children = listOf(innerTitle)
        )
        val outerTitle = makeNodeData(
            nodeId = 2,
            text = "Outer Title",
            viewIdResourceName = "android:id/title"
        )
        val outerParent = makeNodeData(
            nodeId = 1,
            clickable = true,
            children = listOf(outerTitle, innerClickable)
        )

        val (semanticText, semanticDescription) = NodeResolver.deriveSemanticText(outerParent)

        assertEquals("Outer Title", semanticText)
        assertNotEquals("Inner Button Text", semanticText)
    }

    @Test
    fun `clickable parent with empty container child does inherit its title`() {
        val innerTitle = makeNodeData(
            nodeId = 4,
            text = "Some Title",
            viewIdResourceName = "android:id/title"
        )
        val emptyContainer = makeNodeData(
            nodeId = 3,
            children = listOf(innerTitle)
        )
        val outerParent = makeNodeData(
            nodeId = 1,
            clickable = true,
            children = listOf(emptyContainer)
        )

        val (semanticText, _) = NodeResolver.deriveSemanticText(outerParent)

        assertEquals("Some Title", semanticText)
    }

    // ============================================================
    // D. Invisible/degenerate title child + visible clickable parent:
    //    logical element considered actionable/visible
    // ============================================================

    @Test
    fun `logical target is visible when clickable parent is visible even if child is not`() {
        // Simulate: parent is clickable and visible with sane bounds,
        // but child is not visible (e.g., inverted bounds from RecyclerView)
        val parentVisible = true
        val parentBounds = listOf(0, 325, 1272, 434)
        val childVisible = false
        val childBounds = listOf(203, 325, 402, 321) // inverted

        val isVisible = NodeResolver.isLogicalTargetVisibleByBounds(
            isVisibleToUser = parentVisible,
            bounds = parentBounds,
            childVisibleToUser = childVisible,
            childBounds = childBounds
        )

        assertTrue("Logical element should be visible via clickable parent", isVisible)
    }

    @Test
    fun `logical target uses clickable ancestor when child is not clickable`() {
        val mockParent = makeMockNode(clickable = true, isVisibleToUser = true)
        val mockChild = makeMockNode(text = "Battery", isVisibleToUser = false)

        `when`(mockChild.parent).thenReturn(mockParent)

        val descriptor = makeDescriptor(id = 1, text = "Battery")
        val logical = NodeResolver.getLogicalActionableTarget(mockChild, descriptor)

        assertEquals(mockParent, logical.clickableAncestor)
        assertEquals("clickable_ancestor", logical.method)
    }

    @Test
    fun `logical target returns direct node when already clickable`() {
        val mockNode = makeMockNode(clickable = true, isVisibleToUser = true)

        val descriptor = makeDescriptor(id = 1, clickable = true)
        val logical = NodeResolver.getLogicalActionableTarget(mockNode, descriptor)

        assertEquals(mockNode, logical.node)
        assertEquals("directly_clickable", logical.method)
        assertNull(logical.clickableAncestor)
    }

    @Test
    fun `logical target not visible when both parent and child are invisible`() {
        val isVisible = NodeResolver.isLogicalTargetVisibleByBounds(
            isVisibleToUser = false,
            bounds = listOf(0, 4000, 100, 4100),
            childVisibleToUser = false,
            childBounds = listOf(20, 4000, 80, 4100)
        )

        assertFalse("Should not be visible when both are invisible", isVisible)
    }

    @Test
    fun `logical target visible when child is visible even if parent is not`() {
        val isVisible = NodeResolver.isLogicalTargetVisibleByBounds(
            isVisibleToUser = false,
            bounds = listOf(0, 4000, 100, 4100),
            childVisibleToUser = true,
            childBounds = listOf(20, 359, 80, 434)
        )

        assertTrue("Should be visible via visible child", isVisible)
    }

    // ============================================================
    // E. Degenerate bounds: never coordinate-tapped
    // ============================================================

    @Test
    fun `degenerate bounds top equals bottom is not sane`() {
        assertFalse(NodeResolver.isSaneBounds(0, 100, 50, 100))
    }

    @Test
    fun `degenerate bounds bottom less than top is not sane`() {
        assertFalse(NodeResolver.isSaneBounds(0, 200, 50, 100))
    }

    @Test
    fun `degenerate bounds right less than left is not sane`() {
        assertFalse(NodeResolver.isSaneBounds(100, 0, 50, 50))
    }

    @Test
    fun `degenerate bounds left equals right is not sane`() {
        assertFalse(NodeResolver.isSaneBounds(50, 0, 50, 50))
    }

    @Test
    fun `zero width and height is not sane`() {
        assertFalse(NodeResolver.isSaneBounds(50, 50, 50, 50))
    }

    @Test
    fun `normal bounds are sane`() {
        assertTrue(NodeResolver.isSaneBounds(0, 0, 100, 50))
    }

    @Test
    fun `single pixel bounds are sane`() {
        assertTrue(NodeResolver.isSaneBounds(10, 10, 11, 11))
    }

    // ============================================================
    // F. True target below viewport: scroll forward
    // ============================================================

    @Test
    fun `target below viewport determines forward scroll`() {
        val direction = NodeResolver.determineScrollDirectionByBounds(
            bounds = listOf(0, 4000, 100, 4100),
            viewportTop = 0,
            viewportBottom = 2400
        )
        assertEquals(ScrollDirection.FORWARD, direction)
    }

    @Test
    fun `target slightly below viewport determines forward scroll`() {
        val direction = NodeResolver.determineScrollDirectionByBounds(
            bounds = listOf(0, 2500, 100, 2600), // center at 2550 > 2400
            viewportTop = 0,
            viewportBottom = 2400
        )
        assertEquals(ScrollDirection.FORWARD, direction)
    }

    // ============================================================
    // G. True target above viewport: scroll backward
    // ============================================================

    @Test
    fun `target above viewport determines backward scroll`() {
        val direction = NodeResolver.determineScrollDirectionByBounds(
            bounds = listOf(0, -500, 100, -400),
            viewportTop = 0,
            viewportBottom = 2400
        )
        assertEquals(ScrollDirection.BACKWARD, direction)
    }

    @Test
    fun `target slightly above viewport determines backward scroll`() {
        val direction = NodeResolver.determineScrollDirectionByBounds(
            bounds = listOf(0, -100, 100, 0), // center at -50 < 0
            viewportTop = 0,
            viewportBottom = 2400
        )
        assertEquals(ScrollDirection.BACKWARD, direction)
    }

    @Test
    fun `target inside viewport returns unknown direction`() {
        val direction = NodeResolver.determineScrollDirectionByBounds(
            bounds = listOf(0, 1000, 100, 1100), // center at 1050, inside 0..2400
            viewportTop = 0,
            viewportBottom = 2400
        )
        assertEquals(ScrollDirection.UNKNOWN, direction)
    }

    @Test
    fun `degenerate bounds returns unknown direction`() {
        val direction = NodeResolver.determineScrollDirectionByBounds(
            bounds = listOf(200, 400, 100, 300),
            viewportTop = 0,
            viewportBottom = 2400
        )
        assertEquals(ScrollDirection.UNKNOWN, direction)
    }

    // ============================================================
    // H. After scroll: original descriptor re-resolves against fresh hierarchy
    // ============================================================

    @Test
    fun `snapshot manager preserves descriptor for re-resolution`() {
        val descriptors = mapOf(
            1 to makeDescriptor(
                id = 1,
                packageName = "com.android.settings",
                text = "Battery",
                viewIdResourceName = "android:id/title"
            ),
            2 to makeDescriptor(
                id = 2,
                packageName = "com.android.settings",
                text = "Settings",
                viewIdResourceName = "android:id/title"
            )
        )
        val snapshotId = snapshotManager.store(descriptors, "com.android.settings")

        val original = snapshotManager.getDescriptor(snapshotId, 1)
        assertNotNull(original)
        assertEquals("Battery", original!!.text)
        assertEquals("android:id/title", original.viewIdResourceName)

        val mockNode = makeMockNode(
            viewId = "android:id/title",
            text = "Battery",
            isVisibleToUser = true
        )

        val score = original.score(mockNode)
        assertTrue("Descriptor should match re-resolved node (score=$score)", score > 0)
    }

    @Test
    fun `descriptor matches node with different class but same viewId and text`() {
        val descriptor = makeDescriptor(
            id = 1,
            packageName = "com.android.settings",
            viewIdResourceName = "android:id/title",
            text = "Battery",
            className = "android.widget.LinearLayout"
        )
        val mockNode = makeMockNode(
            viewId = "android:id/title",
            text = "Battery",
            className = "android.widget.TextView"
        )

        val score = descriptor.score(mockNode)
        assertTrue("Should match on viewId + text despite class mismatch (score=$score)", score >= 90)
    }

    // ============================================================
    // I. Duplicate Settings titles/viewIds: zero arbitrary first-match
    // ============================================================

    @Test
    fun `duplicate viewId and text returns equal scores for both candidates`() {
        val descriptor = makeDescriptor(
            id = 1,
            packageName = "com.android.settings",
            viewIdResourceName = "android:id/title",
            text = "Battery"
        )

        val node1 = makeMockNode(viewId = "android:id/title", text = "Battery")
        val node2 = makeMockNode(viewId = "android:id/title", text = "Battery")

        `when`(node1.childCount).thenReturn(1)
        `when`(node1.getChild(0)).thenReturn(node2)
        `when`(node2.childCount).thenReturn(0)
        `when`(node2.getChild(0)).thenReturn(null)

        val results = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        NodeResolver.collectCandidatesPublic(node1, descriptor, results)

        assertEquals(2, results.size)
        assertEquals(results[0].second, results[1].second)
    }

    @Test
    fun `semantic text scores same as direct text`() {
        val semanticDescriptor = makeDescriptor(
            id = 1,
            packageName = "com.android.settings",
            text = null,
            semanticText = "Battery",
            clickable = true
        )
        val directDescriptor = makeDescriptor(
            id = 2,
            packageName = "com.android.settings",
            text = "Battery",
            clickable = true
        )

        val mockNode = makeMockNode(text = "Battery")

        val semanticScore = semanticDescriptor.score(mockNode)
        val directScore = directDescriptor.score(mockNode)

        assertEquals("Semantic and direct text should produce same score", directScore, semanticScore)
    }

    @Test
    fun `semantic description scores same as direct description`() {
        val semanticDescriptor = makeDescriptor(
            id = 1,
            packageName = "com.android.settings",
            contentDescription = null,
            semanticDescription = "A battery setting",
            clickable = true
        )
        val directDescriptor = makeDescriptor(
            id = 2,
            packageName = "com.android.settings",
            contentDescription = "A battery setting",
            clickable = true
        )

        val mockNode = makeMockNode(contentDescription = "A battery setting")

        val semanticScore = semanticDescriptor.score(mockNode)
        val directScore = directDescriptor.score(mockNode)

        assertEquals("Semantic and direct description should produce same score", directScore, semanticScore)
    }

    @Test
    fun `descriptor without semantic fields does not match unrelated node`() {
        val descriptor = makeDescriptor(
            id = 1,
            packageName = "com.android.settings",
            text = null,
            contentDescription = null,
            semanticText = null,
            semanticDescription = null,
            clickable = true
        )
        val mockNode = makeMockNode(text = "Unrelated")

        val score = descriptor.score(mockNode)
        assertTrue("Should not match unrelated node (score=$score)", score <= 5)
    }

    // ============================================================
    // ElementId encode/decode tests
    // ============================================================

    @Test
    fun `encode produces snapshotId colon nodeId`() {
        assertEquals("24:86", ElementId(24, 86).encode())
    }

    @Test
    fun `encode with zero ids`() {
        assertEquals("0:0", ElementId(0, 0).encode())
    }

    @Test
    fun `decode valid element id`() {
        val eid = ElementId.decode("24:86")
        assertNotNull(eid)
        assertEquals(24L, eid!!.snapshotId)
        assertEquals(86, eid.nodeId)
    }

    @Test
    fun `decode with leading zeros`() {
        val eid = ElementId.decode("024:086")
        assertNotNull(eid)
        assertEquals(24L, eid!!.snapshotId)
        assertEquals(86, eid.nodeId)
    }

    @Test
    fun `decode missing colon returns null`() {
        assertNull(ElementId.decode("2486"))
    }

    @Test
    fun `decode empty string returns null`() {
        assertNull(ElementId.decode(""))
    }

    @Test
    fun `decode missing node portion returns null`() {
        assertNull(ElementId.decode("24:"))
    }

    @Test
    fun `decode missing snapshot portion returns null`() {
        assertNull(ElementId.decode(":86"))
    }

    @Test
    fun `decode non-numeric snapshot returns null`() {
        assertNull(ElementId.decode("abc:86"))
    }

    @Test
    fun `decode non-numeric node returns null`() {
        assertNull(ElementId.decode("24:xyz"))
    }

    @Test
    fun `decode negative snapshot returns null`() {
        assertNull(ElementId.decode("-1:86"))
    }

    @Test
    fun `decode negative node returns null`() {
        assertNull(ElementId.decode("24:-1"))
    }

    @Test
    fun `decode then encode roundtrip`() {
        val original = "42:100"
        val eid = ElementId.decode(original)
        assertNotNull(eid)
        assertEquals(original, eid!!.encode())
    }

    // ============================================================
    // SnapshotManager tests
    // ============================================================

    @Test
    fun `snapshot manager stores and retrieves correctly`() {
        val descriptors = mapOf(
            1 to makeDescriptor(id = 1, text = "Hello"),
            2 to makeDescriptor(id = 2, text = "World")
        )
        val snapshotId = snapshotManager.store(descriptors, "com.example")

        assertEquals("Hello", snapshotManager.getDescriptor(snapshotId, 1)?.text)
        assertEquals("World", snapshotManager.getDescriptor(snapshotId, 2)?.text)
        assertNull(snapshotManager.getDescriptor(snapshotId, 99))
    }

    @Test
    fun `old snapshot node ID does not leak into newer snapshot`() {
        val oldId = snapshotManager.store(
            mapOf(42 to makeDescriptor(id = 42, text = "Display")),
            "com.android.settings"
        )
        val newId = snapshotManager.store(
            mapOf(42 to makeDescriptor(id = 42, text = "Apps")),
            "com.android.settings"
        )

        assertEquals("Display", snapshotManager.getDescriptor(oldId, 42)!!.text)
        assertEquals("Apps", snapshotManager.getDescriptor(newId, 42)!!.text)
    }

    @Test
    fun `expired snapshot returns stale result`() {
        val snapshotId = snapshotManager.store(
            mapOf(1 to makeDescriptor(id = 1, text = "Display")),
            "com.example"
        )

        for (i in 1..15) {
            snapshotManager.store(mapOf(1 to makeDescriptor(id = 1, text = "Item $i")), "com.example")
        }

        assertFalse(snapshotManager.snapshotExists(snapshotId))
        assertNull(snapshotManager.getDescriptor(snapshotId, 1))
    }

    @Test
    fun `snapshot manager keeps bounded cache`() {
        val ids = (1..15).map { i ->
            snapshotManager.store(
                mapOf(i to makeDescriptor(id = i, text = "Item $i")),
                "com.example"
            )
        }

        assertNull(snapshotManager.getDescriptor(ids[0], 1))
        assertNull(snapshotManager.getDescriptor(ids[1], 2))
        assertNotNull(snapshotManager.getDescriptor(ids[14], 15))
    }

    @Test
    fun `non-existent snapshot returns null descriptor`() {
        assertNull(snapshotManager.getDescriptor(99999, 1))
        assertNull(snapshotManager.getSnapshotPackage(99999))
        assertFalse(snapshotManager.snapshotExists(99999))
    }

    // ============================================================
    // NodeDescriptor tests
    // ============================================================

    @Test
    fun `descriptor stores all fields correctly`() {
        val descriptor = makeDescriptor(
            id = 42,
            packageName = "com.example",
            viewIdResourceName = "example:id/button",
            text = "Click Me",
            contentDescription = "Button to click",
            className = "android.widget.Button",
            clickable = true
        )

        assertEquals(42, descriptor.id)
        assertEquals("com.example", descriptor.packageName)
        assertEquals("example:id/button", descriptor.viewIdResourceName)
        assertEquals("Click Me", descriptor.text)
        assertEquals("Button to click", descriptor.contentDescription)
        assertTrue(descriptor.clickable)
    }

    @Test
    fun `null packageName stored as null`() {
        assertNull(makeDescriptor(packageName = null).packageName)
    }

    @Test
    fun `descriptor stores clickable flag`() {
        assertFalse(makeDescriptor(clickable = false).clickable)
        assertTrue(makeDescriptor(clickable = true).clickable)
    }

    // ============================================================
    // J. Node lifetime: recycleAll properly recycles all nodes
    // ============================================================

    @Test
    fun `FreshTraversalResult stores all required fields`() {
        val mockNode = makeMockNode(text = "Test")
        val mockRoot = makeMockNode(text = "Root")
        val bounds = android.graphics.Rect()

        val result = FreshTraversalResult(
            matchedNode = mockNode,
            score = 90,
            method = "single_match",
            clickableAncestor = null,
            bounds = bounds,
            isVisible = true,
            rootNode = mockRoot
        )

        assertEquals(mockNode, result.matchedNode)
        assertEquals(90, result.score)
        assertEquals("single_match", result.method)
        assertNull(result.clickableAncestor)
        assertNotNull(result.bounds)
        assertTrue(result.isVisible)
        assertEquals(mockRoot, result.rootNode)
    }

    @Test
    fun `FreshTraversalResult with clickable ancestor`() {
        val mockNode = makeMockNode(text = "Child")
        val mockAncestor = makeMockNode(clickable = true)
        val mockRoot = makeMockNode(text = "Root")
        val bounds = android.graphics.Rect()

        val result = FreshTraversalResult(
            matchedNode = mockNode,
            score = 90,
            method = "single_match",
            clickableAncestor = mockAncestor,
            bounds = bounds,
            isVisible = true,
            rootNode = mockRoot
        )

        assertEquals(mockAncestor, result.clickableAncestor)
        assertEquals(mockNode, result.matchedNode)
    }

    @Test
    fun `NodeDescriptor score returns positive for matching node`() {
        val descriptor = makeDescriptor(
            id = 1,
            packageName = "com.android.settings",
            text = "Battery"
        )
        val mockNode = makeMockNode(text = "Battery")
        val score = descriptor.score(mockNode)
        assertTrue("Score should be positive for matching node (score=$score)", score > 0)
    }

    @Test
    fun `NodeDescriptor score returns -1 for mismatched package`() {
        val descriptor = makeDescriptor(
            id = 1,
            packageName = "com.android.settings",
            text = "Battery"
        )
        val mockNode = makeMockNode(
            packageName = "com.other.app",
            text = "Battery"
        )
        val score = descriptor.score(mockNode)
        assertEquals(-1, score)
    }

    @Test
    fun `NodeDescriptor score returns -1 for mismatched viewId`() {
        val descriptor = makeDescriptor(
            id = 1,
            packageName = "com.android.settings",
            viewIdResourceName = "android:id/title",
            text = "Battery"
        )
        val mockNode = makeMockNode(
            viewId = "android:id/other",
            text = "Battery"
        )
        val score = descriptor.score(mockNode)
        assertEquals(-1, score)
    }

    @Test
    fun `buildDescriptors creates descriptors from tree`() {
        val titleChild = makeNodeData(
            nodeId = 2,
            text = "Battery",
            viewIdResourceName = "android:id/title"
        )
        val parent = makeNodeData(
            nodeId = 1,
            clickable = true,
            children = listOf(titleChild)
        )

        val tree = com.agent.accessibility.model.AccessibilityTreeData(
            foregroundPackage = "com.android.settings",
            totalNodeCount = 2,
            root = parent
        )

        val descriptors = NodeResolver.buildDescriptors(tree)

        assertEquals(2, descriptors.size)
        assertNotNull(descriptors[1])
        assertNotNull(descriptors[2])
        assertEquals("Battery", descriptors[2]!!.text)
    }

    @Test
    fun `buildDescriptors stores bounds correctly`() {
        val node = makeNodeData(
            nodeId = 1,
            text = "Test",
            bounds = RectData(10, 20, 100, 50)
        )

        val tree = com.agent.accessibility.model.AccessibilityTreeData(
            foregroundPackage = "com.example",
            totalNodeCount = 1,
            root = node
        )

        val descriptors = NodeResolver.buildDescriptors(tree)
        val descriptor = descriptors[1]!!

        assertEquals(listOf(10, 20, 100, 50), descriptor.bounds)
    }

    @Test
    fun `scrollDirection forward for target below viewport`() {
        val direction = NodeResolver.determineScrollDirectionByBounds(
            bounds = listOf(0, 3000, 100, 3100),
            viewportTop = 0,
            viewportBottom = 2400
        )
        assertEquals(ScrollDirection.FORWARD, direction)
    }

    @Test
    fun `scrollDirection backward for target above viewport`() {
        val direction = NodeResolver.determineScrollDirectionByBounds(
            bounds = listOf(0, -100, 100, 0),
            viewportTop = 0,
            viewportBottom = 2400
        )
        assertEquals(ScrollDirection.BACKWARD, direction)
    }

    @Test
    fun `scrollDirection unknown for target inside viewport`() {
        val direction = NodeResolver.determineScrollDirectionByBounds(
            bounds = listOf(0, 1000, 100, 1100),
            viewportTop = 0,
            viewportBottom = 2400
        )
        assertEquals(ScrollDirection.UNKNOWN, direction)
    }
}
