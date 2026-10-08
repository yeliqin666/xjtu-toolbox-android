package com.xjtu.toolbox.game.hop

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import com.xjtu.toolbox.R

/**
 * 跳一跳的 9 张地标图（Android 侧）。
 *
 * 顺序**必须**与 :core 的 [LANDMARK_NAMES] 一致 —— 屏按下标取名字与图。
 * 这里是原来的 `R.drawable.hop_landmark_*`，逐字未变（搬迁前后 Android 取的是同一批资源）。
 *
 * Web 端对应的是 `web/.../WebHopLandmarks.kt`：那边读 :web 的 composeResources
 * （同一份 webp 字节，只是不能共用 R.drawable —— 见 web/build.gradle.kts 里那段说明）。
 */
@Composable
fun rememberHopLandmarkImages(): List<ImageBitmap> = listOf(
    R.drawable.hop_landmark_library,
    R.drawable.hop_landmark_gate,
    R.drawable.hop_landmark_mainhall,
    R.drawable.hop_landmark_torch,
    R.drawable.hop_landmark_shell,
    R.drawable.hop_landmark_monument,
    R.drawable.hop_landmark_sails,
    R.drawable.hop_landmark_ihub4,
    R.drawable.hop_landmark_ihub5,
).map { ImageBitmap.imageResource(it) }
