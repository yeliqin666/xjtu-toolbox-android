package com.xjtu.toolbox.jiaocai1

import com.xjtu.toolbox.platform.keyValueStore

/**
 * 版式偏好，不是进度，不必进 Room。
 *
 * 搬进 `:core` 时只换了一件事：`Context` + `SharedPreferences` → 平台键值缝 [keyValueStore]
 * （**同一个文件名 `jiaocai1_reader_prefs`、同一个键 `vertical_scroll`** ⇒ Android 上用户
 * 之前选的「上下滚动」不丢）。桌面/Web 那一端的键值缝是各自的实现（见 `KeyValueStore` 的 KDoc）。
 */
object Jiaocai1Prefs {
    private const val NAME = "jiaocai1_reader_prefs"
    private const val KEY_VERTICAL = "vertical_scroll"

    /** `by lazy`：Android 侧要 `Application.onCreate` 里那次 `initAndroidPlatform` 先跑完。 */
    private val store by lazy { keyValueStore(NAME) }

    fun verticalScroll(): Boolean = store.getBoolean(KEY_VERTICAL, false)

    fun setVerticalScroll(value: Boolean) {
        store.putBoolean(KEY_VERTICAL, value)
    }
}
