package com.xjtu.toolbox.dzpz

import com.xjtu.toolbox.auth.DzpzSession
import com.xjtu.toolbox.auth.withDzpzLogin
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.CATEGORY_NAME
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.CANCELLED_TYPE_NAME
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.DEFAULT_DATE
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.DEFAULT_REQUEST_NAME
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.DOC_ID
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.ENROLL_YEAR
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.LINKAGE_UUID
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.OA_ID
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.PDF_BYTES
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.PDF_FILENAME
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.PDF_FILESIZE
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.PDF_LOADLINK
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.PDF_REFERER
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.REQUEST_ID
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.SESSION_KEY_FIRST
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.SESSION_KEY_SECOND
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.STUDENT_ID
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.SUBMIT_TOKEN_FIRST
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.SUBMIT_TOKEN_FORWARD
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.TEMPLATE_PATH
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.TYPE_NAME
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.TYPE_VALUE
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream.Companion.WORKFLOW_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 成绩单取数的**字段级口径**：`TranscriptApi` 对着 `:testkit` 的 [DzpzFakeUpstream] 逐行读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 这一轮把成绩单的取数（`TranscriptApi`）与站点（`DzpzSession` + `DzpzLogin`）从 `:app` 搬进了
 * `:data`（桌面端第 14 条真数据路由）。搬之前先用夹具把**搬之前**的口径钉住：`:app` 那边一行
 * 逻辑没改，只是文件换了地方、`android.util.Log` 换成 `:core` 的 `Log`、五个嵌套数据类升级成
 * 同包的顶层声明 —— 但「没改」这句话得有证据，这些断言就是那个证据。
 *
 * ## 这些断言从哪来
 *
 * 全部来自**夹具原文**与**搬迁前的代码语义**（哪些字段用哪个 field 号、哪一步发几枪、
 * 默认值落在哪儿），**不是从跑通的实现里抄回来的**：
 *
 *  - 建单：`parseTypeOptions` 认 `tableInfo.main.fieldinfomap.7243.selectattr.selectitemlist`，
 *    `cancel=1` 的那条被滤掉；当天日期与请求名分别取 `maindata.field7249.value` / `field-1.value`；
 *  - 联动：两枪分开打（第一枪带 `field7250=userId` 拿学号+入学年，第二枪带 `field7243=类型` 拿
 *    模板路径+业务分类名），`linkageUUID` 两枪都要带上；
 *  - 生成预览：`procfiles` 那枪返回的**纯文本**要 `trim()` 后当文档 id；
 *  - 两次提交：第一次建单 `src=save`、第二次转发 `src=submit`；token 分别取自建单
 *    `submitParams["<uid>_29_addrequest_submit_token"]` 与重载后的
 *    `submitParams["<uid>_555_request_submit_token"]`；
 *  - 下载：`field7564.specialobj.filedatas[0]` 优先（`loadlink` 是相对路径 ⇒ 拼 `BASE`），
 *    没有它才退回用 `field7244` 拼 `FileDownload` 地址。
 */
class TranscriptApiJvmTest {

    @Test
    fun `登录：OAuth2 那条链走完，会话里落着 OA id`() {
        withDzpzLogin { site, fake ->
            // `DzpzSession.onLoginSuccess` 把 loginidweaver 写进 localToken["user_id"]，
            // 而 `TranscriptApi` 的每个请求体都要它 —— 这是「七步能跑」的前提。
            assertEquals(OA_ID, site.localToken["user_id"])
            assertTrue(site.hasLogin, "真登录应当成功")
            // 登录入口页真的被打开过（不是靠别处留下的 cookie 直通）
            assertTrue(fake.dzpz.loginEntryCalls.get() > 0, "登录入口页应当被访问")
            assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")
        }
    }

