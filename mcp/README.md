# zo-mcp-sdk

Kotlin SDK for Zo Computer's MCP surface (`https://api.zo.computer/mcp`) — JSON-RPC 2.0
over streamable HTTP. Pure Kotlin + OkHttp, zero MCP SDK dependencies, JVM 17.

Authored after studying `EthanThatOneKid/zocomputer-tools` (TypeScript client for the
same endpoint) — this is the Kotlin equivalent.

## Usage

```kotlin
import dev.zocomputer.mcp.ZoComputer

val zo = ZoComputer(
    endpoint = "https://api.zo.computer/mcp",
    token = System.getenv("ZO_CLIENT_IDENTITY_TOKEN"),
)
zo.connect()                    // initialize + notifications/initialized handshake
val tools = zo.listTools()      // 104 tools
val result = zo.toolsCall(
    name = "bash",
    args = """{"cmd":"echo hi"}""",
)
println(result.text)            // joined text blocks; result.isError on failure
```

Inside a Zo sandbox the token is pre-bound as `ZO_CLIENT_IDENTITY_TOKEN`. From outside,
create an access token at Zo → Settings → Advanced → Access Tokens and pass it in.

Note: the MCP surface exposes Zo's *agent tools* (bash, web search, image gen, app
integrations…). It does not expose conversation history — use the `zo_sk_` REST API
(`/conversations`) for that.

## Layout

- `src/main/kotlin/dev/zocomputer/mcp/ZoComputer.kt` — client facade
- `src/main/kotlin/dev/zocomputer/mcp/ZoMcp.kt` — transport + parsing
- `src/main/kotlin/dev/zocomputer/mcp/McpJson.kt` — JSON-RPC frame builders/parsers
- `src/main/kotlin/dev/zocomputer/mcp/Demo.kt` — live demo (`make demo`)
- `src/test/kotlin/` — 11 JVM unit tests (frames, SSE extraction, parse robustness)

## Verify

```bash
make test    # unit tests
make verify  # tests + build
make demo    # live round-trip against api.zo.computer
```
