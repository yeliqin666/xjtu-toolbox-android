package com.xjtu.toolbox.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Send
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Button
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 用户反馈屏。三件事：
 * 1. 写一段文字描述 → 写到 cache/feedback/last.txt（不传任何账号信息）
 * 2. 拉取最近 7 天 ErrorReporter 写的错误日志 → 同样落 cache/feedback/ 下
 * 3. 跳 GitHub Issue 模板（占位 URL，后续替换）
 *
 * **不强制账号，不上传凭据**。用户主动选择分享什么。
 */
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "反馈与建议",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "想说点什么？Bug、功能建议、吐槽都行。" +
                    "反馈内容只保存在本地，由你决定要不要发出去。",
                style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2,
            )

            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                label = "写下你的反馈",
                useLabelAsPlaceholder = true,
            )

            Button(
                onClick = {
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            FeedbackStore.writeFeedback(context, text)
                        }
                        android.widget.Toast.makeText(
                            context,
                            if (saved) "反馈已保存到 cache/feedback/"
                            else "保存失败",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  保存到本地")
            }

            Button(
                onClick = {
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            FeedbackStore.collectRecentLogs(context, days = 7)
                        }
                        android.widget.Toast.makeText(
                            context,
                            if (saved) "已收集最近 7 天日志"
                            else "日志收集失败",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null)
                Text("  收集最近 7 天日志")
            }

            Button(
                onClick = {
                    val url = "https://github.com/xjtu-toolbox/xjtu-toolbox/issues/new"
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Description, contentDescription = null)
                Text("  跳 GitHub 提 Issue")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 反馈本地存储：cache/feedback/。所有写文件都走 Dispatchers.IO。
 */
object FeedbackStore {
    private const val DIR = "feedback"
    private const val FEEDBACK_FILE = "last.txt"

    fun writeFeedback(context: android.content.Context, text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            File(dir, FEEDBACK_FILE).writeText(text, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            android.util.Log.w("FeedbackStore", "writeFeedback failed", e)
            false
        }
    }

    /**
     * 把 cache/error_reports/ 下最近 [days] 天的日志合并到 cache/feedback/recent_logs.txt，
     * 方便用户分享。文件大小由 stackTrace 截断保证可控。
     */
    fun collectRecentLogs(context: android.content.Context, days: Int): Boolean {
        return try {
            val src = File(context.cacheDir, "error_reports")
            val out = File(File(context.cacheDir, DIR).apply { mkdirs() }, "recent_logs.txt")
            val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
            val files = src.listFiles { f ->
                f.isFile && f.lastModified() >= cutoff
            }?.sortedBy { it.name } ?: emptyList()
            out.bufferedWriter().use { w ->
                files.forEach { f ->
                    w.write("=== ${f.name} ===\n")
                    f.forEachLine { w.write(it); w.write("\n") }
                }
            }
            files.isNotEmpty()
        } catch (e: Exception) {
            android.util.Log.w("FeedbackStore", "collectRecentLogs failed", e)
            false
        }
    }
}