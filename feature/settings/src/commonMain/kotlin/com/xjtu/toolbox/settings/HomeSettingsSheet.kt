package com.xjtu.toolbox.settings

import androidx.compose.foundation.background
import com.xjtu.toolbox.settings.SettingsColors.Blue as cBlue
import com.xjtu.toolbox.settings.SettingsColors.Orange as cOrange
import com.xjtu.toolbox.settings.SettingsColors.Purple as cPurple
import com.xjtu.toolbox.settings.SettingsColors.Teal as cTeal
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.Composable
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.home.HomeLayout
import com.xjtu.toolbox.home.HomeLayoutCodec
import com.xjtu.toolbox.home.HomeLayoutStore
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.platform.rememberPickTextFile
import com.xjtu.toolbox.platform.rememberSaveTextFile
import com.xjtu.toolbox.platform.showBriefMessage
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import com.xjtu.toolbox.ui.components.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import com.xjtu.toolbox.ui.components.AppDropdownPreference
import com.xjtu.toolbox.ui.components.SwitchPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「主页设置」抽屉（需求 §5 / 补充 §29）。
 *
 * 两套首页布局共用这一个抽屉，因为有三组设置是**跨布局共用**的——背景（壁纸 / 模糊）、
 * 卡片材质（液态玻璃）、顶栏排法，它们落在 [SettingsStore] 里；只有卡片组才有的几项
 * （网格开关、折射子开关、卡片圆角、背景不透明度、导出 / 导入布局、恢复默认分组和布局）落在
 * [com.xjtu.toolbox.home.HomeLayoutStore] 里，切到经典分类卡时整块不画。
 *
 * 做成底部抽屉而不是二级页，是因为它要能从两处打开——全局设置里的那一行，和首页编辑态的齿轮；
 * 两处挂的是**同一个** `HomeSettingsSheet`，从哪儿打开看到的都是同一套状态。
 */
@Composable
fun HomeSettingsSheet(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    // 返回键优先关抽屉（§31）：抽屉在前台时不把返回交给下面的页面
    BackHandler(enabled = show) { onDismiss() }
    // 只有开着的那一份写这个标记：设置页里还挂着另一份（关着），别让它把标记冲掉
    DisposableEffect(show) {
        if (show) HomeSettingsOverlay.open = true
        onDispose { if (show) HomeSettingsOverlay.open = false }
    }
    OverlayBottomSheet(
        show = show,
        title = "主页设置",
        // 默认左右 24dp 内边距再叠卡片的 16dp 就成了 40dp；去掉这一层，标题与卡片的对齐和设置页一致
        insideMargin = DpSize(0.dp, 0.dp),
        // 默认底色 background 在浅色下是纯白，和卡片的 surfaceVariant 一样白，分组就看不出来了
        backgroundColor = MiuixTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
    ) {
        // 抽屉自己要知道怎么关自己：「恢复默认分组和布局」确认之后要立刻合上（§29 补充）
        HomeSettingsBody(
            onDismiss = onDismiss,
            modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding(),
        )
    }
}