    @Test
    fun `建单表单：类型选项滤掉已取消的那条，默认日期与请求名来自 maindata`() {
        withDzpzLogin { site, fake ->
            val api = TranscriptApi(site)
            val ctx = runBlocking { api.loadCreateForm(DzpzDocuments.TRANSCRIPT.workflowId) }

            assertEquals(WORKFLOW_ID, ctx.workflowId)
            assertEquals(LINKAGE_UUID, ctx.linkageUUID)
            // 两个选项里 `cancel=1` 那个被滤掉，只剩一个
            assertEquals(listOf(TYPE_NAME), ctx.typeOptions.map { it.name })
            assertEquals(TYPE_VALUE, ctx.typeOptions.single().value)
            assertTrue(ctx.typeOptions.none { it.name == CANCELLED_TYPE_NAME })
            assertEquals(DEFAULT_DATE, ctx.defaultDate)
            assertEquals(DEFAULT_REQUEST_NAME, ctx.defaultRequestName)

            // 那一枪是 POST + 表单体：建单标记与 workflowId 都得在
            val body = fake.dzpz.loadFormBodies.single()
            assertTrue("iscreate=1" in body, "建单要带 iscreate=1")
            assertTrue("workflowid=$WORKFLOW_ID" in body, "建单要带 workflowid")
            assertTrue("beagenter=0" in body && "isagent=0" in body)
        }
    }

    @Test
    fun `联动：两枪分开打，学号入学年与模板分类名各来自 assignInfo_64 与 assignInfo_43`() {
        withDzpzLogin { site, fake ->
            val api = TranscriptApi(site)
            val (ctx, linkage) = runBlocking {
                val ctx = api.loadCreateForm(WORKFLOW_ID)
                ctx to api.getLinkageData(ctx, TYPE_VALUE)
            }

            assertEquals(STUDENT_ID, linkage.studentId)
            assertEquals(ENROLL_YEAR, linkage.enrollYear)
            assertEquals(TEMPLATE_PATH, linkage.templatePath)
            assertEquals(CATEGORY_NAME, linkage.categoryName)
            assertEquals(WORKFLOW_ID.toString(), linkage.workflowIdField)

            // 两枪：第一枪问学籍（带 userId），第二枪问模板（带类型值）；两枪都带 linkageUUID
            assertEquals(2, fake.dzpz.linkageBodies.size)
            val (first, second) = fake.dzpz.linkageBodies
            // ⚠️ FormBody 会把 `,` 编码成 `%2C`（真请求里长的就是这个样子）
            assertTrue("field7250=$OA_ID" in first && "linkageid=43%2C64" in first)
            assertTrue("triSource=2" in first)
            assertTrue("field7243=$TYPE_VALUE" in second && "linkageid=43" in second)
            assertTrue("triSource=1" in second)
            assertTrue(fake.dzpz.linkageBodies.all { "linkageUUID=$LINKAGE_UUID" in it })
            assertTrue(fake.dzpz.linkageBodies.all { "f_weaver_belongto_userid=$OA_ID" in it })
        }
    }

