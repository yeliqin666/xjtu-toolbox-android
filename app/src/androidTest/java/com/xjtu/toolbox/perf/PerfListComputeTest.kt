package com.xjtu.toolbox.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 列表与重组里几个可纯函数量化的开销。
 *
 * 这些点原本都写在 composable 主体里，随每次重组（含每次按键、每次滚动重建行）重跑。
 * 这里把它们的计算部分单独拎出来测——不含 Compose 的调度开销，所以是**下界**：
 * 实际每帧/每键的成本只会更高。
 */
@RunWith(AndroidJUnit4::class)
class PerfListComputeTest {

    /* -------------------- SimpleDateFormat：逐行新建 vs 缓存 -------------------- */

    /**
     * 当前写法：每次格式化都新建一个 `SimpleDateFormat`。
     *
     * 构造 `SimpleDateFormat` 要解析 pattern、建 `Calendar`、组装内部状态，
     * 比 `format()` 本身贵一个数量级。它出现在若干列表行里（会话抽屉、账号行）。
     */
    @Test
    fun dateFormat_current() {
        val ts = 1_700_000_000_000L
        val stats = Perf.measureMedian(warmup = 200, iterations = 2_000, rounds = 5) {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ts))
        }
        Perf.report("SimpleDateFormat (new)", stats)
    }

    /** 新写法：顶层缓存一个实例。仅主线程使用，无并发问题。 */
    @Test
    fun dateFormat_cached() {
        val ts = 1_700_000_000_000L
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val stats = Perf.measureMedian(warmup = 200, iterations = 2_000, rounds = 5) {
            fmt.format(Date(ts))
        }
        Perf.report("SimpleDateFormat (cached)", stats)
    }

    /* -------------------- Regex：每帧编译 vs 复用 -------------------- */

    /** 当前写法：`text.replace(Regex("\\s+"), " ")` 每次调用都重新编译正则。 */
    @Test
    fun regex_current() {
        val text = "思考过程 第一段\n\n第二段   带多余空白\t和换行"
        val stats = Perf.measureMedian(warmup = 200, iterations = 2_000, rounds = 5) {
            text.replace(Regex("\\s+"), " ").trim()
        }
        Perf.report("Regex compile (per call)", stats)
    }

    /** 新写法：顶层 `val` 编译一次。 */
    @Test
    fun regex_cached() {
        val text = "思考过程 第一段\n\n第二段   带多余空白\t和换行"
        val re = Regex("\\s+")
        val stats = Perf.measureMedian(warmup = 200, iterations = 2_000, rounds = 5) {
            text.replace(re, " ").trim()
        }
        Perf.report("Regex compile (cached)", stats)
    }

    /* -------------------- 考勤列表：过滤 + 计数 -------------------- */

    /**
     * 考勤页每次重组都重跑的过滤与计数。用 240 条记录（一学期量级）。
     * 新写法会把这整块包进 `remember`，所以「每键/每次重组的计算量」直接归零。
     */
    @Test
    fun attendance_filterCount() {
        val records = attendanceRecords()
        val week = 8
        val stats = Perf.measureMedian(warmup = 100, iterations = 1_000, rounds = 5) {
            val display = records.filter { it.week == week }
            val normal = display.count { it.status == 0 }
            val late = display.count { it.status == 1 }
            val absence = display.count { it.status == 2 }
            val leave = display.count { it.status == 3 }
            val rate = if (display.isNotEmpty()) (normal + leave) * 100 / display.size else 100
            intArrayOf(normal, late, absence, leave, rate)
        }
        Perf.report("attendance filter+count", stats)
    }

    /* -------------------- 首页服务列表：O(n²) 查找 vs 预建 Map -------------------- */

    /**
     * `servicesByKeys` 当前写法：每个 key 都对全表做一次线性查找 → O(n²)。
     * 首页每次重组都会走一遍。
     */
    @Test
    fun servicesLookup_linear() {
        val keys = serviceKeys()
        val stats = Perf.measureMedian(warmup = 200, iterations = 2_000, rounds = 5) {
            keys.mapNotNull { k -> ALL_SERVICES.firstOrNull { it == k } }
        }
        Perf.report("servicesByKeys (linear)", stats)
    }

    /** 新写法：预建 `Map`，查找 O(1)。 */
    @Test
    fun servicesLookup_map() {
        val keys = serviceKeys()
        val index = ALL_SERVICES.associateWith { it }
        val stats = Perf.measureMedian(warmup = 200, iterations = 2_000, rounds = 5) {
            keys.mapNotNull { k -> index[k] }
        }
        Perf.report("servicesByKeys (map)", stats)
    }

    /* --------------------------------- 语料 --------------------------------- */

    private class Rec(val week: Int, val status: Int)

    private fun attendanceRecords(): List<Rec> =
        List(240) { Rec(week = (it % 16) + 1, status = it % 4) }

    /** 首页约 28 个服务项。 */
    private val ALL_SERVICES: List<String> =
        (1..28).map { "svc_$it" }

    private fun serviceKeys(): List<String> =
        listOf(3, 7, 11, 19, 23, 27).map { "svc_$it" }
}
