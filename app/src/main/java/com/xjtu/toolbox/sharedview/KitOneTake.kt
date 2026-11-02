package com.xjtu.toolbox.sharedview

import android.app.Activity
import android.graphics.Bitmap
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
     * 当前 ROM 是否声明支持。首次调用会反射探测 ROM 类并问 `isFeatureEnabled()`（毫秒级），
     * AAR 内部有静态缓存；这里只决定「走哪条路」，不要在每次点击时反复问。
     */
    val supported: Boolean by lazy {
        runCatching { ViewAnimationManager.isViewAnimationSupported() > 0 }
            .onFailure { Log.d(TAG, "probe SharedViewKit failed, fall back", it) }
            .getOrDefault(false)
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
     * 从首页公告卡打开公告详情：ROM 支持时走 KIT 的系统级一镜到底，否则调 [fallback]。
     *
     * [fallback] 必须自己做完「压 AppRoute.NoticeDetail 路由」这件事；本方法保证它在失败路径上
     * 恰好被调用一次，且在主线程。
     */
    fun openBulletinDetail(
        activity: Activity,
        notice: AppRoute.NoticeDetail,
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
                        startWithKit(activity, notice, bitmap, bounds, source, fallback)
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
        notice: AppRoute.NoticeDetail,
        bitmap: Bitmap,
        windowBounds: Rect,
        source: SeamlessCardSource,
        fallback: () -> Unit,
    ) {
        try {
            val density = activity.resources.displayMetrics.density
            val radiusPx = source.cornerRadius.value * density
            val bundle: Bundle = ViewAnimationConfig()
                .setRadius(radiusPx)
                .toBundle()
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
            activity.startActivity(NoticeDetailActivity.intent(activity, notice), options.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "start KIT transition failed, fall back", e)
            pendingBitmap = null
            fallback()
        }
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
}
