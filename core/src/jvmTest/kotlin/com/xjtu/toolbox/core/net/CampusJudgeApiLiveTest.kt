package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.judge.CampusJudgeApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 评教的 campus-api 映射**真数据**验证（本机 campus-api `127.0.0.1:3099`）。
 *
 * 默认**跳过**（依赖本机服务，不能进 CI）：
 *   `TOOLBOX_PROBE_LIVE=1 ./gradlew :core:jvmTest`
 *
 * 为什么指定学期：屏用的是上游评教学期 `CSZA`（与 :app 同一口径），而它**常常整学期为空**
 * （实测 `2025-2026-3` 全空）—— 空的时候两端都显示「暂无待评课程」，看不出映射对不对。
 * 所以这里指定一个**确实有数据**的学期（`2025-2026-1`，实测 14 条未评）来盯映射。
 */
class CampusJudgeApiLiveTest {

    private val enabled: Boolean = System.getenv("TOOLBOX_PROBE_LIVE") == "1"
    private val baseUrl: String = System.getenv("TOOLBOX_PROBE_BASE") ?: "http://127.0.0.1:3099"

    @Test
    fun realEvaluations() {
        if (!enabled) {
            println("[探针] 跳过评教真服务器测试；设 TOOLBOX_PROBE_LIVE=1 启用（base=$baseUrl）")
            return
        }
        runBlocking {
            val client = createToolboxClient(cookieStorage = MemoryCookieJar())
            val api = CampusJudgeApi(client, baseUrl, term = "2025-2026-1")
            try {
                assertTrue(api.canSubmit.not(), "Web 端评教必须只读（campus-api 永不实现提交）")

                val (unfinished, finished) = api.load()
                println("[探针] 未评=${unfinished.size} 已评=${finished.size}")
                assertTrue(unfinished.isNotEmpty(), "2025-2026-1 应有未评问卷（实测 14 条）")

                val sample = unfinished.first()
                println("[探针] 首条=${sample.courseName} / ${sample.teacher} / pgType=${sample.pgType} / key=${api.card(sample).key}")
                assertTrue(sample.wjdm.isNotBlank(), "wjdm 不应为空")
                assertTrue(sample.jxbid.isNotBlank(), "jxbid 不应为空")
                assertTrue(sample.courseName.isNotBlank(), "课程名不应为空")
                assertTrue(sample.teacher.isNotBlank(), "教师不应为空")
                assertTrue(api.card(sample).tag in setOf("期末评教", "过程评教", "评教"), "标签文案应与 :app 同一套")
                assertTrue(finished.all { it.finished } && unfinished.none { it.finished }, "未评/已评的切分必须互斥")
            } finally {
                client.close()
            }
        }
    }
}
