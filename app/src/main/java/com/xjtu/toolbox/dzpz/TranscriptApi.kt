package com.xjtu.toolbox.dzpz

import com.xjtu.toolbox.util.requireObj
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.longValue
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.arr
import kotlinx.serialization.json.jsonObject
import com.xjtu.toolbox.util.redactUrl
import android.util.Log
import kotlinx.serialization.json.JsonObject
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.FormBody
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电子成绩单 API
 *
 * 工作流程：
 * 1. loadCreateForm() → 获取表单默认值 + 字段定义（含成绩单类型选项）
 * 2. getLinkageData() → 根据选定类型获取关联字段（学号、入学年份、模板路径等）
 * 3. generatePreviewPdf() → 生成成绩单预览 PDF，返回文档 ID
 * 4. submitCreate() → 第一次提交（创建流程）
 * 5. reloadAndForward() → 重新加载 + 校验 + 第二次提交（自动转发到下载节点）
 * 6. getDownloadInfo() → 获取最终的文件下载链接
 * 7. downloadPdf() → 下载 PDF 二进制文件
 */
class TranscriptApi(private val site: SiteSession) {

    companion object {
        private const val TAG = "TranscriptApi"
        private const val BASE = "https://dzpz.xjtu.edu.cn"
        // workflowId → 身份的映射改由 DzpzDocuments.kt 按「文件」维护（同一个引擎以后
        // 还要接别的证明文件），这里不再重复一份。
    }

    private val userId get() = site.localToken["user_id"] ?: error("未登录")

    private suspend fun execute(request: Request): String =
        site.executeWithReAuth(request).use { it.body?.string().orEmpty() }

    private suspend fun execute(builder: Request.Builder): String = execute(builder.build())

    // ══════════════════════════════════════
    //  数据类
    // ══════════════════════════════════════

    /** 成绩单类型选项（从 loadForm 的 field 定义中解析） */
    data class TranscriptTypeOption(
        val name: String,   // 显示名称，如 "本科生中文成绩单"
        val value: Int,     // 选项值，如 0
        val cancelled: Boolean = false  // 是否已取消
    )

    /** 表单上下文 — 包含后续操作所需的全部状态 */
    data class FormContext(
        val workflowId: Int,
        val params: JsonObject,
        val submitParams: JsonObject,
        val maindata: JsonObject,
        val typeOptions: List<TranscriptTypeOption>,
        val linkageUUID: String,
        val signatureAttributesStr: String,
        val signatureSecretKey: String,
        val defaultDate: String,
        val defaultRequestName: String
    )

    /** 联动查询结果 */
    data class LinkageResult(
        val studentId: String,       // 学号 (field7237)
        val enrollYear: String,      // 入学年份 (field7536)
        val templatePath: String,    // CPT 模板路径 (field7247)
        val categoryName: String,    // 业务分类名 (field7241)
        val workflowIdField: String  // 流程 ID (field7245)
    )

    /** 提交结果 */
    data class SubmitResult(
        val requestId: Int,
        val sessionKey: String,
        val submitToken: Long
    )

    /** 下载信息 */
    data class DownloadInfo(
        val filename: String,
        val downloadUrl: String,
        val filesize: String
    )

    // ══════════════════════════════════════
    //  Step 1: 加载创建表单
    // ══════════════════════════════════════

