@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import kotlin.test.Test
import kotlin.test.assertEquals

/** `@JsFun` 把 Kotlin 回调传给 JS 在 wasmJs 上能否用的最小用例。 */
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

        /** 用例 ② 的收件箱（JS 那一侧调回来时写这里）。 */
        val keptProbeValues = mutableListOf<String>()
    }
}

/** 用例 ①：同步调用回调。 */
@JsFun("(onDone) => { onDone('pong'); }")
external fun probeCallbackSync(onDone: (String) -> Unit)

/** 用例 ②：把回调存进一个全局，等下次再叫时才调用（见 [probeCallbackFire]）。 */
@JsFun("(onDone) => { globalThis.__xjtuToolboxProbeCallback = onDone; }")
external fun probeCallbackKeep(onDone: (String) -> Unit)

/** 用例 ②：调用刚才存下的回调。 */
@JsFun("() => { const cb = globalThis.__xjtuToolboxProbeCallback; if (cb) { cb('pong-later'); } }")
external fun probeCallbackFire()

