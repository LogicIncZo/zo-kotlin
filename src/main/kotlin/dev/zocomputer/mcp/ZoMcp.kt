package dev.zocomputer.mcp

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

/**
 * Kotlin client for Zo Computer's MCP surface over the streamable-HTTP transport
 * (JSON-RPC 2.0 against https://api.zo.computer/mcp). Kotlin sibling of
 * EthanThatOneKid/zocomputer-tools (TypeScript).
 *
 * ```kotlin
 * val zo = ZoMcpClient(authToken = System.getenv("ZO_CLIENT_IDENTITY_TOKEN"))
 * val info = zo.initialize()
 * val tools = zo.listTools()
 * val out = zo.bash("echo hello")
 * if (out.isError) println("failed: ${out.text}") else println(out.text)
 * ```
 *
 * Tool-level failures arrive as [ToolResult.isError] results, not exceptions.
 * Transport failures (bad token, HTTP errors) throw [McpTransportException];
 * JSON-RPC errors throw [McpRpcException].
 */
class ZoMcpClient(
    private val url: String = DEFAULT_URL,
    private val authToken: String,
    private val clientName: String = "zo-mcp-sdk",
    private val clientVersion: String = "0.1.0",
    private val connectTimeoutMs: Long = 30_000,
    private val callTimeoutMs: Long = 600_000,
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .readTimeout(Duration.ofMillis(callTimeoutMs))
        .writeTimeout(Duration.ofMillis(connectTimeoutMs))
        .build()

    private var nextId = 1L
    private var sessionId: String? = null
    private var initialized = false
    private var cachedInfo = ServerInfo("", "", "")

    /**
     * Performs the MCP `initialize` handshake and the `notifications/initialized`
     * follow-up, capturing the `Mcp-Session-Id` header if the server assigns one.
     * The Zo endpoint is lenient (stateless `tools/list` works without a
     * handshake), so handshake failures degrade to stateless mode. Idempotent.
     */
    fun initialize(): ServerInfo {
        if (initialized) return cachedInfo
        val id = nextId++
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", clientName).put("version", clientVersion))
        val (envelope, session) = post(McpJson.rpcRequest(id, "initialize", params), id)
        session?.let { sessionId = it }
        cachedInfo = McpJson.parseServerInfo(envelopeOrThrow(envelope, "initialize"))
        initialized = true
        sendNotification("notifications/initialized")
        return cachedInfo
    }

    /** Lists every tool on the MCP surface (auto-initializes on first use). */
    fun listTools(): List<McpTool> =
        McpJson.parseTools(request("tools/list"))

    /** Calls a tool by exact MCP name with a JSON arguments object. */
    fun callTool(name: String, arguments: JSONObject = JSONObject()): ToolResult =
        McpJson.parseToolResult(request("tools/call", JSONObject().put("name", name).put("arguments", arguments)))

    /** Convenience: run a shell command on the Zo computer. */
    fun bash(cmd: String): ToolResult = callTool("bash", JSONObject().put("cmd", cmd))

    private fun request(method: String, params: JSONObject? = null): JSONObject {
        if (!initialized) initialize()
        val id = nextId++
        val (envelope, _) = post(McpJson.rpcRequest(id, method, params), id)
        return envelopeOrThrow(envelope, method)
    }

    private fun sendNotification(method: String, params: JSONObject? = null) {
        runCatching { post(McpJson.rpcNotification(method, params), expectedId = null) }
    }

    private fun envelopeOrThrow(envelope: McpJson.RpcEnvelope?, what: String): JSONObject {
        val env = envelope ?: throw McpTransportException("no response for `$what`")
        env.error?.let {
            throw McpRpcException(it.optInt("code", -1), it.optString("message").ifEmpty { "`$what` failed" })
        }
        return env.result ?: JSONObject()
    }

    /** Returns the parsed envelope (null when the body carried nothing) + any session header. */
    private fun post(body: String, expectedId: Long?): Pair<McpJson.RpcEnvelope?, String?> {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $authToken")
            .header("Accept", "application/json, text/event-stream")
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        val request = builder
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), body))
            .build()

        val response = runCatching { http.newCall(request).execute() }
            .getOrElse { throw McpTransportException("request to $url failed: ${it.message}", it) }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val detail = resp.body?.string().orEmpty().take(300)
                throw McpTransportException("HTTP ${resp.code} from $url: $detail")
            }
            val session = resp.header("Mcp-Session-Id") ?: resp.header("mcp-session-id")
            val contentType = resp.header("Content-Type") ?: ""
            val text = resp.body?.string().orEmpty()
            when {
                contentType.contains("text/event-stream") ->
                    return McpJson.extractFromSse(text, expectedId) to session
                text.isBlank() -> return null to session
                else -> return McpJson.extractFromJson(text) to session
            }
        }
    }

    companion object {
        const val DEFAULT_URL = "https://api.zo.computer/mcp"
        const val PROTOCOL_VERSION = "2025-03-26"
    }
}

