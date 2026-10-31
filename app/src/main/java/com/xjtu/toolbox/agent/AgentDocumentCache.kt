package com.xjtu.toolbox.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AgentDocumentCache(
    private val maxBytes: Int = 24 * 1024 * 1024,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Document(val name: String, val bytes: ByteArray)
    private data class Entry(val document: Document, val expires: Long)
    private val entries = linkedMapOf<String, Entry>()
    private val mutex = Mutex()

    suspend fun getOrLoad(key: String, load: suspend () -> Document?): Document? = mutex.withLock {
        entries.entries.removeAll { it.value.expires <= now() }
        entries.remove(key)?.let { entries[key] = it; return@withLock it.document }
        val document = load() ?: return@withLock null
        if (document.bytes.size <= maxBytes) {
            while (entries.isNotEmpty() && (entries.size >= 4 || entries.values.sumOf { it.document.bytes.size.toLong() } + document.bytes.size > maxBytes)) {
                entries.remove(entries.keys.first())
            }
            entries[key] = Entry(document, now() + 10 * 60_000)
        }
        document
    }
}
