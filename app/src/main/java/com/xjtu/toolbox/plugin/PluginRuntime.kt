package com.xjtu.toolbox.plugin

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
            // 工具会往 registry 里塞卡片；插件桥不展示卡片，取走丢弃，避免攒到下一轮。
            runCatching { registry.drainWidgets() }
            buildJsonObject {
                put("ok", true)
                put("tool", tool)
                put("text", text)
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
