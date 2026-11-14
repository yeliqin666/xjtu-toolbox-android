package com.xjtu.toolbox.lms


import com.xjtu.toolbox.platform.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.schedule.defaultCourseColor
import com.xjtu.toolbox.auth.LocalAuthExpiry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppChipTabRow
import com.xjtu.toolbox.ui.components.AppInsetColor
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.components.rememberRetainedLazyStaggeredGridState
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private const val TAG = "LmsScreen"

/** 学期胶囊里「全部」的键。 */
private const val ALL_SEMESTERS = ""

// ════════════════════════════════════════
//  页面 1 — 课程列表
// ════════════════════════════════════════

@Composable
internal fun CourseListPage(
    source: LmsSource,
    cache: LmsPageCache,
    onBack: () -> Unit,
    onCourseSelected: (LmsCourseSummary) -> Unit
) {
    val authExpiry = LocalAuthExpiry.current
    val courses = cache.courses
    var isLoading by remember { mutableStateOf(cache.courses.isEmpty()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // null = 还没选过：有本学期的课就看本学期，否则看全部
    var selectedSemester by remember { mutableStateOf(cache.selectedSemester) }
    val scope = rememberCoroutineScope()

    // 学期筛选也跟着缓存走，返回时保持用户的选择
    LaunchedEffect(selectedSemester) { cache.selectedSemester = selectedSemester }

    val listState = rememberRetainedLazyStaggeredGridState("lms_courses")
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()

    fun loadCourses() {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                cache.syncCourses(source)
            } catch (e: Exception) {
                if (e is com.xjtu.toolbox.error.SessionExpiredFailure) {
                    authExpiry.onAuthExpired(AppRoute.Lms(), onBack)
                } else {
                    Log.e(TAG, "loadCourses error", e)
                    errorMsg = com.xjtu.toolbox.error.FriendlyError.of(e, "加载课程")
                }
            } finally {
                isLoading = false
            }
        }
    }

    // 本次已刷新过就不再请求——返回上一层应当是「回到原样」而不是重新加载
    LaunchedEffect(Unit) { if (!cache.coursesSynced) loadCourses() }

    val currentSemester = remember { currentLmsSemester(todayOnCampus()) }
    // 课程到手后扫一遍本学期作业：课程卡的「待交」和顶上的概览都靠它
    LaunchedEffect(cache.coursesSynced) {
        if (cache.coursesSynced) cache.scanOpenHomework(source, courses.filter { it.semesterLabel == currentSemester })
    }

    val semesters = remember(courses) {
        courses.map { it.semesterLabel }.distinct().sortedDescending()
    }
    val semester = selectedSemester ?: currentSemester.takeIf { it in semesters } ?: ALL_SEMESTERS
    val pendingByCourse = courses.associate { it.id to cache.pendingOf(it.id) }

    val filtered = remember(courses, semester, pendingByCourse) {
        (if (semester == ALL_SEMESTERS) courses else courses.filter { it.semesterLabel == semester })
            // 有作业要交的排前面，按最近截止；其余保持原顺序
            .sortedBy { pendingByCourse[it.id]?.firstOrNull()?.deadlineInstant()?.toEpochMilliseconds() ?: Long.MAX_VALUE }
    }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "思源学堂",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Box(Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            when {
                isLoading && courses.isEmpty() -> LoadingIndicator("加载课程列表…")
                errorMsg != null && courses.isEmpty() -> ErrorRetry(errorMsg!!) { loadCourses() }
                courses.isEmpty() -> EmptyState(Icons.Default.School, "没有课程", "暂未加入任何课程")
                else -> {
                    // 宽屏卡片分两三列（见 AdaptiveCardGrid）；卡片自带外边距，间距给 0
                    AdaptiveCardGrid(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = glassTop, bottom = 16.dp),
                        spacing = 0.dp,
                        horizontalSpacing = 0.dp,
                    ) {
                        if (semesters.size > 1) {
                            fullLineItem(key = "semesters") {
                                AppChipTabRow(
                                    options = listOf(ALL_SEMESTERS) + semesters,
                                    selected = semester,
                                    onSelect = { selectedSemester = it },
                                    label = { it.ifEmpty { "全部" } },
                                )
                            }
                        }
                        // 本学期的待交：接了屁岱就让它排先后，否则给本地的一览
                        if (semester == currentSemester) {
                            val cardPadding = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)
                            fullLineItem(key = "todos") {
                                if (com.xjtu.toolbox.agent.AgentInsightHub.insights.available) {
                                    LmsTodoCard(source, courses, cache, onCourseSelected, cardPadding.enterOnce(0))
                                } else {
                                    DueOverviewCard(
                                        scanned = cache.openHomework != null,
                                        pending = filtered.flatMap { c -> pendingByCourse[c.id].orEmpty().map { c to it } }
                                            .sortedBy { it.second.deadlineInstant()?.toEpochMilliseconds() ?: Long.MAX_VALUE },
                                        onOpenCourse = onCourseSelected,
                                        modifier = cardPadding.enterOnce(0),
                                    )
                                }
                            }
                        }
                        itemsIndexed(filtered, key = { _, it -> it.id }) { index, course ->
                            LmsCourseCard(
                                course,
                                pending = pendingByCourse[course.id].orEmpty(),
                                showSemester = semester == ALL_SEMESTERS,
                                modifier = Modifier.enterOnce(1 + index.coerceAtMost(6)),
                            ) { onCourseSelected(course) }
                        }
                    }
                }
            }
        }
    }
}
/** 课程主色：用户在课表或这里改过就用改过的，否则与课表一样按课名取默认色。 */
internal fun lmsAccent(course: LmsCourseSummary): Color = CourseColors.of(course.name) ?: defaultCourseColor(course.name)

