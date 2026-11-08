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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.home.HomeLayout
import com.xjtu.toolbox.home.HomeLayoutCodec
import com.xjtu.toolbox.home.HomeLayoutStore
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.platform.rememberPickTextFile
import com.xjtu.toolbox.platform.rememberSaveTextFile
import com.xjtu.toolbox.platform.showBriefMessage
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
 *
 * [showTopBarStyle] 管「顶栏」那一组：只有自己画首页顶栏按钮的端（`:app`）才拨得动它，
 * 桌面与 web 的首页顶栏在外壳里，那一组整块不画——省得留一个拨了没反应的开关。
 */
@Composable
fun HomeSettingsSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    showTopBarStyle: Boolean = false,
) {
    // 返回键优先关抽屉（§31）：抽屉在前台时不把返回交给下面的页面
    BackHandler(enabled = show) { onDismiss() }
    OverlayBottomSheet(
        show = show,
        title = "主页设置",
        onDismissRequest = onDismiss,
    ) {
        // 抽屉自己要知道怎么关自己：「恢复默认分组和布局」确认之后要立刻合上（§29 补充）
        HomeSettingsBody(onDismiss = onDismiss, showTopBarStyle = showTopBarStyle)
    }
}

/** 主页设置那一行的摘要文案：「卡片组 · 主题色渐变」这种。 */
internal fun homeSettingsSummary(store: SettingsStore): String = listOf(
    when (store.homeLayout) {
        SettingsStore.HOME_LAYOUT_CLASSIC -> "经典分类卡"
        // 只有两个取值，其余一律是卡片组（老存档的 widgets 也被读成 cards）
        else -> "卡片组"
    },
    when (store.homeBackground) {
        "solid" -> "主题色"
        "image" -> "自定义图片"
        else -> "主题色渐变"
    },
).joinToString(" · ")