    suspend fun loadCreateForm(workflowId: Int): FormContext {
        Log.d(TAG, "loadCreateForm: workflowId=$workflowId")
        val body = FormBody.Builder()
            .add("beagenter", "0")
            .add("isagent", "0")
            .add("iscreate", "1")
            .add("workflowid", workflowId.toString())
            .build()

        val request = Request.Builder()
            .url("$BASE/api/workflow/reqform/loadForm")
            .post(body)
            .build()

        val responseBody = execute(Request.Builder()
            .url("$BASE/api/workflow/reqform/loadForm")
            .post(body))
        val json = responseBody.safeParseJsonObject()

        val params = json.requireObj("params")
        val submitParams = json.requireObj("submitParams")
        val maindata = json.requireObj("maindata")

        // 提取成绩单类型选项 (field7243 的 selectattr.selectitemlist)
        val tableInfo = json.requireObj("tableInfo")
        val typeOptions = parseTypeOptions(tableInfo)

        val linkageUUID = params.get("linkageUUID")?.stringValue ?: ""
        val sigAttrStr = params.get("signatureAttributesStr")?.stringValue ?: ""
        val sigSecret = params.get("signatureSecretKey")?.stringValue ?: ""

        // 默认日期和请求名
        val dt = maindata.obj("field7249")?.get("value")?.stringValue
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val reqName = maindata.obj("field-1")?.get("value")?.stringValue ?: ""

        Log.d(TAG, "loadCreateForm: got ${typeOptions.size} type options, linkageUUID=$linkageUUID")

        return FormContext(
            workflowId = workflowId,
            params = params,
            submitParams = submitParams,
            maindata = maindata,
            typeOptions = typeOptions,
            linkageUUID = linkageUUID,
            signatureAttributesStr = sigAttrStr,
            signatureSecretKey = sigSecret,
            defaultDate = dt,
            defaultRequestName = reqName
        )
    }

    private fun parseTypeOptions(tableInfo: JsonObject): List<TranscriptTypeOption> {
        try {
            val mainInfo = tableInfo.obj("main") ?: return emptyList()
            val fieldMap = mainInfo.obj("fieldinfomap") ?: return emptyList()
            val field7243 = fieldMap.obj("7243") ?: return emptyList()
            val selectAttr = field7243.obj("selectattr") ?: return emptyList()
            val items = selectAttr.arr("selectitemlist") ?: return emptyList()
            return items.mapNotNull { elem ->
                val obj = elem.jsonObject
                val name = obj.get("selectname")?.stringValue ?: return@mapNotNull null
                val value = obj.get("selectvalue")?.intValue ?: return@mapNotNull null
                val cancel = obj.get("cancel")?.intValue ?: 0
                TranscriptTypeOption(name, value, cancel == 1)
            }.filter { !it.cancelled }
        } catch (e: Exception) {
            Log.e(TAG, "parseTypeOptions failed", e)
            return emptyList()
        }
    }

    // ══════════════════════════════════════
    //  Step 2: 联动查询 — 获取学号/入学年/模板
    // ══════════════════════════════════════

    suspend fun getLinkageData(ctx: FormContext, typeValue: Int): LinkageResult {
        Log.d(TAG, "getLinkageData: typeValue=$typeValue")

        // 联动 1: 获取学号和入学年（触发字段 field7250=userId + field7243）
        val body1 = FormBody.Builder()
            .add("requestid", "-1")
            .add("workflowid", ctx.workflowId.toString())
            .add("nodeid", "49")
            .add("formid", "-14")
            .add("isbill", "1")
            .add("triSource", "2")
            .add("showAI", "0")
            .add("triFieldid_43", "7243")
            .add("rowIndexStr_43", "-1")
            .add("triTableMark_43", "main")
            .add("field7243", "")
            .add("triFieldid_64", "7250")
            .add("rowIndexStr_64", "-1")
            .add("triTableMark_64", "main")
            .add("field7250", userId)
            .add("linkageid", "43,64")
            .add("linkageUUID", ctx.linkageUUID)
            .add("wfTestStr", "")
            .add("f_weaver_belongto_userid", userId)
            .add("f_weaver_belongto_usertype", "0")
            .build()

        val json1 = execute(
            Request.Builder()
                .url("$BASE/api/workflow/linkage/reqDataInputResult")
                .post(body1)
                .build()
        ).safeParseJsonObject()

        val assign64 = json1.obj("assignInfo_64")
            ?.obj("changeValue")
        val studentId = assign64?.obj("field7237")?.get("value")?.stringValue ?: ""
        val enrollYear = assign64?.obj("field7536")?.get("value")?.stringValue ?: ""

        // 联动 2: 获取模板路径和业务分类名（触发字段 field7243=typeValue）
        val body2 = FormBody.Builder()
            .add("requestid", "-1")
            .add("workflowid", ctx.workflowId.toString())
            .add("nodeid", "49")
            .add("formid", "-14")
            .add("isbill", "1")
            .add("triSource", "1")
            .add("showAI", "0")
            .add("triFieldid_43", "7243")
            .add("rowIndexStr_43", "-1")
            .add("triTableMark_43", "main")
            .add("field7243", typeValue.toString())
            .add("linkageid", "43")
            .add("linkageUUID", ctx.linkageUUID)
            .add("wfTestStr", "")
            .add("f_weaver_belongto_userid", userId)
            .add("f_weaver_belongto_usertype", "0")
            .build()

        val json2 = execute(
            Request.Builder()
                .url("$BASE/api/workflow/linkage/reqDataInputResult")
                .post(body2)
                .build()
        ).safeParseJsonObject()

        val assign43 = json2.obj("assignInfo_43")
            ?.obj("changeValue")
        val templatePath = assign43?.obj("field7247")?.get("value")?.stringValue ?: ""
        val categoryName = assign43?.obj("field7241")?.get("value")?.stringValue ?: ""

        Log.d(TAG, "getLinkageData: studentId=$studentId, enrollYear=$enrollYear, " +
                "template=$templatePath, category=$categoryName")

        return LinkageResult(
            studentId = studentId,
            enrollYear = enrollYear,
            templatePath = templatePath,
            categoryName = categoryName,
            workflowIdField = ctx.workflowId.toString()
        )
    }

