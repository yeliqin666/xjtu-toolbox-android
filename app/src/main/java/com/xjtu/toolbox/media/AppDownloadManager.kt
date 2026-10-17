package com.xjtu.toolbox.media

import android.content.Context
import com.xjtu.toolbox.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Android 侧把 `:core` 的 [DownloadManager] 装起来的那一处。 */
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