private fun Instant.isWithinDay(): Boolean = this - Clock.System.now() < 24.hours

/** 本学期没交的作业一览（没接屁岱时）：几项待交 + 最近的三项，点一项进那门课。 */
@Composable
private fun DueOverviewCard(
    scanned: Boolean,
    pending: List<Pair<LmsCourseSummary, LmsActivity>>,
    onOpenCourse: (LmsCourseSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val urgent = pending.any { it.second.deadlineInstant()?.isWithinDay() == true }
    val accent = if (urgent) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    !scanned -> Text("正在看各门课的作业…", style = MiuixTheme.textStyles.body2, color = summary)
                    pending.isEmpty() -> {
                        Icon(Icons.Default.CheckCircle, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("本学期的作业都交了", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    }
                    else -> {
                        Text("${pending.size}", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold, color = accent)
                        Spacer(Modifier.width(6.dp))
                        Text("项作业待交", style = MiuixTheme.textStyles.body2, color = summary)
                    }
                }
            }
            pending.take(3).forEach { (course, activity) -> DueRow(course, activity, onOpenCourse) }
            if (pending.size > 3) {
                Text("还有 ${pending.size - 3} 项，见下面各门课", style = MiuixTheme.textStyles.footnote2, color = summary)
            }
        }
    }
}

@Composable
private fun DueRow(course: LmsCourseSummary, activity: LmsActivity, onOpenCourse: (LmsCourseSummary) -> Unit) {
    val due = activity.deadlineInstant()
    val soon = due?.isWithinDay() == true
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppInsetColor)
            .clickable { onOpenCourse(course) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(lmsAccent(course)))
        Column(Modifier.padding(start = 10.dp, end = 8.dp).weight(1f)) {
            Text(activity.title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(course.name, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        due?.let {
            Text(
                dueLabel(it),
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = if (soon) FontWeight.Bold else FontWeight.Normal,
                color = if (soon) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun LmsCourseCard(
    course: LmsCourseSummary,
    pending: List<LmsActivity>,
    showSemester: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = lmsAccent(course)
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Card(
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        cornerRadius = 18.dp,
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // 课程色和课表同一种，一眼对得上
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(course.name.take(1), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold, color = accent)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(course.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    course.instructorNames.takeIf { it.isNotBlank() },
                    course.credit.takeIf { it.isNotEmpty() }?.let { "$it 学分" },
                    course.semesterLabel.takeIf { showSemester },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(meta, fontSize = 13.sp, color = summary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pending.isNotEmpty()) {
                    val due = pending.first().deadlineInstant()
                    val tint = if (due?.isWithinDay() == true) MiuixTheme.colorScheme.error else accent
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Assignment, null, tint = tint, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${pending.size} 项待交" + (due?.let { " · ${dueLabel(it)} 截止" } ?: ""),
                            style = MiuixTheme.textStyles.footnote2,
                            fontWeight = FontWeight.Medium,
                            color = tint,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = summary, modifier = Modifier.size(20.dp))
        }
    }
}
