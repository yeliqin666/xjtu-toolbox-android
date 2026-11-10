package com.xjtu.toolbox.ui.glass.coui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Mesh
import android.graphics.MeshSpecification
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 普通投影的 caster, 与组件材质的裁剪轮廓分开。 */
enum class CouiShadowOutline { Oval, SmoothCapsule, RoundRect }

/** 原 Skia 网格、A8 表与厂商 inverse clip 的公开 API 承载; 每个主体仅保留当前两路资源。 */
@RequiresApi(34)
class CouiShadowRenderer(surfaceCount: Int = 1) {
    private val specifications = Array(4) { index ->
        MeshSpecification.make(arrayOf(
            MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT2, 0, "position"),
            MeshSpecification.Attribute(MeshSpecification.TYPE_FLOAT3, 8, "shadow")), 20,
            arrayOf(MeshSpecification.Varying(MeshSpecification.TYPE_FLOAT3, "shadow")),
            ShadowVertexShader, shadowFragmentShader(fast = index < 2, oval = index % 2 == 0))
    }
    private val lut = Bitmap.createBitmap(128, 1, Bitmap.Config.ALPHA_8).apply {
        val bytes = ByteBuffer.allocate(rowBytes)
        repeat(128) { i ->
            val d = 1f - i / 127f
            bytes.put(floor((exp(-4f * d * d) - 0.018f) * 255f + 0.5f).toInt().toByte())
        }
        bytes.rewind()
        copyPixelsFromBuffer(bytes)
    }
    private val lutShader = BitmapShader(lut, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val clipShaders = arrayOf(RuntimeShader(shadowClipShader(oval = true, image = true)),
        RuntimeShader(shadowClipShader(oval = false, image = true)))
    private val materialClipSource = shadowClipShader(oval = false, image = false)
    private val slots = Array(surfaceCount) { arrayOfNulls<ProjectionDraw>(2) }

    init {
        // 创建时检查裁剪片段, 不把失败延迟到首次绘制。
        RuntimeShader(materialClipSource)
    }

    /** 长度均为像素; 网格包含轴向缩放, Canvas 只承接平移, alpha 不再由外层重复相乘。 */
    fun draw(canvas: Canvas, slot: Int, width: Float, height: Float, sx: Float, sy: Float,
        windowX: Float, windowY: Float, alpha: Float, color: Int, elevation: Float,
        lightY: Float, lightZ: Float, lightRadius: Float, outline: CouiShadowOutline,
        radius: Float = min(width, height) * 0.5f, clipping: Boolean = true) {
        if (alpha <= 0f || width <= 0f || height <= 0f || sx <= 0f || sy <= 0f || elevation <= 0f) return
        require(!clipping || outline != CouiShadowOutline.RoundRect) { "普通圆角投影尚未提供 inverse clip" }
        val fast = isFastShadowGeometry(width, height, sx, sy, outline == CouiShadowOutline.Oval)
        for (index in 0..1) {
            // override spot 不乘 renderer 的全局 spot alpha; ambient 先转原厂 JNI 字节值。
            val multiplier = if (index == 0) 9f / 255f else 1f
            val opacity = ((alpha * multiplier) * Color.alpha(color)).toInt().coerceIn(0, 255)
            if (opacity == 0) continue
            val argb = (color and 0xFFFFFF) or (opacity shl 24)
            // 光源随主体平移; ambient 与 fast spot 用局部原点复用资源, generic spot 保留量化前坐标。
            val localOrigin = index == 0 || fast
            val key = ShadowKey(width, height, sx, sy,
                if (localOrigin) 0f else windowX, if (localOrigin) 0f else windowY, alpha < 1f,
                elevation, lightY, lightZ, lightRadius, outline,
                radius.coerceIn(0f, min(width, height) * 0.5f), clipping)
            val draw = slots[slot][index]?.takeIf { it.key == key } ?: makeDraw(key, index == 1).also {
                slots[slot][index] = it
            }
            updateColor(draw, argb)
            draw.node?.let { canvas.drawRenderNode(it) } ?: draw.mesh?.let {
                canvas.save()
                canvas.translate(draw.projection.offset.x, draw.projection.offset.y)
                // 裁剪独立使用局部坐标, Mesh 返回的 LUT 坐标只交给 Paint。
                if (Build.VERSION.SDK_INT >= 35) draw.clipShader?.let { shader -> canvas.clipShader(shader) }
                canvas.drawMesh(it, BlendMode.MODULATE, draw.paint)
                canvas.restore()
            }
        }
    }

    private fun makeDraw(key: ShadowKey, spot: Boolean): ProjectionDraw {
        val oval = key.outline == CouiShadowOutline.Oval
        val projection = floatingShadowGeometry(key.width, key.height, key.sx, key.sy, key.x, key.y,
            key.elevation, key.lightY, key.lightZ, key.lightRadius, key.transparent, oval, spot,
            key.radius, key.outline == CouiShadowOutline.RoundRect)
        val rect = RectF(-projection.offset.x, -projection.offset.y,
            key.width * key.sx - projection.offset.x, key.height * key.sy - projection.offset.y)
        val data = projection.data
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        if (data == null) return blurDraw(key, projection, oval, spot, paint)
        if (data.indices.isEmpty()) return ProjectionDraw(key, projection, paint)
        val bounds = RectF(data.vertices[0], data.vertices[1], data.vertices[0], data.vertices[1])
        for (i in data.vertices.indices step 5) {
            bounds.left = min(bounds.left, data.vertices[i])
            bounds.top = min(bounds.top, data.vertices[i + 1])
            bounds.right = max(bounds.right, data.vertices[i])
            bounds.bottom = max(bounds.bottom, data.vertices[i + 1])
        }
        val spec = specifications[(if (projection.fast) 0 else 2) + if (oval && key.clipping) 0 else 1]
        val mesh = Mesh(spec, Mesh.TRIANGLES, FloatBuffer.wrap(data.vertices), data.vertices.size / 5,
            ShortBuffer.wrap(data.indices), bounds)
        if (oval && key.clipping) mesh.setFloatUniform("u_rect", rect.left, rect.top, rect.right, rect.bottom)
        if (!oval && key.clipping && projection.fast && Build.VERSION.SDK_INT < 35) {
            // Android 14 没有公开的 Shader 裁剪入口, 保留每路独立滤镜。
            val node = filteredNode(bounds, rect, oval, 0f, key.clipping)
            paint.shader = lutShader
            return ProjectionDraw(key, projection, paint, mesh = mesh, node = node)
        }
        val capsuleClip = if (!oval && key.clipping) RuntimeShader(materialClipSource).apply {
            setFloatUniform("u_rect", rect.left, rect.top, rect.right, rect.bottom)
        } else null
        paint.shader = if (projection.fast) lutShader else capsuleClip
        return ProjectionDraw(key, projection, paint, mesh = mesh,
            clipShader = capsuleClip.takeIf { projection.fast })
    }

    private fun blurDraw(key: ShadowKey, projection: ShadowProjection, oval: Boolean,
        spot: Boolean, paint: Paint): ProjectionDraw {
        val r = key.radius
        val rect = RectF(0f, 0f, key.width * key.sx, key.height * key.sy)
        val source = RectF(rect)
        if (spot) {
            val ratio = (key.elevation / (key.lightZ - key.elevation)).coerceIn(0f, 0.95f)
            source.left = -ratio * key.sx * key.width * 0.5f
            source.top = -ratio * key.sy * key.lightY
            source.right = source.left + rect.width() * projection.scale
            source.bottom = source.top + rect.height() * projection.scale
        }
        val path = Path().apply {
            if (oval) addOval(source, Path.Direction.CW)
            else addRoundRect(source, r * key.sx * projection.scale, r * key.sy * projection.scale,
                if (key.outline == CouiShadowOutline.RoundRect) Path.Direction.CW else Path.Direction.CCW)
        }
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = max(projection.stroke, 0f)
        val sigma = if (projection.blur > 0f) projection.blur * 0.57735f + 0.5f else 0f
        val bounds = RectF(source).apply { inset(-ceil(3f * sigma + paint.strokeWidth + 1f), -ceil(3f * sigma + paint.strokeWidth + 1f)) }
        val node = filteredNode(bounds, rect, oval, sigma, key.clipping)
        return ProjectionDraw(key, projection, paint, node = node, path = path)
    }

    private fun updateColor(draw: ProjectionDraw, color: Int) {
        // 系统可在后台丢弃 DisplayList, 同色不代表录制内容仍然有效。
        if (draw.color == color && draw.node?.hasDisplayList() != false) return
        draw.color = color
        draw.mesh?.let { mesh ->
            val a = Color.alpha(color) / 255f
            fun channel(value: Int): Float {
                val premultiplied = value * a
                return if (draw.projection.fast) floor(premultiplied + 0.5f) / 255f else premultiplied / 255f
            }
            mesh.setFloatUniform("u_color", channel(Color.red(color)), channel(Color.green(color)),
                channel(Color.blue(color)), a)
        }
        draw.node?.let { node ->
            // uniform 和颜色录制为快照; 仅重录命令, 不因淡入淡出重建网格。
            val recording = node.beginRecording()
            recording.translate(-node.left.toFloat(), -node.top.toFloat())
            draw.mesh?.let { recording.drawMesh(it, BlendMode.MODULATE, draw.paint) }
                ?: draw.path?.let { draw.paint.color = color; recording.drawPath(it, draw.paint) }
            node.endRecording()
        }
    }

    private fun filteredNode(bounds: RectF, caster: RectF, oval: Boolean, sigma: Float, clipping: Boolean): RenderNode {
        val left = floor(bounds.left).toInt()
        val top = floor(bounds.top).toInt()
        val right = ceil(bounds.right).toInt()
        val bottom = ceil(bounds.bottom).toInt()
        // RenderEffect 保存 uniform 快照, 两路投影可复用同一 Shader 程序。
        val clip = if (clipping) clipShaders[if (oval) 0 else 1].apply {
            setFloatUniform("u_rect", caster.left - left, caster.top - top, caster.right - left, caster.bottom - top)
        }.let { RenderEffect.createRuntimeShaderEffect(it, "image") } else null
        val blur = if (sigma > 0f) RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.DECAL) else null
        val effect = when {
            clip != null && blur != null -> RenderEffect.createChainEffect(clip, blur)
            clip != null -> clip
            else -> blur
        }
        return RenderNode("COUI shadow").apply {
            setPosition(left, top, right, bottom)
            setClipToBounds(false)
            setRenderEffect(effect)
        }
    }

    private data class ShadowKey(val width: Float, val height: Float, val sx: Float, val sy: Float,
        val x: Float, val y: Float, val transparent: Boolean, val elevation: Float,
        val lightY: Float, val lightZ: Float, val lightRadius: Float, val outline: CouiShadowOutline,
        val radius: Float, val clipping: Boolean)

    private class ProjectionDraw(val key: ShadowKey, val projection: ShadowProjection, val paint: Paint,
        val mesh: Mesh? = null, val node: RenderNode? = null, val path: Path? = null,
        val clipShader: RuntimeShader? = null) {
        var color: Int? = null
    }
}
