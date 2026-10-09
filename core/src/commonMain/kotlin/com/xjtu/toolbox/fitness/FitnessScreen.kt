package com.xjtu.toolbox.fitness

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.LocalAuthExpiry
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.FullPageState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.MeshBackground
import com.xjtu.toolbox.ui.components.RollingNumberText
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import com.xjtu.toolbox.ui.isWideLayout
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 体测查询 —— 从 `:app/fitness/FitnessScreen.kt` 搬进 `:core`。
 *
 * 搬迁时被替换掉的四处写法（其余逐字保留）：
 *  1. `SiteSession` + `LocalAppLoginState` → 注入的 [FitnessSource] 与
 *     [LocalAuthExpiry]（后者是这一步新立的平台缝，见 `auth/AuthExpiry.kt`）；
 *  2. `withContext(Dispatchers.IO)` → 去掉：取数已是挂起调用，自己处理 IO
 *     （`Dispatchers.IO` 在 wasm 上根本不存在）；
 *  3. `"%.1f".format(...)` / `"%.0f".format(...)`（`String.format` 是 JVM 专属，
 *     而且在 JVM 上是默认导入 —— import 判据看不见它）→ [oneDecimal] / [int0]；
 *  4. `FriendlyError.of(e, "查询体测")` 保持原样（`FriendlyError` 本来就在 `:core`）。
 *
 * @param source 取数端口。`:app` 传 `FitnessApi`（v3+legacy 两条路），
 *   Web 传 `CampusFitnessApi`。两者产出的都是本文件这套模型。
 */
