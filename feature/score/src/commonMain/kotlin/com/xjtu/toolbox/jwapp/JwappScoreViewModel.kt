package com.xjtu.toolbox.jwapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.score.ReportedGrade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 成绩屏上飘过的一句话（`long = true` 走 SnackbarDuration.Long）。 */
data class ScoreMessage(val text: String, val long: Boolean = false)

/**
 * 成绩：jwapp 的学期成绩为底，教务的 xscjcx 替换成精确的总评和绩点，
 * 再用成绩报表补最新学期看不到的成绩（见 [ScoreReportFill]）。加工后的结果整份缓存，下次先秒显。
 *
 * @param source 传 null = 这一端没有 jwapp 会话（`canRefresh` 为 false，只读缓存）；
 * @param jwxt 传 null = 这一端没有教务会话（只出 jwapp 那一份）；
 * @param cache 传 null = 这一端不落盘（每次都真拉）。
 */
class JwappScoreViewModel(
    private val source: JwappScoreSource?,
    private val jwxt: JwxtScoreSupplement?,
    private val studentId: String,
    private val cache: JwappScoreCache? = null,
) : ViewModel() {
    private val messageChannel = Channel<ScoreMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    var isLoading by mutableStateOf(true); private set
    /** 缓存已显示，后台刷新中。 */
    var isRefreshing by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var allTermScores by mutableStateOf<List<TermScore>>(emptyList()); private set
    val termList: List<Pair<String, String>> get() = allTermScores.map { it.termCode to it.termName }
    var courseDetails by mutableStateOf<Map<String, ScoreDetail>>(emptyMap()); private set
    var detailLoading by mutableStateOf<String?>(null); private set
    /** 最新学期看不到成绩、从报表补了几门的提示。 */
    var reportHint by mutableStateOf<String?>(null); private set
    /** 成绩报表里最新学期的全部课（抽屉用）；null = 还没拉。 */
    var reportGrades by mutableStateOf<List<ReportedGrade>?>(null); private set
    var reportLoading by mutableStateOf(false); private set
    var reportFailed by mutableStateOf(false); private set
    /** 补全了哪几行：成绩行 id → 报表那门。 */
    var reportFilled by mutableStateOf<Map<String, ReportedGrade>>(emptyMap()); private set
    /** 有教务会话才能拉报表。 */
    val canOpenReport get() = jwxt != null && studentId.isNotEmpty()
    val canRefresh get() = source != null

    init { load() }

    fun load(silent: Boolean = false) {
        if (silent) isRefreshing = true else { isLoading = true; isRefreshing = false }
        errorMessage = null
        viewModelScope.launch {
            // 先用缓存秒显；未登录时用极长 TTL，保证能看到缓存
            var cachedCount = -1
            val cache = cache
            if (!silent && cache != null) {
                val ttl = if (source != null) JwappScoreCache.DEFAULT_TTL_MS else Long.MAX_VALUE
                cache.read(ttl)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { cached ->
                        allTermScores = cached
                        cachedCount = cached.sumOf { it.scoreList.size }
                        isLoading = false
                        isRefreshing = source != null
                    }
            }
            val source = source
            if (source == null) {
                if (allTermScores.isEmpty()) errorMessage = "暂无成绩缓存"
                isLoading = false
                isRefreshing = false
                return@launch
            }
            try {
                val grades = fetch(source)
                allTermScores = grades
                if (cache != null) runCatching { cache.write(grades) }
                val freshCount = grades.sumOf { it.scoreList.size }
                when {
                    cachedCount < 0 -> Unit
                    freshCount > cachedCount -> messageChannel.send(ScoreMessage("有 ${freshCount - cachedCount} 门新成绩"))
                    freshCount != cachedCount -> messageChannel.send(ScoreMessage("成绩数据已更新"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    // 不弹回主页：jwapp 拒绝 token 时和自动重登会形成「进-退-进」死循环；停在这页给提示
                    if (allTermScores.isNotEmpty()) messageChannel.send(ScoreMessage("成绩同步暂不可用，显示缓存数据。下拉刷新可重试", long = true))
                    else errorMessage = "成绩查询服务暂不可用：${FriendlyError.of(e, "查询成绩")}"
                } else {
                    if (allTermScores.isNotEmpty()) messageChannel.send(ScoreMessage("网络异常，显示的可能不是最新数据", long = true))
                    else errorMessage = FriendlyError.of(e, "加载成绩")
                }
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    /** 错误页重试：先强制重新认证一次。 */
    fun retry() {
        isLoading = true
        errorMessage = null
        viewModelScope.launch {
            runCatching {
                source?.reAuthenticate()
            }
            load()
        }
    }

    /** jwapp 成绩和 xscjcx 精确成绩互不依赖，并行拉；最后用报表补最新学期看不到的成绩。 */
    private suspend fun fetch(source: JwappScoreSource): List<TermScore> = coroutineScope {
        val jwxt = jwxt ?: return@coroutineScope source.getGrade(null)
        val precise = async { preciseScores(jwxt) }
        var grades = source.getGrade(null)
        grades = withPreciseScores(grades, precise.await())
        grades = grades.map { ts -> ts.copy(scoreList = ts.scoreList.map(::withCourseGroup)) }
        withReportFill(grades)
    }

    private suspend fun preciseScores(jwxt: JwxtScoreSupplement): List<CjcxScore>? = try {
        jwxt.preciseScores()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "xscjcx 失败，用 jwapp 的数: ${e.message}")
        null
    }

    /** 教务的 xscjcx 有精确总评和绩点，按学期 + 课程名（退而求其次按课程号）对上就替换。 */
    private fun withPreciseScores(grades: List<TermScore>, precise: List<CjcxScore>?): List<TermScore> {
        if (precise == null) return grades
        val lookup = precise.associateBy { "${it.termCode}|${normalizeCjcxCourseName(it.courseName)}" }
        val byCode = precise.associateBy { it.kch }
        return grades.map { ts ->
            ts.copy(scoreList = ts.scoreList.map { score ->
                val p = lookup["${ts.termCode}|${normalizeCjcxCourseName(score.courseName)}"]
                    ?: score.courseCode?.let { byCode[it] }
                if (p == null) score
                else score.copy(
                    scoreValue = p.zcj,
                    gpa = p.xfjd,
                    courseCategory = p.kclbdm.ifBlank { null },
                    courseCode = p.kch.ifBlank { score.courseCode },
                )
            })
        }
    }

    /** 课程号前缀区分通核 / 通选。 */
    private fun withCourseGroup(score: ScoreItem): ScoreItem {
        val code = score.courseCode?.uppercase() ?: return score
        val group = when {
            code.startsWith("CORE") -> CourseGroup.GEN_CORE
            code.startsWith("GNED") -> CourseGroup.GEN_ELECTIVE
            else -> return score
        }
        return score.copy(courseGroup = group)
    }

    /**
     * 最新学期里成绩看不到的行（多半没评教），用成绩报表补上；匹配拿不准的留着，
     * 用户可在最新学期的「报表」抽屉里看全。旧学期不补：往后不评教也能看，补了只会误配。
     */
    private suspend fun withReportFill(grades: List<TermScore>): List<TermScore> {
        val latest = ScoreReportFill.latestTerm(grades) ?: return grades
        val (hidden, visible) = grades.first { it.termCode == latest }.scoreList.partition { ScoreReportFill.isHidden(it.score) }
        if (hidden.isEmpty()) {
            reportHint = null
            return grades
        }
        val report = fetchReport(latest) ?: return grades.also {
            reportHint = "最新学期有 ${hidden.size} 门看不到成绩，成绩报表暂时拉不到，可点学期右侧「报表」重试"
        }
        val fills = ScoreReportFill.match(hidden, visible, report)
        reportFilled = fills
        reportHint = buildString {
            append("最新学期有 ${hidden.size} 门看不到成绩")
            if (fills.isNotEmpty()) append("，已从成绩报表补上 ${fills.size} 门")
            if (fills.size < hidden.size) append("；其余可点学期右侧「报表」查看")
        }
        return grades.map { ts ->
            if (ts.termCode != latest) ts
            else ts.copy(scoreList = ts.scoreList.map { item -> fills[item.id]?.let { ScoreReportFill.fill(item, it) } ?: item })
        }
    }

    /** 最新学期的报表成绩；失败返回 null，抽屉里显示重试。 */
    private suspend fun fetchReport(term: String): List<ReportedGrade>? {
        val jwxt = jwxt ?: return null
        if (studentId.isEmpty()) return null
        reportLoading = true
        return try {
            jwxt.reportedGrades(studentId).filter { it.term == term }.also { reportGrades = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "报表加载失败: ${e.message}")
            reportFailed = true
            null
        } finally {
            reportLoading = false
        }
    }

    /** 打开报表抽屉时：还没拉过（最新学期没有看不到的成绩时不会自动拉）就现拉。 */
    fun loadReport(force: Boolean = false) {
        if (reportLoading || (!force && reportGrades != null)) return
        val term = ScoreReportFill.latestTerm(allTermScores) ?: return
        reportFailed = false
        viewModelScope.launch { fetchReport(term) }
    }

    /** 分项成绩：没有分项的课也记一份空的，免得每次展开都再问。 */
    fun loadDetail(item: ScoreItem) {
        if (courseDetails.containsKey(item.id) || detailLoading == item.id) return
        val source = source ?: return
        detailLoading = item.id
        viewModelScope.launch {
            try {
                val detail = source.getScoreDetail(item.id)
                courseDetails = courseDetails + (item.id to detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoScoreDetailException) {
                courseDetails = courseDetails + (item.id to item.asEmptyDetail())
            } catch (e: Exception) {
                Log.w(TAG, "getDetail ${item.courseName}: ${e.message}")
                if (isNoScoreDetailMessage(e.message)) courseDetails = courseDetails + (item.id to item.asEmptyDetail())
                else messageChannel.send(ScoreMessage("加载分项成绩失败，请重试"))
            } finally {
                detailLoading = null
            }
        }
    }

    private companion object {
        const val TAG = "JwappScore"
    }
}
