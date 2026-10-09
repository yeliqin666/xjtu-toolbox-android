package com.xjtu.toolbox.platform

/**
 * 平台能力：**键值 / 偏好存储**。
 *
 * 这是交接文档 §4 里 12 个家族中的「键值/偏好」那一族，也是 :app 里 `SharedPreferences`
 * 那一批用法的落点。三端实现：
 *   - Android = `SharedPreferences`（`name` 就是文件名，行为与现在一致）；
 *   - Web     = `localStorage`（加前缀，避免与同源下别的页面串键）；
 *   - jvm     = 内存（本地测试用，不落盘 —— 免得测试之间互相污染）。
 *
 * 为什么是 `expect fun` 返回接口，而不是 `expect class`：接口 + 工厂的粒度正好是「能力」，
 * 各端实现可以随便换（Android 将来若换 DataStore，只改 androidMain 一个文件）。
 *
 * ⚠️ Android 侧需要 `Context`：由 :app 在 `Application.onCreate` 里调一次
 * [initAndroidPlatform]。这是 KMP 的标准做法 —— 平台句柄只能由宿主进程注入，
 * `:core` 不能自己去找（它连 `Context` 这个类型都不该认识）。
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun remove(key: String)

    /**
     * 键是否存在。`SharedPreferences.contains` 的对应物 —— 课程自定义颜色靠它区分
     * 「用户设过色」与「没设过、按课名哈希取默认色」，而只靠 getInt 的默认值区分不出来
     * （用户可能正好把颜色设成默认值）。
     */
    fun contains(key: String): Boolean

    /** 只清本 store 命名空间下的键，不是清空整个平台的偏好。 */
    fun clear()
}

/** 建一个具名存储。同一 `name` 多次调用应得到同一份数据。 */
expect fun keyValueStore(name: String): KeyValueStore
