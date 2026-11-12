package com.xjtu.toolbox.sharedview

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import com.itgsa.animation.sharedview.ViewAnimationConfig
import com.itgsa.animation.sharedview.ViewAnimationExtras
import com.itgsa.animation.sharedview.ViewAnimationListener
import com.itgsa.animation.sharedview.ViewAnimationManager
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.ExpandOrigins
import com.xjtu.toolbox.nav.SeamlessCardSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * 金标联盟 SharedViewKit 的 App 侧桥。
 *
 * KIT 是 ROM 级 Activity 转场：源侧给出 `Bitmap + bounds + px 圆角` 换来一个 `ActivityOptions`，
 * 目标必须是独立 Activity；ROM 不声明支持时 [makeViewAnimationOptions] 会返回 null / 探测为 0，
 * 这里统一回落成 App 内现有的 Compose 转场（压路由，不丢交互）。
 *
 * 只给「公告卡 → 公告详情」这一条英雄流程用，所以不把 KIT 类型往 `:shared:*` / `:feature:*` 里带。
 */
object KitOneTake {

    private const val TAG = "KitOneTake"

    /**
     * KIT 协议版本；0 = 当前 ROM 不声明支持。首次调用会反射探测 ROM 类并问 `isFeatureEnabled()`
     * （毫秒级），AAR 内部有静态缓存，这里也只读一次。
     */
    private val protocolVersion: Int by lazy {
        runCatching { ViewAnimationManager.isViewAnimationSupported() }
            .onFailure { Log.d(TAG, "probe SharedViewKit failed, fall back", it) }
            .getOrDefault(0)
    }

    /**
     * 当前 ROM 是否声明支持。首次调用会做 [protocolVersion] 的探测，
     * 只决定「走哪条路」，不要在每次点击时反复问；启动时用 [prewarm] 提前问掉。
     */
    val supported: Boolean by lazy {
        val version = protocolVersion
        if (version > 0) {
            Log.i(TAG, "SharedViewKit supported, protocol=$version, extras=${declaredExtras?.keys?.joinToString()}")
        } else {
            Log.d(TAG, "SharedViewKit unsupported on this ROM, fall back to Compose transition")
        }
        version > 0
    }

    /** 启动时后台预热一次：首次探测要反射加载 ROM 类，提前问掉，点击时不再付这笔钱。 */
    fun prewarm() {
        @Suppress("UNUSED_EXPRESSION")
        supported
    }

    /** ROM 声明支持哪些 extras（不支持时为空/ null）；用于决定写不写模糊这类参数。 */
    private val declaredExtras: Map<String, String>? by lazy {
        runCatching { ViewAnimationManager.getViewAnimationExtras() }.getOrNull()
    }

    /** 同一时刻只允许一次 KIT 启动（连点两下不要开两个详情页）。 */
    private val starting = AtomicBoolean(false)

    /** KIT 动画结束前不能让 Bitmap 被回收（ROM 侧还要用）；结束时由 listener 释放引用。 */
    @Volatile
    private var pendingBitmap: Bitmap? = null

    /**
     * 从首页卡片打开任意可独立托管的页面：ROM 支持时走 KIT 的系统级一镜到底，否则调 [fallback]。
     *
     * [fallback] 必须自己做完「压 Compose 路由」这件事；本方法保证它在失败路径上恰好被调用一次，
     * 且在主线程。目标页面能否单独托管由调用方用 `isStandaloneHostable` 判断。
     */
    fun openPage(
        activity: Activity,
        route: AppRoute,
        source: SeamlessCardSource,
        fallback: () -> Unit,
    ) {
        if (!supported) {
            Log.d(TAG, "SharedViewKit unavailable on this ROM, fall back to Compose transition")
            fallback()
            return
        }
        if (!starting.compareAndSet(false, true)) return

        val bounds = Rect(
            source.boundsInWindow.left.roundToInt(),
            source.boundsInWindow.top.roundToInt(),
            source.boundsInWindow.right.roundToInt(),
            source.boundsInWindow.bottom.roundToInt(),
        )
        if (bounds.isEmpty) {
            starting.set(false)
            fallback()
            return
        }

        val density = activity.resources.displayMetrics.density
        val radiusPx = source.cornerRadius.value * density
        val bitmap = runCatching {
            Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            starting.set(false)
            fallback()
            return
        }

        val mainHandler = Handler(Looper.getMainLooper())
        runCatching {
            PixelCopy.request(
                activity.window,
                bounds,
                bitmap,
                { result ->
                    starting.set(false)
                    if (result == PixelCopy.SUCCESS && !activity.isFinishing && !activity.isDestroyed) {
                        // 底板要画成源元素自己的底色：卡片取卡片背景、图标取图标那块浅色底。
                        // 取不到（截图异常）时返回 0，交给 ROM 自动取色兜底。
                        val maskColor = runCatching {
                            KitMaskColor.backgroundOf(bitmap, radiusPx)
                        }.getOrDefault(0)
                        // 截图圆角外还带着点击时的页面像素，动画里会从底板边上露出一圈白；刷成底板同色。
                        if (maskColor != 0) {
                            runCatching { paintOutsideRoundedRect(bitmap, radiusPx, maskColor) }
                        }
                        startWithKit(activity, route, bitmap, bounds, source, radiusPx, maskColor, fallback)
                    } else {
                        Log.d(TAG, "PixelCopy failed: result=$result, fall back")
                        fallback()
                    }
                },
                mainHandler,
            )
        }.onFailure {
            Log.d(TAG, "PixelCopy request threw, fall back", it)
            starting.set(false)
            fallback()
        }
    }

