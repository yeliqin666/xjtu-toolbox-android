package com.xjtu.toolbox.iclassface

import com.xjtu.toolbox.auth.IclassfaceSession
import com.xjtu.toolbox.auth.withIclassfaceLogin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/**
 * 人脸识别签到取数（`IclassfaceApi`）的**字段级口径**：对着 `:testkit` 的
 * [IclassfaceFakeUpstream] 逐行读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 第 20 条真数据路由把 iclassface 的取数（`IclassfaceApi` + `AppIclassfaceSource`）与站点
 * （`IclassfaceSession` / `IclassfaceLogin`）从 `:app` 搬进了 `:data`。这些断言全部来自**夹具原文**
 * 与**搬迁前的代码语义**：CAS OAuth2 `client_id=1543` 那条登录链、`POST /checkin/records` 的
 * `date=` 形态、服务端渲染表格「一**行六格**才算一条记录」的解析口径（残缺行要被跳过）。
 *
 * ## 它与考勤屏的关系
 *
 * 这一屏是考勤屏上的「快速考勤流水」二级入口（`:core` 的 `IclassfaceScreen`）。登录**不借考勤的
 * 会话** —— 它有自己的独立 OAuth2 链路，凭据落地就是 `PHPSESSID`。
 */
class IclassfaceApiJvmTest {

    @Test
    fun `登录：CAS OAuth2 client_id=1543 中转开放平台，回落到本域的 oauth callback`() {
        // 入口 URL 里的 client_id 与回调地址与夹具逐字相同（改一个字，真站点上就会静默走错）
        assertTrue(
            IclassfaceLogin.LOGIN_URL.contains("client_id=${IclassfaceFakeUpstream.CLIENT_ID}"),
            "登录入口要带夹具那个 client_id",
        )
        assertTrue(
            IclassfaceLogin.LOGIN_URL.contains("iclassface.xjtu.edu.cn%2Foauth%2Fcallback%2Fxjtu_new"),
            "redirect_uri 里要有本域的回调（URL 编码形态）",
        )
        assertEquals(IclassfaceFakeUpstream.CHECKIN_URL, IclassfaceLogin.CHECKIN_URL)
        assertEquals(IclassfaceFakeUpstream.FACE_DETECT_URL, IclassfaceLogin.FACE_DETECT_URL)

        withIclassfaceLogin { site, fake ->
            assertTrue(site.hasLogin, "真登录应当成功")
            assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")
            assertTrue(fake.iclassface.orgCallbacks.get() > 0, "应当经过开放平台 authorizesw 那一跳")
            assertTrue(fake.iclassface.callbackCalls.get() > 0, "应当落到本域的回调上")
            assertTrue(fake.iclassface.faceDetectCalls.get() > 0, "落点应当是 /face/detect")
        }
    }

    @Test
    fun `记录：POST checkin records 的表格一行六格算一条，残缺行跳过`() {
        withIclassfaceLogin { site, fake ->
            val api = IclassfaceApi(site)
            val date = LocalDate(2026, 6, 4)
            val records = runBlocking { api.fetchRecords(date) }

            // 夹具那一行之外还有一行只有 3 格的「合计」行 —— 它不算记录
            assertEquals(1, records.size, "只应当有一条记录（残缺行被跳过）")
            val record = records.single()
            assertEquals(IclassfaceFakeUpstream.RECORD_NAME, record.name)
            assertEquals(IclassfaceFakeUpstream.RECORD_STUDENT_NO, record.studentNo)
            assertEquals(IclassfaceFakeUpstream.RECORD_TIME, record.time)
            assertEquals(IclassfaceFakeUpstream.RECORD_LOCATION, record.location)
            assertEquals(IclassfaceFakeUpstream.RECORD_TYPE, record.type)

            // 打出去的日期就是传进来的那天（ISO yyyy-MM-dd，搬迁前 java.time 的写法一个字没变）
            assertEquals("2026-06-04", fake.iclassface.lastDate)
            assertEquals(1, fake.iclassface.checkinCalls.get())
        }
    }

    @Test
    fun `空表：当天没有任何记录时给空列表，不是错误`() {
        withIclassfaceLogin { site, fake ->
            fake.iclassface.emptyRecords = true
            val api = IclassfaceApi(site)
            val records = runBlocking { api.fetchRecords(LocalDate(2026, 6, 5)) }

            assertTrue(records.isEmpty(), "空表 ⇒ 空列表（屏上显示「那天没有任何记录」）")
            assertEquals("2026-06-05", fake.iclassface.lastDate)
        }
    }
}
