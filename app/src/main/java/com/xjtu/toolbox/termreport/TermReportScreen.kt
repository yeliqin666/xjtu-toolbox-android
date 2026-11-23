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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
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
import com.xjtu.toolbox.account.AccountContext
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
    /** false = 调试版指定学期生成的，刷新时也不存进往期。 */
    val archive: Boolean = true,
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

    /** 非空 = 正在补数据，盖一层加载窗。 */
    var fillSteps by remember { mutableStateOf<List<FillStep>?>(null) }
    var fillJob by remember { mutableStateOf<Job?>(null) }
    var showJob by remember { mutableStateOf<Job?>(null) }
    var skipping by remember { mutableStateOf(false) }

    /**
     * 先补齐本机不全的数据（加载窗里逐项显示，可跳过），再生成。[archive] 为 false 时不存进往期（调试版指定学期）。
     * 整个过程只有一个协程：返回 / 关闭取消它，finally 里把加载窗和 busy 一并收掉，不会卡住。
     */
    fun show(archive: Boolean = true, resolve: suspend () -> KnownTerm?) {
        if (busy) return
        busy = true
        error = null
        showJob = scope.launch {
            try {
                val term = resolve() ?: run { error = "没找到这个学期"; return@launch }
                val filler = TermDataFill(context, term)
                val plan = filler.plan()
                if (plan.isNotEmpty()) {
                    var steps = plan
                    fillSteps = steps
                    val job = launch {
                        for (i in plan.indices) {
                            steps = steps.toMutableList().also { it[i] = it[i].copy(state = FillState.RUNNING) }
                            fillSteps = steps
                            val done = filler.runStep(plan[i])
                            steps = steps.toMutableList().also { it[i] = done }
                            fillSteps = steps
                        }
                        delay(600) // 让最后一项的结果看得见再关
                    }
                    fillJob = job
                    job.join() // 点了「跳过」只取消这个子任务，这里照常往下生成
                    fillJob = null
                    fillSteps = null
                }
                runCatching {
                    val r = loadTermReport(context, term)
                    val html = r.toHtml()
                    val snapshot = r.agentSnapshot()
                    if (archive) withContext(Dispatchers.IO) { TermReportStore.save(context, term.code, html, snapshot) }
                    // 学校那边就没有的那几块，不再提示「去加载」
                    val unavailable = unavailableBlocks(AccountContext.activeAccountId, term.code)
                    Viewing(html, snapshot, term, r.missing.filterNot { MISSING_LABEL_KEYS[it.first] in unavailable }, archive)
                }.onSuccess {
                    viewing = it
                    saved = TermReportStore.codes(context)
                }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // 重算失败就退回存档，别让用户什么都看不到
                    val html = if (archive) TermReportStore.load(context, term.code) else null
                    if (html != null) viewing = Viewing(html, TermReportStore.agentSnapshot(context, term.code), term)
                    else error = FriendlyError.of(e, "生成学期总结")
                }
            } finally {
                fillSteps = null
                fillJob = null
                skipping = false
                busy = false
            }
        }
    }

    fun show(term: KnownTerm, archive: Boolean = true) = show(archive) { term }

    fun cancelShow() {
        showJob?.cancel()
        showJob = null
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
                viewing?.let { v -> v.term?.let { show(it, v.archive) } }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = viewing != null || fillSteps != null) {
        if (fillSteps != null) cancelShow() else viewing = null
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1F4B), Color(0xFF16122E))))) {
        val v = viewing
        if (v != null) {
            ReportViewer(
                v,
                busy = busy,
                onRefresh = { v.term?.let { show(it, v.archive) } },
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
                // 调试版专用：不受学期门槛限制，用一整套示例数据看排版和文案，不落盘
                if (com.xjtu.toolbox.BuildConfig.DEBUG) Pill("用示例数据预览（调试版）", filled = false) {
                    val demo = demoTermReport()
                    viewing = Viewing(demo.toHtml(), demo.agentSnapshot(), null)
                }
                // 调试版专用：拿 2025-2026-2 的真实数据走一遍「补齐 + 生成」，不存进往期
                if (com.xjtu.toolbox.BuildConfig.DEBUG) Pill(if (busy) "正在整理…" else "用 2025-2026-2 真实数据（调试版）", filled = false) {
                    show(archive = false) { resolveTerm(DEBUG_TERM) }
                }
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
            when {
                fillSteps != null -> cancelShow()
                viewing != null -> viewing = null
                else -> onBack()
            }
        }
        fillSteps?.let { steps ->
            FillOverlay(
                steps,
                skipping = skipping,
                onSkip = { if (!skipping) { skipping = true; fillJob?.cancel() } },
                onCancel = ::cancelShow,
            )
        }
    }
}

