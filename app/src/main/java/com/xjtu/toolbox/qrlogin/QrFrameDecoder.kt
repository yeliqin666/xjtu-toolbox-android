package com.xjtu.toolbox.qrlogin

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.detector.FinderPattern
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 一帧灰度图 → 二维码文本。纯 JVM，不碰 Android，便于拿照片做单元测试。
 *
 * 两步：
 * 1. 常规 zxing 解码。
 * 2. 失败时尝试「补定位块」：zxing 要求三个定位块都完好（1:1:3:1:1 的黑白比例），
 *    图书馆桌贴那种下沿磨掉一截的码，左下角定位块少了底边，第 1 步直接判定"没有码"，
 *    TRY_HARDER、换二值化都救不回来（2026-09-22 用实拍照片测过，zxing / zxing-cpp /
 *    OpenCV 全军覆没）。但另外两个定位块是好的，第三个的位置能从几何上推出来：
 *    在推出的位置画一个标准定位块再解一次。
 *
 * 补错位置不会解出错码：QR 带 Reed-Solomon 纠错，推错的几种假设解码时校验不过，
 * 直接失败，只有补对的那一种能过校验。磨掉的那几行数据也靠纠错兜住。
 */
object QrFrameDecoder {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        // 分享码那种上千字的码是 25 版以上，模块密、容错只有 L 级，不开 TRY_HARDER 基本扫不出来
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * @param luma Y 平面；每行 [rowStride] 字节，前 [width] 个是有效像素。
     *   CameraX 的 Y 平面行尾常有填充，按 width 当行宽读会整幅错位。
     */
    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int, repair: Boolean = true): String? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        val matrix = try {
            HybridBinarizer(source).blackMatrix
        } catch (_: NotFoundException) {
            return null
        }
        decodeMatrix(matrix)?.let { return it }
        return if (repair) decodeWithRepairedFinder(matrix) else null
    }

    /** ARGB 像素（Bitmap.getPixels 的结果）→ 灰度再解。相册选图用。 */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? {
        val luma = ByteArray(width * height)
        for (i in luma.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            luma[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        return decode(luma, width, width, height)
    }

    private fun decodeMatrix(matrix: BitMatrix): String? = try {
        QRCodeReader().decode(BinaryBitmap(MatrixBinarizer(matrix)), hints).text
    } catch (_: Exception) {
        null
    }

    private fun decodeWithRepairedFinder(matrix: BitMatrix): String? {
        val candidates = com.google.zxing.qrcode.detector.FinderCandidates.of(matrix, hints)
        if (candidates.size < 2) return null
        // 两两配对：模块大小相近、隔得够远的才可能是同一个码的两个定位块
        val sorted = candidates.sortedByDescending { it.count }.take(MAX_CANDIDATES)
        var tries = 0
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val a = sorted[i]
            val b = sorted[j]
            val m = (a.estimatedModuleSize + b.estimatedModuleSize) / 2f
            if (abs(a.estimatedModuleSize - b.estimatedModuleSize) > m * 0.4f) continue
            val dist = hypot(b.x - a.x, b.y - a.y)
            // 最小的 21x21 码，两个定位块中心相距 14 个模块
            if (dist < m * 12f) continue
            for (third in thirdCorners(a, b)) {
                if (tries++ >= MAX_TRIES) return null
                val patched = matrix.clone()
                val ok = paintFinder(patched, third.first, third.second, m, a, b)
                if (!ok) continue
                decodeMatrix(patched)?.let { return it }
            }
        }
        return null
    }

    /**
     * 已知两个定位块 a、b，第三个可能在哪。
     * - a、b 相邻（同一条边的两端）：第三个在 a 或 b 旁边、垂直于 ab 的两侧，共 4 种；
     * - a、b 是对角：第三个在 ab 中点两侧各半条对角线处，共 2 种。
     */
    internal fun thirdCorners(a: FinderPattern, b: FinderPattern): List<Pair<Float, Float>> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val out = mutableListOf<Pair<Float, Float>>()
        // 相邻：旋转 ±90°
        out += (a.x - dy) to (a.y + dx)
        out += (a.x + dy) to (a.y - dx)
        out += (b.x - dy) to (b.y + dx)
        out += (b.x + dy) to (b.y - dx)
        // 对角
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        out += (mx - dy / 2f) to (my + dx / 2f)
        out += (mx + dy / 2f) to (my - dx / 2f)
        return out
    }

    /**
     * 在 ([cx], [cy]) 画一个 7x7 定位块外加一圈白色分隔带，朝向跟 a→b（或其垂直方向）对齐。
     * 落点出了画面就返回 false。
     */
    private fun paintFinder(matrix: BitMatrix, cx: Float, cy: Float, module: Float, a: FinderPattern, b: FinderPattern): Boolean {
        val half = 4.5f * module
        if (cx - half < 0 || cy - half < 0 || cx + half >= matrix.width || cy + half >= matrix.height) return false
        val len = hypot(b.x - a.x, b.y - a.y)
        // 相邻和对角两种情况下，码的边分别平行于 ab 或与之成 45°。
        // 定位块是正方形，旋转 90° 等价，所以只需判断用 ab 方向还是 ab 转 45° 方向：
        // 取与 ab 的夹角更接近 0°/90° 的那一套轴。对角时 ab 本身就是 45°，轴要转回来。
        var ux = (b.x - a.x) / len
        var uy = (b.y - a.y) / len
        if (isDiagonal(a, b, cx, cy)) {
            val rx = (ux - uy) / SQRT2
            val ry = (ux + uy) / SQRT2
            ux = rx; uy = ry
        }
        val vx = -uy
        val vy = ux
        // 推算点只是理想位置：实拍有透视，破损那个块往往偏出一个模块左右，
        // 直接画会和残留的真块错位。它的中心实心块和大半圈外框通常还在，就近找最像的位置。
        val (fx, fy) = refineCenter(matrix, cx, cy, module, ux, uy, vx, vy)
        return paintAt(matrix, fx, fy, module, ux, uy, vx, vy)
    }

    /**
     * 在 ([cx], [cy]) 周围 ±3 个模块内，找与定位块模板（中心 3x3 黑、一圈白、一圈黑）
     * 最吻合的中心。按模块的 1/3 采样，逐像素挪位置，只在常规解码失败时才跑，开销可忽略。
     */
    private fun refineCenter(
        matrix: BitMatrix, cx: Float, cy: Float, module: Float,
        ux: Float, uy: Float, vx: Float, vy: Float,
    ): Pair<Float, Float> {
        val reach = (3f * module).roundToInt()
        val stepPx = max(1, (module / 4f).roundToInt())
        var best = Float.NEGATIVE_INFINITY
        var bestX = cx
        var bestY = cy
        var oy = -reach
        while (oy <= reach) {
            var ox = -reach
            while (ox <= reach) {
                val px = cx + ox
                val py = cy + oy
                var score = 0f
                // 模板 7x7 模块，每模块 3x3 个采样点
                for (si in -10..10) for (sj in -10..10) {
                    val du = si / 3f
                    val dv = sj / 3f
                    val ring = max(abs(du), abs(dv))
                    val want = when {
                        ring < 1.5f -> true
                        ring < 2.5f -> false
                        ring < 3.5f -> true
                        else -> continue
                    }
                    val x = (px + (du * ux + dv * vx) * module).toInt()
                    val y = (py + (du * uy + dv * vy) * module).toInt()
                    if (x < 0 || y < 0 || x >= matrix.width || y >= matrix.height) continue
                    if (matrix.get(x, y) == want) score += 1f
                }
                // 同分时偏向离推算点近的
                score -= (abs(ox) + abs(oy)) / (module * 8f)
                if (score > best) {
                    best = score
                    bestX = px
                    bestY = py
                }
                ox += stepPx
            }
            oy += stepPx
        }
        return bestX to bestY
    }

    private fun paintAt(
        matrix: BitMatrix, cx: Float, cy: Float, module: Float,
        ux: Float, uy: Float, vx: Float, vy: Float,
    ): Boolean {
        val half = 4.5f * module
        if (cx - half < 0 || cy - half < 0 || cx + half >= matrix.width || cy + half >= matrix.height) return false
        val r = (half * SQRT2).toInt() + 1
        for (y in (cy - r).toInt()..(cy + r).toInt()) {
            if (y < 0 || y >= matrix.height) continue
            for (x in (cx - r).toInt()..(cx + r).toInt()) {
                if (x < 0 || x >= matrix.width) continue
                val px = x + 0.5f - cx
                val py = y + 0.5f - cy
                val u = abs(px * ux + py * uy) / module
                val v = abs(px * vx + py * vy) / module
                val ring = max(u, v)
                when {
                    ring < 1.5f -> matrix.set(x, y)
                    ring < 2.5f -> matrix.unset(x, y)
                    ring < 3.5f -> matrix.set(x, y)
                    ring < 4.5f -> matrix.unset(x, y)
                }
            }
        }
        return true
    }

    /** 第三点在 ab 中垂线上（离中点半条 ab）时，ab 是对角线。 */
    private fun isDiagonal(a: FinderPattern, b: FinderPattern, cx: Float, cy: Float): Boolean {
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        val half = hypot(b.x - a.x, b.y - a.y) / 2f
        return abs(hypot(cx - mx, cy - my) - half) < half * 0.05f &&
            abs(hypot(cx - a.x, cy - a.y) - hypot(cx - b.x, cy - b.y)) < half * 0.05f
    }

    /** 把已经二值化好的矩阵原样交给 zxing，不再二值化一遍。 */
    private class MatrixBinarizer(private val matrix: BitMatrix) :
        com.google.zxing.Binarizer(DummySource(matrix.width, matrix.height)) {
        override fun getBlackRow(y: Int, row: com.google.zxing.common.BitArray?): com.google.zxing.common.BitArray =
            matrix.getRow(y, row)
        override fun getBlackMatrix(): BitMatrix = matrix
        override fun createBinarizer(source: com.google.zxing.LuminanceSource) = this
    }

    private class DummySource(w: Int, h: Int) : com.google.zxing.LuminanceSource(w, h) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray = row ?: ByteArray(width)
        override fun getMatrix(): ByteArray = ByteArray(width * height)
    }

    private const val SQRT2 = 1.4142135f
    private const val MAX_CANDIDATES = 6
    /** 每帧补定位块最多试这么多次：6 个候选两两配对、每对 6 种落点，理论上 90 次，实际很少过 10 次。 */
    private const val MAX_TRIES = 36
}
