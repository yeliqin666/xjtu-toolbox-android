package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.toJavaTime
import com.xjtu.toolbox.util.toKx
import com.xjtu.toolbox.ui.components.pressScale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamCountdown
import com.xjtu.toolbox.schedule.cachedNextExam
import com.xjtu.toolbox.schedule.HolidayApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.TermWeeks
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.schedule.XjtuTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import com.xjtu.toolbox.util.formatFixed
import com.xjtu.toolbox.util.formatTwoDigits
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首屏那个「屁岱形象 + 问候」—— `:core` 的 `AgentWelcome` 用 `hero` 槽位把它插进来。 */
/** 挑一句闲话当问候的第二行；避开推广句和带动作的句子，也避开刚说过的那句。 */
private fun pickLine(facts: WelcomeFacts, now: LocalDateTime, avoid: String?): String? {
    repeat(6) {
        val l = ChatterPool.pick(now, emptyList(), facts.nextToday?.courseName, null, facts = facts.chatter)
            ?.takeIf { !it.id.startsWith("app_") }
            ?.text
        if (l != null && l != avoid) return l
    }
    return null
}

@Composable
internal fun AndroidPidaiHero(title: String, facts: WelcomeFacts, now: LocalDateTime) {
    if (PidaiAppearanceHost.plain) {
        agentWelcomePlainHero(title, facts, now)
        return
    }
    val primary = MiuixTheme.colorScheme.primary
    val look = pidaiNavAppearance()
    val host = ProactiveBubbleHost
    var line by remember(facts) {
        mutableStateOf(host.heroLine ?: pickLine(facts, now, null) ?: "课表、考试、空教室、饭卡，直接问我就行")
    }
    // 进来先眨个眼打招呼，之后回到待命；戳它一下换一句话，连戳放彩蛋
    var beat by remember { mutableStateOf(PidaiBeat.IDLE) }
    val pokes = remember { PidaiPokes() }
    val gaze = rememberPidaiGaze()
    LaunchedEffect(pokes.serial) {
        delay(beat.holdMs(look.cast) ?: return@LaunchedEffect)
        beat = PidaiBeat.REST
    }
    // 已经在屁岱页又点了底栏屁岱：那句闲话由这里说，不从底栏冒泡
    val seenPokes = remember { intArrayOf(host.heroPokes) }
    LaunchedEffect(host.heroPokes) {
        if (host.heroPokes == seenPokes[0]) return@LaunchedEffect
        seenPokes[0] = host.heroPokes
        host.heroLine?.let { line = it }
        beat = pokes.poke(beat)
    }
    // 轻轻上下浮动：约 30 帧/秒推进相位，页面不可见就停（和底栏屁岱同一档开销）
    val visible = com.xjtu.toolbox.ui.components.LocalPageVisible.current
    val floatPhase = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    if (visible) {
        LaunchedEffect(Unit) {
            // 时钟用单调源：commonMain 没有 `System.nanoTime()`（见 [BotClock] 的同一处替换）。
            val start = kotlin.time.TimeSource.Monotonic.markNow()
            while (true) {
                floatPhase.floatValue =
                    (start.elapsedNow().inWholeNanoseconds / 1_000_000_000.0 * 1.6).toFloat()
                delay(33)
            }
        }
    }
    var shown by remember(line) { mutableStateOf(0) }
    LaunchedEffect(line) {
        shown = 0
        while (shown < line.length) {
            delay(38)
            shown++
        }
    }

    val enter = rememberEnter(0)
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 12.dp.toPx()
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .clickable {
                    beat = pokes.poke(beat)
                    pickLine(facts, com.xjtu.toolbox.util.nowOnCampus(), line)?.let { line = it }
                },
            contentAlignment = Alignment.Center,
        ) {
            BloubBotIcon(
                beat = beat,
                ink = look.ink,
                paper = MiuixTheme.colorScheme.surface,
                cast = look.cast,
                shape = look.shape,
                pokeSerial = pokes.serial,
                gaze = gaze.look,
                modifier = Modifier
                    .then(gaze.track)
                    .size(72.dp)
                    .graphicsLayer { translationY = -4.dp.toPx() + kotlin.math.sin(floatPhase.floatValue) * 4.dp.toPx() },
            )
        }
        Spacer(Modifier.width(12.dp))
        // 对话气泡：左边一个小尖角指向屁岱
        Column(
            Modifier
                .weight(1f)
                .drawBehind {
                    val r = 7.dp.toPx()
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height / 2f - r)
                        lineTo(-r, size.height / 2f)
                        lineTo(0f, size.height / 2f + r)
                        close()
                    }
                    drawPath(path, primary.copy(alpha = 0.12f))
                }
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(primary.copy(alpha = 0.12f), TintViolet.copy(alpha = 0.08f)),
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Box {
                Text(line, style = MiuixTheme.textStyles.body2, color = Color.Transparent)
                Text(
                    line.take(shown),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
