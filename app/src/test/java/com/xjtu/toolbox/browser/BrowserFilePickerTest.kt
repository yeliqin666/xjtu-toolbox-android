package com.xjtu.toolbox.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * 网页 `<input type="file" accept=...>` 折成系统选择器 MIME 的规则。
 *
 * 这些规则错一个就会变成「选择器里一个文件都不显示」，而思源学堂的作业提交页恰好是
 * 扩展名与 MIME 混着写的（`.pdf,.doc,.docx,application/pdf`），所以逐条钉住。
 */
class BrowserFilePickerTest {

    /** 假的扩展名表：真机走 [android.webkit.MimeTypeMap]，单测里不能碰 android 运行时。 */
    private val fakeMime: (String) -> String? = { ext ->
        when (ext) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> null
        }
    }

    @Test
    fun `没有 accept 就不限类型`() {
        assertArrayEquals(arrayOf("*/*"), acceptTypesToMimeTypes(null, fakeMime))
        assertArrayEquals(arrayOf("*/*"), acceptTypesToMimeTypes(emptyArray(), fakeMime))
    }

    @Test
    fun `通配 accept 也不限类型`() {
        assertArrayEquals(arrayOf("*/*"), acceptTypesToMimeTypes(arrayOf("*/*"), fakeMime))
        // 思源学堂这类页面有时写成 `image/*,application/pdf`：出现通配就整体放宽
        assertArrayEquals(arrayOf("*/*"), acceptTypesToMimeTypes(arrayOf("image/*", "*/*"), fakeMime))
    }

    @Test
    fun `扩展名折成 MIME`() {
        val actual = acceptTypesToMimeTypes(arrayOf(".pdf", ".doc"), fakeMime)
        assertArrayEquals(arrayOf("application/pdf", "application/msword"), actual)
    }

    @Test
    fun `MIME 原样保留，扩展名与 MIME 混写时去重`() {
        val actual = acceptTypesToMimeTypes(arrayOf(".pdf", "application/pdf", ".docx"), fakeMime)
        assertArrayEquals(
            arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            actual,
        )
    }

    @Test
    fun `折不出 MIME 的扩展名退回不限类型`() {
        // 认不出的扩展名丢掉，剩下的也没了 → 不能给个空列表（那等于什么都选不到）
        assertArrayEquals(arrayOf("*/*"), acceptTypesToMimeTypes(arrayOf(".weirdext"), fakeMime))
    }

    @Test
    fun `大小写与空格不敏感`() {
        val actual = acceptTypesToMimeTypes(arrayOf(" .PDF ", "IMAGE/*"), fakeMime)
        assertArrayEquals(arrayOf("application/pdf", "image/*"), actual)
    }
}
