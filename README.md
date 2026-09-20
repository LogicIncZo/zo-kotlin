# zo-kotlin

Kotlin SDK for [Zo Computer](https://zo.computer) — two JVM modules, one repo:

| Module | Surface | Coordinates |
| --- | --- | --- |
| `ask` | `POST /zo/ask` (SSE streaming chat), `GET /models/available`, `GET /personas/available`, `GET /conversations`(+`/{id}`), sentence chunking / TTS sanitization | `dev.zocomputer:ask` |
| `mcp` | `https://api.zo.computer/mcp` — JSON-RPC 2.0 over streamable HTTP, all 104 agent tools | `dev.zocomputer:mcp` |

Kotlin sibling of EthanThatOneKid's [`zocomputer-tools`](https://github.com/EthanThatOneKid/zocomputer-tools)
(TS). Auth: `Authorization: Bearer <token>` with a Zo access token (`zo_sk_…`) or the
session identity token.

## Usage

```kotlin
// chat with streaming deltas (callback API; Call.cancel() is the cancel path)
val zo = ZoApi(
    options = ZoApi.Options(token = System.getenv("ZO_API_KEY")),
)
val call = ZoApi.ask(
    opts = zo.options,
    input = "hello",
    conversationId = null,
    onDelta = { /* assistant text chunk */ },
    onStatus = { /* "Thinking…" etc. */ },
    onDone = { convId -> /* keep convId to continue the chat */ },
    onError = { err -> },
)

// conversations (shape-tolerant: bare array or wrapped, field aliases, ISO/epoch ts)
val convs = ZoConversations.list(token)
val history = ZoConversations.history(token, convs.first().id)
println(ZoConversations.speakableDigest(history))

// MCP surface
val client = ZoMcpClient(auth = System.getenv("ZO_API_KEY"))
client.connect()
client.toolsCall("web_search", JSONObject().put("query", "upi mdr"))
```

## Verify

```sh
make verify   # 33 JVM unit tests across both modules + build
make demo     # live MCP round-trip (needs ZO_CLIENT_IDENTITY_TOKEN)
```

Pure logic (SSE framing, conversation JSON, sentence chunking) lives in framework-free
classes under `ask/src/main/kotlin/dev/zocomputer/ask/` so coverage stays on the JVM.
