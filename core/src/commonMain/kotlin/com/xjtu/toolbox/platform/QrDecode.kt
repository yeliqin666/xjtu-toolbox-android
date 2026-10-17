package com.xjtu.toolbox.platform

/** 平台能力：把一帧 ARGB 像素里的二维码读成文本（读不到返回 null，不抛）。 */
expect fun qrDecodeArgb(pixels: IntArray, width: Int, height: Int): String?
