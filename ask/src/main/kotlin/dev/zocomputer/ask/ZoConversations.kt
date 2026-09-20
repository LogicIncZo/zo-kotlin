package dev.zocomputer.ask

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ZoConversation(
    val id: String,
    val title: String,
    val updatedAt: String?,
    val preview: String?,
    val local: Boolean = false,
)

/**
 * Existing-conversation support: list conversations, fetch history, build
 * spoken digests. Endpoints (GET /conversations, GET /conversations/{id})
 * exist on the API but were unverified at build time, so parsing is
 * shape-tolerant: bare arrays or common wrapper keys, aliased field names.
 * Failures raise ZoException and surface in the UI without breaking chat.
 */
/** Diagnostic result for [ZoConversations.listWithMeta]. */
data class ConversationFetch(val httpCode: Int, val bodyHead: String, val conversations: List<ZoConversation>)

object ZoConversations {
    const val BASE_URL = "https://api.zo.computer"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Diagnostic fetch: returns HTTP code, first bytes of the raw body and the
     * parsed list (possibly empty). Never throws for HTTP-level errors — the
     * caller decides what to show. Network IOExceptions still propagate.
     */
    fun listWithMeta(token: String): ConversationFetch {
        val req = Request.Builder()
            .url("$BASE_URL/conversations")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val parsed = if (resp.isSuccessful) parseConversationList(body) else emptyList()
            return ConversationFetch(resp.code, body.take(400), parsed)
        }
    }

    fun list(token: String): List<ZoConversation> {
        val req = Request.Builder()
            .url("$BASE_URL/conversations")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ZoException(
                    "Could not list conversations (HTTP ${resp.code})" +
                        if (resp.code == 401 || resp.code == 403) " — token may lack access." else ".",
                    isAuthError = resp.code == 401 || resp.code == 403,
                )
            }
            return parseConversationList(body)
        }
    }

    fun history(token: String, conversationId: String): List<HistoryMessage> {
        val req = Request.Builder()
            .url("$BASE_URL/conversations/$conversationId")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ZoException(
                    "Could not fetch conversation history (HTTP ${resp.code})" +
                        if (resp.code == 401 || resp.code == 403) " — token may lack access." else ".",
                    isAuthError = resp.code == 401 || resp.code == 403,
                )
            }
            return parseHistory(body)
        }
    }

    /** Bare array, or an object wrapping the array under a known key. */
    fun unwrapArray(body: String): JSONArray? {
        val root = runCatching { JSONObject(body) }.getOrNull()
        if (root != null) {
            for (key in listOf(
                "conversations", "chats", "threads", "items", "rows",
                "data", "results", "result", "messages", "history", "entries",
            )) {
                val v = root.optJSONArray(key)
                if (v != null) return v
            }
            return null
        }
        return runCatching { JSONArray(body) }.getOrNull()
    }

    /**
     * Map-shaped fallback: some APIs key conversations by id, e.g.
     * {"con_abc": {...}} or {"conversations": {"con_abc": {...}}}.
     * Collects any object value that carries an id-ish field.
     */
    fun parseConversationMap(body: String): List<ZoConversation> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val candidates = mutableListOf<JSONObject>()
        for (key in listOf("conversations", "chats", "threads", "data", "result")) {
            val inner = root.optJSONObject(key)
            if (inner != null) { candidates.add(inner); break }
        }
        if (candidates.isEmpty()) candidates.add(root)
        val out = ArrayList<ZoConversation>()
        for (container in candidates) {
            val keys = container.keys()
            while (keys.hasNext()) {
                val o = container.optJSONObject(keys.next()) ?: continue
                val id = firstString(o, "id", "conversation_id", "uuid", "pk") ?: continue
                val title = firstString(o, "title", "name", "summary", "label") ?: "Untitled"
                val ts = firstString(o, "updated_at", "last_message_at", "modified_at", "updated", "created_at")
                val preview = firstString(o, "last_message", "preview", "snippet", "excerpt", "last_user_message")
                out.add(ZoConversation(id, title, ts, preview))
            }
        }
        return out
    }

    fun parseConversationList(body: String): List<ZoConversation> {
        val arr = unwrapArray(body) ?: return parseConversationMap(body)
        val out = ArrayList<ZoConversation>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = firstString(o, "id", "conversation_id", "uuid", "pk") ?: continue
            val title = firstString(o, "title", "name", "summary", "label") ?: "Untitled"
            val ts = firstString(
                o, "updated_at", "last_message_at", "modified_at", "updated", "created_at",
            )
            val preview = firstString(
                o, "last_message", "preview", "snippet", "excerpt", "last_user_message",
            )
            out.add(ZoConversation(id = id, title = title, updatedAt = ts, preview = preview))
        }
        return out
    }

    fun parseHistory(body: String): List<HistoryMessage> {
        val arr = unwrapArray(body) ?: return emptyList()
        val out = ArrayList<HistoryMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val roleRaw = (firstString(o, "role", "sender", "author", "who") ?: "user").lowercase()
            val role = if ("user" in roleRaw || "human" in roleRaw) "user" else "assistant"
            val text = firstString(o, "content", "text", "message", "body") ?: continue
            out.add(
                HistoryMessage(
                    role = role,
                    text = text,
                    ts = parseWhen(o, "created_at", "timestamp", "ts", "time"),
                )
            )
        }
        return out
    }

    /** Spoken digest of the latest turns, role-labeled and TTS-sanitized. */
    fun speakableDigest(messages: List<HistoryMessage>, max: Int = 3): String {
        val recent = messages.takeLast(max)
        if (recent.isEmpty()) return "No messages found in that conversation."
        return recent.joinToString(". ") { m ->
            val who = if (m.role == "user") "You said" else "Zo said"
            "$who: " + SentenceChunker.sanitize(m.text.take(400))
        } + "."
    }

    /** RFC3339/ISO string or epoch seconds/millis number -> epoch millis; 0 when unparsable. */
    fun parseWhen(o: JSONObject, vararg keys: String): Long {
        for (k in keys) {
            when (val v = o.opt(k)) {
                is Number -> {
                    val n = v.toLong()
                    return if (n > 1_000_000_000_000L) n else n * 1000
                }
                is String -> {
                    val s = v.trim()
                    runCatching { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
                    runCatching { return java.time.Instant.parse(s).toEpochMilli() }
                    s.toLongOrNull()?.let { n -> return if (n > 1_000_000_000_000L) n else n * 1000 }
                }
            }
        }
        return 0L
    }

    private fun firstString(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) return v
        }
        return null
    }
}
