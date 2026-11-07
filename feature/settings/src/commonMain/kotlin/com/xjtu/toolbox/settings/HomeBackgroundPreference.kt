package com.xjtu.toolbox.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.platform.decodeImage
import com.xjtu.toolbox.platform.decodeImageSize
import com.xjtu.toolbox.settings.SettingsColors.Purple as cPurple
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
        // 只有「模糊程度」一条：亮度已经删了（壁纸的明暗交给「背景不透明度」，
        // 两张滑条调的是同一件事，留着只会互相抵消；见 SettingsStore.homeWallpaperBlur 的说明）
        SettingsSliderRow(
            title = "模糊程度",
            valueText = if (blur == 0) "无" else "$blur dp",
            value = blur.toFloat(),
            range = SettingsStore.HERO_IMAGE_BLUR_MIN.toFloat()..SettingsStore.HERO_IMAGE_BLUR_MAX.toFloat(),
            icon = Icons.Default.Tune,
            color = cPurple,
            onChange = {
                blur = it.roundToInt()
                store.homeWallpaperBlur = blur
            },
            // 和圆角那条一样：拖的时候只写盘，松手才让首页重读一次（逐帧 bump 会整页重组）
            onValueChangeFinished = { HomeLayoutRevision.bump() },
        )
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
