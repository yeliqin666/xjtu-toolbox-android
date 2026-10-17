package com.xjtu.toolbox.lms

import com.xjtu.toolbox.platform.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.schedule.defaultCourseColor
import com.xjtu.toolbox.auth.LocalAuthExpiry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import com.xjtu.toolbox.ui.components.rememberRetainedLazyStaggeredGridState
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import androidx.compose.foundation.lazy.staggeredgrid.items
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.xjtu.toolbox.nav.AppRoute

private const val TAG = "LmsScreen"

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
    var selectedSemester by remember { mutableStateOf(cache.selectedSemester) }
    val scope = rememberCoroutineScope()

    // 学期筛选也跟着缓存走，返回时保持用户的选择
    LaunchedEffect(selectedSemester) { cache.selectedSemester = selectedSemester }

    val listState = rememberRetainedLazyStaggeredGridState("lms_courses")
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
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

    val semesters = remember(courses) {
        courses.map { it.semesterLabel }.distinct().sortedDescending()
    }

    val filtered = remember(courses, selectedSemester) {
        if (selectedSemester == null) courses
        else courses.filter { it.semesterLabel == selectedSemester }
    }

    var showSemesterMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "思源学堂",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                actions = {
                    // 学期是整页的筛选，和校园卡的时间范围一样收进顶栏菜单，默认全部
                    if (semesters.size > 1) Box {
                        IconButton(onClick = { showSemesterMenu = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "学期：${selectedSemester ?: "全部"}")
                        }
                        OverlayListPopup(
                            show = showSemesterMenu,
                            alignment = PopupPositionProvider.Align.End,
                            onDismissRequest = { showSemesterMenu = false },
                        ) {
                            val options = listOf<String?>(null) + semesters
                            ListPopupColumn {
                                options.forEachIndexed { idx, sem ->
                                    DropdownImpl(
                                        text = sem ?: "全部学期",
                                        optionSize = options.size,
                                        isSelected = sem == selectedSemester,
                                        onSelectedIndexChange = {
                                            showSemesterMenu = false
                                            selectedSemester = sem
                                        },
                                        index = idx,
                                    )
                                }
                            }
                        }
                    }
                },
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
                        fullLineItem(key = "count") {
                            Text(
                                (selectedSemester ?: "全部学期") + " · 共 ${filtered.size} 门课程",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(filtered, key = { it.id }) { course ->
                            LmsCourseCard(course) { onCourseSelected(course) }
                        }
                    }
                }
            }
        }
    }
}
/** 课程主色：用户在课表或这里改过就用改过的，否则与课表一样按课名取默认色。 */
internal fun lmsAccent(course: LmsCourseSummary): Color = CourseColors.of(course.name) ?: defaultCourseColor(course.name)

@Composable
private fun LmsCourseCard(course: LmsCourseSummary, onClick: () -> Unit) {
    val accent = lmsAccent(course)
    Card(
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(15.dp),
                color = accent.copy(alpha = 0.13f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        course.name.take(1),
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    course.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                if (course.instructors.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            course.instructorNames,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (course.department.name.isNotEmpty()) {
                        Text(course.department.name, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (course.credit.isNotEmpty()) {
                        Text("${course.credit} 学分", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Text(course.semesterLabel, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
        }
    }
}
