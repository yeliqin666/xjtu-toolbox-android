package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.decodeImage
import com.xjtu.toolbox.platform.decodeImageSize
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

/** 首页头卡的外观，落盘在 [SettingsStore] 的 `hero_*` 几项；默认值就是随时间和主题变化的自动外观。 */
data class HeroAppearance(
    val palette: String = "auto",
    val colorHex: String = "#2F7CFF",
    val motion: String = "auto",
    val seed: Int = -1,
    val background: String = "liquid",
    /** 自定义背景裁好的图片（WebP Base64）；空串 = 还没选图。 */
    val imageData: String = "",
    /** 自定义背景的模糊半径（dp）；0 = 不模糊。 */
    val imageBlur: Int = 0,
    /** 自定义背景的亮度（百分比，20..100）；100 = 原图。 */
    val imageBrightness: Int = 100,
) {
    val solidColor: Color get() = CourseColors.parseHex(colorHex) ?: Color(0xFF2F7CFF)

    /** 纯色底的亮度决定用黑字还是白字。 */
    val contentColor: Color
        get() = if (background == "solid" && solidColor.luminance() > 0.179f) Color.Black else Color.White

    /** 设置页那一行右侧的摘要。 */
    val summary: String
        get() = when (background) {
            "solid" -> "纯色 · $colorHex"
            "forest" -> "森林"
            "pixel" -> "像素"
            "custom" -> when {
                imageData.isEmpty() -> "自定义 · 未选图"
                imageBlur > 0 || imageBrightness < 100 ->
                    "自定义 · " + listOfNotNull(
                        imageBlur.takeIf { it > 0 }?.let { "模糊 $it dp" },
                        imageBrightness.takeIf { it < 100 }?.let { "亮度 $it%" },
                    ).joinToString(" · ")
                else -> "自定义"
            }
            else -> "液体 · ${paletteLabel(palette)} · ${motionLabel(motion)}"
        }

    fun save(store: SettingsStore, dark: Boolean) {
        store.heroBackground = background
        store.heroPalette = palette
        store.setHeroColorHex(dark, colorHex)
        store.heroMotion = motion
        store.heroSeed = seed
        store.heroImageData = imageData
        store.heroImageBlur = imageBlur
        store.heroImageBrightness = imageBrightness
        HeroAppearanceRevision.bump()
    }

    companion object {
        fun read(store: SettingsStore, dark: Boolean) = HeroAppearance(
            palette = store.heroPalette,
            colorHex = store.heroColorHex(dark),
            motion = store.heroMotion,
            seed = store.heroSeed,
            background = store.heroBackground,
            imageData = store.heroImageData,
            imageBlur = store.heroImageBlur,
            imageBrightness = store.heroImageBrightness,
        )
    }
}

/**
 * 外观落盘后的进程内信号。首页与设置页各自持有一份 [HeroAppearance]，
 * 改了之后靠它让两边一起重新读，不依赖谁还留在组合里。
 */
object HeroAppearanceRevision {
    var current by mutableIntStateOf(0)
        private set

    internal fun bump() {
        current++
    }
}

private fun paletteLabel(key: String): String = when (key) {
    "midnight" -> "午夜蓝"
    "orchid" -> "兰紫"
    "blossom" -> "樱粉"
    "lagoon" -> "湖绿"
    "honey" -> "蜜橙"
    "custom" -> "自选色"
    else -> "自动配色"
}

private fun motionLabel(key: String): String = when (key) {
    "gentle" -> "轻柔"
    "flowing" -> "舒缓"
    "lively" -> "活跃"
    "still" -> "静止"
    else -> "自动流速"
}

/** 设置页入口用的弹窗：自己读 [SettingsStore]，确定即落盘。 */
@Composable
fun HomeHeroAppearanceDialog(store: SettingsStore, onDismiss: () -> Unit) {
    val dark = LocalIsDarkTheme.current
    val current = remember(HeroAppearanceRevision.current, dark) { HeroAppearance.read(store, dark) }
    HeroAppearanceDialog(
        current = current,
        onDismiss = onDismiss,
        onApply = {
            it.save(store, dark)
            onDismiss()
        },
    )
}

