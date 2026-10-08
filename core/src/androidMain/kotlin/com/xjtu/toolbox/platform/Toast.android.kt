package com.xjtu.toolbox.platform

import android.widget.Toast

/**
 * Android 侧真实现：`android.widget.Toast` 短提示 —— 与搬迁前 :app 里那两处
 * `Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()` **逐字一致**（同样的时长、同样在屏幕底部）。
 *
 * `Context` 从 [androidPlatformContext] 拿（`:app` 在 `Application.onCreate` 里注入的那份进程级 Context），
 * 这样共享屏不必再持有 `LocalContext`。注：它到得比 `initAndroidPlatform` 早就返回 null 静默不弹 ——
 * 与 `KeyValueStore.android.kt` 里那条"未初始化时返回 null，让调用方自己决定"同一个立场（这里选择静默：
 * 一个复制提示不值得为它崩）。
 */
actual fun showBriefMessage(message: String) {
    androidPlatformContext()?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
}
