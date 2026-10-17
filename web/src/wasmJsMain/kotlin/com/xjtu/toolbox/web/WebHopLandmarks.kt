package com.xjtu.toolbox.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.xjtu.toolbox.web.res.Res
import com.xjtu.toolbox.web.res.hop_landmark_gate
import com.xjtu.toolbox.web.res.hop_landmark_ihub4
import com.xjtu.toolbox.web.res.hop_landmark_ihub5
import com.xjtu.toolbox.web.res.hop_landmark_library
import com.xjtu.toolbox.web.res.hop_landmark_mainhall
import com.xjtu.toolbox.web.res.hop_landmark_monument
import com.xjtu.toolbox.web.res.hop_landmark_sails
import com.xjtu.toolbox.web.res.hop_landmark_shell
import com.xjtu.toolbox.web.res.hop_landmark_torch
import org.jetbrains.compose.resources.imageResource

/** 跳一跳的 9 张地标图（Web 侧）。 */
@Composable
fun rememberWebHopLandmarkImages(): List<ImageBitmap> = listOf(
    Res.drawable.hop_landmark_library,
    Res.drawable.hop_landmark_gate,
    Res.drawable.hop_landmark_mainhall,
    Res.drawable.hop_landmark_torch,
    Res.drawable.hop_landmark_shell,
    Res.drawable.hop_landmark_monument,
    Res.drawable.hop_landmark_sails,
    Res.drawable.hop_landmark_ihub4,
    Res.drawable.hop_landmark_ihub5,
).map { imageResource(it) }
