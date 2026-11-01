package com.xjtu.toolbox.agent

import kotlin.test.*

class AgentSessionLifecycleTest {
    @Test fun openingAndRepeatedlyStartingNewChatsDoesNotSaveEmptyConversations() {
        val store = Sessions()
        val vm = AgentViewModel(AgentHost.None)
        vm.bind(store)
        repeat(10) { vm.newSession() }
        assertNull(vm.currentSessionId)
        assertTrue(vm.sessions.isEmpty())
        assertEquals(0, store.created)
        assertTrue(store.list().isEmpty())
    }

    @Test fun bindRemovesOldEmptyChatsAndPreservesTextImagesContextAndPushLog() {
        val store = Sessions()
        store.save("empty", StoredConversation(), "新对话")
        store.save("text", StoredConversation(messages = listOf(StoredMessage("user", "课程安排"))), "课程")
        store.save("image", StoredConversation(messages = listOf(StoredMessage("user", images = listOf("photo.jpg")))), "图片")
        store.save("context", StoredConversation(llmHistory = """[{"role":"user","content":"保留"}]"""), "历史")
        store.save(AgentPushFocus.SESSION_ID, StoredConversation(), AgentPushFocus.SESSION_TITLE)
        val vm = AgentViewModel(AgentHost.None)
        vm.bind(store)
        assertEquals(setOf("text", "image", "context", AgentPushFocus.SESSION_ID), store.list().map { it.id }.toSet())
        assertEquals(0, store.created)
    }

    private class Sessions : AgentSessionPersistence {
        private val metadata = linkedMapOf<String, AgentSession>()
        private val conversations = mutableMapOf<String, StoredConversation>()
        var created = 0
        override fun list() = metadata.values.toList()
        override fun create(title: String): AgentSession = AgentSession("new-${++created}", title).also {
            metadata[it.id] = it; conversations[it.id] = StoredConversation()
        }
        override fun rename(id: String, title: String, lock: Boolean) { metadata[id]?.let { metadata[id] = it.copy(title = title, locked = lock) } }
        override fun delete(id: String) { metadata.remove(id); conversations.remove(id) }
        override fun load(id: String) = conversations[id]
        override fun save(id: String, convo: StoredConversation, title: String) {
            metadata[id] = metadata[id]?.copy(title = title) ?: AgentSession(id, title)
            conversations[id] = convo
        }
        override fun isLocked(id: String) = metadata[id]?.locked == true
    }
}