/** 抽屉和设置「外观」页共用的内容；滚动归外层。[onDismiss]：恢复默认或导入后要收起时调用。 */
@Composable
internal fun HomeSettingsBody(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // 卡片组独有：整页布局（分组与格位）、网格开关、折射、圆角、背景不透明度
    val store = remember { HomeLayoutStore() }
    // 两套布局共用：背景、卡片材质
    val settings = remember { SettingsStore() }
    val revision = HomeLayoutRevision.current

    var layout by remember(revision) { mutableStateOf(settings.homeLayout) }
    var corner by remember { mutableIntStateOf(store.cardCorner) }
    var opacity by remember { mutableIntStateOf(store.backgroundOpacity) }
    var confirmReset by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    // 从文件读回来、等用户确认的那份布局；非 null = 导入确认弹窗开着
    var pendingImport by remember { mutableStateOf<HomeLayout?>(null) }
    val cards = layout == SettingsStore.HOME_LAYOUT_CARDS
    // 只有在卡片组 + 自定义图片这一档，不透明度才有可调的对象
    val wallpaper = remember(revision) {
        settings.homeBackground == HomeLayoutStore.BACKGROUND_IMAGE && settings.homeWallpaperImage.isNotEmpty()
    }
    // 导出的是用户**自己摆过**的那份快照：没有快照 = 首页还在用默认布局，没什么可导
    val savedLayout = remember(revision) { store.saved }
    // 导出 / 导入走平台的文件通道（Android SAF、桌面 JFileChooser、Web 下载 + 选文件）
    val saveLayoutFile = rememberSaveTextFile()
    val pickLayoutFile = rememberPickTextFile(
        onLoaded = { text ->
            // 内容对不对交给编解码判定：解不出来就当这次没导，错误只说人话、不抛格式细节
            val parsed = HomeLayoutCodec.decode(text)
            if (parsed == null) showBriefMessage("这个文件不是岱宗盒子的布局") else pendingImport = parsed
        },
        onError = { showBriefMessage(it.ifEmpty { "读不出这个文件" }) },
    )

    // 抽屉关掉时把「连续值也改过了」告诉首页（滑条不逐帧 bump，见 notifyChanged 的说明）。
    // 圆角那条滑条例外：它松手时自己 bump 一次（见下面的 onValueChangeFinished），
    // 因为「改完圆角立刻回首页」不该再等抽屉关闭。
    DisposableEffect(Unit) {
        onDispose { store.notifyChanged() }
    }

    Column(modifier.fillMaxWidth()) {
        // ── 布局：功能区样式 ──
        SmallTitle("首页布局")
        HomeSettingsCard {
            AppDropdownPreference(
                title = "功能区",
                items = HOME_LAYOUT_LABELS,
                selectedIndex = HOME_LAYOUT_VALUES.indexOf(layout).coerceAtLeast(0),
                startAction = { SettingsIcon(Icons.Default.Dashboard, cBlue) },
                onSelectedIndexChange = { index ->
                    layout = HOME_LAYOUT_VALUES[index]
                    settings.homeLayout = layout
                    HomeLayoutRevision.bump()
                },
            )
        }

        // ── 外观：背景与卡片（背景、材质三套布局共用） ──
        SmallTitle("首页背景与卡片")
        HomeSettingsCard {
            HomeBackgroundPreference(settings, startAction = { SettingsIcon(Icons.Default.Wallpaper, cPurple) })
            // 卡片组独有：壁纸再盖一层主题底色，文字对比度不够时自己往下拨
            if (cards && wallpaper) {
                SettingsSliderRow(
                    title = "背景不透明度",
                    valueText = "$opacity%",
                    value = opacity.toFloat(),
                    range = HomeLayoutStore.BACKGROUND_OPACITY_MIN.toFloat()..
                        HomeLayoutStore.BACKGROUND_OPACITY_MAX.toFloat(),
                    icon = Icons.Default.Layers,
                    color = cPurple,
                    onChange = {
                        opacity = it.roundToInt()
                        store.backgroundOpacity = opacity
                    },
                )
            }
            // 圆角只有卡片组有；编辑态悬浮条恒为全圆，不吃这个值。存的仍是 dp，老存档按最近一档显示
            if (cards) {
                AppDropdownPreference(
                    title = "卡片形状",
                    items = CARD_CORNER_PRESETS.map { it.second },
                    selectedIndex = CARD_CORNER_PRESETS.indices.minBy { abs(CARD_CORNER_PRESETS[it].first - corner) },
                    startAction = { SettingsIcon(Icons.Default.Widgets, cTeal) },
                    onSelectedIndexChange = { index ->
                        corner = CARD_CORNER_PRESETS[index].first
                        store.cardCorner = corner
                        store.notifyChanged()
                    },
                )
            }
        }

        // ── 备份与恢复：三个动作收进一个入口，不常用 ──
        if (cards) {
            SmallTitle("备份")
            HomeSettingsCard {
                SimpleRow(
                    title = "备份与恢复",
                    summary = savedLayout?.let { "当前 ${it.groups.size} 组 · ${it.groups.sumOf { g -> g.tiles.size }} 个组件" }
                        ?: "当前是默认布局",
                    icon = Icons.Default.Description,
                    color = cPurple,
                    onClick = { showBackup = true },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showBackup) {
        WindowDialog(show = true, title = "备份与恢复", onDismissRequest = { showBackup = false }) {
            Column(Modifier.fillMaxWidth()) {
                SimpleRow(
                    title = "导出到文件",
                    summary = "存成 $HOME_LAYOUT_FILE_NAME",
                    icon = Icons.Default.Description,
                    color = cPurple,
                    onClick = {
                        val saved = store.saved
                        if (saved == null) {
                            showBriefMessage("还没有自定义过布局，首页现在是默认布局")
                        } else {
                            showBackup = false
                            saveLayoutFile(HOME_LAYOUT_FILE_NAME, HomeLayoutCodec.encode(saved))
                        }
                    },
                )
                SimpleRow(
                    title = "从文件导入",
                    summary = "覆盖当前的分组与摆放",
                    icon = Icons.Default.FolderOpen,
                    color = cBlue,
                    onClick = {
                        showBackup = false
                        pickLayoutFile()
                    },
                )
                SimpleRow(
                    title = "恢复默认",
                    summary = "分组、顺序、大小回到刚安装时",
                    icon = Icons.Default.RestartAlt,
                    color = cOrange,
                    onClick = {
                        showBackup = false
                        confirmReset = true
                    },
                )
            }
        }
    }

    if (confirmReset) {
        WindowDialog(
            show = true,
            title = "恢复默认分组和布局",
            onDismissRequest = { confirmReset = false },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "自定义的分组、标题、颜色、组件大小与位置都会清掉，换回默认分组和布局。这一步没法撤销。",
                    style = MiuixTheme.textStyles.body2,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "取消", onClick = { confirmReset = false }, modifier = Modifier.weight(1f))
                    TextButton(
                        text = "恢复默认",
                        onClick = {
                            store.reset()
                            opacity = store.backgroundOpacity
                            confirmReset = false
                            // 确认之后就收起来：首页编辑态会立刻换成新的默认布局（草稿跟着
                            // HomeLayoutResetRevision 换），不用等用户再点一次保存
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    // 导入的确认：文件里解出来了，才问「要不要覆盖现在这份」——覆盖不可撤销，
    // 所以和「恢复默认」「开启网格」一样先停一步
    pendingImport?.let { imported ->
        WindowDialog(
            show = true,
            title = "导入自定义布局",
            onDismissRequest = { pendingImport = null },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "文件里有 ${imported.groups.size} 组 · " +
                        "${imported.groups.sumOf { group -> group.tiles.size }} 个组件。" +
                        "导入会覆盖当前的分组与摆放，这一步没法撤销。",
                    style = MiuixTheme.textStyles.body2,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "取消", onClick = { pendingImport = null }, modifier = Modifier.weight(1f))
                    TextButton(
                        text = "导入",
                        onClick = {
                            store.importLayout(imported)
                            pendingImport = null
                            // 和「恢复默认」一样：导完就收起抽屉，首页当场换成导入的布局
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

/**
 * 导出的文件名。
 *
 * 固定一个名字（不带时间戳）：这是「我的布局备份」，不是一堆历史版本——换机 / 重装时
 * 从同一个位置挑回来最省事；真要留多份，用户自己在文件管理器里改名就行了。
 * 内容就是 `HomeLayoutCodec` 的落盘格式，`.txt` 保险（各家文件管理器都认得、能直接看）。
 */
private const val HOME_LAYOUT_FILE_NAME = "xjtu-toolbox-home-layout.txt"

/**
 * 主页设置里所有滑动条的**唯一**写法：直接用 MIUIX 自带的 [SliderPreference]。
 *
 * 以前这里是手搓的一行（自己排标题、数值、`padding(horizontal = 16.dp)` + 一条占满宽度的
 * `Slider`），结果是：标题贴在卡片左边缘（16dp），而上下的
 * `SwitchPreference` / `OverlayDropdownPreference` 因为有左侧图标，标题落在 60dp——
 * 同一张卡片里两套缩进，看着就是"没对齐"。数值也是自己写在标题右边，与开关行的末位控件
 * 不在一条竖线上。
 *
 * [SliderPreference] 与那些行共用同一个 `BasicComponent`：内边距、图标位、标题/摘要字号、
 * 末位数值的位置全部一致，滑条落在组件底部（`fillMaxWidth`，同样吃 16dp 内边距）。
 * 所以这里只做「把我们的图标和取值文案塞进去」，不再自己摆布局。
 *
 * @param onValueChangeFinished 松手那一刻。连续值一律是「改的时候只写盘、松手才通知首页重读」，
 *   逐帧通知会把整页（含一圈液态玻璃）重组几十次。
 */
@Composable
internal fun SettingsSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    icon: ImageVector,
    color: Color,
    onChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
) {
    SliderPreference(
        value = value,
        onValueChange = onChange,
        title = title,
        valueText = valueText,
        startAction = { SettingsIcon(icon, color) },
        valueRange = range,
        onValueChangeFinished = onValueChangeFinished,
    )
}

/** 卡片形状的几档（圆角 dp）：最后一档在任何尺寸上都会被截成圆形。 */
private val CARD_CORNER_PRESETS = listOf(
    8 to "方正",
    HomeLayoutStore.CARD_CORNER_DEFAULT to "标准",
    32 to "圆润",
    HomeLayoutStore.CARD_CORNER_MAX to "圆形",
)

/** 只有标题与说明的一行（重置这类动作），整行可点。 */
@Composable
private fun SimpleRow(
    title: String,
    summary: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon, color)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(
                summary,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 「首页功能区」二选一：经典分类卡（回落）/ 卡片组。
 *
 * 显示顺序与落盘值必须一一对应。原来的「小组件」不再是独立选项——那套实现已经并进卡片组。
 */
private val HOME_LAYOUT_VALUES = listOf(
    SettingsStore.HOME_LAYOUT_CLASSIC,
    SettingsStore.HOME_LAYOUT_CARDS,
)
private val HOME_LAYOUT_LABELS = listOf("分类卡片", "自定义卡片")

@Composable
private fun HomeSettingsCard(content: @Composable () -> Unit) {
    CouiSettingsCard(content = content)
}

