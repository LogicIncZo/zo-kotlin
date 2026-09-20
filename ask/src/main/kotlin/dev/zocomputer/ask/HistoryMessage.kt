package dev.zocomputer.ask

/** One message from a Zo conversation history fetch. */
data class HistoryMessage(val role: String, val text: String, val ts: Long)
