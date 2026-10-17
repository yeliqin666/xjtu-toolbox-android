@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

/** 浏览器这一端的能力体检：逐条回答「这一份部署里那几样浏览器能力在不在」。 */
internal object WebBrowserCapabilities {

    /** 安全上下文：`window.isSecureContext === true`。 */
    val secureContext: Boolean get() = webIsSecureContext()

    /** `navigator.clipboard.writeText` 在不在 —— 本端在用。 */
    val clipboardWriteText: Boolean get() = webHasClipboardWriteText()

    /** 相机：`navigator.mediaDevices.getUserMedia` 在不在 —— 本端在用。 */
    val camera: Boolean get() = webHasCamera()

    /** 浏览器自带的二维码识别器 `BarcodeDetector` 在不在 —— 这一端不靠它。 */
    val qrDetector: Boolean get() = webHasQrDetector()

    /** `crypto.subtle` 在不在 —— 本端没有消费方。 */
    val webCrypto: Boolean get() = webHasWebCrypto()

    /** 自检屏要画的那几行（顺序就是屏上的顺序）。 */
    fun rows(): List<BrowserCapabilityRow> = listOf(
        BrowserCapabilityRow(
            label = "安全上下文（HTTPS 或 localhost）",
            present = secureContext,
            note = "剪贴板 / crypto.subtle / 相机 都只在这种页面里存在。默认的 127.0.0.1 算， " +
                "`--host 0.0.0.0` 且不带 --https 的地址栏地址不算（契约 §3.5；--https 见 D11）。",
        ),
        BrowserCapabilityRow(
            label = "剪贴板写入（navigator.clipboard.writeText）",
            present = clipboardWriteText,
            note = "本端**在用**：共享屏里的「复制文字 / 复制验证码 / 长按复制」都走它。" +
                "为 false 时那几颗按钮会静默失败（CMP 只 console.warn，不报错）。",
        ),
        BrowserCapabilityRow(
            label = "相机（navigator.mediaDevices.getUserMedia）",
            present = camera,
            note = "本端**在用**（2026-10-16 起）：匹配交友那一屏的「扫一扫」走它（`WebQrCamera`）" +
                "—— 安全上下文 + 允许之后真出画面，扫到的码交给 `:core` 的解码器。",
        ),
        BrowserCapabilityRow(
            label = "浏览器自带二维码识别器（BarcodeDetector）",
            present = qrDetector,
            note = "只查构造器在不在。桌面 Chrome 到今天没有它 —— 但本端**不靠它**：二维码解码器" +
                "已按 D12 ③ 搬进 `:core` 的 commonMain（`platform/qr/`）。",
        ),
        BrowserCapabilityRow(
            label = "WebCrypto（crypto.subtle）",
            present = webCrypto,
            note = "本端**没有消费方**：候选的 WebVPN 要的是 AES-128-CFB，而 WebCrypto 里没有 CFB" +
                "（也没有 MD5，体测协议要的就是它）。",
        ),
    )
}

/** 自检屏上的一行：一句人话 + 在不在 + 本端谁在用 / 差什么。 */
internal data class BrowserCapabilityRow(
    val label: String,
    val present: Boolean,
    val note: String,
)

@JsFun("() => window.isSecureContext === true")
private external fun webIsSecureContext(): Boolean

@JsFun("() => !!(window.navigator.clipboard && window.navigator.clipboard.writeText)")
private external fun webHasClipboardWriteText(): Boolean

@JsFun("() => !!(window.navigator.mediaDevices && window.navigator.mediaDevices.getUserMedia)")
private external fun webHasCamera(): Boolean

@JsFun("() => typeof BarcodeDetector !== 'undefined'")
private external fun webHasQrDetector(): Boolean

@JsFun("() => !!(window.crypto && window.crypto.subtle)")
private external fun webHasWebCrypto(): Boolean
