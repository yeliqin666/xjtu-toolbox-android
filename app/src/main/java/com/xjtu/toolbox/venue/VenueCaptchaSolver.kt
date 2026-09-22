package com.xjtu.toolbox.venue

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "VenueCaptchaSolver"
private const val SERVER_IMAGE_WIDTH = 260

/**
 * 自动识别的结果。除了最终要提交的轨迹，也保留目标位置和置信度，方便日志和
 * 后续调参；低置信度结果不会交给预约接口，而是回退到手动滑块。
 */
data class CaptchaSolveResult(
    val sliderResult: SliderResult,
    /** 服务端 260 坐标系中的滑动位移（不是缺口的绝对 x）。 */
    val targetX: Int,
    val confidence: Double
)

/**
 * 场馆滑块验证码自动识别器。
 *
 * 验证码的滑块 PNG 带有透明通道，缺口边缘会在背景图上形成明显的梯度。这里
 * 先用 alpha 通道定位拼图块，再按 PR #54 的方式对拼图块/背景做二值 Sobel
 * 边缘归一化相关匹配，避免只取「边缘总和最大」被背景中的随机纹理误导。
 */
object VenueCaptchaSolver {
    /** 默认最低峰值间隔；不满足时交给用户手动滑动。 */
    const val DEFAULT_MIN_CONFIDENCE = 0.04

    /**
     * 尝试自动识别并生成服务端轨迹。
     *
     * 该函数只做 CPU 图像处理，不发起网络请求，调用方应在 Default dispatcher 执行。
     * 返回 null 表示图片格式不支持、匹配峰值不明确或置信度不足。
     */
    fun solve(
        data: VenueApi.CaptchaData,
        minConfidence: Double = DEFAULT_MIN_CONFIDENCE
    ): CaptchaSolveResult? {
        val background = decodeBase64Image(data.backgroundImage)
        val slider = decodeBase64Image(data.sliderImage)
        if (background == null || slider == null) {
            Log.w(TAG, "decode captcha image failed")
            return null
        }

        return try {
            solveBitmaps(data, background, slider, minConfidence)
        } catch (e: Exception) {
            Log.e(TAG, "solve captcha failed", e)
            null
        } finally {
            // BitmapFactory 返回的位图不再被 UI 使用；尽早释放大图，避免连续换图时
            // 在低内存设备上累积 native heap。recycle 失败不影响回退到手动滑块。
            runCatching { if (!background.isRecycled) background.recycle() }
            runCatching { if (!slider.isRecycled) slider.recycle() }
        }
    }

    private fun solveBitmaps(
        data: VenueApi.CaptchaData,
        background: Bitmap,
        slider: Bitmap,
        minConfidence: Double
    ): CaptchaSolveResult? {
        val bw = background.width
        val bh = background.height
        val sw = slider.width
        val sh = slider.height
        if (bw < 8 || bh < 8 || sw < 2 || sh < 2) {
            Log.w(TAG, "captcha dimensions too small: bg=${bw}x$bh slider=${sw}x$sh")
            return null
        }

        val bgPixels = IntArray(bw * bh)
        background.getPixels(bgPixels, 0, bw, 0, 0, bw, bh)
        val sliderPixels = IntArray(sw * sh)
        slider.getPixels(sliderPixels, 0, sw, 0, 0, sw, sh)

        // ── 1. 透明轮廓 ────────────────────────────────────────────────
        var opaqueCount = 0
        var left = sw
        var top = sh
        var right = -1
        var bottom = -1
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val alpha = sliderPixels[y * sw + x] ushr 24
                // 与 PR #54 保持一致：只把 alpha > 50 的像素视为拼图块。
                if (alpha > 50) {
                    opaqueCount++
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
            }
        }

        // 全不透明通常意味着服务端没有返回滑块 alpha（或返回了错误图片），此时
        // 不能可靠地推断缺口，必须让用户手动处理。
        if (opaqueCount < 8 || opaqueCount > sw * sh * 0.98 || right < left || bottom < top) {
            Log.w(TAG, "invalid slider alpha mask: opaque=$opaqueCount/${sw * sh}")
            return null
        }

        val templateWidth = right - left + 1
        val templateHeight = bottom - top + 1
        if (templateWidth < 3 || templateHeight < 3) return null

