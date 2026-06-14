package com.xjtu.toolbox.jiaoxiaozhi

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.UUID

class JiaoxiaozhiSessionStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "jiaoxiaozhi_sessions").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val gson = Gson()

    fun list(): List<JiaoxiaozhiSession> = readIndex().sortedByDescending { it.updatedAt }

    fun create(
        title: String = DEFAULT_TITLE,
        modelId: String = JiaoxiaozhiModels.DEFAULT_ID,
    ): JiaoxiaozhiSession {
        val now = System.currentTimeMillis()
        val session = JiaoxiaozhiSession(
            id = UUID.randomUUID().toString(),
            title = title,
            modelId = modelId,
            createdAt = now,
            updatedAt = now,
        )
        writeIndex(readIndex() + session)
        writeConversation(session.id, JiaoxiaozhiConversation())
        return session
    }

    fun load(id: String): JiaoxiaozhiConversation? {
        val file = conversationFile(id)
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson(file.readText(), JiaoxiaozhiConversation::class.java)
        }.getOrNull()
    }

    fun save(id: String, messages: List<JiaoxiaozhiMessage>) {
        writeConversation(id, JiaoxiaozhiConversation(messages))
        val now = System.currentTimeMillis()
        writeIndex(readIndex().map {
            if (it.id == id) it.copy(updatedAt = now) else it
        })
    }

    fun rename(id: String, title: String, lock: Boolean = true) {
        val now = System.currentTimeMillis()
        writeIndex(readIndex().map {
            if (it.id == id) it.copy(
                title = title.trim().ifBlank { DEFAULT_TITLE },
                updatedAt = now,
                locked = lock,
            ) else it
        })
    }

    fun updateModel(id: String, modelId: String) {
        val now = System.currentTimeMillis()
        writeIndex(readIndex().map {
            if (it.id == id) it.copy(modelId = JiaoxiaozhiModels.byId(modelId).id, updatedAt = now)
            else it
        })
    }

    fun delete(id: String) {
        writeIndex(readIndex().filterNot { it.id == id })
        conversationFile(id).delete()
    }

    private fun conversationFile(id: String) = File(dir, "conversation_$id.json")

    private fun readIndex(): List<JiaoxiaozhiSession> =
        if (!indexFile.exists()) emptyList()
        else runCatching {
            gson.fromJson(indexFile.readText(), Array<JiaoxiaozhiSession>::class.java)
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())

    private fun writeIndex(items: List<JiaoxiaozhiSession>) {
        writeSafely(indexFile, gson.toJson(items))
    }

    private fun writeConversation(id: String, conversation: JiaoxiaozhiConversation) {
        writeSafely(conversationFile(id), gson.toJson(conversation))
    }

    private fun writeSafely(target: File, text: String) {
        runCatching {
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_TITLE = "新对话"
    }
}
