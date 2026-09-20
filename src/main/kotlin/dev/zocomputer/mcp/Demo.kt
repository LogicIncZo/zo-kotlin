package dev.zocomputer.mcp

import org.json.JSONObject

/**
 * Live demo against https://api.zo.computer/mcp.
 * Run: ZO_CLIENT_IDENTITY_TOKEN=... ./gradlew run   (or: gradle installDist)
 * Only read-only tools are called: bash echo + list_space_routes.
 */
fun main() {
    val token = System.getenv("ZO_CLIENT_IDENTITY_TOKEN")
        ?: error("Set ZO_CLIENT_IDENTITY_TOKEN (or an access token) first.")

    val zo = ZoMcpClient(authToken = token)

    val info = zo.initialize()
    println("server   : ${info.name} v${info.version} (protocol ${info.protocolVersion})")

    val tools = zo.listTools()
    println("tools    : ${tools.size}")
    println("sample   : ${tools.take(6).joinToString(", ") { it.name }}")

    val echo = zo.bash("echo hello from the Kotlin MCP SDK on \$(hostname)")
    println("bash     : isError=${echo.isError}")
    println(echo.text.trim())

    val routes = zo.callTool("list_space_routes", JSONObject())
    println("routes   : isError=${routes.isError}")
    println(routes.text.lineSequence().take(6).joinToString("\n"))
}
