package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 「交小西」形象头像（设置 → 致谢页那一栏），调用方给尺寸，内部裁成圆形。
 *
 * 图片按平台缝放在各自资源目录：Android 走 classpath（`androidMain/resources`），
 * 桌面与 Web 走 composeResources —— 与 [HeroForestBackground] 同一套办法。
 */
@Composable
expect fun JiaoxiaoxiAvatar(modifier: Modifier = Modifier)