private const val DEBUG_TERM = "2025-2026-2"

/** 补数据的加载窗：盖住整页、吃掉点击，逐项显示进度；可跳过（用已有的生成）或取消。 */
@Composable
private fun FillOverlay(steps: List<FillStep>, skipping: Boolean, onSkip: () -> Unit, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f))
            .pointerInput(Unit) { detectTapGestures { } }
            .navigationBarsPadding().statusBarsPadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 420.dp).clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF232849)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("正在补齐这学期的数据", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Small("本机缺的部分从学校系统拉一次，只存在这台手机上")
            steps.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        when (s.state) {
                            FillState.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp))
                            FillState.WAITING -> Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
                            else -> Icon(
                                when (s.state) {
                                    FillState.DONE -> Icons.Default.Check
                                    FillState.SKIPPED, FillState.UNAVAILABLE -> Icons.Default.Remove
                                    else -> Icons.Default.ErrorOutline
                                },
                                contentDescription = null,
                                tint = when (s.state) {
                                    FillState.DONE -> Color(0xFF86EFAC)
                                    FillState.SKIPPED, FillState.UNAVAILABLE -> Color.White.copy(alpha = 0.5f)
                                    else -> Color(0xFFFDBA74)
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(s.label, color = Color.White.copy(alpha = if (s.state == FillState.WAITING) 0.55f else 1f), fontSize = 15.sp)
                        if (s.note.isNotBlank()) Text(s.note, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 2)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                Pill("取消", filled = false, onClick = onCancel)
                Pill(if (skipping) "正在生成…" else "跳过，用已有的", onClick = onSkip)
            }
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
    // 背景音乐默认关；第一次打开才去取直链
    var musicOn by remember { mutableStateOf(false) }
    var musicUsed by remember { mutableStateOf(false) }
    if (musicUsed) BackgroundMusic(musicOn)
    var hintClosed by remember(v.html) { mutableStateOf(false) }
    // 提示条放在报告下面而不是盖在上面：WebView 让出高度，页面按剩下的空间排，不会被挡住
    Column(Modifier.fillMaxSize().background(Color(0xFF101426))) {
    Box(Modifier.fillMaxWidth().weight(1f)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // 缺省 WRAP_CONTENT 时页面视口高度为 0，`height:100%` 的滚动容器塌掉，整页空白
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(0xFF101426.toInt())
                    settings.javaScriptEnabled = true
                    // 报告里的链接（去 GitHub 点星）交给浏览器，不在报告里跳走
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)) }
                            return true
                        }
                    }
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
            RoundButton(if (musicOn) Icons.Default.MusicNote else Icons.Default.MusicOff, if (musicOn) "关闭背景音乐" else "打开背景音乐") {
                musicUsed = true
                musicOn = !musicOn
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
    }
        if (v.missing.isNotEmpty() && !hintClosed) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF16122E)).navigationBarsPadding()
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "还没读到${v.missing.joinToString("、") { it.first }}：可能没登录或学校系统暂时连不上。也可以打开对应页面加载一次，回来自动补上",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.weight(1f).padding(top = 6.dp),
                    )
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickable { hintClosed = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Close, contentDescription = "关闭提示", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp)) }
                }
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
