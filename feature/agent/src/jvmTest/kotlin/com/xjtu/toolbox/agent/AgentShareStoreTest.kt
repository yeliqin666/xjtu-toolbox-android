package com.xjtu.toolbox.agent

import com.xjtu.toolbox.platform.KeyValueStore
import kotlin.test.*

class AgentShareStoreTest {
    @Test fun reloadKeepsMaterialsAndTouchMovesToFront() {
        val prefs = MemoryPrefs()
        val store = AgentShareStore(prefs, now = { 1000L })
        val first = store.add("开班会", listOf("private/image.jpg"))!!
        store.add("材料二")
        val reloaded = AgentShareStore(prefs, now = { 1000L })
        assertEquals(listOf("材料二", "开班会"), reloaded.all().map { it.text })
        reloaded.touch(first.id, "明天开班会")
        assertEquals(first.copy(text = "明天开班会", updatedAt = 1002), reloaded.all().first())
        assertTrue(prefs.getString("materials")!!.contains("\"updatedAt\""))
    }

    @Test fun expiryCapacityAndDeleteReleaseOnlyOwnedImages() {
        var now = 1000L
        val deleted = mutableListOf<String>()
        val store = AgentShareStore(MemoryPrefs(), deleteImages = { deleted += it }, now = { now })
        repeat(31) { store.add("材料$it", listOf("$it.jpg")) }
        assertEquals(30, store.all().size)
        assertEquals(listOf("0.jpg"), deleted)
        val latest = store.all().first()
        store.remove(latest.id)
        assertEquals(listOf("0.jpg", "30.jpg"), deleted)
        now += AgentShareStore.EXPIRY_MS + 100
        assertTrue(store.all().isEmpty())
        assertEquals(31, deleted.size)
    }

    @Test fun sensitiveKeywordsAndIdentifiersRequireReview() {
        assertTrue(sharePrivacyHints("验证码：123456").contains("密码或验证码"))
        assertTrue(sharePrivacyHints("联系电话 13812345678").contains("手机号"))
        assertTrue(sharePrivacyHints("明天 12:00 开班会").isEmpty())
    }

    private class MemoryPrefs : KeyValueStore {
        private val values = mutableMapOf<String, Any>()
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { values[key] = value }
        @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String) = values[key] as? Set<String>
        override fun putStringSet(key: String, value: Set<String>) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun contains(key: String) = key in values
        override fun clear() { values.clear() }
    }
}
