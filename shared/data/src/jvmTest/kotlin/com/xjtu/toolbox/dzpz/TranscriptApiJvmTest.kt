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

/** 成绩单取数的字段级口径：`TranscriptApi` 对着 `:testkit` 的 [DzpzFakeUpstream] 逐行读。 */
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
                // 先保存再生成文件（#109）：procfiles 带上保存拿到的 reqid、sfybc=1
                val first = api.submitCreate(ctx, linkage, type)
                api.generatePreviewPdf(ctx.workflowId, type, first.requestId)
                val second = api.reloadAndForward(ctx, first, type)
                api.getDownloadInfo(second) to Pair(first, second)
            }
            val (info, submissions) = result
            val (first, second) = submissions

            assertTrue("reqid=$REQUEST_ID" in fake.dzpz.procfilesBody.orEmpty(), "生成文件要带保存拿到的申请单号")
            assertTrue("sfybc=1" in fake.dzpz.procfilesBody.orEmpty())
            assertTrue("fjmc=dzcjdyl" in fake.dzpz.procfilesBody.orEmpty(), "文件名那半是硬编码的")
            assertTrue("wfid=$WORKFLOW_ID" in fake.dzpz.procfilesBody.orEmpty())
            assertTrue("cjdlx=$TYPE_VALUE" in fake.dzpz.procfilesBody.orEmpty())

            assertEquals(REQUEST_ID, first.requestId)
            assertEquals(SESSION_KEY_FIRST, first.sessionKey)
            assertEquals(SUBMIT_TOKEN_FIRST, first.submitToken)

            assertEquals(REQUEST_ID, second.requestId)
            assertEquals(SESSION_KEY_SECOND, second.sessionKey)
            assertEquals(SUBMIT_TOKEN_FORWARD, second.submitToken)

            assertEquals(2, fake.dzpz.submitBodies.size)
            val (firstBody, secondBody) = fake.dzpz.submitBodies
            assertTrue("src=save" in firstBody && "iscreate=1" in firstBody)
            assertTrue("field7237=$STUDENT_ID" in firstBody, "第一次提交要带联动拿到的学号")
            assertTrue("field7536=$ENROLL_YEAR" in firstBody)
            assertTrue("field7244=&" in firstBody, "保存时成绩单文档 id 还没有，留空")
            assertTrue("mainFieldUnEmptyCount=11" in firstBody)
            assertTrue("src=submit" in secondBody && "iscreate=0" in secondBody)
            assertTrue("field7249=$DEFAULT_DATE" in secondBody, "第二次提交回填的是重载后的字段值")
            assertTrue("field7244=$DOC_ID" in secondBody)
            assertTrue("authStr=AUTH-FAKE" in secondBody, "第二次提交要先重载拿到新 auth 参数")

            val check = fake.dzpz.checksubmitBody.orEmpty()
            assertTrue("reqid=$REQUEST_ID" in check && "wfid=$WORKFLOW_ID" in check)
            assertTrue("uid=$OA_ID" in check && "cjdlx=$TYPE_VALUE" in check)

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
                val first = api.submitCreate(ctx, linkage, type)
                api.generatePreviewPdf(ctx.workflowId, type, first.requestId)
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