/** 草稿只作用于弹窗预览，确定后落盘；返回、点外面和取消均丢弃草稿。 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun HeroAppearanceDialog(
    current: HeroAppearance,
    nextStartAt: LocalDateTime? = null,
    examSoon: Boolean = false,
    onDismiss: () -> Unit,
    onApply: (HeroAppearance) -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    var draft by remember(current, dark) { mutableStateOf(current) }
    var cropSource by remember { mutableStateOf<ImageBitmap?>(null) }
    var imageBusy by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mood = rememberLiquidMood(nextStartAt, examSoon, draft)
    val pickImage = rememberHeroImagePicker { bytes ->
        scope.launch {
            imageBusy = true
            imageError = null
            val bitmap = withContext(Dispatchers.Default) {
                decodeImageSize(bytes)?.let { decodeImage(bytes, it, maxDim = 1600) }
            }
            imageBusy = false
            if (bitmap != null) cropSource = bitmap else imageError = "这张图片读不出来"
        }
    }
    WindowDialog(
        show = true,
        title = if (cropSource != null) "截取背景" else "首页卡片外观",
        onDismissRequest = onDismiss,
    ) {
        val source = cropSource
        if (source != null) {
            HeroCropContent(
                image = source,
                busy = imageBusy,
                onCancel = { cropSource = null },
                onCrop = { cropped ->
                    imageBusy = true
                    imageError = null
                    scope.launch {
                        val bytes = withContext(Dispatchers.Default) { encodeHeroImage(cropped) }
                        imageBusy = false
                        if (bytes != null) {
                            draft = draft.copy(
                                background = "custom",
                                imageData = Base64.Default.encode(bytes),
                                imageBlur = SettingsStore.HERO_IMAGE_BLUR_MIN,
                                imageBrightness = SettingsStore.HERO_IMAGE_BRIGHTNESS_MAX,
                            )
                        } else {
                            imageError = "保存图片失败"
                        }
                        cropSource = null
                    }
                },
            )
        } else {
            Column {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("样式", style = MiuixTheme.textStyles.body2)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppFilterChip(selected = draft.background == "liquid", onClick = { draft = draft.copy(background = "liquid") }, label = "液体")
                        AppFilterChip(selected = draft.background == "pixel", onClick = { draft = draft.copy(background = "pixel") }, label = "像素")
                        AppFilterChip(selected = draft.background == "forest", onClick = { draft = draft.copy(background = "forest") }, label = "森林")
                        AppFilterChip(selected = draft.background == "solid", onClick = { draft = draft.copy(background = "solid") }, label = "纯色")
                        AppFilterChip(selected = draft.background == "custom", onClick = { draft = draft.copy(background = "custom") }, label = "自定义")
                    }
                    Box(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(20.dp))) {
                        when (draft.background) {
                            "solid" -> Box(Modifier.matchParentSize().background(draft.solidColor))
                            "forest" -> {
                                HeroForestBackground(Modifier.matchParentSize())
                                Box(Modifier.matchParentSize().background(HeroForestScrim))
                            }
                            "pixel" -> {
                                HeroPixelBackground(Modifier.matchParentSize())
                                Box(Modifier.matchParentSize().background(HeroPixelScrim))
                            }
                            "custom" -> {
                                HeroCustomBackground(
                                    imageData = draft.imageData,
                                    modifier = Modifier.matchParentSize(),
                                    blur = draft.imageBlur,
                                    brightness = draft.imageBrightness,
                                )
                                Box(Modifier.matchParentSize().background(HeroCustomScrim))
                            }
                            else -> {
                                LiquidBackground(
                                    colors = mood.colors, seed = mood.seed, speed = mood.speed,
                                    crease = mood.crease, animated = mood.animated,
                                    modifier = Modifier.matchParentSize(),
                                )
                                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.18f)))
                            }
                        }
                        Column(Modifier.align(Alignment.CenterStart).padding(18.dp)) {
                            Text("今日安排", color = draft.contentColor, style = MiuixTheme.textStyles.body1)
                            Text(
                                when (draft.background) {
                                    "forest" -> "预览森林与暗色渐隐"
                                    "pixel" -> "预览像素抖动"
                                    "custom" -> if (draft.imageData.isEmpty()) "选择一张图片作为背景" else "预览自定义图片"
                                    else -> "预览配色与流动效果"
                                },
                                color = draft.contentColor.copy(alpha = 0.8f),
                                style = MiuixTheme.textStyles.footnote1,
                            )
                        }
                    }
                    if (draft.background == "custom") {
                        Text("自定义图片", style = MiuixTheme.textStyles.body2)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                text = if (draft.imageData.isEmpty()) "选择图片" else "更换图片",
                                enabled = !imageBusy,
                                onClick = pickImage,
                            )
                            when {
                                imageBusy -> Text("处理中…", style = MiuixTheme.textStyles.footnote2)
                                imageError != null -> Text(
                                    imageError.orEmpty(),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.error,
                                )
                            }
                        }
                        if (draft.imageData.isNotEmpty()) {
                            Text("模糊程度", style = MiuixTheme.textStyles.body2)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Slider(
                                    value = draft.imageBlur.toFloat(),
                                    onValueChange = { draft = draft.copy(imageBlur = it.roundToInt()) },
                                    valueRange = SettingsStore.HERO_IMAGE_BLUR_MIN.toFloat()..SettingsStore.HERO_IMAGE_BLUR_MAX.toFloat(),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (draft.imageBlur == 0) "无" else "${draft.imageBlur} dp",
                                    style = MiuixTheme.textStyles.footnote2,
                                    modifier = Modifier.width(48.dp),
                                )
                            }
                            Text("亮度", style = MiuixTheme.textStyles.body2)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Slider(
                                    value = draft.imageBrightness.toFloat(),
                                    onValueChange = { draft = draft.copy(imageBrightness = it.roundToInt()) },
                                    valueRange = SettingsStore.HERO_IMAGE_BRIGHTNESS_MIN.toFloat()..SettingsStore.HERO_IMAGE_BRIGHTNESS_MAX.toFloat(),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${draft.imageBrightness}%",
                                    style = MiuixTheme.textStyles.footnote2,
                                    modifier = Modifier.width(48.dp),
                                )
                            }
                        }
                    }
                    if (draft.background == "liquid" || draft.background == "solid") {
                        Text(
                            if (dark) "颜色 · 深色模式" else "颜色 · 浅色模式",
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                    if (draft.background == "liquid") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "auto" to "自动", "midnight" to "午夜蓝", "orchid" to "兰紫",
                                "blossom" to "樱粉", "lagoon" to "湖绿", "honey" to "蜜橙", "custom" to "自选色",
                            ).forEach { (key, label) -> AppFilterChip(selected = draft.palette == key, onClick = { draft = draft.copy(palette = key) }, label = label) }
                        }
                    }
                    if (draft.background == "solid" || (draft.background == "liquid" && draft.palette == "custom")) {
                        ColorPalette(
                            color = CourseColors.parseHex(draft.colorHex) ?: Color(0xFF2F7CFF),
                            onColorChanged = { draft = draft.copy(colorHex = CourseColors.toHex(it)) },
                        )
                    }
                    if (draft.background == "liquid") {
                        Text("流动方式", style = MiuixTheme.textStyles.body2)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "auto" to "自动", "gentle" to "轻柔", "flowing" to "舒缓", "lively" to "活跃", "still" to "静止",
                            ).forEach { (key, label) -> AppFilterChip(selected = draft.motion == key, onClick = { draft = draft.copy(motion = key) }, label = label) }
                        }
                        Text("纹理形状", style = MiuixTheme.textStyles.body2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppFilterChip(selected = draft.seed < 0, onClick = { draft = draft.copy(seed = -1) }, label = "每天自动换纹理")
                            AppFilterChip(
                                selected = draft.seed >= 0,
                                onClick = {
                                    // 每次都换一种，并固定到下一次用户主动修改。
                                    draft = draft.copy(seed = (draft.seed + Random.nextInt(1, 10000)) % 10000)
                                },
                                label = "随机纹理并固定",
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    TextButton(
                        text = "确定",
                        enabled = draft.background != "custom" || draft.imageData.isNotEmpty(),
                        onClick = { onApply(draft) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}