        // ── 2. 与 PR #54 一致的边缘模板匹配 ─────────────────────────────
        // 服务端把缺口位置对应的拼图块单独返回。对拼图块本身和背景图分别做
        // Sobel，再在背景同高区域上做 NCC；这比只把 alpha 轮廓当模板更稳。
        val bgGray = FloatArray(bw * bh)
        for (i in bgPixels.indices) {
            val p = bgPixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            bgGray[i] = (r + g + b) / 3f
        }
        val pieceGray = FloatArray(templateWidth * templateHeight)
        for (py in 0 until templateHeight) {
            for (px in 0 until templateWidth) {
                val p = sliderPixels[(top + py) * sw + left + px]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                pieceGray[py * templateWidth + px] = (r + g + b) / 3f
            }
        }

        val bgEdge = sobelMagnitude(bgGray, bw, bh)
        val pieceEdge = sobelMagnitude(pieceGray, templateWidth, templateHeight)
        val bgThreshold = edgeThreshold(bgEdge)
        val pieceThreshold = edgeThreshold(pieceEdge)
        val bgBinary = FloatArray(bgEdge.size) { if (bgEdge[it] > bgThreshold) 1f else 0f }
        val pieceBinary = FloatArray(pieceEdge.size) { if (pieceEdge[it] > pieceThreshold) 1f else 0f }

        val templateCount = pieceBinary.size
        val templateMean = pieceBinary.average()
        val centeredTemplate = FloatArray(templateCount) { pieceBinary[it] - templateMean.toFloat() }
        val templateNorm = sqrt(centeredTemplate.sumOf { (it * it).toDouble() })
        if (templateNorm < 1e-6) {
            Log.w(TAG, "slider edge template has no variance")
            return null
        }

        // PR 的 best_x 是「缺口模板左边缘」。滑块实际从 x=0 开始，因而提交的
        // 位移必须减去 alpha bbox 的 left；同时限制在 UI 可拖动的最大距离内。
        val imageEnd = bw - templateWidth
        val maxDisplacement = (bw - sw).coerceAtLeast(0)
        // 滑块只能向右移动，缺口左边缘不可能落在初始 alpha bbox 的左侧；
        // 排除这段候选也能避免背景中的强纹理把结果吸到负位移。
        val searchStart = left.coerceAtLeast(0)
        val searchEnd = min(imageEnd, left + maxDisplacement)
        if (searchEnd < searchStart) return null
        val scores = DoubleArray(searchEnd + 1) { Double.NEGATIVE_INFINITY }
        for (candidate in searchStart..searchEnd) {
            var sum = 0.0
            for (py in 0 until templateHeight) {
                val row = (top + py) * bw + candidate
                for (px in 0 until templateWidth) sum += bgBinary[row + px]
            }
            val mean = sum / templateCount
            var dot = 0.0
            var variance = 0.0
            var index = 0
            for (py in 0 until templateHeight) {
                val row = (top + py) * bw + candidate
                for (px in 0 until templateWidth) {
                    val centered = bgBinary[row + px] - mean
                    dot += centered * centeredTemplate[index++]
                    variance += centered * centered
                }
            }
            scores[candidate] = dot / (templateNorm * (sqrt(variance) + 1e-6))
        }

        var bestX = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (x in scores.indices) {
            if (scores[x] > bestScore) {
                bestScore = scores[x]
                bestX = x
            }
        }
        if (bestX < 0 || !bestScore.isFinite()) return null

        // 相邻像素本来就会有相似分数，第二峰排除一个模板宽度的邻域，
        // 否则 confidence 几乎总被相邻点压成 0，自动识别会形同关闭。
        val exclusionRadius = max(4, templateWidth / 3)
        var secondScore = Double.NEGATIVE_INFINITY
        for (x in scores.indices) {
            if (abs(x - bestX) > exclusionRadius && scores[x] > secondScore) {
                secondScore = scores[x]
            }
        }
        if (!secondScore.isFinite()) secondScore = bestScore - 1.0
        val peakMargin = ((bestScore - secondScore) / (abs(bestScore) + 1e-3))
            .coerceIn(0.0, 1.0)
        val confidence = (peakMargin * 0.65 + ((bestScore + 1.0) / 2.0).coerceIn(0.0, 1.0) * 0.35)
            .coerceIn(0.0, 1.0)
        Log.d(
            TAG,
            "match: gap=$bestX, pieceLeft=$left, move=${bestX - left}, " +
                "ncc=${"%.3f".format(bestScore)}, second=${"%.3f".format(secondScore)}, " +
                "confidence=${"%.3f".format(confidence)}"
        )
        // 绝对分数下限防止「所有候选都很差但仍有一个略高」的情况。
        if (bestScore < 0.08 || confidence < minConfidence) return null

