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
 * 再从成绩报表补上未评教（jwapp 不显示）的课程。加工后的结果整份缓存，下次先秒显。
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
    var unevaluatedCourses by mutableStateOf<Set<String>>(emptySet()); private set
    /** 从报表补了几门课的提示。 */
    var reportHint by mutableStateOf<String?>(null); private set
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

    /** jwapp 成绩、xscjcx 精确成绩、未评教名单三路互不依赖，并行拉。 */
    private suspend fun fetch(source: JwappScoreSource): List<TermScore> = coroutineScope {
        val jwxt = jwxt ?: return@coroutineScope source.getGrade(null)
        val precise = async { preciseScores(jwxt) }
        val uneval = if (studentId.isNotEmpty()) async { unevaluated(jwxt) } else null
        var grades = source.getGrade(null)
        grades = withPreciseScores(grades, precise.await())
        grades = grades.map { ts -> ts.copy(scoreList = ts.scoreList.map(::withCourseGroup)) }
        if (uneval != null) grades = withUnevaluated(grades, jwxt, uneval.await())
        grades
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

    private suspend fun unevaluated(jwxt: JwxtScoreSupplement): Set<String> = try {
        jwxt.unevaluatedCourseNames()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "未评教查询失败: ${e.message}")
        emptySet()
    }

    /** 未评教的课 jwapp 不给成绩，从成绩报表里补上。 */
    private suspend fun withUnevaluated(grades: List<TermScore>, jwxt: JwxtScoreSupplement, uneval: Set<String>): List<TermScore> {
        unevaluatedCourses = uneval
        if (uneval.isEmpty()) return grades
        return try {
            val existing = grades.flatMap { ts -> ts.scoreList.map { courseKey(ts.termCode, it.courseName) } }.toMutableSet()
            val extras = jwxt.reportedGrades(studentId)
                .filter { it.courseName in uneval && existing.add(courseKey(it.term, it.courseName)) }
                .groupBy({ it.term }, { it.toScoreItem() })
                .toMutableMap()
            if (extras.isEmpty()) return grades
            val merged = grades.map { ts -> extras.remove(ts.termCode)?.let { ts.copy(scoreList = ts.scoreList + it) } ?: ts } +
                extras.map { (term, items) -> TermScore(term, "报表·" + term.replace("-", "—"), items) }
            val count = merged.sumOf { ts -> ts.scoreList.count { it.source == ScoreSource.REPORT } }
            if (count > 0) reportHint = "已从报表补充 $count 门未评教课程成绩"
            merged
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "报表加载失败: ${e.message}")
            grades
        }
    }

    /** 课程名标准化后当键：全角 / 空白 / 课程标记符号 / 末尾课程代码两边写法常不一致。 */
    private fun courseKey(term: String, name: String): String {
        val n = name.trim()
            .replace("　", " ")
            .replace(" ", " ")
            .replace(Regex("\\s+"), " ")
            .replace("（", "(").replace("）", ")")
            .replace("＋", "+").replace("－", "-")
            .replace(Regex("[◇◆◎○●★☆※▲△▼▽]"), "")
            .replace(Regex("\\([A-Z]{2,}\\d{4,}\\)$"), "")
            .trim()
            .lowercase()
        return "${term.trim()}|$n"
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

private fun ReportedGrade.toScoreItem(): ScoreItem = ScoreItem(
    id = "report_${term}_${courseName.hashCode()}",
    termCode = term,
    courseName = courseName,
    score = score,
    scoreValue = score.toDoubleOrNull(),
    passFlag = gpa?.let { it > 0.0 } ?: (score.toDoubleOrNull()?.let { it >= 60.0 } ?: false),
    coursePoint = coursePoint,
    gpa = gpa,
    source = ScoreSource.REPORT,
)
