package com.xjtu.toolbox.agent.skin

import android.content.Context
import java.io.File

class PidaiSkinRepository(context: Context) {
    private val root = File(context.applicationContext.filesDir, "pidai_skins")

    fun list(): List<PidaiSkin> {
        if (!root.exists()) return emptyList()
        return root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir -> runCatching { load(dir) }.getOrNull() }
            .sortedBy { it.manifest.name.lowercase() }
    }

    fun install(skin: PidaiSkin): PidaiSkin {
        root.mkdirs()
        val temp = File(root, ".${skin.manifest.id}-${System.nanoTime()}")
        check(temp.mkdirs()) { "无法创建皮肤临时目录" }
        try {
            skin.files.forEach { (name, bytes) ->
                val file = File(temp, name)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
            // 落盘后再读一次，保证安装目录和预览时校验的是同一份数据。
            val verified = load(temp)
            val target = File(root, skin.manifest.id)
            val backup = File(root, ".${skin.manifest.id}-backup")
            if (backup.exists()) backup.deleteRecursively()
            if (target.exists() && !target.renameTo(backup)) error("无法替换旧皮肤")
            if (!temp.renameTo(target)) {
                backup.renameTo(target)
                error("无法安装皮肤")
            }
            backup.deleteRecursively()
            return verified
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    fun delete(id: String) {
        val target = File(root, id)
        require(target.canonicalFile.parentFile == root.canonicalFile) { "无效皮肤 id" }
        if (target.exists() && !target.deleteRecursively()) error("无法删除皮肤")
    }

    private fun load(dir: File): PidaiSkin = PidaiSkinParser.parseFiles(
        buildMap {
            listOf("manifest.json", "motion.json", "persona.json").forEach { name ->
                val file = File(dir, name)
                if (file.isFile) put(name, file.readBytes())
            }
            File(dir, "images").listFiles().orEmpty()
                .filter { it.isFile }
                .forEach { put("images/" + it.name, it.readBytes()) }
        }
    )
}
