package com.xjtu.toolbox.settings

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
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WaterDrop
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
import com.xjtu.toolbox.home.HomeLayoutStore
import com.xjtu.toolbox.platform.BackHandler
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「主页设置」抽屉（需求 §5 / 补充 §29）。
 *
 * 两套首页布局共用这一个抽屉，因为有三组设置是**跨布局共用**的——背景（壁纸 / 模糊 / 亮度）、
 * 卡片材质（液态玻璃）、顶栏排法，它们落在 [SettingsStore] 里；只有卡片组才有的几项
 * （网格开关、折射子开关、背景不透明度、恢复默认分组和布局）落在
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
    // 卡片组独有：整页布局（分组与格位）、网格开关、折射、背景不透明度
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
    val cards = layout == SettingsStore.HOME_LAYOUT_CARDS
    // 只有在卡片组 + 自定义图片这一档，不透明度才有可调的对象
    val wallpaper = remember(revision) {
        settings.homeBackground == HomeLayoutStore.BACKGROUND_IMAGE && settings.homeWallpaperImage.isNotEmpty()
    }

    // 抽屉关掉时把「连续值也改过了」告诉首页（滑条不逐帧 bump，见 notifyChanged 的说明）
    DisposableEffect(Unit) {
        onDispose { store.notifyChanged() }
    }

    val cPurple = Color(0xFF7E57C2)
    val cBlue = Color(0xFF42A5F5)
    val cTeal = Color(0xFF26A69A)
    val cOrange = Color(0xFFFFA726)

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
                SliderRow(
                    title = "背景不透明度",
                    valueText = "$opacity%",
                    value = opacity.toFloat(),
                    range = HomeLayoutStore.BACKGROUND_OPACITY_MIN.toFloat()..
                        HomeLayoutStore.BACKGROUND_OPACITY_MAX.toFloat(),
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
            SwitchPreference(
                title = "液态玻璃",
                summary = when {
                    !liquid -> "毛玻璃：半透明纸面 + 高光细边"
                    wallpaper -> "透出壁纸并带折射（最费电的一档）"
                    else -> "纯色 / 渐变背景上不采不糊，换成薄色 + 高光轮廓"
                },
                checked = liquid,
                startAction = { SettingsIcon(Icons.Default.WaterDrop, cBlue) },
                onCheckedChange = {
                    liquid = it
                    settings.homeLiquidGlass = it
                    HomeLayoutRevision.bump()
                },
            )
            // 圆角只有卡片组有（经典分类卡 / 小组件那两套的圆角是写死的）
            if (cards) {
                SliderRow(
                    title = "卡片圆角",
                    valueText = when (corner) {
                        0 -> "直角"
                        HomeLayoutStore.CARD_CORNER_MAX -> "圆饼"
                        else -> "$corner dp"
                    },
                    value = corner.toFloat(),
                    range = HomeLayoutStore.CARD_CORNER_MIN.toFloat()..HomeLayoutStore.CARD_CORNER_MAX.toFloat(),
                    onChange = {
                        corner = it.roundToInt()
                        store.cardCorner = corner
                    },
                )
            }
            // 折射子项只有卡片组吃：经典版与小组件那两套没有这一层
            if (cards && liquid) {
                SwitchPreference(
                    title = "开启折射",
                    summary = if (refraction) {
                        "边缘把背景扭出透镜的弯光，带一点色散（最费的一层）"
                    } else {
                        "只留模糊与高光：轮廓和通透感都在，省一层离屏采样"
                    },
                    checked = refraction,
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
                        "三个圆钮都在右侧，左侧是家小西头像、不画标题（改造前的顶栏）"
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

            SmallTitle("重置")
            HomeSettingsCard {
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
}

/** 一行的两栏布局：左边标题 + 右侧数值，下面一条滑条（不透明度用它）。 */
@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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

/** 和设置页其它行同一个图标样式（32dp 圆底 + 18dp 图标 + 12dp 间隔）。 */
@Composable
private fun SettingsIcon(icon: ImageVector, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        }
    }
    Spacer(Modifier.width(12.dp))
}
