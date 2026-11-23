package com.xjtu.toolbox.termreport

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.drawToBitmap
import com.xjtu.toolbox.agent.AgentForward
import com.xjtu.toolbox.card.shareImage
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 学期总结（#282）。只给**所有教学周都过完**的学期生成，进行中的学期一律不开预览；
 * 进入新学期后，刚结束的那个学期照样能生成。生成后存成 HTML，以后在「往期回顾」里看。
 */
@Composable
fun TermReportScreen(onBack: () -> Unit, onAskAgent: (AgentForward) -> Unit, onNavigate: (AppRoute) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TermStatus?>(null) }
    var saved by remember { mutableStateOf(TermReportStore.codes(context)) }
    /** 正在看的那份：(HTML, 给小岱的摘要)。 */
    var viewing by remember { mutableStateOf<Pair<String, String?>?>(null) }
    /** 生成好了但缺几块数据，等用户决定是先去加载还是直接存。 */
    var draft by remember { mutableStateOf<TermReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { status = termStatus() }

    fun publish(r: TermReport) {
        val html = r.toHtml()
        val snapshot = r.agentSnapshot()
        scope.launch {
            withContext(Dispatchers.IO) { TermReportStore.save(context, r.term.code, html, snapshot) }
            saved = TermReportStore.codes(context)
            draft = null
            viewing = html to snapshot
        }
    }

    fun generate(term: KnownTerm) {
        scope.launch {
            busy = true
            error = null
            runCatching { loadTermReport(context, term) }
                .onSuccess { if (it.missing.isEmpty()) publish(it) else draft = it }
                .onFailure { error = FriendlyError.of(it, "生成学期总结") }
            busy = false
        }
    }

    fun open(code: String) {
        val html = TermReportStore.load(context, code) ?: return
        viewing = html to TermReportStore.agentSnapshot(context, code)
    }

    BackHandler(enabled = viewing != null) { viewing = null }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1F4B), Color(0xFF16122E))))) {
        val shown = viewing
        if (shown != null) {
            ReportWebView(shown.first, shown.second, onAskAgent)
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 72.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Small("学期总结")
                val s = status
                val ended = s?.ended
                val d = draft
                when {
                    s == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        Body("正在查校历…")
                    }
                    ended == null -> {
                        Big("还没到时候")
                        Body(s.current?.let { (t, week) -> "${t.name}现在是第 $week 周，共 ${t.weeks} 周，全部结束后才能生成这学期的总结。" }
                            ?: "校历里还没有已经结束的学期。")
                    }
                    d != null -> {
                        Big("还差一点数据")
                        Body("本机还没有下面这些数据，去对应页面加载一次再回来，总结会更完整；也可以就这样生成。")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            d.missing.forEach { (name, route) -> Pill("去加载$name", filled = false) { draft = null; onNavigate(route) } }
                        }
                        Pill("就这样生成") { publish(d) }
                    }
                    ended.code in saved -> {
                        Big("${ended.name}\n的总结")
                        Pill("打开") { open(ended.code) }
                        Pill(if (busy) "生成中…" else "补了数据，重新生成", filled = false) { if (!busy) generate(ended) }
                    }
                    else -> {
                        Big("${ended.name}\n结束啦")
                        Body("用这台手机上已有的课表、成绩、待办、校园卡、体测，以及小游戏和小岱的使用记录拼一份回顾。只在本机计算，不会上传；生成后会保存下来，以后随时能翻。")
                        Pill(if (busy) "生成中…" else "开始回顾") { if (!busy) generate(ended) }
                        Small("没准备好也没关系，想看的时候再来")
                    }
                }
                s?.current?.takeIf { ended != null }?.let { (t, week) -> Small("${t.name}进行到第 $week 周，结束后再来看它的总结") }
                error?.let { Body(it, color = Color(0xFFFFB4A9)) }
                val older = saved.filter { it != ended?.code }
                if (older.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Small("往期回顾")
                    older.forEach { code ->
                        Text(
                            termLabel(code), color = Color.White, fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.1f)).clickable { open(code) }.padding(16.dp),
                        )
                    }
                }
            }
        }
        RoundButton(Icons.Default.Close, "关闭", Modifier.statusBarsPadding().padding(12.dp)) {
            if (viewing != null) viewing = null else onBack()
        }
    }
}

/** 存下来的 HTML 用 WebView 原样展示；右上角可截当前页分享、交给小岱点评。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReportWebView(html: String, agentSnapshot: String?, onAskAgent: (AgentForward) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0xFF101426.toInt())
                    settings.javaScriptEnabled = true
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    webView = this
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundButton(Icons.Default.Share, "截这一页分享") {
                val v = webView ?: return@RoundButton
                scope.launch { shareImage(context, v.drawToBitmap().asImageBitmap(), "term-report.png", "分享学期总结") }
            }
            if (agentSnapshot != null) RoundButton(Icons.Default.SmartToy, "让小岱聊聊这学期") {
                onAskAgent(AgentForward(text = "帮我回顾一下这学期", snapshot = agentSnapshot, label = "学期总结"))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = Color.White) }
}

@Composable
private fun Big(text: String) = Text(text, color = Color.White, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Body(text: String, color: Color = Color.White.copy(alpha = 0.92f)) =
    Text(text, color = color, fontSize = 16.sp, lineHeight = 24.sp)

@Composable
private fun Small(text: String) = Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)

@Composable
private fun Pill(text: String, filled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = if (filled) Color(0xFF1B1F4B) else Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape)
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