    // ══════════════════════════════════════
    //  Step 3: 生成成绩单预览 PDF
    // ══════════════════════════════════════

    suspend fun generatePreviewPdf(workflowId: Int, typeValue: Int): String {
        Log.d(TAG, "generatePreviewPdf: wfId=$workflowId, type=$typeValue")
        val body = FormBody.Builder()
            .add("reqid", "-1")
            .add("wfid", workflowId.toString())
            .add("uid", userId)
            .add("fjmc", "dzcjdyl")
            .add("cjdwj", "")
            .add("sfybc", "0")
            .add("cjdlx", typeValue.toString())
            .build()

        val docId = execute(
            Request.Builder()
                .url("$BASE/api/xjtuapi/procfiles")
                .post(body)
                .build()
        ).trim().ifEmpty { error("生成成绩单失败：服务器无响应") }
        Log.d(TAG, "generatePreviewPdf: docId=$docId")
        return docId
    }

    // ══════════════════════════════════════
    //  Step 4: 第一次提交（创建流程 → 获取 requestId）
    // ══════════════════════════════════════

    suspend fun submitCreate(
        ctx: FormContext,
        linkage: LinkageResult,
        typeValue: Int,
        docId: String
    ): SubmitResult {
        Log.d(TAG, "submitCreate: docId=$docId, typeValue=$typeValue")

        val token = ctx.submitParams.get("${userId}_${ctx.workflowId}_addrequest_submit_token")
            ?.longValue ?: System.currentTimeMillis()

        val body = FormBody.Builder()
            .add("formid", "-14")
            .add("f_weaver_belongto_userid", userId)
            .add("isWorkflowDoc", "false")
            .add("f_weaver_belongto_usertype", "0")
            .add("nodetype", "0")
            .add("method", "")
            .add("needoutprint", "")
            .add("src", "save")
            .add("isMultiDoc", "")
            .add("topage", "")
            .add("${userId}_${ctx.workflowId}_addrequest_submit_token", token.toString())
            .add("workflowtype", "26")
            .add("iscreate", "1")
            .add("comemessage", "")
            .add("remindTypes", "")
            .add("rand", "")
            .add("requestid", "-1")
            .add("linkageUUID", ctx.linkageUUID)
            .add("htmlfieldids", "")
            .add("needwfback", "")
            .add("lastloginuserid", userId)
            .add("nodeid", "49")
            .add("workflowid", ctx.workflowId.toString())
            .add("isbill", "1")
            .add("isOdocRequest", "0")
            .add("enableIntervenor", "")
            .add("linkageUnFinishedKey", "")
            .add("remark", "")
            .add("remarkquote", "")
            .add("actiontype", "requestOperation")
            .add("closePage", "false")
            .add("type", "save")
            .add("isFirstSubmit", "")
            .add("existChangeRange",
                "field7536,field7237,field7245,field7243,field7247,field7241,field7244,field7501")
            // ── 表单字段 ──
            .add("field7249", ctx.defaultDate)
            .add("field7501", "1")       // 份数
            .add("requestname", ctx.defaultRequestName)
            .add("requestlevel", "0")    // 紧急程度：正常
            .add("field7240", "西安交通大学")
            .add("field7250", userId)    // 创建人
            .add("field7246", userId)    // 申请人
            .add("field7243", typeValue.toString())
            .add("field-10", "")
            .add("field7237", linkage.studentId)
            .add("field7239", "")        // 邮箱
            .add("field7238", "")        // 电话
            .add("field7502", "")
            .add("field7536", linkage.enrollYear)
            .add("field7244", docId)     // 成绩单预览文档 ID
            .add("field7241", linkage.categoryName)
            .add("field7504", "")
            .add("field7247", linkage.templatePath)
            .add("field7245", linkage.workflowIdField)
            .add("mainFieldUnEmptyCount", "12")
            .add("detailFieldUnEmptyCount", "0")
            .add("signatureAttributesStr", ctx.signatureAttributesStr)
            .add("signatureSecretKey", ctx.signatureSecretKey)
            .add("selectNextFlow", "0")
            .add("openDataVerify", "0")
            .add("wfTestStr", "")
            .build()

        val json = execute(
            Request.Builder()
                .url("$BASE/api/workflow/reqform/requestOperation")
                .post(body)
                .build()
        ).safeParseJsonObject()

        val data = json.obj("data")
            ?: error("提交失败：${json.get("message")?.stringValue ?: "未知错误"}")
        val type = data.get("type")?.stringValue
        if (type != "SUCCESS") {
            error("提交失败：$type")
        }

        val resultInfo = data.requireObj("resultInfo")
        val requestId = resultInfo.get("requestid").intValue
        val sessionKey = resultInfo.get("sessionkey").stringValue
        val submitData = data.obj("submitParams")
        val newToken = submitData?.get("${userId}_${ctx.workflowId}_addrequest_submit_token")
            ?.longValue ?: (submitData?.get("${userId}_${requestId}_request_submit_token")?.longValue
            ?: System.currentTimeMillis())

        Log.d(TAG, "submitCreate: requestId=$requestId, sessionKey=$sessionKey")
        return SubmitResult(requestId, sessionKey, newToken)
    }

