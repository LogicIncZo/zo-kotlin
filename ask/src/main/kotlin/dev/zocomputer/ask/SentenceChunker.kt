package dev.zocomputer.ask

/**
 * Pure sentence-chunker for streaming TTS (no Android deps — JVM-testable).
 * Buffers stream chunks, emits sanitized sentence-sized segments as enders
 * arrive (. ! ? newline); flush() emits any trailing partial sentence.
 */
class SentenceChunker(private val onSentence: (String) -> Unit) {
    private val buffer = StringBuilder()

    fun feed(chunk: String) {
        buffer.append(chunk)
        drain(final = false)
    }

    fun flush() = drain(final = true)

    fun reset() {
        synchronized(buffer) { buffer.setLength(0) }
    }

    private fun drain(final: Boolean) {
        synchronized(buffer) {
            var consumed = 0
            var i = 0
            while (i < buffer.length) {
                if (buffer[i] in SENTENCE_ENDERS) {
                    val seg = sanitize(buffer.substring(consumed, i + 1))
                    if (seg.isNotEmpty()) onSentence(seg)
                    consumed = i + 1
                }
                i++
            }
            if (final) {
                val rest = sanitize(buffer.substring(consumed))
                if (rest.isNotEmpty()) onSentence(rest)
                buffer.setLength(0)
            } else if (consumed > 0) {
                val tail = buffer.substring(consumed)
                buffer.setLength(0)
                buffer.append(tail)
            }
        }
    }

    companion object {
        private val SENTENCE_ENDERS = charArrayOf('.', '!', '?', '\n')
        private val fencedCode = Regex("(?s)```.*?(```|$)")
        private val mdSymbols = Regex("[*_`#>\\[\\]]")
        private val bareUrls = Regex("\\(?https?://\\S+\\)?")
        private val whitespace = Regex("\\s+")

        fun sanitize(text: String): String = text
            .replace(fencedCode, " Code block. ")
            .replace(bareUrls, "")
            .replace(mdSymbols, "")
            .replace(whitespace, " ")
            .trim()
    }
}
