package com.xjtu.toolbox.desktop.dzpz

import com.xjtu.toolbox.dzpz.TranscriptSaveSink
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.platform.showBriefMessage
import java.io.File

/**
 * 成绩单 PDF 在桌面端的落盘 —— `:core` 的 [TranscriptSaveSink] 在桌面侧的填法。
 *
 * 与 Android 那份（`:app` 的 `AppTranscriptSaver`，MediaStore + 下载管理）形状相同、落脚点不同：
 * 桌面**没有** MediaStore，也没有「我的 · 下载管理」那一屏，所以写用户的下载目录
 *（`$HOME/Downloads`；Linux 上 `xdg-user-dir DOWNLOAD` 的默认值就是它）。
 *
 * 界面上那两行文案因此也不是 Android 那两句：按钮仍是「保存到下载」，说明改成桌面真能做的
 * 那件事（文件就落在下载目录，用任何 PDF 阅读器打开）——**不抄 Android 的「保存在『我的 · 下载管理』」**，
 * 这个端没有那东西。
 *
 * 落盘失败（目录不可写、磁盘满）如实报一句，不假装成功：与 Android 侧那三句 Toast 同一条口径，
 * 只是这一端没有系统气泡，走 `:core` 的 `showBriefMessage`（桌面 = 打到标准输出）。
 */
object DesktopTranscriptSaver : TranscriptSaveSink {

    override val actionLabel: String = "保存到下载"

    override val hint: String = "保存到「下载」目录（$saveDirectory/），可用任意 PDF 阅读器打开。"

    override val locationLabel: String = "下载/"

    /** 用户下载目录：`$HOME/Downloads`（与系统约定的默认值一致，不额外查 xdg 配置）。 */
    private val saveDirectory: File
        get() = File(System.getProperty("user.home") ?: ".", "Downloads")

    override fun save(filename: String, bytes: ByteArray) {
        try {
            val dir = saveDirectory
            if (!dir.exists() && !dir.mkdirs()) error("下载目录不存在也建不出来：$dir")
            // 文件名里可能有 `/`（服务端给的名字是它自己的），只取最后一段，别把文件写到别处去
            val name = filename.substringAfterLast('/').ifBlank { "成绩单.pdf" }
            File(dir, name).writeBytes(bytes)
            showBriefMessage("已保存到 $dir/$name")
        } catch (e: Exception) {
            showBriefMessage(FriendlyError.of(e, "保存"))
        }
    }
}