    @Test
    fun `七步全跑通：文档 id 取纯文本 trim，两次提交的 token 与 sessionKey 各来自该来的地方`() {
        withDzpzLogin { site, fake ->
            val api = TranscriptApi(site)
            val result = runBlocking {
                val ctx = api.loadCreateForm(WORKFLOW_ID)
                val type = ctx.typeOptions.single().value
                val linkage = api.getLinkageData(ctx, type)
                val docId = api.generatePreviewPdf(ctx.workflowId, type)
                val first = api.submitCreate(ctx, linkage, type, docId)
                val second = api.reloadAndForward(ctx, first, type)
                api.getDownloadInfo(second) to Pair(docId, Pair(first, second))
            }
            val (info, steps) = result
            val (docId, submissions) = steps
            val (first, second) = submissions

            // ① 预览文档 id 是 procfiles 返回的**纯文本**（夹具里面是 `9001`，没有引号）
            assertEquals(DOC_ID, docId)
            assertTrue("fjmc=dzcjdyl" in fake.dzpz.procfilesBody.orEmpty(), "文件名那半是硬编码的")
            assertTrue("wfid=$WORKFLOW_ID" in fake.dzpz.procfilesBody.orEmpty())
            assertTrue("cjdlx=$TYPE_VALUE" in fake.dzpz.procfilesBody.orEmpty())

            // ② 第一次提交：requestId/sessionKey 来自 data.resultInfo，token 来自建单时的 submitParams
            assertEquals(REQUEST_ID, first.requestId)
            assertEquals(SESSION_KEY_FIRST, first.sessionKey)
            assertEquals(SUBMIT_TOKEN_FIRST, first.submitToken)

            // ③ 第二次提交：sessionKey 换成转发那一枪给的（resultInfo 优先、messageInfo 兜底），
            //    返回的 token 取自**转发那一枪的响应**（`<uid>_555_request_submit_token`），
            //    不是重载时读到的那个 —— 这两者容易混，所以这里钉死
            assertEquals(REQUEST_ID, second.requestId)
            assertEquals(SESSION_KEY_SECOND, second.sessionKey)
            assertEquals(SUBMIT_TOKEN_FORWARD, second.submitToken)

            // 两次提交的请求体：只有第一次带 iscreate=1 / src=save，第二次是 src=submit。
            // 第二次的表单字段是从**重载后的 maindata** 读回来的（`fieldVal` 那一族），
            // 所以那一枪里应当能看到夹具重载响应里的 field7249 / field7244 / field7237。
            assertEquals(2, fake.dzpz.submitBodies.size)
            val (firstBody, secondBody) = fake.dzpz.submitBodies
            assertTrue("src=save" in firstBody && "iscreate=1" in firstBody)
            assertTrue("field7237=$STUDENT_ID" in firstBody, "第一次提交要带联动拿到的学号")
            assertTrue("field7536=$ENROLL_YEAR" in firstBody)
            assertTrue("field7244=$DOC_ID" in firstBody, "第一次提交要带预览文档 id")
            assertTrue("mainFieldUnEmptyCount=12" in firstBody)
            assertTrue("src=submit" in secondBody && "iscreate=0" in secondBody)
            assertTrue("field7249=$DEFAULT_DATE" in secondBody, "第二次提交回填的是重载后的字段值")
            assertTrue("field7244=$DOC_ID" in secondBody)
            assertTrue("authStr=AUTH-FAKE" in secondBody, "第二次提交要先重载拿到新 auth 参数")

            // ④ checksubmit 那一枪：带 reqid/wfid/uid/日期/类型，返回值只当日志（夹具给 "1"）
            val check = fake.dzpz.checksubmitBody.orEmpty()
            assertTrue("reqid=$REQUEST_ID" in check && "wfid=$WORKFLOW_ID" in check)
            assertTrue("uid=$OA_ID" in check && "cjdlx=$TYPE_VALUE" in check)

            // ⑤ 下载信息：文件名/大小原样，相对 loadlink 拼上站点 BASE
            assertEquals(PDF_FILENAME, info.filename)
            assertEquals(PDF_FILESIZE, info.filesize)
            assertEquals("https://${DzpzFakeUpstream.HOST}$PDF_LOADLINK", info.downloadUrl)
        }
    }

    @Test
    fun `下载 PDF：带 Referer 取字节，非 200 就当失败`() {
        withDzpzLogin { site, fake ->
            val api = TranscriptApi(site)
            val bytes = runBlocking { api.downloadPdf("https://${DzpzFakeUpstream.HOST}$PDF_LOADLINK") }
            assertEquals(PDF_BYTES.toList(), bytes.toList())
            assertEquals(PDF_REFERER, fake.dzpz.downloadReferer, "下载那一枪的 Referer 是硬编码的")
        }
    }

    @Test
    fun `取下载信息：没有 field7564 时退回用预览文档 id 拼 FileDownload 地址`() {
        withDzpzLogin { site, fake ->
            fake.dzpz.downloadInfoFallback = true
            val api = TranscriptApi(site)
            val info = runBlocking {
                val ctx = api.loadCreateForm(WORKFLOW_ID)
                val type = ctx.typeOptions.single().value
                val linkage = api.getLinkageData(ctx, type)
                val docId = api.generatePreviewPdf(ctx.workflowId, type)
                val first = api.submitCreate(ctx, linkage, type, docId)
                val second = api.reloadAndForward(ctx, first, type)
                api.getDownloadInfo(second)
            }

            assertEquals(PDF_FILENAME, info.filename)
            assertEquals("", info.filesize)
            val url = info.downloadUrl
            assertTrue(url.startsWith("https://${DzpzFakeUpstream.HOST}/weaver/weaver.file.FileDownload?"), url)
            assertTrue("fileid=$DOC_ID" in url)
            assertTrue("requestid=$REQUEST_ID" in url)
            assertTrue("authStr=AUTH-FAKE" in url && "authSignatureStr=AUTHSIG-FAKE" in url)
            assertTrue("f_weaver_belongto_userid=$OA_ID" in url && "fromrequest=1" in url)
        }
    }
}
