@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import com.xjtu.toolbox.platform.CodeMatrix
import com.xjtu.toolbox.platform.qrCodeModules
import com.xjtu.toolbox.platform.qrDecodeArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 匹配交友那一屏「扫码」这一档在真浏览器里的验收。 */
class WebMatchScanTest {

    @Test
    fun `画布上的二维码经那一段 ARGB 换算回到 Kotlin 之后能解出原文`() {
        val text = "XJTU-WEBSCAN-0001-fake"
        val matrix = assertNotNull(qrCodeModules(text), "这一条夹具应当编得出二维码")
        // 先把**生产那一段**换算与包帧装上（`WebQrCamera.startCamera` / `pickImage` 自己也会装；
        // 这条用例不走它们）
        installArgbHelper()
        testCanvasPut(KEY, bitsOf(matrix), matrix.width, matrix.height, CELL_PIXELS, QUIET_ZONE_MODULES)

        val frame = assertNotNull(testFrame(KEY), "JS 那一侧应当留下一帧")
        val width = (matrix.width + 2 * QUIET_ZONE_MODULES) * CELL_PIXELS
        val height = (matrix.height + 2 * QUIET_ZONE_MODULES) * CELL_PIXELS
        assertEquals(width * height, frame.length, "一帧的像素数应当是宽 × 高")

        // 与 `WebQrCamera.grabInto` 同一段读法：逐个读进 Kotlin 的 IntArray
        val pixels = IntArray(frame.length)
        for (i in 0 until frame.length) pixels[i] = frame.valueAt(i)

        assertEquals(text, qrDecodeArgb(pixels, width, height), "画到 canvas 上再读回来之后应当解出原文")
    }

    @Test
    fun `相机那三条失败态各自一句人话`() {
        assertEquals(
            "得先允许用一下相机（地址栏那把锁里能改），改完再点一次。",
            WebQrCamera.cameraFailureReason("NotAllowedError", ""),
        )
        assertEquals(
            "得先允许用一下相机（地址栏那把锁里能改），改完再点一次。",
            WebQrCamera.cameraFailureReason("SecurityError", ""),
        )
        assertEquals(
            "没找到能用的相机（或它被别的程序占着）。用「从相册选图」也一样。",
            WebQrCamera.cameraFailureReason("NotFoundError", ""),
        )
        assertEquals(
            "没找到能用的相机（或它被别的程序占着）。用「从相册选图」也一样。",
            WebQrCamera.cameraFailureReason("NotReadableError", ""),
        )
        // 认不出的名字：把 message 原样带出来（不为一句认不出的错编一句假话）
        assertEquals("打不开相机：boom", WebQrCamera.cameraFailureReason("WhateverError", "boom"))
        assertEquals("打不开相机：WeirdError", WebQrCamera.cameraFailureReason("WeirdError", "  "))
    }

    @Test
    fun `这一份部署能开相机（localhost 上），而测试给的流不在时那条错路真跑到`() {
        // karma 跑在 http://localhost:<port> ⇒ 安全上下文，mediaDevices 也在
        assertTrue(WebQrCamera.secureContext(), "localhost 应当是安全上下文")
        assertTrue(WebQrCamera.hasGetUserMedia(), "localhost 上 navigator.mediaDevices.getUserMedia 应当在")
        assertNull(WebQrCamera.cameraUnavailableReason(), "这一份部署不该判成「不给相机」")

        // 「复用一条不存在的流」那条错路：JS 同步回调回来（顺便钉住 onError 的形态是 name + message）
        var name = ""
        var message = ""
        WebQrCamera.startCamera(
            key = "test-missing-stream",
            reuseStreamKey = "no-such-stream",
            onCancelled = {},
            onError = { n, m -> name = n; message = m },
        )
        WebQrCamera.closeCamera("test-missing-stream")
        assertEquals("NotFoundError", name, "流不在就该报 NotFoundError")
        assertTrue(message.contains("no-such-stream"), "错话里要带上那个 key，好让人查：$message")
        // 收过摊之后画面上不该再留覆盖层（这条同时钉住 close 是幂等的）
        assertNull(testFrame("test-missing-stream"), "没开起来的那一路不该留下帧")
    }

    /** [CodeMatrix] → 一行 `'0'` / `'1'`（行优先）—— 过 JS 门只需要字符串。 */
    private fun bitsOf(matrix: CodeMatrix): String = buildString(matrix.width * matrix.height) {
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) append(if (matrix[x, y]) '1' else '0')
        }
    }

    private companion object {
        /** 这条用例里那块 canvas 的 key。 */
        const val KEY = "test-canvas"

        /** 每个模块几个像素。 */
        const val CELL_PIXELS = 4

        /** 静默区留几个模块（扫码器要求的下限）。 */
        const val QUIET_ZONE_MODULES = 4
    }
}

/** 把一行 `'0'` / `'1'` 摆成一块 canvas（「相机那一帧」的替身），再用生产那一段换算与包帧（`globalThis.__xjtuToolboxArgbFromCanvas` / `__xjtuToolboxFrameOf`）留下一帧。 */
@JsFun(
    """
(key, matrix, matrixWidth, matrixHeight, cell, quiet) => {
  const width = (matrixWidth + 2 * quiet) * cell;
  const height = (matrixHeight + 2 * quiet) * cell;
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const img = ctx.createImageData(width, height);
  for (let y = 0; y < height; y++) {
    const my = Math.floor(y / cell) - quiet;
    for (let x = 0; x < width; x++) {
      const mx = Math.floor(x / cell) - quiet;
      const inside = my >= 0 && my < matrixHeight && mx >= 0 && mx < matrixWidth;
      const black = inside && matrix.charAt(my * matrixWidth + mx) === '1';
      const j = (y * width + x) * 4;
      const v = black ? 0 : 255;
      img.data[j] = v;
      img.data[j + 1] = v;
      img.data[j + 2] = v;
      img.data[j + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  const frames = globalThis.__xjtuToolboxTestFrames || (globalThis.__xjtuToolboxTestFrames = {});
  frames[key] = globalThis.__xjtuToolboxFrameOf(globalThis.__xjtuToolboxArgbFromCanvas(canvas));
}
""",
)
private external fun testCanvasPut(
    key: String,
    matrix: String,
    matrixWidth: Int,
    matrixHeight: Int,
    cell: Int,
    quiet: Int,
)

/** 取回 [testCanvasPut] 留下的那一帧（没有就 null）。 */
@JsFun("(key) => ((globalThis.__xjtuToolboxTestFrames || {})[key]) || null")
private external fun testFrame(key: String): JsPixelFrame?
