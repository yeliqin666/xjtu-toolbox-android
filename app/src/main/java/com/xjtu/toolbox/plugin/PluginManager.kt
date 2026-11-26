package com.xjtu.toolbox.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.auth.ProcessLoginState
import com.xjtu.toolbox.plugin.api.Plugin
import com.xjtu.toolbox.plugin.api.PluginApi
import com.xjtu.toolbox.plugin.api.PluginEntry
import com.xjtu.toolbox.plugin.api.PluginPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipFile

private const val TAG = "PluginManager"

/**
 * 插件相关的后台活。
 *
 * 卸载必须跑在这里：详情页点完「卸载」马上 `onBack()` 把页面拆掉，用
 * `rememberCoroutineScope()` 的话协程会跟着取消——表现就是**卸载按钮点了没反应**。
 */
internal val PluginWork = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
private const val MAX_DEX_FILES = 8
private const val MAX_TOOL_RESULT = 16_000

/** 已验证、等用户确认的一次安装；确认后交给 [PluginManager.commit]，取消调 [PluginManager.cancel]。 */
internal class PendingPluginInstall(
    /** 缓存里的临时 zip，确认后才解压落盘。 */
    val tempPackage: File,
    val inspected: InspectedPackage,
    val sizeBytes: Long,
    val oldVersionName: String?,
    val isUpdate: Boolean,
) {
    val manifest: PluginManifest get() = inspected.runtime
}

/** 打开插件页需要的那几样。 */
internal class OpenedPluginPage(
    val page: PluginPage,
    val runtime: PluginRuntime,
    val title: String,
)

/**
 * 插件系统的唯一入口：安装、开关、加载、工具清单、工具执行。
 *
 * 线程：`refresh` / `inspect` / `commit` / `setEnabled` / `uninstall` 是挂起函数，
 * 自己切 IO；`load` / `toolDefinitions` / `executeTool` 是同步的，调用方负责放在 IO 线程
 * （`PluginAgentTools` 已经 `withContext(Dispatchers.IO)`）。
 */
internal class PluginManager private constructor(private val appContext: Context) {

    private val lock = Any()
    private val loaded = HashMap<String, LoadedPlugin>()
    private var cachedRecords: List<PluginRecord>? = null

    /** 给设置页观察的已安装列表。 */
    var records by mutableStateOf<List<PluginRecord>>(emptyList())
        private set

    /** APK 丢了/读不了的插件 id；列表里标灰，打开时直接报错。 */
    var brokenIds by mutableStateOf<Set<String>>(emptySet())
        private set

    suspend fun refresh() {
        val list = withContext(Dispatchers.IO) { PluginRegistry.read(appContext) }
        val broken = withContext(Dispatchers.IO) {
            list.filterNot { record ->
                record.root.isNotBlank() && PluginRegistry.packageDir(appContext, record).isDirectory
            }.map { it.id }.toSet()
        }
        withContext(Dispatchers.Main) {
            records = list
            brokenIds = broken
        }
    }

    // ── 安装 ────────────────────────────────────────────────────────────────

