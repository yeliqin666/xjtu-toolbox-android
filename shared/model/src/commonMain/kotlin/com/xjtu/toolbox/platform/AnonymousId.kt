package com.xjtu.toolbox.platform

/**
 * 设备级匿名标识（不含学号账号）：反馈用它把同一个人的多条反馈串起来，小游戏全服榜用它按
 * 「游戏 + 这个 ID」去重。两边用的是**同一个** ID（dev 上榜单直接调 `FeedbackStore.anonId`），
 * 所以放在 `:shared:model`，`feature:community` 与 `feature:game` 各自来取，彼此不依赖。
 *
 * 能拿到设备级安装 ID（Android 的 `ANDROID_ID` 哈希）就用它，并顺手记一份；拿不到（桌面 / Web）
 * 就用第一次生成的随机串。存储键沿用反馈那份 `feedback/anon_id`，老数据不丢。
 */
object AnonymousId {
    private const val PREFS = "feedback"
    private const val K_ANON_ID = "anon_id"
    private const val SALT = "feedback"

    /** `by lazy`：Android 的 `keyValueStore` 要等 `initAndroidPlatform` 交进 `Context`。 */
    private val store by lazy { keyValueStore(PREFS) }

    fun get(): String {
        val fromDevice = deviceInstallId(SALT)
        if (fromDevice != null) {
            if (store.getString(K_ANON_ID) != fromDevice) {
                store.putString(K_ANON_ID, fromDevice)
            }
            return fromDevice
        }
        store.getString(K_ANON_ID)?.let { return it }
        val id = buildString(8) { repeat(8) { append(HEX[secureRandomInt(HEX.length)]) } }
        store.putString(K_ANON_ID, id)
        return id
    }

    private const val HEX = "0123456789abcdef"
}