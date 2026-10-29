package com.agent.accessibility.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * R-14: the element id is documented and validated one way. `id` is
 * canonical; `elementId`/`nodeId` are accepted legacy aliases.
 *
 * [parseElementId] itself needs an McpHandler (Android Context) to frame
 * its error response, so these tests pin the pure pieces it delegates to:
 * [parseElementIdRaw] (key resolution) and [ElementId.decode] (parsing).
 */
class ParseElementIdTest {

    @Test
    fun `empty args yield the missing-field error naming id`() {
        assertNull(parseElementIdRaw(JSONObject()))
        assertTrue(
            ELEMENT_ID_MISSING_MESSAGE.contains("missing required field 'id'")
        )
        assertTrue(ELEMENT_ID_MISSING_MESSAGE.contains("elementId"))
        assertTrue(ELEMENT_ID_MISSING_MESSAGE.contains("nodeId"))
    }

    @Test
    fun `id elementId nodeId all parse to the same ElementId`() {
        val expected = ElementId.decode("1:7")
        assertNotNull(expected)
        for (key in listOf("id", "elementId", "nodeId")) {
            val raw = parseElementIdRaw(JSONObject().put(key, "1:7"))
            assertEquals("1:7", raw)
            assertEquals(expected, ElementId.decode(raw!!))
        }
    }

    @Test
    fun `id wins over legacy aliases`() {
        val raw = parseElementIdRaw(
            JSONObject().put("id", "1:7").put("elementId", "2:9").put("nodeId", "3:11")
        )
        assertEquals("1:7", raw)
    }

    @Test
    fun `blank id falls through to aliases`() {
        assertEquals("3:11", parseElementIdRaw(JSONObject().put("id", "").put("nodeId", "3:11")))
        assertEquals("2:9", parseElementIdRaw(JSONObject().put("elementId", "2:9")))
        assertNull(parseElementIdRaw(JSONObject().put("id", "  ")))
    }
}
