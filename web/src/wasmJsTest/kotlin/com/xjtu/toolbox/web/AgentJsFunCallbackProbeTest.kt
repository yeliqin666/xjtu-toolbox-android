@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 设计稿 §4.5 要求的那条**最小探针**：`@JsFun` 把 Kotlin 回调传给 JS，在 wasmJs 上真能用吗？
 *
 * ## 为什么要单写一条（而不是「编过就算」）
 *
 * 本仓此前三处 `@JsFun` 全是无参 / 纯字符串（`Data.kt` 的 `window.location.search`、`BrowserUrl.kt`
 * 的导航、`WebArchiveIo.kt` 那个 `<input type=file>` 选择器），**没有一处把 Kotlin 的 lambda 当回调
 * 交给 JS 并由 JS 反过来调用**。设计稿的未核对 #3 明写「落地时先写一条最小探针」—— 因为它决定
 * 这一族的形态：能回调就用**回调推送**（`AgentSseBridge` 现在这个形状），不能就得退化成
 * 「JS 攒缓冲 + Kotlin 用 `@JsFun` 轮询拉」那种等价形态。
 *
 * 结论（真浏览器里跑的，见下面两条用例）：**能用** ⇒ 这一族用**回调推送**的形态，不必退化成
 * 「JS 攒缓冲 + Kotlin 轮询拉」那种等价形态。验的是三件不同的事，从弱到强：
 *
 * 1. **同步回调**：JS 拿到的那一格是可调用的函数，JVM 侧收得到值（这是最小的一条）；
 * 2. **先存下、以后由 JS 调用**：回调在 JS 侧是个**稳定的函数句柄**（不是只能用一次的一次性适配器）——
 *    这一条正对着 `AgentSseBridge` 的用法：JS 把它存进 fetch 的 `.then(...)` 闭包里，几百毫秒后
 *    才逐块调用；
 * 3. **`Promise` 微任务里回调**（真异步）：**本测试源集验不了** —— `kotlin.test` 的 wasmJs 适配器不支持
 *    `suspend` 测试函数（实测：编译器直接报「suspend functions annotated with @kotlin.test.Test are
 *    unsupported」），而这一支天然是异步的。它靠 ①② 已经盖住的那件事（回调是个真可以调、且可以反复调的
 *    JS 函数）+ 真浏览器里手点一次（`docs/serve-visual-checklist.md` 的 ⑪）来证明 —— 这里**不假装验过**。
 * ①② 都跑在**真浏览器**里（`:web:wasmJsBrowserTest` 走的是 karma + Chrome headless），不打任何
 * 服务端、不花钱、不碰用户那台 serve。
 */
class AgentJsFunCallbackProbeTest {

    @Test
    fun `探针 ①：JS 同步调用 Kotlin 回调，值真的落回 Kotlin`() {
        var got: String? = null
        probeCallbackSync { got = it }
        assertEquals("pong", got)
    }

    @Test
    fun `探针 ②：回调先交给 JS 存下、以后再由 JS 调用（句柄是稳定的）`() {
        probeCallbackKeep { value -> keptProbeValues += value }
        assertEquals(emptyList(), keptProbeValues)
        probeCallbackFire()
        assertEquals(listOf("pong-later"), keptProbeValues)
        // 再调一次：同一个句柄还能用（fetch 的每一块都会调它）
        probeCallbackFire()
        assertEquals(listOf("pong-later", "pong-later"), keptProbeValues)
    }

    private companion object {

        /** 探针 ② 的收件箱（JS 那一侧调回来时写这里）。 */
        val keptProbeValues = mutableListOf<String>()
    }
}

/** 探针 ①：同步调用回调。 */
@JsFun("(onDone) => { onDone('pong'); }")
external fun probeCallbackSync(onDone: (String) -> Unit)

/** 探针 ②：把回调存进一个全局，等 Kotlin 这一侧下次再叫时才调用（见 [probeCallbackFire]）。 */
@JsFun("(onDone) => { globalThis.__xjtuToolboxProbeCallback = onDone; }")
external fun probeCallbackKeep(onDone: (String) -> Unit)

/** 探针 ②：现在调用刚才存下的那个回调。 */
@JsFun("() => { const cb = globalThis.__xjtuToolboxProbeCallback; if (cb) { cb('pong-later'); } }")
external fun probeCallbackFire()

