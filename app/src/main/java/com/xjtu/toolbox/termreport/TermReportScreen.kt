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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

/** 正在看的那份总结。[term] 非空 = 最近结束的学期，可以刷新；往期的是定稿。 */
private data class Viewing(
    val html: String,
    val agentSnapshot: String?,
    val term: KnownTerm?,
    val missing: List<Pair<String, AppRoute>> = emptyList(),
)

/**
 * 学期总结（#282）。只给**所有教学周都过完**的学期生成，进行中的学期一律不开预览；
 * 进入新学期后，刚结束的那个学期照样能看。最近结束的那份每次打开都按本机最新缓存重算
 * （成绩出得晚也能补上），下一个学期结束后它才定稿，存成 HTML 留在「往期回顾」。
 */
@Composable
fun TermReportScreen(onBack: () -> Unit, onAskAgent: (AgentForward) -> Unit, onNavigate: (AppRoute) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TermStatus?>(null) }
    var saved by remember { mutableStateOf(TermReportStore.codes(context)) }
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 去别的页加载数据了，回来时自动重算一遍。 */
    var refreshOnReturn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { status = termStatus() }

    fun show(term: KnownTerm) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            runCatching {
                val r = loadTermReport(context, term)
                val html = r.toHtml()
                val snapshot = r.agentSnapshot()
                withContext(Dispatchers.IO) { TermReportStore.save(context, term.code, html, snapshot) }
                Viewing(html, snapshot, term, r.missing)
            }.onSuccess {
                viewing = it
                saved = TermReportStore.codes(context)
            }.onFailure { e ->
                // 重算失败就退回存档，别让用户什么都看不到
                val html = TermReportStore.load(context, term.code)
                if (html != null) viewing = Viewing(html, TermReportStore.agentSnapshot(context, term.code), term)
                else error = FriendlyError.of(e, "生成学期总结")
            }
            busy = false
        }
    }

    fun openArchived(code: String) {
        val html = TermReportStore.load(context, code) ?: return
        viewing = Viewing(html, TermReportStore.agentSnapshot(context, code), null)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && refreshOnReturn) {
                refreshOnReturn = false
                viewing?.term?.let(::show)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = viewing != null) { viewing = null }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1F4B), Color(0xFF16122E))))) {
        val v = viewing
        if (v != null) {
            ReportViewer(
                v,
                busy = busy,
                onRefresh = { v.term?.let(::show) },
                onAskAgent = onAskAgent,
                onLoad = { route -> refreshOnReturn = true; onNavigate(route) },
            )
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 72.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Small("学期总结")
                val s = status
                val ended = s?.ended
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
                    ended.code in saved -> {
                        Big("${ended.name}\n的总结")
                        Body("每次打开都会按手机里最新的数据重算一遍，成绩出得晚也没关系。")
                        Pill(if (busy) "正在整理…" else "打开") { show(ended) }
                    }
                    else -> {
                        Big("${ended.name}\n结束啦")
                        Body("用这台手机上已有的课表、考勤、成绩、待办、校园卡、体测，以及小游戏、小岱和工具箱的使用记录拼一份回顾。只在本机计算，不会上传，生成后会保存下来。")
                        Pill(if (busy) "正在整理…" else "开始回顾") { show(ended) }
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
                                .background(Color.White.copy(alpha = 0.1f)).clickable { openArchived(code) }.padding(16.dp),
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

/** 存下来的 HTML 用 WebView 原样展示；右上角刷新、截当前页分享、交给小岱，缺数据时底部给去加载的入口。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReportViewer(
    v: Viewing,
    busy: Boolean,
    onRefresh: () -> Unit,
    onAskAgent: (AgentForward) -> Unit,
    onLoad: (AppRoute) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0xFF101426.toInt())
                    settings.javaScriptEnabled = true
                    webView = this
                }
            },
            update = {
                if (it.tag != v.html) {
                    it.tag = v.html
                    it.loadDataWithBaseURL(null, v.html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (v.term != null) {
                if (busy) CircularProgressIndicator(Modifier.size(28.dp))
                else RoundButton(Icons.Default.Refresh, "按最新数据刷新", onClick = onRefresh)
            }
            RoundButton(Icons.Default.Share, "截这一页分享") {
                val w = webView ?: return@RoundButton
                scope.launch { shareImage(context, w.drawToBitmap().asImageBitmap(), "term-report.png", "分享学期总结") }
            }
            v.agentSnapshot?.let { snapshot ->
                RoundButton(Icons.Default.SmartToy, "让小岱聊聊这学期") {
                    onAskAgent(AgentForward(text = "帮我回顾一下这学期", snapshot = snapshot, label = "学期总结"))
                }
            }
        }
        if (v.missing.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Small("还没读到${v.missing.joinToString("、") { it.first }}：打开对应页面加载一次，回来自动补上")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    v.missing.forEach { (name, route) -> Pill("去加载$name", filled = false) { onLoad(route) } }
                }
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