    // ══════════════════════════════════════
    //  Step 5: 重新加载 → 校验 → 第二次提交
    // ══════════════════════════════════════

    suspend fun reloadAndForward(
        ctx: FormContext,
        firstResult: SubmitResult,
        typeValue: Int
    ): SubmitResult {
        Log.d(TAG, "reloadAndForward: requestId=${firstResult.requestId}")

        // 5a: 重新加载表单（获取新的 auth 参数）
        val loadBody = FormBody.Builder()
            .add("belongTest", "false")
            .add("f_weaver_belongto_userid", userId)
            .add("f_weaver_belongto_usertype", "0")
            .add("isOpenContinuationProcess", "undefined")
            .add("isaffirmance", "0")
            .add("needRemind", "false")
            .add("requestid", firstResult.requestId.toString())
            .add("saveType", "undefined")
            .add("selectNextFlow", "0")
            .add("sessionkey", firstResult.sessionKey)
            .build()

        val loadJson = execute(
            Request.Builder()
                .url("$BASE/api/workflow/reqform/loadForm")
                .post(loadBody)
                .build()
        ).safeParseJsonObject()
        val newParams = loadJson.obj("params")
        val newSubmitParams = loadJson.obj("submitParams")
        val newMaindata = loadJson.obj("maindata")

        val authStr = newParams?.get("authStr")?.stringValue ?: ""
        val authSigStr = newParams?.get("authSignatureStr")?.stringValue ?: ""
        val newSigAttr = newParams?.get("signatureAttributesStr")?.stringValue ?: ""
        val newSigSecret = newParams?.get("signatureSecretKey")?.stringValue ?: ""
        val newLinkageUUID = newParams?.get("linkageUUID")?.stringValue ?: ""
        val currentDate = newParams?.get("lastOperateDate")?.stringValue ?: ctx.defaultDate
        val currentTime = newParams?.get("lastOperateTime")?.stringValue ?: ""

        // 5b: 提交前校验
        val checkBody = FormBody.Builder()
            .add("reqid", firstResult.requestId.toString())
            .add("wfid", ctx.workflowId.toString())
            .add("uid", userId)
            .add("sqrq", ctx.defaultDate)
            .add("cjdlx", typeValue.toString())
            .build()

        val checkResult = execute(
            Request.Builder()
                .url("$BASE/api/xjtuapi/checksubmit")
                .post(checkBody)
                .build()
        ).trim().ifEmpty { "1" }
        Log.d(TAG, "reloadAndForward: checksubmit=$checkResult")

        // 5c: 读取表单字段值
        fun fieldVal(fieldName: String): String {
            return newMaindata?.obj(fieldName)?.get("value")?.stringValue ?: ""
        }

        val submitToken = newSubmitParams?.get("${userId}_${firstResult.requestId}_request_submit_token")
            ?.longValue ?: firstResult.submitToken
        val addToken = newSubmitParams?.get("${userId}_${ctx.workflowId}_addrequest_submit_token")
            ?.longValue ?: firstResult.submitToken

        // 5d: 第二次提交（转发到下载节点）
        val submitBody = FormBody.Builder()
            .add("formid", "-14")
            .add("isSubmitDirectNode", "")
            .add("openByDefaultBrowser", "")
            .add("iscreate", "0")
            .add("creatertype", "0")
            .add("isdialog", "1")
            .add("${userId}_${firstResult.requestId}_request_submit_token", submitToken.toString())
            .add("lastOperateDate", currentDate)
            .add("createdoc", "")
            .add("nodeid", "49")
            .add("workflowid", ctx.workflowId.toString())
            .add("isbill", "1")
            .add("authStr", authStr)
            .add("f_weaver_belongto_userid", userId)
            .add("currenttime", currentTime)
            .add("f_weaver_belongto_usertype", "0")
            .add("agentorByAgentId", "-1")
            .add("isMultiDoc", "")
            .add("inputcheck", "")
            .add("comemessage", "")
            .add("lastOperateTime", currentTime)
            .add("temphasUseTempletSucceed", "")
            .add("workflowRequestLogId", "")
            .add("edesign_layout", "")
            .add("requestid", firstResult.requestId.toString())
            .add("isremark", "0")
            .add("creater", userId)
            .add("htmlfieldids", "")
            .add("SubmitToNodeid", "")
            .add("isCptwf", "false")
            .add("isovertime", "")
            .add("agentType", "0")
            .add("authSignatureStr", authSigStr)
            .add("nodetype", "0")
            .add("needoutprint", "")
            .add("lastOperator", userId)
            .add("topage", "")
            .add("${userId}_${ctx.workflowId}_addrequest_submit_token", addToken.toString())
            .add("isFormSignature", "0")
            .add("remindTypes", "")
            .add("fromFlowDoc", "")
            .add("RejectNodes", "")
            .add("linkageUUID", newLinkageUUID)
            .add("billid", newParams?.get("billid")?.stringValue ?: "")
            .add("lastnodeid", "")
            .add("uploadType", "")
            .add("isSignMustInput", "")
            .add("RejectToNodeid", "")
            .add("isWorkflowDoc", "false")
            .add("src", "submit")
            .add("annexmaxUploadImageSize", "")
            .add("takisremark", "0")
            .add("workflowtype", "26")
            .add("remarkLocation", "")
            .add("needcheck", "")
            .add("needcheckLock", "false")
            .add("selectfieldvalue", "")
            .add("RejectToType", "")
            .add("currentdate", currentDate)
            .add("needwfback", "0")
            .add("isOdocRequest", "0")
            .add("enableIntervenor", "")
            .add("verifyRequiredRange", "field-9999,field7243,")
            .add("linkageUnFinishedKey", "")
            .add("remark", "")
            .add("remarkquote", "")
            .add("actiontype", "requestOperation")
            .add("isFirstSubmit", "0")
            .add("existChangeRange", "")
            // ── 表单字段 ──
            .add("field7249", fieldVal("field7249"))
            .add("field7502", fieldVal("field7502"))
            .add("field7248", fieldVal("field7248"))
            .add("field7501", fieldVal("field7501"))
            .add("field7247", fieldVal("field7247"))
            .add("field7505", fieldVal("field7505"))
            .add("field7504", fieldVal("field7504"))
            .add("field7242", fieldVal("field7242"))
            .add("field7241", fieldVal("field7241"))
            .add("field7240", fieldVal("field7240"))
            .add("field-9", fieldVal("field-9"))
            .add("field7246", fieldVal("field7246"))
            .add("field7245", fieldVal("field7245"))
            .add("field7564", fieldVal("field7564"))
            .add("field7244", fieldVal("field7244"))
            .add("field7243", fieldVal("field7243"))
            .add("field7239", fieldVal("field7239"))
            .add("field7536", fieldVal("field7536"))
            .add("field7238", fieldVal("field7238"))
            .add("field7237", fieldVal("field7237"))
            .add("field7250", fieldVal("field7250"))
            .add("requestname", newMaindata?.obj("field-1")?.get("value")?.stringValue ?: "")
            .add("requestlevel", "0")
            .add("field-10", "")
            .add("chatsType", "-1")
            .add("messageType", "-1")
            .add("mainFieldUnEmptyCount", "12")
            .add("detailFieldUnEmptyCount", "0")
            .add("signatureAttributesStr", newSigAttr)
            .add("signatureSecretKey", newSigSecret)
            .add("selectNextFlow", "0")
            .add("openDataVerify", "0")
            .add("wfTestStr", "")
            .build()

        val submitJson = execute(
            Request.Builder()
                .url("$BASE/api/workflow/reqform/requestOperation")
                .post(submitBody)
                .build()
        ).safeParseJsonObject()

        val data = submitJson.obj("data")
        if (data == null) {
            // 提取详细错误信息
            val message = submitJson.get("message")?.stringValue
            val errorMsg = submitJson.get("errorMsg")?.stringValue
            val tips = submitJson.get("tips")?.stringValue
            val detail = errorMsg ?: message ?: tips ?: "未知错误"
            Log.e(TAG, "reloadAndForward: submit failed, no data object. detail=$detail")
            error("转发失败：$detail")
        }
        
        val resultType = data.get("type")?.stringValue
        if (resultType != "SUCCESS") {
            // 提取详细错误信息
            val msgInfo = data.obj("messageInfo")
            val errorMsg = msgInfo?.get("message")?.stringValue
                ?: data.get("message")?.stringValue
                ?: data.get("errorMsg")?.stringValue
            val detail = errorMsg ?: resultType ?: "提交被拒绝"
            Log.e(TAG, "reloadAndForward: resultType=$resultType, errorMsg=$errorMsg")
            error("转发失败：$detail")
        }

        val msgInfo = data.obj("messageInfo")
        val resultInfo = data.obj("resultInfo")
        val newSessionKey = resultInfo?.get("sessionkey")?.stringValue
            ?: msgInfo?.get("sessionkey")?.stringValue ?: ""
        val forwardSubmitParams = data.obj("submitParams")
        val newSubmitToken = forwardSubmitParams?.get("${userId}_${firstResult.requestId}_request_submit_token")
            ?.longValue ?: System.currentTimeMillis()

        Log.d(TAG, "reloadAndForward: SUCCESS, newSessionKey=$newSessionKey, " +
                "nextNode=${msgInfo?.get("nextNodeNames")?.stringValue}")

        return SubmitResult(firstResult.requestId, newSessionKey, newSubmitToken)
    }

