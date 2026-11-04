package com.xjtu.toolbox.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.platform.decodeImage
import com.xjtu.toolbox.platform.decodeImageSize
import com.xjtu.toolbox.ui.components.encodeHeroImage
import com.xjtu.toolbox.ui.components.rememberHeroImagePicker
import com.xjtu.toolbox.ui.components.ImageCropContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalWindowInfo
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Text
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@OptIn(ExperimentalEncodingApi::class)
@Composable
internal fun HomeBackgroundPreference(store: SettingsStore, startAction: @Composable () -> Unit) {
    var mode by remember(HomeLayoutRevision.current) { mutableStateOf(store.homeBackground) }
    val scope = rememberCoroutineScope()
    var cropSource by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pickImage = rememberHeroImagePicker { bytes ->
        scope.launch {
            cropSource = withContext(Dispatchers.Default) {
                decodeImageSize(bytes)?.let { decodeImage(bytes, it, maxDim = 2048) }
            }
            error = if (cropSource == null) "这张图片读不出来，换一张试试" else null
        }
    }
    val modes = listOf("solid", "gradient", "image")
    OverlayDropdownPreference(
        title = "主页背景",
        summary = if (mode == "solid") "素笺 · 随主题变化" else null,
        items = listOf("主题色", "主题色渐变", "自定义图片"),
        selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
        startAction = startAction,
        onSelectedIndexChange = { index ->
            if (modes[index] == "image") pickImage()
            else {
                mode = modes[index]
                store.homeBackground = mode
                HomeLayoutRevision.bump()
            }
        },
    )
    if (mode == "image") {
        ArrowPreference(title = "更换背景图片", onClick = pickImage)
        var blur by remember { mutableStateOf(store.homeWallpaperBlur) }
        var brightness by remember { mutableStateOf(store.homeWallpaperBrightness) }
        WallpaperSlider("模糊程度", blur, SettingsStore.HERO_IMAGE_BLUR_MIN..SettingsStore.HERO_IMAGE_BLUR_MAX,
            label = { if (it == 0) "无" else "$it dp" }) {
            blur = it; store.homeWallpaperBlur = it; HomeLayoutRevision.bump()
        }
        WallpaperSlider("亮度", brightness, SettingsStore.HERO_IMAGE_BRIGHTNESS_MIN..SettingsStore.HERO_IMAGE_BRIGHTNESS_MAX,
            label = { "$it%" }) {
            brightness = it; store.homeWallpaperBrightness = it; HomeLayoutRevision.bump()
        }
    }
    val source = cropSource
    if (source != null) {
        val window = LocalWindowInfo.current.containerSize
        WindowDialog(show = true, title = "截取主页壁纸", onDismissRequest = { if (!busy) cropSource = null }) {
            ImageCropContent(
                image = source, busy = busy, aspect = window.width.toFloat() / window.height.coerceAtLeast(1), maxOutput = 1600,
                onCancel = { cropSource = null },
                onCrop = { cropped ->
                    busy = true
                    scope.launch {
                        val encoded = withContext(Dispatchers.Default) { encodeHeroImage(cropped) }
                        busy = false
                        if (encoded != null) {
                            store.homeWallpaperImage = Base64.Default.encode(encoded)
                            store.homeBackground = "image"
                            mode = "image"
                            cropSource = null
                            HomeLayoutRevision.bump()
                        } else error = "保存图片失败，请重试"
                    }
                },
            )
            error?.let { Text(it) }
        }
    } else error?.let { Text(it) }
}

/** 与首页大卡自定义背景同一套滑块，缩进对齐设置行的文字。 */
@Composable
private fun WallpaperSlider(title: String, value: Int, range: IntRange, label: (Int) -> String, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MiuixTheme.textStyles.body2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(label(value), style = MiuixTheme.textStyles.footnote2, modifier = Modifier.padding(start = 12.dp).width(48.dp))
        }
    }
}
