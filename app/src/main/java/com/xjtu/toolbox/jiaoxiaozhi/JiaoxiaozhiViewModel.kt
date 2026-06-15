package com.xjtu.toolbox.jiaoxiaozhi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class JiaoxiaozhiViewModel : ViewModel() {
    val sessions = mutableStateListOf<JiaoxiaozhiSession>()
    val messages = mutableStateListOf<JiaoxiaozhiMessage>()

    var currentSessionId by mutableStateOf<String?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var store: JiaoxiaozhiSessionStore? = null
    private var currentJob: Job? = null

    val currentSession: JiaoxiaozhiSession?
        get() = sessions.firstOrNull { it.id == currentSessionId }

    fun bind(store: JiaoxiaozhiSessionStore) {
        if (this.store != null) return
        this.store = store
        refreshSessions()
        sessions.firstOrNull()?.let { switchSession(it.id) } ?: newSession()
    }

    fun newSession() {
        if (isLoading) return
        val store = store ?: return
        val previousModel = currentSession?.modelId ?: JiaoxiaozhiModels.DEFAULT_ID
        val session = store.create(modelId = previousModel)
        currentSessionId = session.id
        messages.clear()
        errorMessage = null
        refreshSessions()
    }

    fun switchSession(id: String) {
        if (isLoading) return
        val store = store ?: return
        val conversation = store.load(id) ?: return
        currentSessionId = id
        messages.clear()
        messages.addAll(conversation.messages)
        errorMessage = null
    }

    fun renameSession(id: String, title: String) {
        store?.rename(id, title)
        refreshSessions()
    }

    fun deleteSession(id: String) {
        if (isLoading) return
        val store = store ?: return
        store.delete(id)
        refreshSessions()
        if (id == currentSessionId) {
            sessions.firstOrNull()?.let { switchSession(it.id) } ?: newSession()
        }
    }

    fun selectModel(modelId: String) {
        if (isLoading) return
        val id = currentSessionId ?: return
        store?.updateModel(id, modelId)
        refreshSessions()
    }

    fun stop() {
        currentJob?.cancel()
    }

    fun sendMessage(
        text: String,
        sessionManager: SessionManager,
        networkEnabled: Boolean = true,
    ) {
        val question = text.trim()
        if (question.isBlank() || isLoading) return
        if (currentSessionId == null) newSession()
        val conversationId = currentSessionId ?: return
        val modelId = currentSession?.modelId ?: JiaoxiaozhiModels.DEFAULT_ID

        errorMessage = null
        messages.add(JiaoxiaozhiMessage("user", question))
        messages.add(JiaoxiaozhiMessage("assistant", ""))
        val assistantIndex = messages.lastIndex
        isLoading = true
        persist()

        currentJob = viewModelScope.launch {
            try {
                val answer = JiaoxiaozhiCompat(sessionManager).ask(
                    question = cleanPrompt(question),
                    modelId = modelId,
                    conversationId = conversationId,
                    networkEnabled = networkEnabled,
                    onDelta = { delta ->
                        if (assistantIndex <= messages.lastIndex) {
                            val current = messages[assistantIndex]
                            messages[assistantIndex] = current.copy(content = current.content + delta)
                        }
                    },
                )
                if (assistantIndex <= messages.lastIndex) {
                    messages[assistantIndex] = messages[assistantIndex].copy(content = answer)
                }
                autoTitle(question)
            } catch (_: CancellationException) {
                if (assistantIndex <= messages.lastIndex && messages[assistantIndex].content.isBlank()) {
                    messages.removeAt(assistantIndex)
                }
            } catch (e: Exception) {
                if (assistantIndex <= messages.lastIndex && messages[assistantIndex].content.isBlank()) {
                    messages.removeAt(assistantIndex)
                }
                errorMessage = e.message ?: "交晓智请求失败"
            } finally {
                isLoading = false
                currentJob = null
                persist()
            }
        }
    }

    private fun cleanPrompt(question: String): String = """
        请直接、准确地回答下面的问题。不要输出数字人播报提示，不要虚构引用；
        无法确认的信息请明确说明。若问题与校园事务无关，也按普通助手方式回答。

        用户问题：
        $question
    """.trimIndent()

    private fun autoTitle(firstQuestion: String) {
        val id = currentSessionId ?: return
        val session = currentSession ?: return
        if (session.locked || session.title != JiaoxiaozhiSessionStore.DEFAULT_TITLE) return
        val title = firstQuestion.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 18) it else it.take(18) + "…"
        }
        store?.rename(id, title, lock = false)
        refreshSessions()
    }

    private fun persist() {
        currentSessionId?.let { store?.save(it, messages.toList()) }
        refreshSessions()
    }

    private fun refreshSessions() {
        val items = store?.list().orEmpty()
        sessions.clear()
        sessions.addAll(items)
    }
}
