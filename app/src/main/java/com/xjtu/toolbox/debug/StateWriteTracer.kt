package com.xjtu.toolbox.debug

import androidx.compose.runtime.snapshots.Snapshot

/**
 * debug 包的性能探针：全局快照里的每次状态写入记一个 trace 段，段名是写入它的应用代码位置
 * （`xjtu:write:文件:行`）。配合组合追踪，能看出某一帧的重组是哪一行写状态引起的。
 *
 * 默认不装：录制时每次写入都要取一次调用栈，会把 trace 里的耗时整体放大。要找写入源时
 * `adb shell setprop debug.xjtu.writetrace 1` 后重启应用。
 */
internal object StateWriteTracer {
    fun install() {
        if (!enabled()) return
        Snapshot.registerGlobalWriteObserver {
            if (!android.os.Trace.isEnabled()) return@registerGlobalWriteObserver
            val site = Throwable().stackTrace.firstOrNull {
                it.className.startsWith("com.xjtu.toolbox") && !it.className.startsWith("com.xjtu.toolbox.debug")
            }
            android.os.Trace.beginSection("xjtu:write:${site?.fileName}:${site?.lineNumber}")
            android.os.Trace.endSection()
        }
    }

    private fun enabled(): Boolean = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, "debug.xjtu.writetrace") == "1"
    }.getOrDefault(false)
}
