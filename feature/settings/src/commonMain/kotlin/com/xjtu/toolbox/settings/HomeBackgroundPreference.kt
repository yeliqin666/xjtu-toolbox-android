package com.xjtu.toolbox.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.platform.decodeImage
import com.xjtu.toolbox.platform.decodeImageSize
import com.xjtu.toolbox.ui.components.encodeHeroImage
import com.xjtu.toolbox.ui.components.rememberHeroImagePicker
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
    val pickImage = rememberHeroImagePicker { bytes ->
        scope.launch {
            val encoded = withContext(Dispatchers.Default) {
                decodeImageSize(bytes)?.let { decodeImage(bytes, it, maxDim = 1600) }?.let(::encodeHeroImage)
            }
            if (encoded != null) {
                store.homeWallpaperImage = Base64.Default.encode(encoded)
                store.homeBackground = "image"
                mode = "image"
                HomeLayoutRevision.bump()
            }
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
    }
}
