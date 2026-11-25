package com.xjtu.toolbox.library

import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.InsightOutcome
import com.xjtu.toolbox.agent.InsightSpec
import com.xjtu.toolbox.agent.PidaiCard
import com.xjtu.toolbox.platform.cropImageToJpegDataUri
import com.xjtu.toolbox.ui.components.InsightState
import com.xjtu.toolbox.ui.components.PidaiChip
import com.xjtu.toolbox.ui.components.PidaiChipFlow
import com.xjtu.toolbox.ui.components.PidaiContextLine
import com.xjtu.toolbox.ui.components.PidaiFilterChip
import com.xjtu.toolbox.ui.components.PidaiHero
import com.xjtu.toolbox.ui.components.PidaiHeroText
import com.xjtu.toolbox.ui.components.PidaiIconAction
import com.xjtu.toolbox.ui.components.PidaiInsightCard
import com.xjtu.toolbox.ui.components.PidaiList
import com.xjtu.toolbox.ui.components.PidaiListRow
import com.xjtu.toolbox.ui.components.PidaiSummary
import com.xjtu.toolbox.util.safeStringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 小岱荐座：本地热力（[SeatComfort]）先挑候选，再把平面图上候选所在那块按原分辨率裁给视觉模型，
 * 让它看窗、门、柱子、过道和座位号，结合偏好挑三个。只建议，预约仍由用户自己点。
 */
internal fun seatPicksSpec(images: () -> List<String>) = InsightSpec(
    id = "library_seat_picks",
    ttlMs = 10 * 60 * 1000L,
    images = images,
    busyHint = "在看平面图…",
    instruction = """
任务：帮用户在图书馆这个区域挑座位。给你的是候选空座（本地按周围人数、靠边、临过道算过分），附图是平面图上候选所在的那几块（原分辨率，图上有座位号、桌子、窗、门等）。
- 看图判断每个候选：靠窗、靠墙角、离门口和楼梯远近、在不在主过道边、是不是长桌中间被夹着。
- 结合用户记住的偏好（安静、靠窗、要插座、离厕所近等）和现在时间（快闭馆就别推远处）。
- 只从候选里挑，最多 3 个，第一个最推荐。座位号必须原样写候选里的。
只输出：
{"summary":"不超过 30 字：这个区域现在怎么样","picks":[{"seat":"座位号","why":"不超过 20 字"}]}
    """.trimIndent(),
    card = PidaiCard.LIBRARY,
)

/** 交给小岱的文字快照：区域、空座数、候选及本地评分。 */
internal fun seatPicksInput(
    areaName: String,
    layout: SeatLayout,
    candidates: List<PlanSeat>,
    comfort: Map<String, SeatComfort.Comfort>,
    prefs: Set<String>,
): String =
    buildString {
        val free = layout.seats.count { it.available }
        append("区域：$areaName；空座 $free / ${layout.seats.size}\n")
        append("用户这次的偏好：").append(prefs.joinToString("、").ifBlank { "没选，按常理挑安静好坐的" }).append('\n')
        append("候选（座位号｜本地评分 0–1｜特点）：\n")
        candidates.forEach { s ->
            val c = comfort[s.seatId]
            append("- ${s.seatId}｜${c?.score?.let { (it * 100).toInt() / 100f } ?: "?"}｜${c?.label.orEmpty()}\n")
        }
    }

/** 本地热力里最好的若干空座。 */
internal fun seatCandidates(layout: SeatLayout, comfort: Map<String, SeatComfort.Comfort>): List<PlanSeat> =
    SeatComfort.best(layout.seats, comfort, count = CANDIDATES)

/**
 * 候选所在那块的原分辨率截图：外扩三个座位边长，长边超过 [TILE] 就切成网格（最多 4 张），
 * 每张都不缩，座位号才看得清。
 */