        val sourceBgWidth = bw
        val sourceSliderHeight = data.sliderHeight.takeIf { it > 0 } ?: sh
        val moveX = (bestX - left).coerceAtLeast(0)
        val targetX = (moveX * SERVER_IMAGE_WIDTH.toDouble() / sourceBgWidth)
            .roundToInt()
            .coerceIn(0, (maxDisplacement * SERVER_IMAGE_WIDTH.toDouble() / sourceBgWidth).roundToInt())
        if (targetX <= 0) return null

        val track = generateHumanLikeTrack(targetX, duration = (1000L..1400L).random())
        val end = Instant.now()
        val elapsed = (track.lastOrNull()?.t ?: 0L) - (track.firstOrNull()?.t ?: 0L)
        val start = end.minusMillis(elapsed.coerceAtLeast(0L))
        val fmt = DateTimeFormatter.ISO_INSTANT
        val result = SliderResult(
            bgImageWidth = SERVER_IMAGE_WIDTH,
            bgImageHeight = 0,
            sliderImageWidth = 0,
            sliderImageHeight = (sourceSliderHeight * SERVER_IMAGE_WIDTH.toDouble() / sourceBgWidth)
                .roundToInt(),
            startSlidingTime = fmt.format(start),
            entSlidingTime = fmt.format(end),
            trackList = track
        )
        return CaptchaSolveResult(result, targetX, confidence)
    }

    /** Sobel 梯度幅值；边缘使用复制填充，行为与 PR #54 的 numpy 实现一致。 */
    private fun sobelMagnitude(gray: FloatArray, width: Int, height: Int): FloatArray {
        val result = FloatArray(width * height)
        fun at(x: Int, y: Int): Float = gray[
            y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)
        ]
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gx = at(x + 1, y - 1) - at(x - 1, y - 1) +
                    2f * (at(x + 1, y) - at(x - 1, y)) +
                    at(x + 1, y + 1) - at(x - 1, y + 1)
                val gy = at(x - 1, y - 1) + 2f * at(x, y - 1) + at(x + 1, y - 1) -
                    at(x - 1, y + 1) - 2f * at(x, y + 1) - at(x + 1, y + 1)
                result[y * width + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return result
    }

    /** PR #54 使用 mean + 0.6 * std 把 Sobel 图二值化。 */
    private fun edgeThreshold(edge: FloatArray): Float {
        if (edge.isEmpty()) return Float.POSITIVE_INFINITY
        var sum = 0.0
        var sumSquares = 0.0
        for (value in edge) {
            sum += value
            sumSquares += value * value
        }
        val mean = sum / edge.size
        val variance = (sumSquares / edge.size - mean * mean).coerceAtLeast(0.0)
        return (mean + sqrt(variance) * 0.6).toFloat()
    }

    /** 生成带有停顿、加减速和轻微纵向抖动的轨迹。 */
    fun generateHumanLikeTrack(targetX: Int, duration: Long = 1200L): List<TrackPoint> {
        val target = targetX.coerceAtLeast(1)
        val baseDelay = (800L..1500L).random()
        val totalDuration = duration.coerceIn(700L, 1800L)
        val steps = max(28, (totalDuration / 16L).toInt())
        val points = ArrayList<TrackPoint>(steps + 2)
        points += TrackPoint(0, 0, "down", baseDelay)

        var lastX = 0
        var timestamp = baseDelay + (100L..200L).random()
        for (i in 1..steps) {
            val progress = i.toDouble() / steps
            // easeOutCubic：先快后慢，末段仍保留若干细粒度 move 点。
            val eased = 1.0 - (1.0 - progress) * (1.0 - progress) * (1.0 - progress)
            val jitter = if (i == steps) 0 else (-1..1).random()
            val x = (target * eased).roundToInt().plus(jitter).coerceIn(lastX, target)
            lastX = x
            val y = if (i == steps) 0 else (-2..2).random()
            points += TrackPoint(x, y, "move", timestamp)
            timestamp += (12L..20L).random()
        }
        points += TrackPoint(target, (-2..0).random(), "up", timestamp + (400L..600L).random())
        return points
    }

    private fun decodeBase64Image(dataUri: String): Bitmap? {
        return try {
            val encoded = dataUri.substringAfter("base64,", dataUri)
                .replace(Regex("\\s"), "")
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
                .getOrElse { Base64.decode(encoded, Base64.URL_SAFE) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "invalid base64 image", e)
            null
        }
    }
}

fun generateHumanLikeTrack(targetX: Int, duration: Long = 1200L): List<TrackPoint> =
    VenueCaptchaSolver.generateHumanLikeTrack(targetX, duration)
