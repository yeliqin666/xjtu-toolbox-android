package com.xjtu.toolbox.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class AgentSharedMaterial(
    val id: Long,
    val text: String,
    val images: List<String> = emptyList(),
    val updatedAt: Long,
)

class AgentShareStore(
    private val prefs: KeyValueStore,
    private val deleteImages: (List<String>) -> Unit = {},
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun all(): List<AgentSharedMaterial> {
        val saved = runCatching { AppJson.decodeFromString<List<AgentSharedMaterial>>(prefs.getString(KEY) ?: "[]") }.getOrDefault(emptyList())
        val kept = saved.filter { now() - it.updatedAt < EXPIRY_MS }.sortedByDescending { it.updatedAt }.take(MAX_ITEMS)
        if (kept != saved) {
            deleteImages(saved.filter { old -> kept.none { it.id == old.id } }.flatMap { it.images })
            write(kept)
        }
        return kept
    }

    fun add(text: String, images: List<String> = emptyList()): AgentSharedMaterial? {
        if (text.isBlank() && images.isEmpty()) return null
        val saved = all()
        val stamp = maxOf(now(), (saved.maxOfOrNull { it.id } ?: 0) + 1)
        val item = AgentSharedMaterial(stamp, text.trim().take(30_000), images.take(AgentVision.MAX_IMAGES_PER_MESSAGE), stamp)
        val next = listOf(item) + saved
        deleteImages(next.drop(MAX_ITEMS).flatMap { it.images })
        write(next.take(MAX_ITEMS))
        return item
    }

    fun touch(id: Long, text: String) {
        val saved = all()
        val stamp = maxOf(now(), (saved.maxOfOrNull { it.updatedAt } ?: 0) + 1)
        write(saved.map { if (it.id == id) it.copy(text = text.take(30_000), updatedAt = stamp) else it }.sortedByDescending { it.updatedAt })
    }

    fun remove(id: Long) {
        val saved = all()
        deleteImages(saved.filter { it.id == id }.flatMap { it.images })
        write(saved.filterNot { it.id == id })
    }

    private fun write(items: List<AgentSharedMaterial>) { prefs.putString(KEY, AppJson.encodeToString(items)) }

    companion object {
        private const val KEY = "materials"
        const val MAX_ITEMS = 30
        const val EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

object AgentShareInbox {
    var generation by mutableIntStateOf(0)
        private set
    var pendingOpen = false
        private set

    fun received() { pendingOpen = true; generation++ }
    fun consumeOpen() { pendingOpen = false }
}

internal fun sharePrivacyHints(text: String): List<String> = personalDataHints(text) + listOfNotNull(
    "密码或验证码".takeIf { Regex("验证码|密码|口令|(?i:password|secret|token)\\s*[:=]").containsMatchIn(text) },
)
