package com.xjtu.toolbox.plugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import com.xjtu.toolbox.util.AppJson
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.AgentHost
import com.xjtu.toolbox.agent.AgentToolRegistry
import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.plugin.api.PluginContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipFile

/**
 * 一个插件实例的宿主上下文。
 *
 * 只把"只读学校取数"做成显式 API：登录、MFA、WebVPN、缓存、限流全部复用
 * [AgentToolRegistry]，不把 cookie / SiteSession 交给插件。写类工具不在白名单里。
 */
internal class PluginRuntime(
    private val appContext: Context,
    override val pluginId: String,
    private val loginState: AppLoginState,
    private val apkFile: File,
    /** 插件包解压后的目录（`files/plugins/<id>/<versionCode>`），[openPackageFile] 从这里读。 */
    private val packageDir: File,
) : PluginContext {

    @Volatile override var isDarkTheme: Boolean = false
    @Volatile override var primaryColor: Int = 0

    override val appVersionCode: Int get() = BuildConfig.VERSION_CODE
    override val applicationContext: Context get() = appContext

    private val mainHandler = Handler(Looper.getMainLooper())
    private val registryLock = Any()
    private var cachedAccount: String? = null
    private var cachedRegistry: AgentTools? = null

    fun applyTheme(dark: Boolean, colorArgb: Int) {
        isDarkTheme = dark
        primaryColor = colorArgb
    }

    override fun schoolTool(name: String, argsJson: String): String {
        val tool = name.trim()
        if (tool !in READ_ONLY_TOOLS) {
            return errorJson(tool, "not_allowed")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return errorJson(tool, "main_thread")
        }
        return try {
            val registry = registry()
            val text = runBlocking { registry.execute(tool, argsJson.ifBlank { "{}" }) }
            // 工具会往 registry 里塞结构化卡片（课表、成绩、空教室……）。给小岱用的时候这些被丢掉，
            // 但插件要拿它们画界面——所以取出来一并回给插件。没有卡片的工具这里就是空数组。
            val widgets = runCatching { registry.drainWidgets() }.getOrDefault(emptyList())
            buildJsonObject {
                put("ok", true)
                put("tool", tool)
                put("text", text)
                put("widgets", AppJson.encodeToJsonElement(widgets))
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(tool, e.message?.take(200) ?: e.javaClass.simpleName)
        }
    }

    private fun registry(): AgentTools = synchronized(registryLock) {
        val account = AccountContext.activeAccountId
        cachedRegistry?.takeIf { cachedAccount == account }?.let { return it }
        val created = AgentToolRegistry(
            loginState = loginState,
            dataCache = DataCache(appContext, account),
            context = appContext,
            host = AgentHost.None,
            allowInteractiveLogin = true,
            ocrKey = "",
        )
        cachedRegistry = created
        cachedAccount = account
        created
    }

    private fun errorJson(tool: String, reason: String): String = buildJsonObject {
        put("ok", false)
        put("tool", tool)
        put("error", reason)
    }.toString()

    override fun openAsset(name: String): ByteArray? = runCatching {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("assets/${name.trimStart('/')}") ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }.getOrNull()

    override fun openPackageFile(path: String): ByteArray? = runCatching {
        val relative = path.trimStart('/')
        if (!PluginPackages.isSafePath(relative)) return null
        val root = packageDir.canonicalFile
        val target = File(root, relative).canonicalFile
        // 防 ../ 跳出包目录：解析完的真实路径必须还在包里
        if (!target.path.startsWith(root.path + File.separator)) return null
        if (!target.isFile) return null
        target.readBytes()
    }.getOrNull()

    override fun notify(title: String, text: String) {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                appContext,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
        runCatching { manager.notify(pluginId.hashCode(), notification) }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "插件通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "插件给你的提醒"
            },
        )
    }

    override fun dataDir(): String = PluginRegistry.dataDir(appContext, pluginId).absolutePath

    override fun toast(message: String) {
        mainHandler.post {
            runCatching { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun log(message: String) {
        Log.d("Plugin/$pluginId", message)
    }

    companion object {
        /** 插件通知识别用的渠道 id。 */
        private const val CHANNEL_ID = "plugin_notifications"

        /**
         * 只读学校工具白名单。写入类（add_schedule_event / set_alarm / preference /
         * app_setting / save_file / activity）刻意不在这里，插件硬调会回 not_allowed。
         */
        private val READ_ONLY_TOOLS = setOf(
            "get_calendar",
            "get_schedule",
            "get_exam_schedule",
            "search_school_courses",
            "get_empty_rooms",
            "get_attendance",
            "get_grades",
            "get_card_info",
            "get_notifications",
            "search_yellow_page",
            "find_faculty",
            "get_library",
            "list_zyxf",
            "read_zyxf_file",
            "get_textbooks",
            "get_coupons",
            "get_lms",
            "get_lms_activity",
            "get_fitness_score",
        )
    }
}