@Composable
fun FitnessScreen(
    source: FitnessSource,
    onBack: () -> Unit,
) {
    val authExpiry = LocalAuthExpiry.current
    val scope = rememberCoroutineScope()
    var years by remember { mutableStateOf<List<FitnessYear>>(emptyList()) }
    var selectedYear by remember { mutableStateOf<FitnessYear?>(null) }
    var score by remember { mutableStateOf<FitnessScore?>(null) }
    var loading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadYears() {
        loading = true
        error = null
        try {
            years = source.years()
            // 不要信服务端 checked，也不要 years.first()：列表常把还没开测的下一学年排在最前。
            val candidates = orderedFitnessYears(years)
            var picked: Pair<FitnessYear, FitnessScore>? = null
            var fallback: Pair<FitnessYear, FitnessScore>? = null
            var lastError: String? = null
            for (year in candidates.take(3)) {
                val result = runCatching { source.score(year.yearNum) }
                val s = result.getOrNull()
                if (s == null) {
                    lastError = result.exceptionOrNull()?.let { FriendlyError.of(it, "查询体测") }
                        ?: "该学年暂无体测数据"
                    continue
                }
                if (s.hasUsableTotal()) {
                    picked = year to s
                    break
                }
                if (fallback == null) fallback = year to s
            }
            val chosen = picked ?: fallback
            if (chosen != null) {
                selectedYear = chosen.first
                score = chosen.second
                error = null
            } else {
                selectedYear = candidates.firstOrNull()
                score = null
                error = lastError ?: "该学年暂无体测数据"
            }
        } catch (e: Exception) {
            if (e.isAuthExpiry()) {
                authExpiry.onAuthExpired(AppRoute.Fitness, onBack)
                return
            }
            error = FriendlyError.of(e, "查询体测")
        } finally {
            loading = false
        }
    }

    suspend fun selectYear(year: FitnessYear) {
        selectedYear = year
        loading = true
        error = null
        try {
            score = source.score(year.yearNum)
        } catch (e: Exception) {
            if (e.isAuthExpiry()) {
                authExpiry.onAuthExpired(AppRoute.Fitness, onBack)
                return
            }
            score = null
            error = FriendlyError.of(e, "查询体测")
        } finally {
            loading = false
        }
    }

    suspend fun refreshCurrent() {
        isRefreshing = true
        try {
            selectedYear?.let { year ->
                error = null
                try {
                    score = source.score(year.yearNum)
                } catch (e: Exception) {
                    if (e.isAuthExpiry()) {
                        authExpiry.onAuthExpired(AppRoute.Fitness, onBack)
                        return
                    }
                    error = FriendlyError.of(e, "查询体测")
                }
            } ?: loadYears()
        } finally {
            isRefreshing = false
        }
    }

    val yearListState = rememberLazyListState()
    // 选中的学年滚进可视区
    LaunchedEffect(selectedYear, years) {
        val index = years.indexOf(selectedYear)
        if (index >= 0) runCatching { yearListState.animateScrollToItem(index) }
    }

    LaunchedEffect(source) { loadYears() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "体测查询",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        AppPullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = { scope.launch { refreshCurrent() } },
            scrollBehavior = scrollBehavior,
            topPadding = glassTop,
            modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass),
        ) {
            when {
                loading && score == null -> FullPageState(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = glassTop)) {
                    LoadingState("正在读取体测成绩…", Modifier.fillMaxSize())
                }
                years.isEmpty() && error != null && score == null -> FullPageState(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = glassTop)) {
                    ErrorState("查询失败：$error", onRetry = { scope.launch { loadYears() } }, modifier = Modifier.fillMaxSize())
                }
                years.isEmpty() -> FullPageState(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = glassTop)) {
                    EmptyState("暂无可查询的体测学年", modifier = Modifier.fillMaxSize())
                }
                else -> {
                    // 宽屏两栏：左边学年 + 总分，右边各项目成绩。以前一列卡片横跨整个平板，
                    // 项目名在最左、分数在最右，中间隔着大半个屏幕。
                    val wide = isWideLayout()
                    val itemsCard: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                        score?.let { result ->
                            if (result.items.isNotEmpty()) {
                                item {
                                    Card(
                                        modifier = Modifier.enterOnce(2).fillMaxWidth().padding(horizontal = 16.dp),
                                        colors = CardDefaults.defaultColors(
                                            color = MiuixTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Column {
                                            Text(
                                                "项目成绩",
                                                style = MiuixTheme.textStyles.title2,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                                            )
                                            // 卡片落位后，各项目再一行行跟上
                                            result.items.forEachIndexed { i, item ->
                                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                                Box(Modifier.enterOnce(i + 3)) { FitnessItemRow(item) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .then(if (wide) Modifier.width(460.dp) else Modifier.weight(1f))
                                .fillMaxHeight()
                                .overScrollVertical(),
                            contentPadding = PaddingValues(top = glassTop + 16.dp, bottom = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                // 用 LazyRow 而非 horizontalScroll 的 Row：学年多起来时选中项常在最右侧，
                                // 普通 Row 没法定位到某一项，用户看到的就是一排全未选中的胶囊
                                // ——数据其实是对的，只是选中的那枚在屏幕外。
                                LazyRow(
                                    state = yearListState,
                                    modifier = Modifier.enterOnce(0).fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(years) { year ->
                                        YearChip(
                                            year = year,
                                            selected = year == selectedYear,
                                            onClick = { scope.launch { selectYear(year) } }
                                        )
                                    }
                                }
                            }
                            score?.let { result ->
                                item { Box(Modifier.enterOnce(1)) { ScoreHero(result) } }
                            }
                            if (!wide) itemsCard()
                            if (error != null && score == null) {
                                item {
                                    ErrorState(
                                        "查询失败：$error",
                                        onRetry = { scope.launch { refreshCurrent() } },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        }
                        if (wide) {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxHeight().overScrollVertical(),
                                contentPadding = PaddingValues(top = glassTop + 16.dp, bottom = 28.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsCard()
                            }
                        }
                    } // Row
                }
            }
        }
    }
}

/**
 * 会话失效的判据。
 *
 * `:app` 的 `AuthExpiredException` 在 `:core` 不存在，所以靠 [com.xjtu.toolbox.error.UserFacingFailure]
 * 之外的第二条线索识别：`:app` 的 `AuthExpiredException` 实现了
 * `com.xjtu.toolbox.error.SessionExpiredFailure` 标记接口（`:core` 定义），
 * `FriendlyError` 也是按它给出「登录态已失效」那句话的 —— 两边同一份判据。
 */
private fun Throwable.isAuthExpiry(): Boolean =
    this is com.xjtu.toolbox.error.SessionExpiredFailure

@Composable
private fun YearChip(year: FitnessYear, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MiuixTheme.colorScheme.primary
    else MiuixTheme.colorScheme.surfaceVariant
    val fg = if (selected) MiuixTheme.colorScheme.onPrimary
    else MiuixTheme.colorScheme.onSurface
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp)
    ) {
        Text(year.name, color = fg, style = MiuixTheme.textStyles.footnote1)
    }
}

@Composable
private fun ScoreHero(score: FitnessScore) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.defaultColors(color = Color.Transparent)
    ) {
        Box(Modifier.fillMaxWidth()) {
            // 蓝绿两色的流动底色（原来的静态渐变改成 Mesh），6 秒后停：这张卡在玻璃顶栏下面
            MeshBackground(
                modifier = Modifier.matchParentSize(),
                lightVertexColors = FitnessHeroMesh,
                darkVertexColors = FitnessHeroMesh,
                runForMillis = 6_000L,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier.size(50.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.DirectionsRun,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        score.studentName.ifBlank { "体测成绩" },
                        color = Color.White,
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOf(score.sex, score.grade)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        color = Color.White.copy(alpha = 0.78f),
                        style = MiuixTheme.textStyles.footnote1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        score.reportStatus.ifBlank { score.reportType },
                        color = Color.White.copy(alpha = 0.88f),
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(
                    modifier = Modifier.width(86.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    // 总分是数字就从 0 滚上来；「未测」这类文字照原样
                    val total = score.totalScore.trim().toDoubleOrNull()
                    if (total != null) {
                        RollingNumberText(
                            value = total,
                            format = { if (score.totalScore.contains('.')) oneDecimal(it) else int0(it) },
                            color = Color.White,
                            style = MiuixTheme.textStyles.title1,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Text(
                            score.totalScore,
                            color = Color.White,
                            style = MiuixTheme.textStyles.title1,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                    Text(
                        score.totalGrade,
                        color = Color.White.copy(alpha = 0.85f),
                        style = MiuixTheme.textStyles.footnote1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private val FitnessHeroMesh = listOf(
    listOf(Color(0xFF1565C0), Color(0xFF1B6FC4), Color(0xFF1E88A8)),
    listOf(Color(0xFF1662B8), Color(0xFF0F7D9E), Color(0xFF00897B)),
    listOf(Color(0xFF136AAE), Color(0xFF0B8C8A), Color(0xFF00796B)),
)

@Composable
private fun FitnessItemRow(item: FitnessItem) {
    val accent = when (item.tone.lowercase()) {
        "green" -> Color(0xFF2E7D32)
        "red" -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(9.dp).clip(CircleShape).background(accent)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            item.name,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                item.value,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold
            )
            Text(
                item.grade,
                color = accent,
                style = MiuixTheme.textStyles.footnote2
            )
        }
    }
}

// ── `String.format("%.1f"/"%.0f")` 的跨端等价实现（JVM 专属，且是默认导入） ──

private fun int0(value: Float): String = value.roundToInt().toString()

private fun oneDecimal(value: Float): String {
    val negative = value < 0
    val scaled = kotlin.math.round(kotlin.math.abs(value) * 10).toLong()
    val body = "${scaled / 10}.${scaled % 10}"
    return if (negative) "-$body" else body
}