@Composable
private fun HomeSettingsBody(onDismiss: () -> Unit, showTopBarStyle: Boolean) {
    // 卡片组独有：整页布局（分组与格位）、网格开关、折射、圆角、背景不透明度
    val store = remember { HomeLayoutStore() }
    // 两套布局共用：背景、卡片材质、顶栏排法
    val settings = remember { SettingsStore() }
    val revision = HomeLayoutRevision.current

    var layout by remember(revision) { mutableStateOf(settings.homeLayout) }
    var liquid by remember(revision) { mutableStateOf(settings.homeLiquidGlass) }
    var topActionsRight by remember(revision) { mutableStateOf(settings.homeTopActionsRight) }
    var refraction by remember { mutableStateOf(store.liquidRefraction) }
    var grid by remember { mutableStateOf(store.grid) }
    var corner by remember { mutableIntStateOf(store.cardCorner) }
    var opacity by remember { mutableIntStateOf(store.backgroundOpacity) }
    var confirmReset by remember { mutableStateOf(false) }
    // 打开网格会当场把非整数的摆位取整、收空行，且回不去（见 [confirmGrid]）——先二次确认
    var confirmGrid by remember { mutableStateOf(false) }
    // 从文件读回来、等用户确认的那份布局；非 null = 导入确认弹窗开着
    var pendingImport by remember { mutableStateOf<HomeLayout?>(null) }
    val cards = layout == SettingsStore.HOME_LAYOUT_CARDS
    // 只有在卡片组 + 自定义图片这一档，不透明度才有可调的对象
    val wallpaper = remember(revision) {
        settings.homeBackground == HomeLayoutStore.BACKGROUND_IMAGE && settings.homeWallpaperImage.isNotEmpty()
    }
    // 卡片那层玻璃还能不能看出模糊 / 折射：壁纸糊到阈值以上时就看不出差别了
    val glassHasDetail = remember(wallpaper, revision) {
        wallpaper && settings.homeWallpaperBlur < SettingsStore.HOME_WALLPAPER_BLUR_HIDES_GLASS
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        // ── 首页功能区：经典分类卡（回落）/ 卡片组（合并了原「小组件」能力的实现） ──
        SmallTitle("首页功能区")
        HomeSettingsCard {
            OverlayDropdownPreference(
                title = "布局",
                summary = when (layout) {
                    SettingsStore.HOME_LAYOUT_CLASSIC -> "刊头 + 头卡 + 分类卡，功能按分类排"
                    else -> "长按进入编辑：可自由摆放、缩放、分组"
                },
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

        // ── 背景（三套布局共用） ──
        SmallTitle("背景")
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
        }

        // ── 卡片材质 ──
        SmallTitle("卡片")
        HomeSettingsCard {
            // 圆角排在材质**上面**：先定「长什么样」（形状），再定「用什么材质」。
            // 圆角只有卡片组有（经典分类卡 / 小组件那两套的圆角是写死的）；
            // 编辑态那条悬浮条**恒为全圆**，不吃这个值
            if (cards) {
                SettingsSliderRow(
                    title = "卡片圆角",
                    valueText = when (corner) {
                        0 -> "直角"
                        HomeLayoutStore.CARD_CORNER_MAX -> "圆饼"
                        else -> "$corner dp"
                    },
                    value = corner.toFloat(),
                    range = HomeLayoutStore.CARD_CORNER_MIN.toFloat()..HomeLayoutStore.CARD_CORNER_MAX.toFloat(),
                    icon = Icons.Default.Widgets,
                    color = cTeal,
                    onChange = {
                        corner = it.roundToInt()
                        store.cardCorner = corner
                    },
                    // 松手才通知首页重读：拖的时候逐帧 bump 会把整页（含一圈液态玻璃）重组几十次，
                    // 而这段连续值本来就只写盘、不逐帧刷新（同 notifyChanged 的约定）
                    onValueChangeFinished = { store.notifyChanged() },
                )
            }
            SwitchPreference(
                title = "液态玻璃",
                summary = when {
                    !liquid -> "毛玻璃：半透明纸面 + 高光细边"
                    glassHasDetail -> "透出壁纸并带折射（最费电的一档）"
                    else -> "这张背景上看不出模糊与折射，只留薄色与高光轮廓"
                },
                checked = liquid,
                startAction = { SettingsIcon(Icons.Default.WaterDrop, cBlue) },
                onCheckedChange = {
                    liquid = it
                    settings.homeLiquidGlass = it
                    HomeLayoutRevision.bump()
                },
            )
            // 折射子项只有卡片组吃：经典版与小组件那两套没有这一层。
            //
            // 没有可折 / 可糊的细节时它**置灰但保留用户的设置**（2026-11 按维护者要求改）：
            // ①纯色 / 渐变背景上没有壁纸可采，`lens` 折的是一张纯色底，`blur` 也糊不出信息；
            // ②自定义图片但「模糊程度」≥ 5dp 时，壁纸已经被糊成一片色块，卡片再采它一遍糊出来的
            // 与「薄色底」几乎分不出。两种情况都是「既不模糊也不折射」，而这两层是全页最贵的
            // （每张卡一次离屏位移采样），所以渲染侧直接不跑。
            //
            // 但开关**不改成关**：显示的是用户自己那一档（`checked = refraction`），只是拨不动
            // （`enabled = glassHasDetail`）。「用不上」和「用户关掉了」是两件事——前者换一档背景
            // 就恢复，后者得用户自己再拨回来。存盘值一律不动。
            //
            // 说明文字只用那一句（维护者定的）：**不加括号解释**成因，两档共用。
            if (cards && liquid) {
                SwitchPreference(
                    title = "开启折射",
                    summary = when {
                        !glassHasDetail -> "当前背景设置下折射/模糊无明显效果，为节省资源已关闭"
                        refraction -> "边缘把背景扭出透镜的弯光（最费的一层）"
                        else -> "只留模糊与高光：轮廓和通透感都在，省一层离屏采样"
                    },
                    checked = refraction,
                    enabled = glassHasDetail,
                    startAction = { SettingsIcon(Icons.Default.WaterDrop, cBlue.copy(alpha = 0.72f)) },
                    onCheckedChange = {
                        refraction = it
                        store.liquidRefraction = it
                        store.notifyChanged()
                    },
                )
            }
        }

        // ── 顶栏 ──
        // 排法只有 :app 真的画得出两种（桌面 / web 的首页顶栏在外壳里，`showTopBarStyle` 为假）
        if (showTopBarStyle) {
            SmallTitle("顶栏")
            HomeSettingsCard {
                SwitchPreference(
                    title = "功能按钮统一右对齐",
                    summary = if (topActionsRight) {
                        "三个圆钮都在右侧，左侧是交小西头像、不画标题（改造前的顶栏）"
                    } else {
                        "扫码靠左、标题居中，右侧只留搜索与通知"
                    },
                    checked = topActionsRight,
                    startAction = { SettingsIcon(Icons.Default.SwapHoriz, cPurple) },
                    onCheckedChange = {
                        topActionsRight = it
                        settings.homeTopActionsRight = it
                        HomeLayoutRevision.bump()
                    },
                )
            }
        }

        // ── 网格与重置：只有卡片组有格位这个概念 ──
        if (cards) {
            SmallTitle("网格")
            HomeSettingsCard {
                SwitchPreference(
                    title = "开启网格",
                    summary = if (grid) {
                        "拖动与缩放吸附到 1×1、1×2、2×1 这类标准栅格"
                    } else {
                        "自由摆放，拖到相邻边界时自动贴合"
                    },
                    checked = grid,
                    startAction = { SettingsIcon(Icons.Default.GridOn, cTeal) },
                    onCheckedChange = { on ->
                        // 关掉是立刻生效的（不会动数据）；打开要把非整数摆位取整，先问一句
                        if (!on) {
                            grid = false
                            store.grid = false
                            store.notifyChanged()
                        } else {
                            confirmGrid = true
                        }
                    },
                )
            }

            SmallTitle("备份与重置")
            HomeSettingsCard {
                // 三行按「先备份、再导入、最后推倒重来」排：导出在上，是因为它是唯一
                // 无副作用的一步，也是下面两步的后悔药
                SimpleRow(
                    title = "导出布局到文件",
                    summary = savedLayout?.let { custom ->
                        "共 ${custom.groups.size} 组 · " +
                            "${custom.groups.sumOf { group -> group.tiles.size }} 个组件，存成一个 ${HOME_LAYOUT_FILE_NAME}"
                    } ?: "还没摆过：首页现在用默认布局，没有可导出的内容",
                    icon = Icons.Default.Description,
                    color = cPurple,
                    onClick = {
                        val saved = store.saved
                        if (saved == null) {
                            showBriefMessage("还没有自定义过布局，首页现在是默认布局")
                        } else {
                            saveLayoutFile(HOME_LAYOUT_FILE_NAME, HomeLayoutCodec.encode(saved))
                        }
                    },
                )
                SimpleDivider()
                SimpleRow(
                    title = "从文件导入布局",
                    summary = "选一个导出过的 ${HOME_LAYOUT_FILE_NAME}，覆盖当前的分组与摆放",
                    icon = Icons.Default.FolderOpen,
                    color = cBlue,
                    onClick = pickLayoutFile,
                )
                SimpleDivider()
                SimpleRow(
                    title = "恢复默认分组和布局",
                    summary = "分组、顺序、组件大小全部回到刚安装时的样子",
                    icon = Icons.Default.RestartAlt,
                    color = cOrange,
                    onClick = { confirmReset = true },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
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
                            refraction = store.liquidRefraction
                            grid = store.grid
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

    // 打开网格的二次确认：它会把自由摆位取整落盘，且没有回头路（参照「恢复默认」那个弹窗）
    if (confirmGrid) {
        WindowDialog(
            show = true,
            title = "开启网格",
            onDismissRequest = { confirmGrid = false },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "自由摆放的组件会按格子取整、收掉空出来的行，改成吸附到 1×1 / 2×1 / 1×2 / 2×2 这些标准格子。" +
                        "这一步不可撤销：之后再关掉网格，也不会恢复成原来的位置与大小。",
                    style = MiuixTheme.textStyles.body2,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "取消", onClick = { confirmGrid = false }, modifier = Modifier.weight(1f))
                    TextButton(
                        text = "开启网格",
                        onClick = {
                            // 取整落盘 + 开开关 + 换掉编辑态草稿，全在 store 里一次做完
                            store.enableGrid()
                            grid = true
                            confirmGrid = false
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

/**
 * 卡内两行之间那条细线。
 *
 * 只有「重置」这张卡里有两行（导出 / 恢复默认），而这两行是手搓的 [SimpleRow]——MIUIX 的
 * preference 组件自己不带分隔线，所以这里补一条：起点对齐文字（16dp 内边距 + 32dp 图标 +
 * 12dp 间隔 = 60dp），右端留 16dp，和设置页其它卡片的观感一致。
 */
@Composable
private fun SimpleDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 16.dp)
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}

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
private val HOME_LAYOUT_LABELS = listOf("经典分类卡", "卡片组")

@Composable
private fun HomeSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(content = content)
    }
}

