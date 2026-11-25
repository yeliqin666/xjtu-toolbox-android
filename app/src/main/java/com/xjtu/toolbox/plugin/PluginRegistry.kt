package com.xjtu.toolbox.plugin

import android.content.Context
import com.xjtu.toolbox.util.AppJson
import java.io.File

/** `filesDir/plugins/registry.json` 的读写。文件很小，安装/启用时才碰它。 */
internal object PluginRegistry {

    fun root(context: Context): File = File(context.filesDir, "plugins")

    fun apkFile(context: Context, record: PluginRecord): File = File(root(context), record.apk)

    fun dataDir(context: Context, pluginId: String): File =
        File(root(context), "data/$pluginId").apply { mkdirs() }

    fun read(context: Context): List<PluginRecord> {
        val file = File(root(context), "registry.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            AppJson.decodeFromString(PluginRegistryFile.serializer(), file.readText(Charsets.UTF_8)).plugins
        }.getOrDefault(emptyList())
    }

    /** 先写 `.tmp` 再 rename；写一半断电不会留下半截 JSON。 */
    fun write(context: Context, records: List<PluginRecord>) {
        val dir = root(context).apply { mkdirs() }
        val file = File(dir, "registry.json")
        val tmp = File(dir, "registry.json.tmp")
        val text = AppJson.encodeToString(PluginRegistryFile.serializer(), PluginRegistryFile(records))
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "插件清单写入失败" }
        }
    }
}
