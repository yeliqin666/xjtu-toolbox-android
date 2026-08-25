package com.xjtu.toolbox.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.schedule.CourseItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 课表匹配度。
 *
 * 全程离线：生成一段分享码给朋友，粘贴朋友的码算契合度。没有服务器、没有账号，
 * 我们一条数据都不经手——给谁看、看多少由用户自己决定，见 [MatchProfile] 的说明。
 */
@Composable
fun MatchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var nickname by remember { mutableStateOf("") }
    var dims by remember { mutableStateOf(MatchProfile.Dimensions()) }
    var dietInput by remember { mutableStateOf("") }
    var courses by remember { mutableStateOf<List<CourseItem>>(emptyList()) }
    var diningCounts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    var theirCode by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<MatchProfile.Result?>(null) }
    var theirName by remember { mutableStateOf("") }
    var decodeError by remember { mutableStateOf<String?>(null) }

    // 课表和消费记录都读本地缓存，这个功能不为自己发任何请求。
    // 缓存是空的（没进过日程页/校园卡页）时对应维度就没数据，界面会说明。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            courses = runCatching {
                val dc = com.xjtu.toolbox.util.DataCache(context)
                val gson = com.google.gson.Gson()
                val term = dc.get("schedule_term_list", Long.MAX_VALUE)
                    ?.let { gson.fromJson(it, Array<String>::class.java)?.firstOrNull() }
                term?.let { t ->
                    dc.get("schedule_$t", Long.MAX_VALUE)?.let { json ->
                        gson.fromJson(json, Array<CourseItem>::class.java).toList()
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())
            diningCounts = runCatching { DiningHabit.readCachedHourCounts(context) }
                .getOrDefault(emptyMap())
        }
        loading = false
    }

    val myProfile = remember(nickname, courses, diningCounts, dietInput, dims) {
        MatchProfile.build(
            nickname = nickname,
            courses = courses,
            diningHourCounts = diningCounts,
            dietTags = dietInput.split(Regex("""[,，、\s]+""")).filter { it.isNotBlank() }.toSet(),
            dims = dims,
        )
    }
    val myCode = remember(myProfile) { MatchProfile.encode(myProfile) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "课表匹配",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                LinearProgressIndicator(Modifier.width(120.dp))
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("要分享什么") {
                    Text(
                        "全程在你手机上算，不上传任何地方。关掉的项不会写进分享码，对方也就看不到。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = "怎么称呼你（可留空）",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    DimRow(
                        "空课时间",
                        if (courses.isEmpty()) "没读到课表，先去日程页看一次" else "一周 ${courses.size} 节课",
                        dims.schedule,
                        courses.isNotEmpty(),
                    ) { dims = dims.copy(schedule = it) }
                    DimRow(
                        "共同课程",
                        "只分享课程号，不含成绩",
                        dims.sameCourses,
                        courses.isNotEmpty(),
                    ) { dims = dims.copy(sameCourses = it) }
                    DimRow(
                        "作息（早八 / 晚课）",
                        "只分享最早和最晚的节次",
                        dims.routine,
                        courses.isNotEmpty(),
                    ) { dims = dims.copy(routine = it) }
                    DimRow(
                        "常去食堂的时段",
                        if (diningCounts.isEmpty()) "没读到消费记录，先去校园卡页看一次"
                        else "只分享小时，不含金额和商户",
                        dims.diningHours,
                        diningCounts.isNotEmpty(),
                    ) { dims = dims.copy(diningHours = it) }
                    DimRow("口味标签", "自己填，随便写", dims.dietTags, true) {
                        dims = dims.copy(dietTags = it)
                    }
                    if (dims.dietTags) {
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = dietInput,
                            onValueChange = { dietInput = it },
                            label = "如：辣, 面食, 咖啡, 不吃香菜",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
            item {
                SectionCard("我的分享码") {
                    Text(
                        myCode,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = "复制，发给朋友",
                        onClick = { clipboard.setText(AnnotatedString(myCode)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                SectionCard("对方的分享码") {
                    TextField(
                        value = theirCode,
                        onValueChange = { theirCode = it; decodeError = null },
                        label = "粘贴到这里",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    decodeError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = "算一下",
                        onClick = {
                            val theirs = MatchProfile.decode(theirCode)
                            if (theirs == null) {
                                decodeError = "这段码读不出来。确认复制完整了，或者对方用的是旧版本。"
                                result = null
                            } else {
                                decodeError = null
                                theirName = theirs.nickname.ifBlank { "对方" }
                                result = MatchProfile.compare(myProfile, theirs)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = theirCode.isNotBlank(),
                    )
                }
            }
            result?.let { r ->
                item {
                    SectionCard("和 $theirName 的匹配") {
                        if (r.facets.isEmpty()) {
                            Text(
                                "没有双方都分享了的维度，算不出来。让对方也打开几项试试。",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            Text(
                                "${r.overall}%",
                                style = MiuixTheme.textStyles.title1,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                // 说清这个数是怎么来的。只对双方都开的维度算，
                                // 否则"对方注重隐私"会被读成"你俩不合"。
                                "只统计你俩都分享了的 ${r.facets.size} 项，取平均。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(10.dp))
                            r.facets.forEach { f ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.label, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                                        Text(
                                            f.detail,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    Text(
                                        "${f.score}%",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DimRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body2)
            Text(
                summary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        // 没数据的维度直接禁用而不是隐藏：让人知道这一项存在、以及为什么现在用不了。
        Switch(checked = checked && enabled, enabled = enabled, onCheckedChange = onChange)
    }
}
