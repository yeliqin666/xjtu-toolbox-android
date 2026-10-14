package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.round
import com.xjtu.toolbox.dormpower.DormPowerSource
import com.xjtu.toolbox.dormpower.DormRoom
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.FullPageState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 宿舍电费的**读数页**（`:web` 自己的一张屏，不在 `:core` 里）。
 *
 * ## 为什么不是 `:core` 那张 `DormPowerScreen`
 *
 * `:core` 的 `DormPowerScreen` 的**全部内容**是「缴费页浏览器」：它只做一件事 —— 向取数端口要
 * `freshPayUrl()`，然后把地址交给宿主的内置浏览器（App）/ 系统浏览器（桌面）。绑定、查询、充值
 * 都在那一页里，屏自己不画读数。
 *
 * serve 契约 §5.5 **不端 `freshPayUrl`**（它每次要重走一遍 OAuth、拦下带 code 的那一跳，还顺手
 * 记「用户进过这一页」——那是宿主侧状态），也不端绑定 / 充值 ⇒ 这张屏在 serve 模式下**画不出来**
 *（照旧用它会落到一张 ErrorState 上）。而契约真端了另外两个只读端点：`/api/dorm/rooms` 与
 * `/api/dorm/reading`。所以这一端按拿得到的两个端点画一份**只读读数页**，把「去缴费那一跳
 * 在浏览器里不可达」写在页顶。
 *
 * ## 如实处理的几处
 *
 * | 处 | 本端怎么处理 |
 * |---|---|
 * | 读数的时间戳 | 上游**不给**（§5.5）⇒ 本页记「本机拿到响应那一刻」，并标明是**本机时刻**，不编一个上游时间 |
 * | `kwh == null` | = 上游明确说「这间房没有表」（**不是 0**，也不是「查不到」）⇒ 单独一句话说清楚 |
 * | 某一间查失败 | 与「没有表」分开说（失败给重试，不给一个 0 或「没有表」） |
 * | 「去缴费」/ 绑定 / 充值 | 没有端点 ⇒ 不画按钮，只在页顶写清理由（不跳、不假装跳得过去） |
 *
 * @param source 取数（serve 模式 = `:core` 的 `CampusDormPowerApi`）：只用它拿得到的两格
 *   （`rooms()` / `kwh(roomId)`）—— `freshPayUrl()` 在那一份实现里是抛错的，这里不碰。
 * @param onBack 返回上一屏（壳给的导航）
 */
