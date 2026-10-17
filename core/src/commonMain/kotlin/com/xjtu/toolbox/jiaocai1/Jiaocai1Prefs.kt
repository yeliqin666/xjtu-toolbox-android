package com.xjtu.toolbox.jiaocai1

import com.xjtu.toolbox.platform.keyValueStore

/** 版式偏好，不是进度，不必进 Room。 */
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
