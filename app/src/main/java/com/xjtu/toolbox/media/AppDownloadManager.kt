package com.xjtu.toolbox.media

import android.content.Context
import com.xjtu.toolbox.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android 侧把 `:core` 的 [DownloadManager] 装起来的那一处（原来这个角色由 `DownloadManager`
 * 自己的 `companion object` 担任，搬走之后单例这件事只属于宿主）。
 *
 * 三个端口都在这里填：Room（[AppDownloadTaskStore]）、okhttp 搬运循环（[AppDownloadEngine]）、
 * 应用专属外部目录（[AppDownloadFileSystem]）；作用域仍是搬迁前那个
 * `Dispatchers.IO + SupervisorJob()` —— 下载是磁盘与网络的活，不该占主线程。
 *
 * 调用点（`AppLmsSink` 的排队、`ProfileTab` 的统计、下载管理那一屏）拿到的还是**同一个实例**，
 * 与搬迁前同一个进程级单例、同一份队列。
 */
object AppDownloadManager {

    @Volatile
    private var instance: DownloadManager? = null

    fun getInstance(context: Context): DownloadManager {
        val appContext = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: DownloadManager(
                store = AppDownloadTaskStore(AppDatabase.getInstance(appContext).downloadTaskDao()),
                engine = AppDownloadEngine(),
                fileSystem = AppDownloadFileSystem(appContext),
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            ).also { instance = it }
        }
    }
}