    // ══════════════════════════════════════
    //  Step 6: 获取下载链接
    // ══════════════════════════════════════

    suspend fun getDownloadInfo(secondResult: SubmitResult): DownloadInfo {
        Log.d(TAG, "getDownloadInfo: requestId=${secondResult.requestId}")

        val body = FormBody.Builder()
            .add("belongTest", "false")
            .add("f_weaver_belongto_userid", userId)
            .add("f_weaver_belongto_usertype", "0")
            .add("isOpenContinuationProcess", "undefined")
            .add("isRefresh", "1")
            .add("isShowChart", "3")
            .add("isaffirmance", "0")
            .add("needRemind", "false")
            .add("requestid", secondResult.requestId.toString())
            .add("saveType", "undefined")
            .add("sessionkey", secondResult.sessionKey)
            .build()

        val json = execute(
            Request.Builder()
                .url("$BASE/api/workflow/reqform/loadForm")
                .post(body)
                .build()
        ).safeParseJsonObject()
        val maindata = json.obj("maindata")

        // 从 field7564 提取下载链接
        val field7564 = maindata?.obj("field7564")
        val specialobj = field7564?.obj("specialobj")
        val filedatas = specialobj?.arr("filedatas")

        if (filedatas != null && filedatas.size > 0) {
            val fileData = filedatas[0].jsonObject
            val filename = fileData.get("filename")?.stringValue ?: "成绩单.pdf"
            val loadlink = fileData.get("loadlink")?.stringValue
                ?: error("下载链接不存在")
            val filesize = fileData.get("filesize")?.stringValue ?: ""

            Log.d(TAG, "getDownloadInfo: filename=$filename, size=$filesize")
            return DownloadInfo(
                filename = filename,
                downloadUrl = if (loadlink.startsWith("http")) loadlink else "$BASE$loadlink",
                filesize = filesize
            )
        }

        // Fallback: 从 field7244 提取文档 ID 后构建下载 URL
        val field7244 = maindata?.obj("field7244")
        val docId = field7244?.get("value")?.stringValue
        if (docId != null) {
            val params2 = json.obj("params")
            val authStr2 = params2?.get("authStr")?.stringValue ?: ""
            val authSig2 = params2?.get("authSignatureStr")?.stringValue ?: ""
            val docName = field7244.obj("specialobj")?.get("name")?.stringValue ?: "成绩单.pdf"
            val dlUrl = "$BASE/weaver/weaver.file.FileDownload?fileid=$docId&download=1" +
                    "&requestid=${secondResult.requestId}&desrequestid=0" +
                    "&authStr=$authStr2&authSignatureStr=$authSig2" +
                    "&f_weaver_belongto_userid=$userId&f_weaver_belongto_usertype=0&fromrequest=1"
            Log.d(TAG, "getDownloadInfo: fallback URL with docId=$docId")
            return DownloadInfo(filename = docName, downloadUrl = dlUrl, filesize = "")
        }

        error("无法获取下载链接：成绩单文件尚未生成")
    }

    // ══════════════════════════════════════
    //  Step 7: 下载 PDF
    // ══════════════════════════════════════

    suspend fun downloadPdf(url: String): ByteArray {
        Log.d(TAG, "downloadPdf: url=${url.redactUrl()}")
        val request = Request.Builder()
            .url(url)
            .header("Referer", "$BASE/spa/workflow/static4form/index.html")
            .get()
            .build()
        val response = site.executeWithReAuth(request)
        if (response.code != 200) {
            error("下载失败：HTTP ${response.code}")
        }
        return response.body?.bytes() ?: error("下载失败：空响应")
    }
}