    private fun startWithKit(
        activity: Activity,
        route: AppRoute,
        bitmap: Bitmap,
        windowBounds: Rect,
        source: SeamlessCardSource,
        radiusPx: Float,
        maskColor: Int,
        fallback: () -> Unit,
    ) {
        try {
            // 返回动画类型保持不传 = `ViewAnimationType.VIEW_ANIM_DEFAULT`（0），也就是 ROM 默认的
            // 「缩回源元素」一镜到底。其余档位换的是返回语义（1 不播返回动画 / 2 跳过 seamless /
            // 3 强制淡出），4 还要 ROM 调试属性 debug.view_seamless.scale（默认 false）才生效，
            // 都不适合默认开；要试其中某一档时再加 `setBackAnimType(...)`。
            val config = ViewAnimationConfig().setRadius(radiusPx)
            // 底板填充色：不传由 ROM 自动取色，会挑中图标强调色在卡片四周露色（见 KitMaskColor）。
            if (maskColor != 0) config.setColor(maskColor)
            val bundle: Bundle = config.toBundle()
            // 只写 ROM 声明支持的 extras：没声明的写了也不会生效，收益为零。
            if (declaredExtras?.containsKey(ViewAnimationExtras.EXTRA_BLUR) == true) {
                bundle.putBoolean(ViewAnimationExtras.EXTRA_BLUR, true)
            }

            pendingBitmap = bitmap
            val listener = object : ViewAnimationListener {
                override fun onAnimationStart(animMode: Int) {
                    Log.d(TAG, "KIT animation start, mode=$animMode")
                }

                override fun onAnimationCancel(animMode: Int) {
                    Log.d(TAG, "KIT animation cancel, mode=$animMode")
                    pendingBitmap = null
                }

                override fun onAnimationEnd(animMode: Int) {
                    Log.d(TAG, "KIT animation end, mode=$animMode")
                    pendingBitmap = null
                }
            }

            val kitBounds = kitCoordinateBounds(activity, windowBounds)
            Log.i(
                TAG,
                "start KIT: route=${route.id}, protocol=$protocolVersion, windowRect=$windowBounds, " +
                    "kitBounds=$kitBounds, radiusPx=$radiusPx, maskColor=" + maskColorHex(maskColor) + ", " +
                    "blur=${declaredExtras?.containsKey(ViewAnimationExtras.EXTRA_BLUR)}",
            )
            val options = ViewAnimationManager.makeViewAnimationOptions(
                bitmap,
                kitBounds,
                radiusPx,
                bundle,
                listener,
            )
            if (options == null) {
                Log.d(TAG, "makeViewAnimationOptions returned null, fall back")
                pendingBitmap = null
                fallback()
                return
            }

            // 这条分支不再压 Compose 路由：把首页刚登记的 ExpandOrigins 起点清掉，免得落到别的页面上去。
            ExpandOrigins.clear()
            activity.startActivity(SeamlessPageActivity.intent(activity, route), options.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "start KIT transition failed, fall back", e)
            pendingBitmap = null
            fallback()
        }
    }

    /**
     * 把截图圆角外那圈页面像素刷成底板色。
     *
     * KIT 的底板比源元素大一圈，而源截图是矩形的：圆角以外仍然带着点击那一刻的页面像素。
     * 动画过程中底板和截图错开，这些页面像素就会在底板和卡片之间露出一圈白边。
     * 刷成与底板相同的颜色后，截图上没有内容的地方和底板连成一片，看不出接缝。
     */
    private fun paintOutsideRoundedRect(bitmap: Bitmap, radiusPx: Float, color: Int) {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val radius = radiusPx.coerceIn(0f, minOf(width, height) / 2f)
        // EVEN_ODD：外圈整块矩形减去中间圆角矩形，只留下圆角外的那四块。
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width, height, Path.Direction.CW)
            addRoundRect(0f, 0f, width, height, radius, radius, Path.Direction.CW)
        }
        val paint = Paint().apply {
            this.color = color
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        Canvas(bitmap).drawPath(path, paint)
    }

    /**
     * `EXTRA_BOUNDS` 的坐标系由 ROM 声明：`rect|WINDOW|...` 用窗口坐标，否则用屏幕坐标。
     * 传错坐标系是 KIT 落点偏移的常见原因，别按机型硬编码。
     */
    private fun kitCoordinateBounds(activity: Activity, windowBounds: Rect): Rect {
        val meta = declaredExtras?.get(ViewAnimationExtras.EXTRA_BOUNDS)
        return if (parseBoundsToken(meta) == "WINDOW") {
            Rect(windowBounds)
        } else {
            val location = IntArray(2)
            activity.window.decorView.getLocationOnScreen(location)
            Rect(windowBounds).apply { offset(location[0], location[1]) }
        }
    }

    /** metadata 形如 `rect|WINDOW|...`；第二段优先，`WINDOW` 子串只作为老描述兜底。 */
    private fun parseBoundsToken(meta: String?): String {
        if (meta.isNullOrBlank()) return "SCREEN"
        val parts = meta.split('|')
        if (parts.size >= 2) {
            val token = parts[1].trim().uppercase()
            if (token == "SCREEN" || token == "WINDOW") return token
        }
        return if (meta.uppercase().contains("WINDOW")) "WINDOW" else "SCREEN"
    }

    /** 日志里把底板色打成 `#AARRGGBB`；没取到色时标记出来，方便真机上确认走的是哪条路。 */
    private fun maskColorHex(maskColor: Int): String =
        if (maskColor == 0) "none" else "#%08X".format(maskColor)
}
