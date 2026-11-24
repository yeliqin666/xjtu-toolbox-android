package com.xjtu.toolbox.desktop.media

import java.awt.Desktop
import java.io.File

/** 把一个地址或本地文件路径交给系统的默认程序打开（Windows `url.dll` / macOS `open` / Linux `xdg-open`）。 */
internal fun openWithSystemProgram(target: String): Boolean = runCatching {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        // 不走 `cmd /c start`：cmd 会把地址里的 `&` 当命令分隔符，签名直链与带 `&` 的文件名会被截断
        os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", target)
        os.contains("mac") || os.contains("darwin") -> listOf("open", target)
        else -> listOf("xdg-open", target)
    }
    ProcessBuilder(command).redirectErrorStream(true).start()
    true
}.getOrElse {
    // 外部命令起不来：退回 AWT（本地文件用关联程序打开，地址交给浏览器）
    runCatching {
        if (!Desktop.isDesktopSupported()) return@runCatching false
        val desktop = Desktop.getDesktop()
        val file = File(target)
        when {
            file.exists() && desktop.isSupported(Desktop.Action.OPEN) -> desktop.open(file)
            target.contains("://") && desktop.isSupported(Desktop.Action.BROWSE) -> desktop.browse(java.net.URI(target))
            else -> return@runCatching false
        }
        true
    }.getOrDefault(false)
}
