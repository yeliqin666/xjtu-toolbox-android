package com.xjtu.toolbox.game.hop

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import com.xjtu.toolbox.R

/** 跳一跳的 9 张地标图（Android 侧）。 */
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
