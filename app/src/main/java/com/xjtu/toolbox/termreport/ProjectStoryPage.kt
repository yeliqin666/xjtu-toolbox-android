package com.xjtu.toolbox.termreport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.ArrowPreference
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import com.xjtu.toolbox.ui.components.SmallTitle
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ln

private const val REPO_URL = "https://github.com/yeliqin666/xjtu-toolbox-android"

/** 「设置 › 关于 › 岱宗盒子的故事」的正文：来历、你和它、星星、一起做它的人、写在最后。外壳由设置页给。 */
@Composable
fun ProjectStoryBody(openUrl: (String) -> Unit) {
    val context = LocalContext.current
    val story by produceState<ProjectStory?>(null) { value = loadProjectStory() }
    val installed = remember(context) { installDate(context) }
    val p = story ?: run {
        Note("正在读取…")
        return
    }
    val born = p.bornDate
    val asOf = LocalDate.parse(p.date)
    val multi = p.multiNames

    SmallTitle("它的来历")
    StoryCard {
        Text(
            "${born.year} 年 ${born.md()}",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary,
        )
        Para(buildString {
            // 2026-02-18 是农历正月初二
            if (born == LocalDate(2026, 2, 18)) append("大年初二，")
            append("晚上 ${p.born.drop(11)}，第一次提交写着：「${p.firstCommit.substringAfter(": ")}」")
        })
        p.firstStarGap()?.let { gap ->
            Para(when (gap) { 0L -> "当天"; 1L -> "第二天"; else -> "第 ${gap + 1} 天" } + "，它收到了第一颗星。")
        }
        if (p.releases.isNotEmpty()) Para("到 ${asOf.year} 年 ${asOf.md()}，一共发了 ${p.releases.size} 个版本。")
    }

    installed?.takeIf { it >= born }?.let { d ->
        val then = p.releases.lastOrNull { LocalDate.parse(it.d) <= d }
        val after = p.releases.count { LocalDate.parse(it.d) > d }
        SmallTitle("你和它")
        StoryCard {
            // 安装日期按系统的首次安装时间，卸载重装会往后推，所以说「这一段」
            Para("你和它的这一段从 ${d.year} 年 ${d.md()}开始，那是它出生后的第 ${d.toEpochDays() - born.toEpochDays() + 1} 天" +
                (then?.let { "，那时它还是 v${it.v}" } ?: "") + "。")
            if (after > 0) Para("从那天到现在，它又更新了 $after 个版本。")
        }
    }

    if (p.stars.isNotEmpty()) {
        SmallTitle("星星 · ${p.stars.size} 颗")
        StoryCard {
            StarCurve(p.stars.map(LocalDate::parse), born, maxOf(asOf, LocalDate.parse(p.stars.last())))
            Para(p.starMilestones().joinToString("　") { (n, d) -> "第 $n 颗 · ${d.md()}" })
        }
    }

    if (p.coders.isNotEmpty()) {
        SmallTitle("写代码的人 · ${p.coders.size} 位")
        StoryCard {
            Para("${p.commits} 次提交，分布在 ${p.commitDays} 天里，其中 ${p.lateCommits} 次在凌晨 0 点到 5 点。")
            NameWall(p.coders, multi)
        }
    }
    if (p.askers.isNotEmpty()) {
        SmallTitle("提问题的人 · ${p.askers.size} 位")
        StoryCard {
            Para(buildString {
                append("${p.issues} 个 issue，${p.issuesClosed} 个已经关闭。")
                p.firstIssue?.let { f ->
                    append("第 ${f.n} 个是 ${LocalDate.parse(f.date).md()}提的：「${f.title}」")
                    f.closeMinutes?.let { append("，$it 分钟后被关闭") }
                    append("。")
                }
                p.hottestIssue?.takeIf { it.comments >= 5 }?.let { append("讨论最热的一个有 ${it.comments} 条回复。") }
            })
            NameWall(p.askers, multi)
        }
    }
    if (p.talkers.isNotEmpty()) {
        SmallTitle("在讨论区说话的人 · ${p.talkers.size} 位")
        StoryCard {
            Para("${p.discussions} 个帖子。")
            NameWall(p.talkers, multi)
        }
    }
    Note("名字后的小点：在不止一处留下过名字。这面墙只认得 GitHub 上的名字，QQ 群、集市、飞书表单里的同学也一起做了它。")

    SmallTitle("写在最后")
    StoryCard {
        Text("泰山不让土壤，故能成其大", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
        Text("—— 李斯《谏逐客书》", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Para("岱宗，就是泰山。一行代码、一个 issue、群里的一句「又崩了」，都是这座山上的一捧土。")
        Para("岱宗盒子以 GPL-3.0 开源，免费，由同学做给同学。会写代码的来提 PR，不会写的说一句话也算。")
    }
    CouiSettingsCard {
        ArrowPreference(
            title = "去 GitHub 点亮一颗星",
            summary = "yeliqin666/xjtu-toolbox-android",
            onClick = { openUrl(REPO_URL) },
        )
    }
}

@Composable
private fun StoryCard(content: @Composable ColumnScope.() -> Unit) {
    CouiSettingsCard {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun Para(text: String) =
    Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = MiuixTheme.colorScheme.onSurface)

@Composable
private fun Note(text: String) = Text(
    text,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
)

/** 名字墙：参与越多字越大；[multi] 里的名字后面带个小点。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameWall(people: List<ProjectStory.Person>, multi: Set<String>) {
    val max = ln(people.maxOf { it.n } + 1.0)
    val primary = MiuixTheme.colorScheme.primary
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        people.forEach { person ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(primary.copy(alpha = 0.10f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    person.name,
                    fontSize = (12 + 5 * ln(person.n + 1.0) / max).sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (person.name.lowercase() in multi) {
                    Box(Modifier.padding(start = 4.dp).size(5.dp).clip(CircleShape).background(primary))
                }
            }
        }
    }
}

/** Star 累计曲线：横轴从 [from] 到 [until]，台阶式往上走。 */
@Composable
private fun StarCurve(dates: List<LocalDate>, from: LocalDate, until: LocalDate) {
    val color = MiuixTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val span = (until.toEpochDays() - from.toEpochDays()).coerceAtLeast(1).toFloat()
        val top = 8.dp.toPx()
        fun x(d: LocalDate) = (d.toEpochDays() - from.toEpochDays()) / span * size.width
        fun y(n: Int) = size.height - n.toFloat() / dates.size * (size.height - top)
        val line = Path().apply {
            moveTo(0f, size.height)
            dates.forEachIndexed { i, d ->
                lineTo(x(d), y(i))
                lineTo(x(d), y(i + 1))
            }
            lineTo(size.width, y(dates.size))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            close()
        }
        drawPath(area, color.copy(alpha = 0.14f))
        drawPath(line, color, style = Stroke(width = 2.5.dp.toPx()))
        drawCircle(color, radius = 4.dp.toPx(), center = Offset(x(dates.last()), y(dates.size)))
    }
}
