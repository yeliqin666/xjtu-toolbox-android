package com.xjtu.toolbox.desktop.dzpz

import com.xjtu.toolbox.dzpz.TranscriptSaveSink
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.platform.showBriefMessage
import java.io.File

/** 成绩单 PDF 在桌面端的落盘 —— `:core` 的 [TranscriptSaveSink] 在桌面侧的填法。 */
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