/** Server identity returned by the `initialize` handshake. */
data class ServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
)

/** One tool from `tools/list`. [inputSchema] is the raw JSON Schema object. */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject? = null,
)

/** Result of `tools/call`: concatenated text blocks + error flag. */
data class ToolResult(
    val isError: Boolean,
    val text: String,
    val contentCount: Int,
)

class McpTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class McpRpcException(val code: Int, message: String) : RuntimeException("JSON-RPC $code: $message")

/**
 * Pure JSON-RPC/MCP framing — no I/O, fully unit-testable.
 * Handles both plain-JSON and SSE-framed responses.
 */
internal object McpJson {

    fun rpcRequest(id: Long, method: String, params: JSONObject?): String {
        val o = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) o.put("params", params)
        return o.toString()
    }

    fun rpcNotification(method: String, params: JSONObject? = null): String {
        val o = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) o.put("params", params)
        return o.toString()
    }

    /** Parses a plain JSON-RPC response body. */
    fun extractFromJson(text: String): RpcEnvelope = envelope(JSONObject(text))

    /**
     * Walks SSE `data:` lines, returning the first JSON-RPC response found
     * (optionally filtered by request id). Ignores keep-alives, comments,
     * notifications, and malformed frames.
     */
    fun extractFromSse(sseText: String, expectedId: Long?): RpcEnvelope? {
        for (line in sseText.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            if (expectedId != null && obj.optLong("id", Long.MIN_VALUE) != expectedId) continue
            if (!obj.has("result") && !obj.has("error")) continue
            return envelope(obj)
        }
        return null
    }

    data class RpcEnvelope(val result: JSONObject?, val error: JSONObject?)

    private fun envelope(obj: JSONObject): RpcEnvelope =
        RpcEnvelope(result = obj.optJSONObject("result"), error = obj.optJSONObject("error"))

    fun parseServerInfo(result: JSONObject): ServerInfo {
        val info = result.optJSONObject("serverInfo")
        return ServerInfo(
            name = info?.optString("name").orEmpty(),
            version = info?.optString("version").orEmpty(),
            protocolVersion = result.optString("protocolVersion"),
        )
    }

    fun parseTools(result: JSONObject): List<McpTool> {
        val arr = result.optJSONArray("tools") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = t.optString("name")
            if (name.isEmpty()) return@mapNotNull null
            McpTool(name = name, description = t.optString("description"), inputSchema = t.optJSONObject("inputSchema"))
        }
    }

    fun parseToolResult(result: JSONObject): ToolResult {
        val content = result.optJSONArray("content")
        val sb = StringBuilder()
        if (content != null) {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                val text = block.optString("text")
                if (text.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            }
        }
        return ToolResult(
            isError = result.optBoolean("isError", false),
            text = sb.toString(),
            contentCount = content?.length() ?: 0,
        )
    }
}
