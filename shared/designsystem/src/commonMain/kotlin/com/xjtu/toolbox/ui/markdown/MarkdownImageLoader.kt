package com.xjtu.toolbox.ui.markdown

import androidx.compose.ui.graphics.ImageBitmap

/** 按需加载 Markdown 远程图片，未提供加载器时显示为链接。 */
interface MarkdownImageLoader {

    /** 已经在内存里的那张图（重组时先看这里，避免反复读盘/重下）。 */
    fun cached(url: String): ImageBitmap?

    /** 这张图此前加载失败过：屏上直接画「加载失败，点开看原图」，不再重试。 */
    fun hasFailed(url: String): Boolean

    /** 拉一张图并解码；失败返回 null（调用方据此标 [hasFailed]）。 */
    suspend fun load(url: String): ImageBitmap?

    companion object {
        /** 没有取图引擎的端：一切都「加载不出来」，于是退成一行可点的链接。 */
        val None: MarkdownImageLoader = object : MarkdownImageLoader {
            override fun cached(url: String): ImageBitmap? = null
            override fun hasFailed(url: String): Boolean = false
            override suspend fun load(url: String): ImageBitmap? = null
        }
    }
}
