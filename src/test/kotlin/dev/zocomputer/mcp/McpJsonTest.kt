package dev.zocomputer.mcp

import org.json.JSONObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class McpJsonTest {

    @Test
    fun `rpc request frames jsonrpc id method params`() {
        val body = McpJson.rpcRequest(7L, "tools/list", JSONObject())
        val o = JSONObject(body)
        assertEquals("2.0", o.getString("jsonrpc"))
        assertEquals(7L, o.getLong("id"))
        assertEquals("tools/list", o.getString("method"))
        assertTrue(o.has("params"))
    }

    @Test
    fun `rpc request omits params when null`() {
        val o = JSONObject(McpJson.rpcRequest(1L, "ping", null))
        assertFalse(o.has("params"))
    }

    @Test
    fun `notification has no id`() {
        val o = JSONObject(McpJson.rpcNotification("notifications/initialized"))
        assertFalse(o.has("id"))
        assertEquals("notifications/initialized", o.getString("method"))
    }

    @Test
    fun `extracts plain json response`() {
        val env = McpJson.extractFromJson("""{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""")
        assertTrue(env.error == null)
        assertTrue(env.result!!.has("tools"))
    }

    @Test
    fun `extracts jsonrpc error envelope`() {
        val env = McpJson.extractFromJson("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"no such method"}}""")
        assertEquals(-32601, env.error!!.getInt("code"))
        assertEquals("no such method", env.error!!.getString("message"))
    }

    @Test
    fun `extracts response from sse frames`() {
        val sse = """
            id: x@1
            event: message
            data: {"jsonrpc":"2.0","method":"notify/progress","params":{}}

            id: x@2
            event: message
            data: {"jsonrpc":"2.0","id":9,"result":{"content":[{"type":"text","text":"pong"}]}}

        """.trimIndent()
        val env = McpJson.extractFromSse(sse, expectedId = 9L)
        assertTrue(env != null)
        assertEquals("pong", McpJson.parseToolResult(env!!.result!!).text)
    }

    @Test
    fun `sse extraction ignores other ids and garbage`() {
        val sse = """
            : keep-alive

            data: [DONE]

            data: not json
            data: {"jsonrpc":"2.0","id":5,"result":{}}
        """.trimIndent()
        assertNull(McpJson.extractFromSse(sse, expectedId = 9L))
        val env = McpJson.extractFromSse(sse, expectedId = 5L)
        assertTrue(env != null)
    }

    @Test
    fun `parses server info`() {
        val info = McpJson.parseServerInfo(
            JSONObject(
                """{"protocolVersion":"2025-03-26","serverInfo":{"name":"mcpo","version":"1.2.3"}}""",
            ),
        )
        assertEquals("mcpo", info.name)
        assertEquals("1.2.3", info.version)
        assertEquals("2025-03-26", info.protocolVersion)
    }

    @Test
    fun `parses tools and skips unnamed`() {
        val result = JSONObject(
            """{"tools":[
                {"name":"bash","description":"Run a command","inputSchema":{"type":"object"}},
                {"description":"no name"},
                {"name":"list_rules","description":"List rules"}
            ]}""",
        )
        val tools = McpJson.parseTools(result)
        assertEquals(2, tools.size)
        assertEquals("bash", tools[0].name)
        assertEquals("list_rules", tools[1].name)
        assertEquals("object", tools[0].inputSchema!!.getString("type"))
    }

    @Test
    fun `parses tool result with text blocks and error flag`() {
        val result = JSONObject(
            """{"isError":true,"content":[
                {"type":"text","text":"line one"},
                {"type":"text","text":"line two"},
                {"type":"image","data":"..."}
            ]}""",
        )
        val out = McpJson.parseToolResult(result)
        assertTrue(out.isError)
        assertEquals(3, out.contentCount)
        assertEquals("line one\nline two", out.text)
    }

    @Test
    fun `tool result defaults to empty and not-error`() {
        val out = McpJson.parseToolResult(JSONObject())
        assertFalse(out.isError)
        assertEquals("", out.text)
        assertEquals(0, out.contentCount)
    }
}
