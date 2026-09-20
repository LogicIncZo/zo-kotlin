package dev.zocomputer.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationJsonTest {

    @Test
    fun `parses bare array of conversations`() {
        val body = """[
            {"id":"con_1","title":"UPI cost notes","updated_at":"2026-09-20T09:00:00Z","last_message":"ok"},
            {"id":"con_2","title":"BoB follow-up"}
        ]"""
        val out = ZoConversations.parseConversationList(body)
        assertEquals(2, out.size)
        assertEquals("con_1", out[0].id)
        assertEquals("UPI cost notes", out[0].title)
        assertEquals("2026-09-20T09:00:00Z", out[0].updatedAt)
        assertEquals("ok", out[0].preview)
        assertNull(out[1].updatedAt)
    }

    @Test
    fun `parses wrapped conversations object`() {
        val body = """{"conversations":[{"conversation_id":"con_9","summary":"Wrapped shape"}]}"""
        val out = ZoConversations.parseConversationList(body)
        assertEquals(1, out.size)
        assertEquals("con_9", out[0].id)
        assertEquals("Wrapped shape", out[0].title)
    }

    @Test
    fun `falls back to Untitled when no title-ish field`() {
        val body = """[{"id":"con_3"}]"""
        val out = ZoConversations.parseConversationList(body)
        assertEquals("Untitled", out[0].title)
    }

    @Test
    fun `skips rows without an id`() {
        val body = """[{"title":"no id"},{"id":"con_4","name":"kept"}]"""
        val out = ZoConversations.parseConversationList(body)
        assertEquals(1, out.size)
        assertEquals("con_4", out[0].id)
        assertEquals("kept", out[0].title)
    }

    @Test
    fun `returns empty on garbage`() {
        assertTrue(ZoConversations.parseConversationList("not json").isEmpty())
        assertTrue(ZoConversations.parseConversationList("""{"foo":1}""").isEmpty())
    }

    @Test
    fun `parses wrapped message history`() {
        val body = """{"messages":[
            {"role":"user","content":"what changed in UPI MDR"},
            {"sender":"assistant","text":"The MDR is zero.","created_at":"2026-09-20T09:00:00Z"}
        ]}"""
        val out = ZoConversations.parseHistory(body)
        assertEquals(2, out.size)
        assertEquals("user", out[0].role)
        assertEquals("what changed in UPI MDR", out[0].text)
        assertEquals("assistant", out[1].role)
        assertEquals("The MDR is zero.", out[1].text)
        assertEquals(1789894800000L, out[1].ts)
    }

    @Test
    fun `maps human sender to user and skips empty content`() {
        val body = """[
            {"role":"human","content":"hi"},
            {"role":"assistant"},
            {"role":"assistant","body":"second message"}
        ]"""
        val out = ZoConversations.parseHistory(body)
        assertEquals(2, out.size)
        assertEquals("user", out[0].role)
        assertEquals("second message", out[1].text)
    }

    @Test
    fun `digest labels speakers and truncates long text`() {
        val long = "x".repeat(5000)
        val msgs = listOf(
            HistoryMessage("user", long, 1),
            HistoryMessage("assistant", "Short answer", 2),
        )
        val digest = ZoConversations.speakableDigest(msgs)
        assertTrue(digest.startsWith("You said: "))
        assertTrue(digest.contains("Zo said: Short answer"))
        assertTrue(digest.length < 1200)
    }

    @Test
    fun `digest on empty history explains itself`() {
        assertEquals(
            "No messages found in that conversation.",
            ZoConversations.speakableDigest(emptyList()),
        )
    }
}

@Test
fun `parses map-shaped conversations keyed by id`() {
    val body = """{
        "con_7": {"title": "MDR thread", "updated_at": "2026-09-20T10:00:00Z"},
        "con_8": {"title": "BoB thread"}
    }"""
    val out = ZoConversations.parseConversationList(body)
    assertEquals(2, out.size)
    assertEquals("con_7", out[0].id)
    assertEquals("MDR thread", out[0].title)
}

@Test
fun `parses wrapped map-shaped conversations`() {
    val body = """{"conversations": {"con_9": {"name": "Wrapped map"}}}"""
    val out = ZoConversations.parseConversationList(body)
    assertEquals(1, out.size)
    assertEquals("con_9", out[0].id)
    assertEquals("Wrapped map", out[0].title)
}