@Composable
internal fun WebDormPowerScreen(
    source: DormPowerSource,
    onBack: () -> Unit,
) {
    var rooms by remember { mutableStateOf<List<DormRoom>?>(null) }
    var readings by remember { mutableStateOf<Map<String, RoomReading>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(source, attempt) {
        loading = true
        error = null
        readings = emptyMap()
        try {
            val list = source.rooms()
            rooms = list
            // 读数是**一间一发**（契约把列表与读数拆成两个端点）：某间失败只影响那一间，
            // 别让整页变成错误页 —— 与 `DormPowerStore.refresh` 的容错口径一致（但这里不写盘）。
            readings = list.associate { room ->
                room.id to queryReading(source, room)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = FriendlyError.of(e, "加载宿舍列表")
        } finally {
            loading = false
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "宿舍电费",
                glass = rememberPageGlass(),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        val message = error
        when {
            loading && rooms == null -> FullPageState(Modifier.fillMaxSize().padding(padding)) {
                LoadingState("正在读取宿舍电量...", Modifier.fillMaxSize())
            }
            message != null && rooms == null -> FullPageState(Modifier.fillMaxSize().padding(padding)) {
                ErrorState(message = message, onRetry = { attempt++ }, modifier = Modifier.fillMaxSize())
            }
            else -> {
                val list = rooms.orEmpty()
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "gap") { DormPowerGapCard() }
                    items(list, key = { it.id }) { room ->
                        DormRoomCard(room = room, reading = readings[room.id], onRetry = { attempt++ })
                    }
                }
            }
        }
    }
}

/** 一间宿舍这一次查到的读数：`kwh == null && error == null` = 上游说这间房没有表。 */
private data class RoomReading(val kwh: Double?, val at: Long, val error: String?)

/**
 * 查一间的读数。**「什么时候查到的」由本页记**：上游不给时间戳（契约 §5.5），所以这里取
 * 「拿到响应那一刻」的本机时间 —— 编一个上游时间反而是第二个口径。
 */
private suspend fun queryReading(source: DormPowerSource, room: DormRoom): RoomReading = try {
    val kwh = source.kwh(room.id)
    RoomReading(kwh = kwh, at = Clock.System.now().toEpochMilliseconds(), error = null)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    RoomReading(kwh = null, at = 0L, error = FriendlyError.of(e, "查询电量"))
}

/** 页顶那一张**如实缺口**卡（见这一屏的 KDoc）。 */
@Composable
private fun DormPowerGapCard() {
    val cs = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(12.dp),
    ) {
        Text(
            "这一页只画得了两个只读端点（GET /api/dorm/rooms 与 /api/dorm/reading）：" +
                "绑定 / 解绑 / 充值发生在缴费页里，而缴费页入口（freshPayUrl）要重走一遍 OAuth、" +
                "还要在本机记「进过这一页」—— serve 契约刻意不端它（docs/api-contract.md §5.5）⇒ " +
                "「去缴费」这一跳在浏览器里不可达，要绑定或充值请在 App / 桌面端操作。" +
                "下面的读数是本次查询的结果，时间戳是本机拿到响应的那一刻（上游不给）。",
            color = cs.onBackgroundVariant,
            fontSize = 11.sp,
        )
    }
}

/** 一间宿舍一张卡：读数、本机时刻、或「没有表」/「没查到」。 */
@Composable
private fun DormRoomCard(room: DormRoom, reading: RoomReading?, onRetry: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(room.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
            when {
                reading == null -> Text("正在查询…", style = MiuixTheme.textStyles.body2, color = cs.onSurfaceVariantSummary)
                reading.error != null -> {
                    Text("这一间没查到：${reading.error}", style = MiuixTheme.textStyles.body2, color = cs.error)
                    Text("（查不到与「没有电表」不是一回事，所以这里分开说）", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                }
                // 契约 §4/§5.5：null = 上游明确说这间房没有表 —— 不画成 0
                reading.kwh == null -> Text(
                    "这一间没有电表（上游明确说没有，不是 0）",
                    style = MiuixTheme.textStyles.body2,
                    color = cs.onSurfaceVariantSummary,
                )
                else -> Text(
                    "剩余 ${formatKwh(reading.kwh)} 度",
                    style = MiuixTheme.textStyles.title4,
                    color = cs.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (reading != null && reading.at > 0L) {
                Text(
                    "查询时刻（本机）${localStamp(reading.at)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceVariantSummary,
                )
            }
            if (reading?.error != null) {
                TextButton(
                    text = "重查这一间",
                    onClick = onRetry,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * 度数的显示口径：一位小数，四舍五入（`"%.1f".format(...)` 是 JVM 专属写法，`:core` 还要编到
 * wasm ⇒ 按既有先例手写）。幂等：`12.34` → `12.3`，`12.35` → `12.4`，`0.05` → `0.1`。
 */
private fun formatKwh(kwh: Double): String {
    val tenths = round(kwh * 10).toLong()
    val sign = if (tenths < 0) "-" else ""
    val abs = if (tenths < 0) -tenths else tenths
    return "$sign${abs / 10}.${abs % 10}"
}

/** `MM-dd HH:mm`（本机时区）：上游不给时间戳，所以这一格写的是**本机**那一刻。 */
private fun localStamp(epochMillis: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun p2(v: Int) = v.toString().padStart(2, '0')
    return "${p2(t.monthNumber)}-${p2(t.dayOfMonth)} ${p2(t.hour)}:${p2(t.minute)}"
}
