package dev.zocomputer.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event samples taken from live /zo/ask captures (2026-09-20).
 * If Zo's stream format changes, update these fixtures from a fresh capture.
 */
class ZoSseParserTest {

    private class Capture {
        val deltas = mutableListOf<String>()
        val statuses = mutableListOf<String?>()
        fun parser() = ZoSseParser({ deltas.add(it) }, { statuses.add(it) })
    }

    @Test
    fun `text parts accumulate and thinking parts are ignored`() {
        val c = Capture()
        val p = c.parser()
        val lines = listOf(
            "event: PartStartEvent",
            """data: {"event_kind":"part_start","index":0,"part":{"content":"The keeper","part_kind":"thinking","id":"reasoning_content"}}""",
            "event: PartDeltaEvent",
            """data: {"delta":{"content_delta":" watching","part_delta_kind":"thinking"},"event_kind":"part_delta","index":0}""",
            "event: PartStartEvent",
            """data: {"event_kind":"part_start","index":1,"part":{"content":"The","part_kind":"text"}}""",
            "event: PartDeltaEvent",
            """data: {"delta":{"content_delta":" lighthouse","part_delta_kind":"text"},"event_kind":"part_delta","index":1}""",
            "event: PartDeltaEvent",
            """data: {"delta":{"content_delta":" held.","part_delta_kind":"text"},"event_kind":"part_delta","index":1}""",
        )
        val done = lines.fold(false) { acc, line -> p.feedLine(line) || acc }
        assertEquals(false, done)
        assertEquals(listOf("The", " lighthouse", " held."), c.deltas)
        assertTrue(c.statuses.isEmpty())
    }

    @Test
    fun `status chunks surface messages, other runtime chunks do not`() {
        val c = Capture()
        val p = c.parser()
        p.feedLine("event: AgentRuntimeStreamChunk")
        p.feedLine("""data: {"type":"status","data":{"message":"Thinking…","phase":"model_request"}}""")
        p.feedLine("event: AgentRuntimeStreamChunk")
        p.feedLine("""data: {"type":"persisted","data":{"message_id":"abc"}}""")
        assertEquals(listOf("Thinking…"), c.statuses)
        assertTrue(c.deltas.isEmpty())
    }

    @Test
    fun `completed succeeded signals end of stream`() {
        val c = Capture()
        val p = c.parser()
        p.feedLine("event: completed\r")
        val done = p.feedLine("""data: {"status":"succeeded","error":null}""")
        assertTrue(done)
    }

    @Test
    fun `completed failure throws`() {
        val p = ZoSseParser({}, {})
        p.feedLine("event: completed")
        val ex = assertThrows(ZoException::class.java) {
            p.feedLine("""data: {"status":"failed","error_type":"quota"}""")
        }
        assertTrue(ex.message!!.contains("failed: quota"))
    }

    @Test
    fun `error event throws with its message`() {
        val p = ZoSseParser({}, {})
        p.feedLine("event: Error")
        val ex = assertThrows(ZoException::class.java) {
            p.feedLine("""data: {"message":"model unavailable"}""")
        }
        assertEquals("model unavailable", ex.message)
    }

    @Test
    fun `docs-documented FrontendModelResponse shape is honoured`() {
        val c = Capture()
        val p = c.parser()
        p.feedLine("event: FrontendModelResponse")
        p.feedLine("""data: {"content":"hello voice"}""")
        assertEquals(listOf("hello voice"), c.deltas)
    }

    @Test
    fun `malformed data lines and unrelated lines are ignored`() {
        val c = Capture()
        val p = c.parser()
        p.feedLine("id: 3907e0ce@1789870143394-0")
        p.feedLine("")
        p.feedLine("event: PartDeltaEvent")
        p.feedLine("data: {broken json")
        p.feedLine("data: [DONE]")
        assertTrue(c.deltas.isEmpty())
    }
}
