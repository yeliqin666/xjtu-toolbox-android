package com.xjtu.toolbox.platform

/** jvm/desktop：进程内内存实现。刻意不落盘 —— 免得测试之间互相污染。 */
private class MemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, Any>()

    override fun getString(key: String): String? = map[key] as? String
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun clear() = map.clear()
}

private val stores = mutableMapOf<String, KeyValueStore>()

actual fun keyValueStore(name: String): KeyValueStore = stores.getOrPut(name) { MemoryKeyValueStore() }
