package com.xjtu.toolbox.web

import com.xjtu.toolbox.platform.qrDecodeArgb
import com.xjtu.toolbox.social.MatchScanOutcome
import com.xjtu.toolbox.social.MatchScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * 浏览器这一端的匹配交友**扫码那一档**（D12 ③）：`MatchScreen` 的 [MatchScanner] 在这个端的实现。
 *
 * ## 两档各自走到哪
 *
 * | 那一档 | 这一端 | 与 `:app` 的关系 |
 * |---|---|---|
 * | 「从相册选图」[fromImage] | **真能扫**：`<input type=file>` → `createImageBitmap` → canvas → ARGB → `:core` 的解码器（本批搬进 commonMain 的那份纯 Kotlin 链） | 与 Android 同一件事（`ImageDecoder` + `QrBitmap.read`），差异见下 |
 * | 「扫一扫」[scan] | **真开相机**：`getUserMedia` → `<video>` → 每 250ms 拉一帧 → 同一份解码器 | 与 Android 同一件事（`QrScannerView`），覆盖层是 DOM 画的（见 [WebQrCamera] 的 KDoc 第 1 条） |
 *
 * 「没找到码」那一句与 `:app` 的 `AppMatchScreen` **逐字相同**（`图里没找到二维码。换一张完整点、清楚点的截图试试。`）
 * —— 两端要么解出码、要么说同一句话。取消（用户没选图 / 点了取消）什么都不说，与 Android
 *「相册返回 null 直接 return」同一条口径。
 *
 * `scan()` 的循环是**这一端按自己的节拍拉**，不是 JS 推（`IntArray` 不能当 JS 回调的参数类型 ——
 * 实测，见 [WebQrCamera] 的 KDoc）。
 *
 * ## 三处**如实**差异（都不在屏幕上骗人）
 *
 * 1. **「补定位块」那一档没有** —— `:app` 的 `QrFrameDecoder` 在常规解码失败后还能补一个磨掉的定位块
 *    再解一次（图书馆那种被磨掉一角的码）。解码链搬进 `:core` 时**没搬那一手**（要 `FinderCandidates`
 *    那一整套），所以那种码在这一端**扫不出来**（Android 能）。依据在 `:core` 的 `platform/QrDecode.kt` 的 KDoc。
 * 2. **中日文那一档可能解不出来** —— 码里写的是 KANJI / HANZI 段、或 BYTE 段被猜成 Shift_JIS 时，这一端
 *    没有那些码表 ⇒ 解不出来（返回 null ⇒ 落到「没找到码」那句）。依据在 `platform/qr/QrCharset.kt` 的边界表。
 *    匹配交友的分享码是 base64url（纯 ASCII）⇒ 这一屏**不受影响**。
 * 3. **失败态多两条** —— 浏览器还会「这一份部署不是安全上下文」与「没允许用相机」，各自一句人话
 *    （见 [WebQrCamera.cameraFailureReason] / [WebQrCamera.cameraUnavailableReason]）。
 *
 * 哪些验过、哪些没验过，逐条写在 `WebMatchScanTest` 的 KDoc 里（不在这里许愿）。
 */
internal class WebMatchScanner : MatchScanner {

    /** 逐路取景的 key（`@JsFun` 传不出对象句柄，就用一个 key 换手）。 */
    private var scanSeq = 0

    /**
     * 拉帧用的复用缓冲（按 [WebQrCamera.ANALYSIS_MAX_EDGE] 的平方给足：4 fps 下每帧新分配一块
     * 一百多万个 int 的数组没有意义）。宽高变了就换一块更大的。
     */
    private var frameBuffer = IntArray(0)

    override suspend fun scan(): MatchScanOutcome {
        // 「这一份部署根本不给相机」在开门之前就判掉，不让用户对着一块黑屏等（见 WebQrCamera 的 KDoc）
        WebQrCamera.cameraUnavailableReason()?.let { return MatchScanOutcome(reason = it) }

        val key = "match-scan-${scanSeq++}"
        // 取消 / 出错都由 JS 那一侧回过来（覆盖层上的「取消」、getUserMedia 的拒绝、播放失败）
        val ended = CompletableDeferred<MatchScanOutcome>()
        WebQrCamera.startCamera(
            key = key,
            // 生产永远是 null（真叫 getUserMedia）；非空那个分支是给真浏览器测试的，见 WebQrCamera.startCamera
            reuseStreamKey = null,
            onCancelled = { if (!ended.isCompleted) ended.complete(MatchScanOutcome()) },
            onError = { name, message ->
                if (!ended.isCompleted) {
                    ended.complete(MatchScanOutcome(reason = WebQrCamera.cameraFailureReason(name, message)))
                }
            },
        )
        try {
            // 一直抓到「扫到码 / 用户取消 / 出错」为止：与 Android 那边「取景层一直画着」同一条口径
            while (true) {
                ended.takeIf { it.isCompleted }?.let { return it.await() }
                val width = WebQrCamera.frameWidth(key)
                val height = WebQrCamera.frameHeight(key)
                if (width > 0 && height > 0) {
                    val needed = width * height
                    if (frameBuffer.size < needed) frameBuffer = IntArray(needed)
                    // 第一帧之前 videoWidth/videoHeight 还是 0 ⇒ 这一轮什么都不做，等下一拍
                    if (WebQrCamera.grabInto(key, frameBuffer) >= needed) {
                        val code = qrDecodeArgb(frameBuffer, width, height)
                        // 没扫到就继续拉下一帧
                        if (code != null) return MatchScanOutcome(code = code)
                    }
                }
                delay(WebQrCamera.FRAME_INTERVAL_MS.toLong())
            }
        } finally {
            // 拿到码 / 取消 / 出错都要收摊：停流、去掉覆盖层（close 是幂等的）
            WebQrCamera.closeCamera(key)
        }
    }

    override suspend fun fromImage(): MatchScanOutcome {
        val waiter = CompletableDeferred<MatchScanOutcome>()
        WebQrCamera.pickImage { width, height, error ->
            when {
                error != null -> waiter.complete(MatchScanOutcome(reason = error))
                // 用户取消：什么都不说（与 :app「相册返回 null 直接 return」同一条口径）
                width <= 0 || height <= 0 -> waiter.complete(MatchScanOutcome())
                else -> {
                    val needed = width * height
                    val buffer = if (frameBuffer.size >= needed) frameBuffer else IntArray(needed)
                    frameBuffer = buffer
                    val code = if (WebQrCamera.takePickedPixels(buffer) > 0) {
                        qrDecodeArgb(buffer, width, height)
                    } else {
                        null
                    }
                    waiter.complete(
                        if (code == null) {
                            // 与 :app 的 AppMatchScreen 逐字相同的一句
                            MatchScanOutcome(reason = "图里没找到二维码。换一张完整点、清楚点的截图试试。")
                        } else {
                            MatchScanOutcome(code = code)
                        },
                    )
                }
            }
        }
        return waiter.await()
    }
}
