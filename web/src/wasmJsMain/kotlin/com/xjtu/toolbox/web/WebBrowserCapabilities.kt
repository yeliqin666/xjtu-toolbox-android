@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

/**
 * 浏览器这一端的**能力体检**（D12 ③ 的落点）：逐条回答「这一份部署里那几样浏览器能力在不在」。
 *
 * ## 为什么要有它（而不是只写进报告）
 *
 * 「能不能用」这件事**取决于部署形态**而不是代码：`navigator.clipboard` / `crypto.subtle` /
 * `getUserMedia` / Service Worker 都只在**安全上下文**里存在（HTTPS 或 localhost）—— 同一份产物在
 * `http://127.0.0.1` 上全都在，在 `http://<内网 IP>` 上全都不在（`docs/api-contract.md` §3.5、
 * D11 批④）。光在文档里写「换个地址就不一样」没人能核，所以把它做成 `?route=probe` 自检屏上的
 * 一张表：用户拿自己的地址一开就看见**这一份**是什么情况。
 *
 * ## 每一条都带「本端谁在用」（这是这一张表和「浏览器支持什么」那种表的区别）
 *
 * 查清三样（相机扫码 / 剪贴板 / WebCrypto）之后，本端在它们上面的真实状态是三种不同的：
 *  - **在用**：剪贴板 —— 共享屏里的「复制」走 `LocalClipboardManager`，而 CMP 在 wasm 上的
 *    实现就是 `navigator.clipboard.writeText`；
 *  - **有 API 但没消费方**：WebCrypto —— 唯一的候选（WebVPN 的 AES-128-CFB）WebCrypto 根本不支持，
 *    而转换本来就在服务端（§5.7）；
 *  - **缺的不是这个 API**：相机扫码 —— `getUserMedia` 只是一个前提，真正的卡点是**二维码解码器**
 *    （见 [qrDetector] 那一行的说明与 `docs/desktop-port-plan.md` §16.11）。
 *
 * 这些结论的机器可验那一半在 `WebBrowserCapabilitiesTest`（真浏览器里断言事实），
 * 另一半（差什么、最小可行方案）在 §16.11 —— **不在屏上许诺任何本端做不到的事**。
 */
internal object WebBrowserCapabilities {

    /**
     * 安全上下文：`window.isSecureContext === true`。
     *
     * 下面四条里的前三条**只**在这种页面里存在（Service Worker 也只在安全上下文里能注册 ——
     * D12 ④ PWA 的前置就是它）。`--https`（D11 批④）开出来即可满足；`127.0.0.1` 属
     * localhost，也算安全上下文。
     */
    val secureContext: Boolean get() = webIsSecureContext()

    /**
     * `navigator.clipboard.writeText` 在不在 —— **本端在用**。
     *
     * 上到屏幕上的复制动作全是共享屏里那几处（匹配交友的「复制文字」、社区验证码、
     * 空闲教室长按复制教室名、屁岱会话导出）：它们调 `LocalClipboardManager.setText`，而 CMP
     * 在 wasm 上的实现就是这一条（`PlatformClipboard.wasm.kt` 的 `WasmPlatformClipboardManager`，
     * 非安全上下文里它只 `console.warn` ⇒ 按钮点了没反应、也不报错）。
     * 也就是说：**这一条为 false 时，页面上那几颗复制按钮会静默失败** —— 那是部署要说清的事。
     */
    val clipboardWriteText: Boolean get() = webHasClipboardWriteText()

    /**
     * 相机：`navigator.mediaDevices.getUserMedia` 在不在 —— **本端没有消费方**。
     *
     * 匹配交友那一屏的扫码档今天传 `scanner = null`（两颗按钮一个字都不画）。缺的不是这一个 API：
     * 没有二维码**解码器**，拿到取景画面也读不出码，见 [qrDetector]。
     */
    val camera: Boolean get() = webHasCamera()

    /**
     * 浏览器自带的二维码识别器 `BarcodeDetector` 在不在 —— 这是相机扫码那一路**另一半**的判据。
     *
     * 只查构造器在不在（查它支持哪些格式是异步的，自检屏这一格不等它）：桌面 Chrome 到今天
     * 都没有这个构造器（只有 Chrome on Android / ChromeOS 有），所以在这一端「开相机扫码」就还差
     * 一个**在浏览器里能跑的二维码解码器** —— `:core` 只有 zxing 的**编码器**被搬成了纯 Kotlin
     *（D12 ①），解码那一侧（`QrBitmap.read`）还在 `:app`/JVM 上，`decodeImagePixels` 在 wasm 上恒 null。
     */
    val qrDetector: Boolean get() = webHasQrDetector()

    /**
     * `crypto.subtle` 在不在 —— **本端没有消费方**。
     *
     * 全仓要用密码学的地方只有三处，都在 `:data`（JVM/Android）：WebVPN 的 AES-128-CFB、
     * 体测协议的 AES-CBC + MD5、以及 Android 凭据库（`EncryptedSharedPreferences`）。
     * 前两样在浏览器上**都搬不过来**：WebCrypto 没有 CFB 模式（只有 CBC / CTR / GCM / KW），
     * 也没有 MD5。所以「这一条为 true」只说明浏览器有这个能力，不说明本端用它做了什么事
     *（WebVPN 那条最小可行方案见 §16.11 与契约 §5.7）。
     */
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
            note = "本端**没有消费方**：匹配交友的扫码档传 null（按钮不画）—— 缺的是二维码解码器，不是这个 API。",
        ),
        BrowserCapabilityRow(
            label = "浏览器自带二维码识别器（BarcodeDetector）",
            present = qrDetector,
            note = "只查构造器在不在。桌面 Chrome 到今天没有它 ⇒ 相机扫码还差一个能在浏览器里跑的" +
                "二维码解码器（详见 D12 ③ 的报告）。",
        ),
        BrowserCapabilityRow(
            label = "WebCrypto（crypto.subtle）",
            present = webCrypto,
            note = "本端**没有消费方**：候选的 WebVPN 要的是 AES-128-CFB，而 WebCrypto 里没有 CFB" +
                "（也没有 MD5，体测协议要的就是它）。",
        ),
    )
}

/**
 * 自检屏上的一行：一句人话 + 在不在 + 本端谁在用 / 差什么。
 *
 * @param present 这一条在**此刻这份部署**里在不在
 * @param note 「本端谁在用」或者「差的是什么」——这一列才是这张表存在的理由
 */
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
