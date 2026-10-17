package com.xjtu.toolbox.platform

/** 平台能力：键值 / 偏好存储。 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)

    /** 字符串集合那一档。 */
    fun getStringSet(key: String): Set<String>?

    /** 写一个字符串集合。空集合是合法值（与「键不存在」不同）。 */
    fun putStringSet(key: String, value: Set<String>)

    fun remove(key: String)

    /** 键是否存在。 */
    fun contains(key: String): Boolean

    /** 只清本 store 命名空间下的键，不是清空整个平台的偏好。 */
    fun clear()
}

/** 建一个具名存储。同一 `name` 多次调用应得到同一份数据。 */
expect fun keyValueStore(name: String): KeyValueStore
