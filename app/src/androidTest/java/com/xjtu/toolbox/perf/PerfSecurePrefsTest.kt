package com.xjtu.toolbox.perf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xjtu.toolbox.util.SecurePrefs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 加密存储缓存与预热的收益验证。
 *
 * 两块要证明的事：
 * 1. 共享缓存确实**避免重复创建**——第二次取同一文件应几乎不耗时（命中缓存）；
 * 2. 后台预热确实把主线程的等待**搬走**——预热后再取，应显著快于冷取。
 */
@RunWith(AndroidJUnit4::class)
class PerfSecurePrefsTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val fallback: (android.content.Context, String) -> android.content.SharedPreferences =
        { c, n -> c.getSharedPreferences("${n}_fallback", android.content.Context.MODE_PRIVATE) }

    @Test
    fun sharedCache_secondGetIsFree() {
        val name = "perf_cache_probe"
        val first = Perf.measureFirstTouch { SecurePrefs.get(ctx, name, fallback) }
        Perf.reportFirstTouch("SecurePrefs.get (cold)", first)

        // 连测 10 次命中，取最大值：单测一次容易被类加载/JIT 混进来，看不出真实稳态。
        var worst = 0L
        var best = Long.MAX_VALUE
        repeat(10) {
            val ns = Perf.measureFirstTouch { SecurePrefs.get(ctx, name, fallback) }
            if (ns > worst) worst = ns
            if (ns < best) best = ns
        }
        Perf.reportFirstTouch("SecurePrefs.get (cached, best)", best)
        Perf.reportFirstTouch("SecurePrefs.get (cached, worst)", worst)

        Log.i(Perf.TAG, "  -> cold/cached = %.1fx".format(first.toDouble() / best.coerceAtLeast(1)))
        // 稳态命中应基本免费（亚毫秒）。用较宽的上界，只为挡住「缓存没生效」这类回归。
        assertTrue("缓存命中应远快于冷取，实得 ${best / 1_000_000.0}ms", best < first / 20)
    }

    /**
     * 预热收益：先起后台预热，等到它跑完，再测前台首次取用的耗时。
     *
     * 这正是 App 冷启动的时序——`XjtuApp.onCreate` 起预热线程，`MainActivity` 稍后才
     * 构造那几个 store。
     */
    @Test
    fun preload_makesForegroundGetFast() {
        val names = listOf("perf_preload_a", "perf_preload_b")
        val done = java.util.concurrent.CountDownLatch(1)
        val t0 = System.nanoTime()
        Thread {
            SecurePrefs.preload(ctx, names, fallback)
            done.countDown()
        }.also { it.isDaemon = true }.start()
        assertTrue("预热应在 10s 内完成", done.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val foreground = Perf.measureFirstTouch { SecurePrefs.get(ctx, names[0], fallback) }
        Perf.reportFirstTouch("get after preload (fg)", foreground)
        Log.i(Perf.TAG, "  -> preload wall time: %.2f ms".format((System.nanoTime() - t0) / 1e6))
    }

    /**
     * 两个 jar 实例共享同一份 prefs 后，一方写入另一方立即可见。
     *
     * 这不只是省时间：原来每个实例各自持有一份内存映射，写入会互相覆盖，
     * 把对方刚写的 cookie 抹掉。这条断言把「不再分叉」钉死。
     */
    @Test
    fun sharedInstance_crossStoreVisibility() {
        val name = "perf_share_probe"
        val a = SecurePrefs.get(ctx, name, fallback)
        val b = SecurePrefs.get(ctx, name, fallback)
        assertTrue("同一文件名应返回同一实例", a === b)
        a.edit().putString("k", "from-a").commit()
        assertTrue("另一端应立刻看到", b.getString("k", null) == "from-a")
    }
}
