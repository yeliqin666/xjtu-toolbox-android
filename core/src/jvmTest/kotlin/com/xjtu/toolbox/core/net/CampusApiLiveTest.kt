package com.xjtu.toolbox.core.net

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真服务器打通测试（本机 campus-api `127.0.0.1:3099`，无凭据直连可读）。
 *
 * 默认**跳过**，因为它依赖本机服务，不能进 CI：
 *   `TOOLBOX_PROBE_LIVE=1 ./gradlew :core:jvmTest`
 *
 * 用 runBlocking 而不是 runTest：这里跑的是真 IO，不要虚拟时钟掺和（会假死）。
 */
class CampusApiLiveTest {

    private val enabled: Boolean = System.getenv("TOOLBOX_PROBE_LIVE") == "1"
    private val baseUrl: String = System.getenv("TOOLBOX_PROBE_BASE") ?: "http://127.0.0.1:3099"

    @Test
    fun realEndpoints() {
        if (!enabled) {
            println("[探针] 跳过真服务器测试；设 TOOLBOX_PROBE_LIVE=1 启用（base=$baseUrl）")
            return
        }
        runBlocking {
            val jar = MemoryCookieJar()
            val client = createToolboxClient(cookieStorage = jar)
            val api = CampusApi(client, baseUrl)
            try {
                val status = api.status()
                println("[探针] status.authenticated=${status.authenticated} uptime=${status.uptimeSeconds}s")
                val term = api.term()
                println("[探针] jwxt.term=$term")
                assertTrue(term.isNotBlank(), "真实 term 不应为空")
                assertTrue(term.contains("-"), "term 形状应为 2026-2027-1")
            } finally {
                client.close()
            }
        }
    }
}
