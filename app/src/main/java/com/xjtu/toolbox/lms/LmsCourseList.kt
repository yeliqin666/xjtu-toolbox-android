package com.xjtu.toolbox.lms

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.rememberRetainedLazyStaggeredGridState
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import androidx.compose.foundation.lazy.staggeredgrid.items
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    api: LmsApi,
    cache: LmsPageCache,
    onBack: () -> Unit,
    onCourseSelected: (LmsCourseSummary) -> Unit
) {
    val appLoginState = LocalAppLoginState.current
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
                cache.courses = withContext(Dispatchers.IO) { api.getMyCourses() }
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(AppRoute.Lms(), onBack)
            } catch (e: Exception) {
                Log.e(TAG, "loadCourses error", e)
                errorMsg = "加载课程失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // 已有缓存就不再请求——返回上一层应当是「回到原样」而不是重新加载
    LaunchedEffect(Unit) { if (cache.courses.isEmpty()) loadCourses() }

    val semesters = remember(courses) {
        courses.map { it.semesterLabel }.distinct().sortedDescending()
    }

    val filtered = remember(courses, selectedSemester) {
        if (selectedSemester == null) courses
        else courses.filter { it.semesterLabel == selectedSemester }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "思源学堂",
                largeTitle = "思源学堂",
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
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
                            fullLineItem(key = "semester_filter") {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    cornerRadius = 22.dp,
                                    colors = CardDefaults.defaultColors(
                                        color = MiuixTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                                        Text(
                                            "选择学期",
                                            style = MiuixTheme.textStyles.subtitle,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                        Text(
                                            selectedSemester ?: "显示所有学期",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)
                                        )
                                        Row(
                                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                                .padding(horizontal = 12.dp, vertical = 7.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            AppFilterChip(selected = selectedSemester == null, onClick = { selectedSemester = null }, label = "全部")
                                            semesters.forEach { sem ->
                                                AppFilterChip(selected = selectedSemester == sem, onClick = { selectedSemester = sem }, label = sem)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        fullLineItem(key = "count") {
                            Text(
                                "共 ${filtered.size} 门课程" + if (selectedSemester != null) " ($selectedSemester)" else "",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
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
@Composable
private fun LmsCourseCard(course: LmsCourseSummary, onClick: () -> Unit) {
    val accent = listOf(
        Color(0xFF5B6FD8), Color(0xFF2D9B86), Color(0xFFD07A45), Color(0xFF8B63C7)
    )[(course.id.hashCode() and Int.MAX_VALUE) % 4]
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