internal fun seatPicksImages(images: PlanImages, candidates: List<PlanSeat>): List<String> {
    val bytes = images.baseBytes ?: return emptyList()
    if (candidates.isEmpty()) return emptyList()
    val unit = candidates.map { min(it.width, it.height) }.sorted()[candidates.size / 2]
    val margin = unit * 3
    val l = max(0f, candidates.minOf { it.left } - margin)
    val t = max(0f, candidates.minOf { it.top } - margin)
    val r = min(images.origWidth.toFloat(), candidates.maxOf { it.right } + margin)
    val b = min(images.origHeight.toFloat(), candidates.maxOf { it.bottom } + margin)
    val cols = ceil((r - l) / TILE).toInt().coerceIn(1, 2)
    val rows = ceil((b - t) / TILE).toInt().coerceIn(1, 2)
    val w = (r - l) / cols
    val h = (b - t) / rows
    return buildList {
        for (row in 0 until rows) for (col in 0 until cols) {
            val x0 = l + col * w
            val y0 = t + row * h
            // 这一格里一个候选都没有就不发，省 token
            if (candidates.none { it.right >= x0 && it.left <= x0 + w && it.bottom >= y0 && it.top <= y0 + h }) continue
            cropImageToJpegDataUri(bytes, x0.toInt(), y0.toInt(), (x0 + w).toInt(), (y0 + h).toInt(), TILE)?.let(::add)
        }
    }
}

/** 小岱挑中的座位号（给平面图描边）。 */
internal fun seatPickIds(state: InsightState): List<String> =
    ((state.outcome as? InsightOutcome.Ok)?.json?.get("picks") as? JsonArray).orEmpty()
        .mapNotNull { runCatching { it.jsonObject["seat"].safeStringOrNull() }.getOrNull() }

/** 挑座偏好：按账号记在本机，变了就按新偏好重挑。 */
internal object SeatPickPrefs {
    val OPTIONS = listOf("靠窗", "安静", "有插座", "离门远", "角落", "离厕所近")
    private val store get() = com.xjtu.toolbox.platform.keyValueStore("seat_picks" + com.xjtu.toolbox.account.AccountContext.safeSuffix())

    fun load(): Set<String> = store.getString("prefs").orEmpty().split(',').filter { it in OPTIONS }.toSet()
    fun save(prefs: Set<String>) = store.putString("prefs", prefs.joinToString(","))
}

@Composable
internal fun LibrarySeatPicksCard(
    state: InsightState,
    areaName: String,
    prefs: Set<String>,
    onPrefsChange: (Set<String>) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PidaiInsightCard(
        title = "小岱荐座",
        state = state,
        modifier = modifier,
        idleAction = "让小岱挑座",
        brief = { json -> seatPicks(json).firstOrNull()?.let { (seat, why) -> "首选 $seat" + why.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() } },
        askPrompt = { "图书馆「$areaName」你推荐的座位，再说说理由，还有别的区域更合适吗？" },
        controls = {
            PidaiContextLine(
                lead = if (prefs.isEmpty()) "$areaName · 没选偏好，按常理挑" else "$areaName · 按 ",
                emphasis = prefs.joinToString(" · "),
                tail = if (prefs.isEmpty()) "" else " 挑",
            ) {
                PidaiChipFlow {
                    SeatPickPrefs.OPTIONS.forEach { p ->
                        PidaiFilterChip(p, selected = p in prefs, onClick = { onPrefsChange(if (p in prefs) prefs - p else prefs + p) })
                    }
                }
            }
        },
        primary = { json ->
            seatPicks(json).firstOrNull()?.let { (seat, _) -> PidaiChip("预约 $seat", emphasized = true, onClick = { onPick(seat) }) }
        },
    ) { json ->
        PidaiSummary(json["summary"].safeStringOrNull())
        val picks = seatPicks(json)
        picks.firstOrNull()?.let { (seat, why) ->
            PidaiHero(title = why.ifBlank { "$seat 号" }, label = "首选", leading = { PidaiHeroText(seat) })
        }
        PidaiList(picks.drop(1)) { (seat, why) ->
            PidaiListRow(
                title = why.ifBlank { "备选" },
                leading = { Text(seat, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp), maxLines = 1) },
                trailing = { PidaiIconAction(Icons.Filled.EventSeat, "预约 $seat", onClick = { onPick(seat) }) },
            )
        }
    }
}

/** 小岱挑的座位：座位号 to 理由。 */
private fun seatPicks(json: JsonObject): List<Pair<String, String>> =
    (json["picks"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        .mapNotNull { p -> p["seat"].safeStringOrNull()?.takeIf { it.isNotBlank() }?.let { it to p["why"].safeStringOrNull().orEmpty() } }

private const val CANDIDATES = 12
private const val TILE = 1280
