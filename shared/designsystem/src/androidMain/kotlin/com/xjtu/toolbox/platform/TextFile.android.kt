package com.xjtu.toolbox.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android 侧的文本文件通道：SAF 的「新建文档」/「打开文档」。
 *
 * 不申请任何存储权限：`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` 由系统把 URI 交回来，
 * 我们只拿它写一次 / 读一次（与 `:app` 里档案导出导入同一条路）。
 */
@Composable
actual fun rememberSaveTextFile(): (fileName: String, text: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 待写入的内容：SAF 是「先弹窗、用户选完位置才回来」，所以文本得在这中间挂着
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TEXT_FILE_MIME),
    ) { uri ->
        val payload = pending
        pending = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.second.toByteArray(Charsets.UTF_8))
                        true
                    } ?: false
                }.getOrDefault(false)
            }
            showBriefMessage(if (ok) "已保存：${payload.first}" else "保存失败，换个位置试试")
        }
    }
    return remember(launcher) {
        { fileName, text ->
            pending = fileName to text
            launcher.launch(fileName)
        }
    }
}

@Composable
actual fun rememberPickTextFile(onLoaded: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded = rememberUpdatedState(onLoaded)
    val failed = rememberUpdatedState(onError)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text == null) failed.value("读不出这个文件") else loaded.value(text)
        }
    }
    // 不限 MIME：各家文件选择器对 .txt 的标注并不统一（有的报 application/octet-stream），
    // 卡 MIME 会把用户自己的备份文件灰掉；内容对不对由调用方解一遍来判断。
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}
