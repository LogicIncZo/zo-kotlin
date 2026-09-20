package dev.zocomputer.ask

import org.json.JSONObject

/**
 * Pure parser for Zo's /zo/ask SSE event stream (no Android/network deps — JVM-testable).
 *
 * Stream facts verified live 2026-09-20 against api.zo.computer:
 *  - PartStartEvent: part.part_kind == "text" | "thinking"; only "text" carries the reply.
 *  - PartDeltaEvent: delta.part_delta_kind == "text" | "thinking"; append delta.content_delta for "text".
 *  - AgentRuntimeStreamChunk type=status carries data.message like "Thinking...".
 *  - completed: data.status "succeeded" or a failure + error_type.
 *  - Error: data.message.
 *  - FrontendModelResponse (docs-documented shape): data.content.
 */
class ZoSseParser(
    private val onDelta: (String) -> Unit,
    private val onStatus: (String?) -> Unit,
) {
    private var currentEvent = ""

    /** Feed one raw SSE line. Returns true when the stream signalled completion. */
    fun feedLine(rawLine: String): Boolean {
        val line = rawLine.trimEnd('\r')
        return when {
            line.startsWith("event:") -> {
                currentEvent = line.removePrefix("event:").trim()
                false
            }
            line.startsWith("data:") -> handleData(currentEvent, line.removePrefix("data:").trim())
            else -> false
        }
    }

    private fun handleData(event: String, data: String): Boolean {
        if (data.isEmpty() || data == "[DONE]") return false
        val obj = try {
            JSONObject(data)
        } catch (_: Exception) {
            return false
        }
        when (event) {
            "PartStartEvent" -> {
                val part = obj.optJSONObject("part") ?: return false
                if (part.optString("part_kind") == "text") {
                    val content = part.optString("content")
                    if (content.isNotEmpty()) onDelta(content)
                }
            }
            "PartDeltaEvent" -> {
                val delta = obj.optJSONObject("delta") ?: return false
                if (delta.optString("part_delta_kind") == "text") {
                    val d = delta.optString("content_delta")
                    if (d.isNotEmpty()) onDelta(d)
                }
            }
            "FrontendModelResponse" -> {
                val content = obj.optString("content")
                if (content.isNotEmpty()) onDelta(content)
            }
            "AgentRuntimeStreamChunk" -> {
                if (obj.optString("type") == "status") {
                    val msg = obj.optJSONObject("data")?.optString("message").orEmpty()
                    onStatus(msg.ifEmpty { obj.optString("status").ifEmpty { null } })
                }
            }
            "completed" -> {
                val status = obj.optString("status")
                if (status.isNotEmpty() && status != "succeeded") {
                    val detail = obj.optString("error_type")
                    throw ZoException("Zo run $status" + (if (detail.isNotEmpty()) ": $detail" else ""))
                }
                return true
            }
            "Error" -> throw ZoException(obj.optString("message").ifEmpty { "Zo stream error" })
        }
        return false
    }
}
