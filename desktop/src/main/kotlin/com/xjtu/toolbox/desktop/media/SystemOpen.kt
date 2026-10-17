package com.xjtu.toolbox.desktop.media

import java.awt.Desktop
import java.io.File

/** 把一个地址或本地文件路径交给系统的默认程序打开（Linux `xdg-open` / macOS `open` / Windows `start`）。 */
internal fun openWithSystemProgram(target: String): Boolean = runCatching {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        os.contains("win") -> listOf("cmd", "/c", "start", "", target)
        os.contains("mac") || os.contains("darwin") -> listOf("open", target)
        else -> listOf("xdg-open", target)
    }
    ProcessBuilder(command).redirectErrorStream(true).start()
    true
}.getOrElse {
    // 没有 xdg-open 的机器（或 PATH 里找不到）：退回 AWT 的「用关联程序打开本地文件」
    runCatching {
        val file = File(target)
        if (file.exists() && Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
        ) {
            Desktop.getDesktop().open(file)
            true
        } else {
            false
        }
    }.getOrDefault(false)
}
