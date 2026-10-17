package com.xjtu.toolbox.core.net

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** 真服务器打通测试（本机 campus-api `127.0.0.1:3099`，无凭据直连可读）。 */
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
