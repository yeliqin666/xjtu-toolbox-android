package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.platform.showBriefMessage
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * 桌面端的几个「系统文件对话框」——AWT 的 [FileDialog]，三个用途共用一份：
 * 导出 ICS（保存）、请假附件（选文件）、屁岱挑图（选图）。
 *
 * ### 为什么是 AWT 而不是 Compose 的文件选择器
 * Compose Desktop 没有自带的选择器，而 AWT 的 [FileDialog] 是**系统原生**那一个（Linux 走 GTK、
 * macOS 走面板、Windows 走 Win32），用户看到的是自己熟悉的那一版。先例见
 * `DesktopMealReportExport`（干饭人账单存图）。
 *
 * ### 约好的几件事
 * - 取消 = `null` / 空表（**不当成失败**：调用方那一侧不该因为用户按了取消就报错）。
 * - 只挑文件不挑目录，也不做"另存为"的覆盖确认以外的加工。
 * - 这里只负责挑/存，内容怎么来怎么去由调用方决定。
 */
internal object DesktopFileDialogs {

    /** 「保存」：弹保存框，把 [bytes] 写成 [suggestedName]（用户可以改名、换目录）；返回写成的文件。 */
    fun saveBytes(title: String, suggestedName: String, bytes: ByteArray): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply { file = suggestedName }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val target = File(dialog.directory, name)
        return runCatching {
            target.writeBytes(bytes)
            target
        }.getOrNull()
    }

    /**
     * 「打开」：多选/单选若干**已存在**的文件。
     *
     * @param extensions 只要这些后缀（`null` = 全都收）。AWT 的过滤是
     *   `*.pdf;*.png` 这种一份通配串；过滤器只是**初始视图**，用户可以切到「全部文件」，
     *   所以调用方那一侧仍然要自己判一遍（见 `isAccepted`）。
     */
    fun pickFiles(title: String, multi: Boolean, extensions: List<String>? = null): List<File> {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            isMultipleMode = multi
            extensions?.takeIf { it.isNotEmpty() }?.let { setFilenameFilter { _, name -> isAccepted(name, it) } }
        }
        dialog.isVisible = true
        val files = dialog.files?.toList().orEmpty()
        if (files.isEmpty()) return emptyList()
        // 过滤器之外的照样能选中（用户切到「全部文件」），这里再筛一遍，免得挑进来的东西用不了
        return if (extensions.isNullOrEmpty()) files else files.filter { isAccepted(it.name, extensions) }
    }
    /**
     * 「请假附件」那一格（`AttendanceScreen.pickEvidenceFiles`）：系统多选文件 → （文件名, 字节）表。
     *
     * 与 `:app` 的 `rememberPickEvidenceFiles` 同语义：取消 = 空表（表单照旧，不发附件），
     * 不是报错。上限与 Android 那侧一样是“不把整个磁盘读进内存”：[MAX_EVIDENCE_BYTES] 以上的文件
     * 跳过并说一句，而不是默默把人家的几个 G 读进堆里。
     */
    fun pickEvidenceFiles(): List<Pair<String, ByteArray>> {
        val files = pickFiles("选择请假附件", multi = true)
        if (files.isEmpty()) return emptyList()
        var skipped = 0
        val loaded = files.take(MAX_EVIDENCE_FILES).mapNotNull { f ->
            runCatching {
                if (f.length() > MAX_EVIDENCE_BYTES) {
                    skipped++
                    null
                } else {
                    f.name to f.readBytes()
                }
            }.getOrNull()
        }
        if (skipped > 0) {
            showBriefMessage("有 $skipped 个附件超过 ${MAX_EVIDENCE_BYTES / 1024 / 1024}MB，没有带上")
        }
        return loaded
    }

    /** 一次最多带几个附件（与表单那一排缩略图的宽度一个量级）。 */
    private const val MAX_EVIDENCE_FILES = 6

    /** 单个附件的上限：超过它就不读字节（不把几个 G 的文件读进堆里）。 */
    private const val MAX_EVIDENCE_BYTES = 25L * 1024 * 1024

    /** 这个文件名是不是这些后缀之一（大小写不敏感，后缀串不带点）。 */
    private fun isAccepted(name: String, extensions: List<String>): Boolean =
        extensions.any { name.endsWith(".$it", ignoreCase = true) }
}
