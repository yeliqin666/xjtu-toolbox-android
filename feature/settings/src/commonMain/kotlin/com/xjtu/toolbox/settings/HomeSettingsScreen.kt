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
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.xjtu.toolbox.platform.decodeImage
import com.xjtu.toolbox.platform.decodeImageSize
import com.xjtu.toolbox.ui.components.encodeHeroImage
import com.xjtu.toolbox.ui.components.rememberHeroImagePicker
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「主页设置」抽屉（需求 §5 / 补充 §29）：背景、卡片材质、网格开关、恢复默认分组和布局。
 *
 * 做成底部抽屉而不是二级页，是因为它要能从两处打开——全局设置里的那一行，和首页编辑态的齿轮；
 * 两处挂的是**同一个** [HomeSettingsSheet]，读写同一份 [HomeLayoutStore]（`app_settings`），
 * 所以从哪儿打开看到的都是同一套状态。改完叫一次 [HomeLayoutStore.notifyChanged]，
 * 首页那边靠 `HomeLayoutRevision` 重读，返回首页时改动已经生效。
 */
@Composable
fun HomeSettingsSheet(show: Boolean, onDismiss: () -> Unit) {
    // 返回键优先关抽屉（§31）：抽屉在前台时不把返回交给下面的页面
    BackHandler(enabled = show) { onDismiss() }
    OverlayBottomSheet(
        show = show,
        title = "主页设置",
        onDismissRequest = onDismiss,
    ) {
        // 抽屉自己要知道怎么关自己：「恢复默认分组和布局」确认之后要立刻合上（§29 补充）
        HomeSettingsBody(onDismiss = onDismiss)
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun HomeSettingsBody(onDismiss: () -> Unit) {
    val store = remember { HomeLayoutStore() }
    // 本地状态是这一屏的真源：滑条拖动时先改它，写盘也顺手做，但只有离散动作才 bump revision
    var liquid by remember { mutableStateOf(store.liquidCard) }
    var grid by remember { mutableStateOf(store.grid) }
    var image by remember { mutableStateOf(store.backgroundImage) }
    var blur by remember { mutableIntStateOf(store.backgroundBlur) }
    var opacity by remember { mutableIntStateOf(store.backgroundOpacity) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    // 打开网格会当场把非整数的摆位取整、收空行，且回不去（见 [confirmGrid]）——先二次确认
    var confirmGrid by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 抽屉关掉时把「连续值也改过了」告诉首页（滑条不逐帧 bump，见 notifyChanged 的说明）
    DisposableEffect(Unit) {
        onDispose { store.notifyChanged() }
    }

    val pickImage = rememberHeroImagePicker { bytes ->
        scope.launch {
            busy = true
            error = null
            val encoded = withContext(Dispatchers.Default) {
                decodeImageSize(bytes)
                    ?.let { size -> decodeImage(bytes, size, maxDim = 1600) }
                    ?.let { encodeHeroImage(it) }
            }
            busy = false
            if (encoded != null) {
                val base64 = Base64.Default.encode(encoded)
                image = base64
                store.backgroundImage = base64
                store.notifyChanged()
            } else {
                error = "这张图片读不出来"
            }
        }
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
        // ── 背景 ──
        SmallTitle("背景")
        HomeSettingsCard {
            ArrowPreference(
                title = "更换背景图片",
                summary = when {
                    busy -> "处理中…"
                    error != null -> error.orEmpty()
                    image.isEmpty() -> "还没有背景图，用主题底色"
                    else -> "已设置 · 模糊 $blur dp · 不透明度 $opacity%"
                },
                startAction = { SettingsIcon(Icons.Default.Wallpaper, cPurple) },
                onClick = pickImage,
            )
            if (image.isNotEmpty()) {
                SliderRow(
                    title = "背景模糊",
                    valueText = if (blur == 0) "无" else "$blur dp",
                    value = blur.toFloat(),
                    range = HomeLayoutStore.BACKGROUND_BLUR_MIN.toFloat()..HomeLayoutStore.BACKGROUND_BLUR_MAX.toFloat(),
                    onChange = {
                        blur = it.roundToInt()
                        store.backgroundBlur = blur
                    },
                )
                SliderRow(
                    title = "背景透明度",
                    valueText = "$opacity%",
                    value = opacity.toFloat(),
                    range = HomeLayoutStore.BACKGROUND_OPACITY_MIN.toFloat()..HomeLayoutStore.BACKGROUND_OPACITY_MAX.toFloat(),
                    onChange = {
                        opacity = it.roundToInt()
                        store.backgroundOpacity = opacity
                    },
                )
                SimpleRow(
                    title = "移除背景图片",
                    summary = "回到主题底色",
                    icon = Icons.Default.RestartAlt,
                    color = cOrange,
                    onClick = {
                        image = ""
                        store.backgroundImage = ""
                        store.notifyChanged()
                    },
                )
            }
        }

        // ── 卡片材质 ──
        SmallTitle("卡片")
        HomeSettingsCard {
            SwitchPreference(
                title = "主页卡片液态玻璃",
                summary = if (liquid) "液态玻璃：透出背景并带折射" else "毛玻璃：半透明纸面 + 高光细边",
                checked = liquid,
                startAction = { SettingsIcon(Icons.Default.WaterDrop, cBlue) },
                onCheckedChange = {
                    liquid = it
                    store.liquidCard = it
                    store.notifyChanged()
                },
            )
        }

        // ── 网格 ──
        SmallTitle("网格")
        HomeSettingsCard {
            SwitchPreference(
                title = "开启网格",
                summary = if (grid) "拖动与缩放吸附到 1×1、1×2、2×1 这类标准栅格" else "自由摆放，拖到相邻边界时自动贴合",
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

        // ── 重置 ──
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
                            liquid = store.liquidCard
                            grid = store.grid
                            image = store.backgroundImage
                            blur = store.backgroundBlur
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

/** 一行的两栏布局：左边标题 + 右侧数值，下面一条滑条（模糊 / 不透明度都用它）。 */
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

/** 只有标题与说明的一行（重置、移除背景这类动作），整行可点。 */
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
