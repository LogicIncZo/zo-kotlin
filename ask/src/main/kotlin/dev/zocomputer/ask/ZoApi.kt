package dev.zocomputer.ask

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal Zo API client: POST /zo/ask (SSE streaming), GET /models/available,
 * GET /personas/available. Auth: Bearer access token from Zo Settings > Advanced.
 */
/** Error raised for Zo API and stream failures; [isAuthError] marks 401/403 token problems. */
class ZoException(message: String, val isAuthError: Boolean = false) : Exception(message)

object ZoApi {

    const val BASE_URL = "https://api.zo.computer"

    data class Options(val token: String, val model: String, val persona: String)

    data class ModelInfo(val name: String, val label: String, val vendor: String)
    data class PersonaInfo(val id: String, val name: String)

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Sends [input] to /zo/ask with stream=true.
     * onDelta: assistant text chunk (OkHttp worker thread).
     * onStatus: transient runtime status ("Thinking...", tool activity) or null.
     * onDone: called once with the conversation id for follow-up turns.
     * onError: terminal failure.
     */
    fun ask(
        opts: Options,
        input: String,
        conversationId: String?,
        onDelta: (String) -> Unit,
        onStatus: (String?) -> Unit,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ): Call {
        val body = JSONObject().apply {
            put("input", input)
            put("stream", true)
            if (!conversationId.isNullOrBlank()) put("conversation_id", conversationId)
            if (opts.model.isNotBlank()) put("model_name", opts.model)
            if (opts.persona.isNotBlank()) put("persona_id", opts.persona)
        }.toString().toRequestBody(jsonMedia)

        val request = Request.Builder()
            .url("$BASE_URL/zo/ask")
            .header("Authorization", "Bearer ${opts.token}")
            .post(body)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onError(ZoException(networkMessage(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) {
                    response.close()
                    return
                }
                try {
                    val convId = consume(response, onDelta, onStatus)
                    if (!call.isCanceled()) onDone(convId)
                } catch (t: Throwable) {
                    if (call.isCanceled()) return
                    onError(if (t is ZoException) t else ZoException(t.message ?: "Unexpected error"))
                } finally {
                    response.close()
                }
            }
        })
        return call
    }

    private fun consume(
        response: Response,
        onDelta: (String) -> Unit,
        onStatus: (String?) -> Unit,
    ): String {
        if (!response.isSuccessful) {
            val snippet = response.body?.string().orEmpty().take(300)
            throw when (response.code) {
                401, 403 -> ZoException("Unauthorized — check your Zo API token in Settings.", isAuthError = true)
                429 -> ZoException("Rate limited by the Zo API. Wait a moment and try again.")
                else -> ZoException("Zo API error ${response.code}: $snippet")
            }
        }
        val contentType = response.header("Content-Type") ?: ""
        if (contentType.contains("text/event-stream")) {
            parseSse(response, onDelta, onStatus)
        } else {
            val text = response.body?.string().orEmpty()
            if (text.isNotBlank()) {
                val obj = JSONObject(text)
                if (obj.has("error")) throw ZoException(obj.optString("error"))
                val output = obj.opt("output")
                val out = when (output) {
                    null -> ""
                    is String -> output
                    else -> output.toString()
                }
                if (out.isNotEmpty()) onDelta(out)
            }
        }
        return response.header("x-conversation-id") ?: ""
    }

    private fun parseSse(response: Response, onDelta: (String) -> Unit, onStatus: (String?) -> Unit) {
        val source = response.body?.source() ?: throw ZoException("Empty response body")
        val parser = ZoSseParser(onDelta, onStatus)
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (parser.feedLine(line)) break
        }
    }

    suspend fun listModels(token: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/models/available")
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ZoException("Could not load models (HTTP ${resp.code}).")
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("models")
            if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = m.optString("model_name")
                if (name.isEmpty()) null
                else ModelInfo(name, m.optString("label", name), m.optString("vendor"))
            }
        }
    }

    suspend fun listPersonas(token: String): List<PersonaInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/personas/available")
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ZoException("Could not load personas (HTTP ${resp.code}).")
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("personas")
            if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = p.optString("id")
                if (id.isEmpty()) null else PersonaInfo(id, p.optString("name", id))
            }
        }
    }

    private fun networkMessage(e: IOException): String =
        "Network error — can't reach api.zo.computer (${e.message ?: "offline"})."
}
