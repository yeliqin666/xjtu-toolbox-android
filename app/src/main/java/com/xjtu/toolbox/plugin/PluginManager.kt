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
import kotlinx.coroutines.Dispatchers
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
import java.security.MessageDigest
import java.util.zip.ZipFile

private const val TAG = "PluginManager"
private const val MAX_APK_BYTES = 30L * 1024 * 1024
private const val MAX_DEX_FILES = 8
private const val MAX_TOOL_RESULT = 16_000

/** 已验证、等用户确认的一次安装；确认后交给 [PluginManager.commit]，取消调 [PluginManager.cancel]。 */
internal class PendingPluginInstall(
    val tempApk: File,
    val manifest: PluginManifest,
    val certSha256: String,
    val sizeBytes: Long,
    val oldVersionName: String?,
    val isUpdate: Boolean,
)

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
            list.filterNot { PluginRegistry.apkFile(appContext, it).isFile }.map { it.id }.toSet()
        }
        withContext(Dispatchers.Main) {
            records = list
            brokenIds = broken
        }
    }

    // ── 安装 ────────────────────────────────────────────────────────────────

    /** 把用户选的 APK 复制到临时文件并完整校验；不执行插件代码，只反射检查入口类存在。 */
    suspend fun inspect(uri: Uri): PendingPluginInstall = withContext(Dispatchers.IO) {
        val temp = File(appContext.cacheDir, "plugin_import_${System.currentTimeMillis()}.apk")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("读不到这个文件")
            require(temp.length() in 1..MAX_APK_BYTES) { "插件包为空或超过 30 MB" }
            // Android 14+ 拒绝从可写文件加载 dex（"Writable dex file is not allowed"）。
            // 插件 APK 本来就不该被改，装完/验完就置为只读。
            temp.setReadOnly()

            val pkg = appContext.packageManager
                .getPackageArchiveInfo(temp.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw IllegalArgumentException("不是可解析的 APK")
            val signers = pkg.signingInfo?.apkContentsSigners
            require(!signers.isNullOrEmpty()) { "APK 没有签名" }
            val cert = sha256(signers[0].toByteArray())

            val manifest = readManifest(temp)
            require(manifest.minAppVersionCode <= BuildConfig.VERSION_CODE) {
                "这个插件要求 App 版本 ${manifest.minAppVersionCode}+，当前 ${BuildConfig.VERSION_CODE}"
            }
            val existing = currentRecords().firstOrNull { it.id == manifest.id }
            if (existing != null) {
                require(existing.certSha256 == cert) { "更新必须使用与已安装版本相同的签名" }
                require(manifest.versionCode > existing.versionCode) {
                    "新版本 versionCode 必须大于已装的 ${existing.versionCode}"
                }
            }
            validateEntryClass(temp, manifest)

            PendingPluginInstall(
                tempApk = temp,
                manifest = manifest,
                certSha256 = cert,
                sizeBytes = temp.length(),
                oldVersionName = existing?.versionName,
                isUpdate = existing != null,
            )
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /** 用户确认后落盘并更新 registry。旧版本 APK 删除，但旧 classloader 可能还有页面在用。 */
    suspend fun commit(pending: PendingPluginInstall) = withContext(Dispatchers.IO) {
        val manifest = pending.manifest
        val pluginDir = File(PluginRegistry.root(appContext), manifest.id).apply { mkdirs() }
        val target = File(pluginDir, "${manifest.versionCode}.apk")
        if (!pending.tempApk.renameTo(target)) {
            pending.tempApk.copyTo(target, overwrite = true)
            pending.tempApk.delete()
        }
        target.setReadOnly()

        val record = PluginRecord(
            id = manifest.id,
            versionCode = manifest.versionCode,
            versionName = manifest.version,
            apk = "${manifest.id}/${manifest.versionCode}.apk",
            certSha256 = pending.certSha256,
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
        // 新版本落定后再清旧 APK，失败也不影响新版本
        pluginDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") && f.name != target.name }
            ?.forEach { runCatching { it.delete() } }
        if (PluginOverlay.current?.pluginId == record.id) PluginOverlay.close()
        withContext(Dispatchers.Main) {
            records = next
            brokenIds = brokenIds - record.id
        }
    }

    fun cancel(pending: PendingPluginInstall) {
        runCatching { pending.tempApk.delete() }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = updateRecord(id) { it.copy(enabled = enabled) }

    suspend fun setToolsEnabled(id: String, enabled: Boolean) = updateRecord(id) { it.copy(toolsEnabled = enabled) }

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
                record.manifest.tools.any { qualifiedName(record.id, it.name) == qualifiedName }
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

    private fun readManifest(apk: File): PluginManifest = ZipFile(apk).use { zip ->
        val entry = zip.getEntry("assets/${PluginApi.MANIFEST_ASSET}")
            ?: throw IllegalArgumentException("APK 缺少 assets/${PluginApi.MANIFEST_ASSET}")
        parsePluginManifest(zip.getInputStream(entry).use { it.readBytes() })
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
