package dev.zocomputer.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceChunkerTest {

    @Test
    fun `splits complete sentences as they stream in`() {
        val out = mutableListOf<String>()
        val c = SentenceChunker { out.add(it) }
        c.feed("Hello there. ")
        c.feed("Second sentence! And a frag")
        assertEquals(listOf("Hello there.", "Second sentence!"), out)
        c.flush()
        assertEquals(listOf("Hello there.", "Second sentence!", "And a frag"), out)
    }

    @Test
    fun `newline acts as a segment break`() {
        val out = mutableListOf<String>()
        val c = SentenceChunker { out.add(it) }
        c.feed("Line one\nLine two\n")
        assertEquals(listOf("Line one", "Line two"), out)
    }

    @Test
    fun `flush emits trailing partial without ender`() {
        val out = mutableListOf<String>()
        val c = SentenceChunker { out.add(it) }
        c.feed("Complete. Partial tail")
        assertEquals(listOf("Complete."), out)
        c.flush()
        assertEquals(listOf("Complete.", "Partial tail"), out)
    }

    @Test
    fun `reset drops buffered text`() {
        val out = mutableListOf<String>()
        val c = SentenceChunker { out.add(it) }
        c.feed("half a sentence")
        c.reset()
        c.flush()
        assertEquals(emptyList<String>(), out)
    }

    @Test
    fun `sanitize strips markdown fences urls and symbols`() {
        assertEquals("Code block. Done", SentenceChunker.sanitize("```py\nx=1\n``` Done"))
        assertEquals("see docs", SentenceChunker.sanitize("see (https://example.com/a?b=c) docs"))
        assertEquals("bold text", SentenceChunker.sanitize("**bold** `text` #>"))
    }

    @Test
    fun `sanitize collapses whitespace`() {
        assertEquals("a b", SentenceChunker.sanitize("  a\n\n b  "))
    }
}
