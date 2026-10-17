package com.xjtu.toolbox.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 浏览器能力在 `:web` 上能不能用的机器可验那一半。 */
class WebBrowserCapabilitiesTest {

    @Test
    fun `安全上下文里剪贴板与 crypto.subtle 真的在（D12 ③ 的前置）`() {
        assertTrue(
            WebBrowserCapabilities.secureContext,
            "karma 跑在 localhost 上，而 localhost 属安全上下文 —— 这一条不该是 false" +
                "（判据：web/.../WebBrowserCapabilities.kt 的 KDoc）",
        )
        assertTrue(
            WebBrowserCapabilities.clipboardWriteText,
            "安全上下文里 navigator.clipboard.writeText 应当在 —— 共享屏里的「复制文字 / 复制验证码 /" +
                "长按复制」走的就是它（CMP 在 wasm 上的 ClipboardManager）",
        )
        assertTrue(
            WebBrowserCapabilities.webCrypto,
            "安全上下文里 crypto.subtle 应当在（本端没有消费方，但它是「能不能把 WebVPN 搬到浏览器」" +
                "那条最小可行方案的前提）",
        )
    }

    @Test
    fun `相机扫码那一档的判据：桌面 Chrome 没有浏览器自带的二维码识别器`() {
        assertFalse(
            WebBrowserCapabilities.qrDetector,
            "这条红了 = 这台机器上的 Chrome 有了 BarcodeDetector（相机扫码那一路的「另一半」）。" +
                "那就该回去重读 docs/desktop-port-plan.md §16.11 与 WebBrowserCapabilities 的 KDoc，" +
                "评估把匹配交友那一屏的扫码档接起来 —— 不是把这条断言删掉",
        )
    }

    @Test
    fun `自检屏那五行与对象里的五条事实一一对应（同一顺序）`() {
        val facts = listOf(
            WebBrowserCapabilities.secureContext,
            WebBrowserCapabilities.clipboardWriteText,
            WebBrowserCapabilities.camera,
            WebBrowserCapabilities.qrDetector,
            WebBrowserCapabilities.webCrypto,
        )
        val rows = WebBrowserCapabilities.rows()
        assertEquals(
            facts,
            rows.map { it.present },
            "?route=probe 上那张「浏览器能力」表必须就是这几条事实（顺序也一样）；" +
                "屏幕上的说法与这里的判据对不上，用户核到的就不是真东西",
        )
        rows.forEach { row ->
            assertTrue(row.label.isNotBlank(), "能力表里有一行没写它是什么")
        }
    }
}
