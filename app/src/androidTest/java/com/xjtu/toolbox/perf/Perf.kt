package com.xjtu.toolbox.perf

import android.os.Debug
import android.util.Log

/**
 * 性能测量工具。只用于 androidTest，不进产物。
 *
 * 为什么用 `art.gc.bytes-allocated` 而不是自己算对象大小：它是 ART 真实的累计分配
 * 计数器，包含所有内部分配（对象头、数组、装箱），比手工估算可信。代价是进程级、
 * 含后台线程——所以每个测量都先预热让类加载/JIT 落地，再用足够多的迭代让信号压过噪声。
 *
 * 用法约定：测量结果必须走 [Log.i]，tag 固定 [TAG]，便于 `adb logcat -s ZPERF` 抓取。
 */
internal object Perf {
    const val TAG = "ZPERF"

    /** 防止 JIT 把被测代码当死代码消除。 */
    @JvmStatic
    @Volatile
    var sink: Any? = null

    /** 当前进程累计分配字节；读不到返回 -1。 */
    fun allocBytes(): Long {
        // API 23+ 的正式接口
        Debug.getRuntimeStat("art.gc.bytes-allocated")?.let {
            return it.toLongOrNull() ?: -1L
        }
        // 兜底：老接口，语义相同（均为累计值），API 23 起 deprecated 但仍可用
        @Suppress("DEPRECATION")
        return Debug.getGlobalAllocSize().toLong()
    }

    class Stats(
        /** 每次迭代的分配字节（可能为负：计数器不可用或噪声）， */
        val bytesPerOp: Double,
        /** 每次迭代的纳秒 */
        val nsPerOp: Double,
        val iterations: Int,
    )

    /**
     * 测 [body] 的每次迭代分配量与耗时。
     *
     * [warmup] 让类加载与 JIT 先发生，否则首轮会把编译开销算进结果（对「首帧成本」而言
     * 那反而是要测的东西，所以那个单独用 [measureFirstTouch]）。
     */
    fun measure(warmup: Int, iterations: Int, body: (Int) -> Any?): Stats {
        repeat(warmup) { sink = body(it) }
        val alloc0 = allocBytes()
        val t0 = System.nanoTime()
        repeat(iterations) { sink = body(it) }
        val t1 = System.nanoTime()
        val alloc1 = allocBytes()
        return Stats(
            bytesPerOp = (alloc1 - alloc0).toDouble() / iterations,
            nsPerOp = (t1 - t0).toDouble() / iterations,
            iterations = iterations,
        )
    }

    /** 连测 [rounds] 轮，返回中位数（抗单次抖动）。 */
    fun measureMedian(warmup: Int, iterations: Int, rounds: Int, body: (Int) -> Any?): Stats {
        val all = (1..rounds).map { measure(warmup, iterations, body) }
        val byBytes = all.sortedBy { it.bytesPerOp }
        val byTime = all.sortedBy { it.nsPerOp }
        val mid = all.size / 2
        return Stats(
            bytesPerOp = byBytes[mid].bytesPerOp,
            nsPerOp = byTime[mid].nsPerOp,
            iterations = iterations * rounds,
        )
    }

    /** 只测第一次调用的耗时：用于类加载 / 加密存储首建这类「一次性的」成本。 */
    fun measureFirstTouch(body: () -> Any?): Long {
        val t0 = System.nanoTime()
        sink = body()
        return System.nanoTime() - t0
    }

    /**
     * 打印结果。[divisor] 把「每次迭代」换算成「每个单位」（例如每帧），
     * [unit] 是该单位名，只影响可读性、不影响数值。
     */
    fun report(name: String, stats: Stats, divisor: Int = 1, unit: String = "op") {
        val d = divisor.coerceAtLeast(1)
        Log.i(
            TAG,
            "%-26s bytes/%-6s=%8.1f  us/%-6s=%7.3f  (iters=%d×%d)"
                .format(
                    name, unit, stats.bytesPerOp / d,
                    unit, stats.nsPerOp / d / 1000.0,
                    stats.iterations, divisor,
                ),
        )
    }

    fun reportFirstTouch(name: String, ns: Long) {
        Log.i(TAG, "%-28s firstTouch=%.2f ms".format(name, ns / 1_000_000.0))
    }
}
