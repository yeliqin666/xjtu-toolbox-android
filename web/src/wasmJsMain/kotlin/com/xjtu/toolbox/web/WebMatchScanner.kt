package com.xjtu.toolbox.web

import com.xjtu.toolbox.platform.qrDecodeArgb
import com.xjtu.toolbox.social.MatchScanOutcome
import com.xjtu.toolbox.social.MatchScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/** 浏览器这一端的匹配交友扫码那一档：`MatchScreen` 的 [MatchScanner] 在这个端的实现。 */
internal class WebMatchScanner : MatchScanner {

    /** 逐路取景的 key（`@JsFun` 传不出对象句柄，就用一个 key 换手）。 */
    private var scanSeq = 0

    /** 拉帧用的复用缓冲（按 [WebQrCamera.ANALYSIS_MAX_EDGE] 的平方给足）。 */
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
