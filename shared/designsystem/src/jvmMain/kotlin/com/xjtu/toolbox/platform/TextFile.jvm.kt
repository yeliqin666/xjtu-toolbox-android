package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 桌面侧的文本文件通道：`JFileChooser` 的保存 / 打开对话框。
 *
 * 与 `rememberHeroImagePicker` 的 jvm 实现同一个路子（那个也是 Swing 对话框 + 阻塞读），
 * 所以不用为「挑文件」另起一套。
 */
@Composable
actual fun rememberSaveTextFile(): (fileName: String, text: String) -> Unit = remember {
    { fileName, text ->
        val chooser = JFileChooser().apply { selectedFile = File(fileName) }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val target = chooser.selectedFile
            val ok = runCatching { target.writeText(text, Charsets.UTF_8) }.isSuccess
            showBriefMessage(if (ok) "已保存：${target.absolutePath}" else "保存失败：写不进这个位置")
        }
    }
}

@Composable
actual fun rememberPickTextFile(onLoaded: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val loaded = rememberUpdatedState(onLoaded)
    val failed = rememberUpdatedState(onError)
    return remember {
        {
            val chooser = JFileChooser().apply {
                fileFilter = FileNameExtensionFilter("布局文本", "txt", "json", "md")
                isAcceptAllFileFilterUsed = true
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                if (text == null) failed.value("读不出这个文件") else loaded.value(text)
            }
        }
    }
}