    /**
     * 把用户选的 zip 复制到缓存、验签、核对包内文件，再检查入口类。
     *
     * 这一步不执行插件代码，只把 APK 解到临时目录里反射看一眼入口类。
     */
    suspend fun inspect(uri: Uri): PendingPluginInstall = withContext(Dispatchers.IO) {
        val temp = File(appContext.cacheDir, "plugin_import_${System.currentTimeMillis()}.zip")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("读不到这个文件")
            require(temp.length() in 1..(MAX_PACKAGE_BYTES + 8L * 1024 * 1024)) {
                "插件包为空，或者大得离谱"
            }

            val inspected = PluginPackages.inspect(temp)
            val manifest = inspected.runtime
            require(manifest.minAppVersionCode <= BuildConfig.VERSION_CODE) {
                "这个插件要求 App 版本 ${manifest.minAppVersionCode}+，当前 ${BuildConfig.VERSION_CODE}"
            }

            val existing = currentRecords().firstOrNull { it.id == manifest.id }
            if (existing != null) {
                require(existing.signerSha256.isNotBlank() && existing.signerSha256 == inspected.signerSha256) {
                    "更新必须使用与已安装版本相同的签名密钥"
                }
                require(manifest.versionCode > existing.versionCode) {
                    "新版本 versionCode 必须大于已装的 ${existing.versionCode}"
                }
            }

            // dex 插件才需要检查入口类；web 插件不加载代码
            if (!manifest.isWeb) {
                val apkEntry = inspected.files[manifest.dexApk]
                    ?: throw IllegalArgumentException("包里没有 ${manifest.dexApk}")
                val tempApk = File(appContext.cacheDir, "plugin_inspect_${System.nanoTime()}.apk")
                try {
                    PluginPackages.extractOne(temp, apkEntry, tempApk)
                    validateEntryClass(tempApk, manifest)
                } finally {
                    runCatching { tempApk.delete() }
                }
            }

            PendingPluginInstall(
                tempPackage = temp,
                inspected = inspected,
                sizeBytes = temp.length(),
                oldVersionName = existing?.versionName,
                isUpdate = existing != null,
            )
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /**
     * 用户确认后落盘并更新 registry：解压到 `<id>/<versionCode>/`，先写暂存目录再改名，
     * 半路失败不会留下半个包。旧版本目录随后清掉，但旧 classloader 可能还有页面在用。
     */
    suspend fun commit(pending: PendingPluginInstall) = withContext(Dispatchers.IO) {
        val manifest = pending.manifest
        val pluginDir = File(PluginRegistry.root(appContext), manifest.id).apply { mkdirs() }
        val target = File(pluginDir, manifest.versionCode.toString())
        val staging = File(pluginDir, ".staging-${manifest.versionCode}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            PluginPackages.extract(pending.tempPackage, pending.inspected.files.values, staging)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "插件包落盘失败" }
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        } finally {
            runCatching { pending.tempPackage.delete() }
        }

        val record = PluginRecord(
            id = manifest.id,
            versionCode = manifest.versionCode,
            versionName = manifest.version,
            root = "${manifest.id}/${manifest.versionCode}",
            apk = manifest.dexApk,
            signerSha256 = pending.inspected.signerSha256,
            enabled = true,
            toolsEnabled = false,
            installedAt = System.currentTimeMillis(),
            manifest = manifest,
        )
        val next = currentRecords().filterNot { it.id == record.id } + record
        PluginRegistry.write(appContext, next)
        synchronized(lock) {
            cachedRecords = next
            loaded.remove(record.id)
        }
        // 新版本落定后再清旧版本目录，失败也不影响新版本
        pluginDir.listFiles { f -> f.isDirectory && f.name != target.name }?.forEach { old ->
            runCatching { old.deleteRecursively() }
        }
        if (PluginOverlay.current?.pluginId == record.id) PluginOverlay.close()
        withContext(Dispatchers.Main) {
            records = next
            brokenIds = brokenIds - record.id
        }
    }

    fun cancel(pending: PendingPluginInstall) {
        runCatching { pending.tempPackage.delete() }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = updateRecord(id) { it.copy(enabled = enabled) }

    suspend fun setToolsEnabled(id: String, enabled: Boolean) = updateRecord(id) { it.copy(toolsEnabled = enabled) }

    /** 单个工具开关；总开关关着时这些设置留着，重新打开后照旧生效。 */
    suspend fun setToolEnabled(id: String, tool: String, enabled: Boolean) = updateRecord(id) { record ->
        record.copy(
            disabledTools = if (enabled) record.disabledTools - tool else record.disabledTools + tool,
        )
    }

    /** web 插件的 WebVPN 开关；required / off 两档不让改。 */
    suspend fun setVpnEnabled(id: String, enabled: Boolean) = updateRecord(id) { record ->
        if (record.webVpnLocked()) record else record.copy(vpnOverride = enabled)
    }

    private suspend fun updateRecord(id: String, transform: (PluginRecord) -> PluginRecord) =
        withContext(Dispatchers.IO) {
            val next = currentRecords().map { if (it.id == id) transform(it) else it }
            PluginRegistry.write(appContext, next)
            synchronized(lock) {
                cachedRecords = next
                loaded.remove(id)
            }
            if (PluginOverlay.current?.pluginId == id) PluginOverlay.close()
            withContext(Dispatchers.Main) { records = next }
        }

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) {
        val next = currentRecords().filterNot { it.id == id }
        PluginRegistry.write(appContext, next)
        File(PluginRegistry.root(appContext), id).deleteRecursively()
        File(PluginRegistry.root(appContext), "data/$id").deleteRecursively()
        synchronized(lock) {
            cachedRecords = next
            loaded.remove(id)
        }
        if (PluginOverlay.current?.pluginId == id) PluginOverlay.close()
        withContext(Dispatchers.Main) {
            records = next
            brokenIds = brokenIds - id
        }
    }

    // ── 页面 ────────────────────────────────────────────────────────────────

    fun openPage(pluginId: String, pageId: String): OpenedPluginPage? {
        val loaded = load(pluginId) ?: return null
        val page = runCatching { loaded.plugin.pages().firstOrNull { it.id() == pageId } }.getOrNull()
            ?: return null
        return OpenedPluginPage(page, loaded.runtime, loaded.record.manifest.pageTitle(pageId))
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 合并所有已启用插件的工具定义；名称统一改写为 `plugin_<id>_<tool>`。 */
    fun toolDefinitions(): String {
        val seen = HashSet<String>()
        val definitions = mutableListOf<JsonObject>()
        for (record in currentRecords()) {
            if (!record.enabled || !record.toolsEnabled) continue
            for (tool in record.manifest.tools) {
                // 总开关开着时，用户还可以单独关掉某几个
                if (tool.name in record.disabledTools) continue
                val qualified = qualifiedName(record.id, tool.name) ?: continue
                if (!seen.add(qualified)) continue
                val function = tool.definition["function"]?.jsonObject ?: continue
                val description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val rewritten = JsonObject(
                    function.toMutableMap().apply {
                        put("name", JsonPrimitive(qualified))
                        put("description", JsonPrimitive("插件「${record.manifest.name}」：$description"))
                    },
                )
                definitions += buildJsonObject {
                    put("type", "function")
                    put("function", rewritten)
                }
            }
        }
        return JsonArray(definitions).toString()
    }

    fun executeTool(qualifiedName: String, argsJson: String): String {
        val record = currentRecords().firstOrNull { record ->
            record.enabled && record.toolsEnabled &&
                qualifiedName in record.manifest.tools
                    .filterNot { it.name in record.disabledTools }
                    .mapNotNull { qualifiedName(record.id, it.name) }
        } ?: return "error: not_found; tool: $qualifiedName"
        val original = record.manifest.tools.first { qualifiedName(record.id, it.name) == qualifiedName }.name
        val loaded = load(record.id)
            ?: return "error: plugin_unavailable; id: ${record.id}"
        val tool = runCatching { loaded.plugin.tools().firstOrNull { it.name() == original } }.getOrNull()
            ?: return "error: not_found; tool: $qualifiedName"
        return try {
            tool.execute(argsJson).take(MAX_TOOL_RESULT)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            "error: plugin_failed; tool: $qualifiedName; reason: " +
                (t.message?.take(120) ?: t.javaClass.simpleName)
        }
    }

    // ── 加载 ────────────────────────────────────────────────────────────────

    private fun load(id: String): LoadedPlugin? = synchronized(lock) {
        val record = currentRecords().firstOrNull { it.id == id } ?: return null
        if (!record.enabled || id in brokenIds) return null
        loaded[id]?.takeIf { it.record.versionCode == record.versionCode }?.let { return it }

        if (record.manifest.isWeb) return null
        val apk = PluginRegistry.apkFile(appContext, record)
        if (!apk.isFile) return null
        val dexDir = File(appContext.codeCacheDir, "plugin_dex/$id/${record.versionCode}")
        if (!dexDir.exists() && !dexDir.mkdirs()) {
            Log.w(TAG, "无法创建 dex 目录 $dexDir")
            return null
        }
        return try {
            val loader = PluginDexLoader(apk.absolutePath, dexDir, appContext.classLoader)
            val cls = Class.forName(record.manifest.entryClass, true, loader)
            val entry = cls.getDeclaredConstructor().newInstance() as PluginEntry
            val runtime = PluginRuntime(
                appContext = appContext,
                pluginId = id,
                loginState = ProcessLoginState.get(appContext).loginState,
                apkFile = apk,
                packageDir = PluginRegistry.packageDir(appContext, record),
            )
            val plugin: Plugin = entry.create(runtime)
            LoadedPlugin(record, loader, plugin, runtime).also { loaded[id] = it }
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "加载插件 $id 失败", t)
            null
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun currentRecords(): List<PluginRecord> = synchronized(lock) {
        cachedRecords ?: PluginRegistry.read(appContext).also { cachedRecords = it }
    }

    private fun validateEntryClass(apk: File, manifest: PluginManifest) {
        val dexDir = File(appContext.codeCacheDir, "plugin_dex/_inspect/${System.nanoTime()}")
        dexDir.mkdirs()
        try {
            val loader = PluginDexLoader(apk.absolutePath, dexDir, appContext.classLoader)
            val cls = try {
                Class.forName(manifest.entryClass, false, loader)
            } catch (e: ClassNotFoundException) {
                throw IllegalArgumentException("找不到入口类 ${manifest.entryClass}")
            } catch (e: LinkageError) {
                throw IllegalArgumentException("入口类加载失败：${e.message}")
            }
            require(PluginEntry::class.java.isAssignableFrom(cls)) {
                "入口类 ${manifest.entryClass} 没有实现 PluginEntry"
            }
            val dexCount = ZipFile(apk).use { zip ->
                zip.entries().asSequence().count { it.name.matches(Regex("classes\\d*\\.dex")) }
            }
            require(dexCount in 1..MAX_DEX_FILES) { "dex 文件数 $dexCount 超出 1–$MAX_DEX_FILES" }
        } finally {
            runCatching { dexDir.deleteRecursively() }
        }
    }

    private fun qualifiedName(pluginId: String, toolName: String): String? {
        val id = pluginId.replace(Regex("[^a-zA-Z0-9_]"), "_")
        return "plugin_${id}_$toolName".takeIf { it.length <= 64 }
    }

    private class LoadedPlugin(
        val record: PluginRecord,
        @Suppress("unused") val loader: PluginDexLoader,
        val plugin: Plugin,
        val runtime: PluginRuntime,
    )

    companion object {
        @Volatile
        private var instance: PluginManager? = null

        fun get(context: Context): PluginManager = instance ?: synchronized(this) {
            instance ?: PluginManager(context.applicationContext).also { instance = it }
        }
    }
}
