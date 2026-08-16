package com.xjtu.toolbox.dzpz

import com.xjtu.toolbox.auth.DzpzLogin
import com.xjtu.toolbox.util.Logger
import com.xjtu.toolbox.util.currentTimeMillis
import com.xjtu.toolbox.util.safeParseJsonObject
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.Parameters
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private const val TAG = "TranscriptApi"

class TranscriptApi(private val login: DzpzLogin) {

    companion object {
        private const val BASE = DzpzLogin.BASE_URL

        val WORKFLOW_MAP = mapOf(
            "在校本科生" to 29,
            "研究生" to 34,
            "已毕业本科(校友)" to 46,
            "研究生校友" to 49
        )
    }

    private val client get() = login.client
    private val userId get() = login.userId ?: error("未登录")

    // ══════════════════════════════════════
    //  数据类
    // ══════════════════════════════════════

    data class TranscriptTypeOption(
        val name: String, val value: Int, val cancelled: Boolean = false
    )

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

    data class LinkageResult(
        val studentId: String, val enrollYear: String,
        val templatePath: String, val categoryName: String,
        val workflowIdField: String
    )

    data class SubmitResult(
        val requestId: Int, val sessionKey: String, val submitToken: Long
    )

    data class DownloadInfo(
        val filename: String, val downloadUrl: String, val filesize: String
    )

    // ══════════════════════════════════════
    //  Step 1: 加载创建表单
    // ══════════════════════════════════════

    suspend fun loadCreateForm(workflowId: Int): FormContext {
        Logger.d(TAG, "loadCreateForm: workflowId=$workflowId")

        val body = login.executeWithReAuth {
            val resp = client.submitForm(
                url = "$BASE/api/workflow/reqform/loadForm",
                formParameters = Parameters.build {
                    append("beagenter", "0"); append("isagent", "0")
                    append("iscreate", "1"); append("workflowid", workflowId.toString())
                }
            )
            resp.status.value to resp.bodyAsText()
        }

        val json = body.safeParseJsonObject()
        val params = json["params"]!!.jsonObject
        val submitParams = json["submitParams"]!!.jsonObject
        val maindata = json["maindata"]!!.jsonObject

        val tableInfo = json["tableInfo"]!!.jsonObject
        val typeOptions = parseTypeOptions(tableInfo)

        val linkageUUID = params["linkageUUID"]?.jsonPrimitive?.content ?: ""
        val sigAttrStr = params["signatureAttributesStr"]?.jsonPrimitive?.content ?: ""
        val sigSecret = params["signatureSecretKey"]?.jsonPrimitive?.content ?: ""

        val dt = maindata["field7249"]?.jsonObject?.get("value")?.jsonPrimitive?.content
            ?: Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        val reqName = maindata["field-1"]?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""

        Logger.d(TAG, "loadCreateForm: got ${typeOptions.size} type options, linkageUUID=$linkageUUID")

        return FormContext(
            workflowId = workflowId, params = params, submitParams = submitParams,
            maindata = maindata, typeOptions = typeOptions, linkageUUID = linkageUUID,
            signatureAttributesStr = sigAttrStr, signatureSecretKey = sigSecret,
            defaultDate = dt, defaultRequestName = reqName
        )
    }

    private fun parseTypeOptions(tableInfo: JsonObject): List<TranscriptTypeOption> {
        return try {
            val mainInfo = tableInfo["main"]?.jsonObject ?: return emptyList()
            val fieldMap = mainInfo["fieldinfomap"]?.jsonObject ?: return emptyList()
            val field7243 = fieldMap["7243"]?.jsonObject ?: return emptyList()
            val selectAttr = field7243["selectattr"]?.jsonObject ?: return emptyList()
            val items = selectAttr["selectitemlist"]?.jsonArray ?: return emptyList()
            items.mapNotNull { elem ->
                val obj = elem.jsonObject
                val name = obj["selectname"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val value = obj["selectvalue"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
                val cancel = obj["cancel"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                TranscriptTypeOption(name, value, cancel == 1)
            }.filter { !it.cancelled }
        } catch (e: Exception) {
            Logger.e(TAG, "parseTypeOptions failed", e)
            emptyList()
        }
    }

    // ══════════════════════════════════════
    //  Step 2: 联动查询
    // ══════════════════════════════════════

    suspend fun getLinkageData(ctx: FormContext, typeValue: Int): LinkageResult {
        Logger.d(TAG, "getLinkageData: typeValue=$typeValue")

        val body1 = client.submitForm(
            url = "$BASE/api/workflow/linkage/reqDataInputResult",
            formParameters = Parameters.build {
                append("requestid", "-1"); append("workflowid", ctx.workflowId.toString())
                append("nodeid", "49"); append("formid", "-14")
                append("isbill", "1"); append("triSource", "2"); append("showAI", "0")
                append("triFieldid_43", "7243"); append("rowIndexStr_43", "-1")
                append("triTableMark_43", "main"); append("field7243", "")
                append("triFieldid_64", "7250"); append("rowIndexStr_64", "-1")
                append("triTableMark_64", "main"); append("field7250", userId)
                append("linkageid", "43,64"); append("linkageUUID", ctx.linkageUUID)
                append("wfTestStr", ""); append("f_weaver_belongto_userid", userId)
                append("f_weaver_belongto_usertype", "0")
            }
        ).bodyAsText()
        val json1 = body1.safeParseJsonObject()

        val assign64 = json1["assignInfo_64"]?.jsonObject?.get("changeValue")?.jsonObject
        val studentId = assign64?.get("field7237")?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""
        val enrollYear = assign64?.get("field7536")?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""

        val body2 = client.submitForm(
            url = "$BASE/api/workflow/linkage/reqDataInputResult",
            formParameters = Parameters.build {
                append("requestid", "-1"); append("workflowid", ctx.workflowId.toString())
                append("nodeid", "49"); append("formid", "-14")
                append("isbill", "1"); append("triSource", "1"); append("showAI", "0")
                append("triFieldid_43", "7243"); append("rowIndexStr_43", "-1")
                append("triTableMark_43", "main"); append("field7243", typeValue.toString())
                append("linkageid", "43"); append("linkageUUID", ctx.linkageUUID)
                append("wfTestStr", ""); append("f_weaver_belongto_userid", userId)
                append("f_weaver_belongto_usertype", "0")
            }
        ).bodyAsText()
        val json2 = body2.safeParseJsonObject()

        val assign43 = json2["assignInfo_43"]?.jsonObject?.get("changeValue")?.jsonObject
        val templatePath = assign43?.get("field7247")?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""
        val categoryName = assign43?.get("field7241")?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""

        Logger.d(TAG, "getLinkageData: studentId=$studentId, enrollYear=$enrollYear, template=$templatePath")

        return LinkageResult(
            studentId = studentId, enrollYear = enrollYear,
            templatePath = templatePath, categoryName = categoryName,
            workflowIdField = ctx.workflowId.toString()
        )
    }

    // ══════════════════════════════════════
    //  Step 3: 生成成绩单预览 PDF
    // ══════════════════════════════════════

    suspend fun generatePreviewPdf(workflowId: Int, typeValue: Int): String {
        Logger.d(TAG, "generatePreviewPdf: wfId=$workflowId, type=$typeValue")
        val docId = client.submitForm(
            url = "$BASE/api/xjtuapi/procfiles",
            formParameters = Parameters.build {
                append("reqid", "-1"); append("wfid", workflowId.toString())
                append("uid", userId); append("fjmc", "dzcjdyl")
                append("cjdwj", ""); append("sfybc", "0")
                append("cjdlx", typeValue.toString())
            }
        ).bodyAsText().trim()
        Logger.d(TAG, "generatePreviewPdf: docId=$docId")
        return docId.ifEmpty { error("生成成绩单失败：服务器无响应") }
    }

    // ══════════════════════════════════════
    //  Step 4: 第一次提交
    // ══════════════════════════════════════

    suspend fun submitCreate(
        ctx: FormContext, linkage: LinkageResult, typeValue: Int, docId: String
    ): SubmitResult {
        Logger.d(TAG, "submitCreate: docId=$docId, typeValue=$typeValue")

        val token = ctx.submitParams["${userId}_${ctx.workflowId}_addrequest_submit_token"]
            ?.jsonPrimitive?.long ?: currentTimeMillis()

        val body = client.submitForm(
            url = "$BASE/api/workflow/reqform/requestOperation",
            formParameters = Parameters.build {
                append("formid", "-14"); append("f_weaver_belongto_userid", userId)
                append("isWorkflowDoc", "false"); append("f_weaver_belongto_usertype", "0")
                append("nodetype", "0"); append("method", ""); append("needoutprint", "")
                append("src", "save"); append("isMultiDoc", ""); append("topage", "")
                append("${userId}_${ctx.workflowId}_addrequest_submit_token", token.toString())
                append("workflowtype", "26"); append("iscreate", "1")
                append("comemessage", ""); append("remindTypes", ""); append("rand", "")
                append("requestid", "-1"); append("linkageUUID", ctx.linkageUUID)
                append("htmlfieldids", ""); append("needwfback", "")
                append("lastloginuserid", userId); append("nodeid", "49")
                append("workflowid", ctx.workflowId.toString()); append("isbill", "1")
                append("isOdocRequest", "0"); append("enableIntervenor", "")
                append("linkageUnFinishedKey", ""); append("remark", "")
                append("remarkquote", ""); append("actiontype", "requestOperation")
                append("closePage", "false"); append("type", "save")
                append("isFirstSubmit", "")
                append("existChangeRange", "field7536,field7237,field7245,field7243,field7247,field7241,field7244,field7501")
                append("field7249", ctx.defaultDate); append("field7501", "1")
                append("requestname", ctx.defaultRequestName); append("requestlevel", "0")
                append("field7240", "西安交通大学"); append("field7250", userId)
                append("field7246", userId); append("field7243", typeValue.toString())
                append("field-10", ""); append("field7237", linkage.studentId)
                append("field7239", ""); append("field7238", ""); append("field7502", "")
                append("field7536", linkage.enrollYear); append("field7244", docId)
                append("field7241", linkage.categoryName); append("field7504", "")
                append("field7247", linkage.templatePath)
                append("field7245", linkage.workflowIdField)
                append("mainFieldUnEmptyCount", "12"); append("detailFieldUnEmptyCount", "0")
                append("signatureAttributesStr", ctx.signatureAttributesStr)
                append("signatureSecretKey", ctx.signatureSecretKey)
                append("selectNextFlow", "0"); append("openDataVerify", "0")
                append("wfTestStr", "")
            }
        ).bodyAsText()

        val json = body.safeParseJsonObject()
        val data = json["data"]?.jsonObject
            ?: error("提交失败：${json["message"]?.jsonPrimitive?.content ?: "未知错误"}")
        val type = data["type"]?.jsonPrimitive?.content
        if (type != "SUCCESS") error("提交失败：$type")

        val resultInfo = data["resultInfo"]!!.jsonObject
        val requestId = resultInfo["requestid"]!!.jsonPrimitive.int
        val sessionKey = resultInfo["sessionkey"]!!.jsonPrimitive.content
        val submitData = data["submitParams"]?.jsonObject
        val newToken = submitData?.get("${userId}_${ctx.workflowId}_addrequest_submit_token")?.jsonPrimitive?.long
            ?: (submitData?.get("${userId}_${requestId}_request_submit_token")?.jsonPrimitive?.long
                ?: currentTimeMillis())

        Logger.d(TAG, "submitCreate: requestId=$requestId, sessionKey=$sessionKey")
        return SubmitResult(requestId, sessionKey, newToken)
    }

    // ══════════════════════════════════════
    //  Step 5: 重新加载 → 校验 → 第二次提交
    // ══════════════════════════════════════

    suspend fun reloadAndForward(
        ctx: FormContext, firstResult: SubmitResult, typeValue: Int
    ): SubmitResult {
        Logger.d(TAG, "reloadAndForward: requestId=${firstResult.requestId}")

        // 5a: 重新加载表单
        val loadBody = client.submitForm(
            url = "$BASE/api/workflow/reqform/loadForm",
            formParameters = Parameters.build {
                append("belongTest", "false"); append("f_weaver_belongto_userid", userId)
                append("f_weaver_belongto_usertype", "0")
                append("isOpenContinuationProcess", "undefined")
                append("isaffirmance", "0"); append("needRemind", "false")
                append("requestid", firstResult.requestId.toString())
                append("saveType", "undefined"); append("selectNextFlow", "0")
                append("sessionkey", firstResult.sessionKey)
            }
        ).bodyAsText()
        val loadJson = loadBody.safeParseJsonObject()
        val newParams = loadJson["params"]?.jsonObject
        val newSubmitParams = loadJson["submitParams"]?.jsonObject
        val newMaindata = loadJson["maindata"]?.jsonObject

        val authStr = newParams?.get("authStr")?.jsonPrimitive?.content ?: ""
        val authSigStr = newParams?.get("authSignatureStr")?.jsonPrimitive?.content ?: ""
        val newSigAttr = newParams?.get("signatureAttributesStr")?.jsonPrimitive?.content ?: ""
        val newSigSecret = newParams?.get("signatureSecretKey")?.jsonPrimitive?.content ?: ""
        val newLinkageUUID = newParams?.get("linkageUUID")?.jsonPrimitive?.content ?: ""
        val currentDate = newParams?.get("lastOperateDate")?.jsonPrimitive?.content ?: ctx.defaultDate
        val currentTime = newParams?.get("lastOperateTime")?.jsonPrimitive?.content ?: ""

        // 5b: 提交前校验
        val checkResult = client.submitForm(
            url = "$BASE/api/xjtuapi/checksubmit",
            formParameters = Parameters.build {
                append("reqid", firstResult.requestId.toString())
                append("wfid", ctx.workflowId.toString()); append("uid", userId)
                append("sqrq", ctx.defaultDate); append("cjdlx", typeValue.toString())
            }
        ).bodyAsText().trim()
        Logger.d(TAG, "reloadAndForward: checksubmit=$checkResult")

        // 5c: 读取表单字段值
        fun fieldVal(fieldName: String): String {
            return newMaindata?.get(fieldName)?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""
        }

        val submitToken = newSubmitParams?.get("${userId}_${firstResult.requestId}_request_submit_token")
            ?.jsonPrimitive?.long ?: firstResult.submitToken
        val addToken = newSubmitParams?.get("${userId}_${ctx.workflowId}_addrequest_submit_token")
            ?.jsonPrimitive?.long ?: firstResult.submitToken

        // 5d: 第二次提交（转发到下载节点）
        val submitBody = client.submitForm(
            url = "$BASE/api/workflow/reqform/requestOperation",
            formParameters = Parameters.build {
                append("formid", "-14"); append("isSubmitDirectNode", "")
                append("openByDefaultBrowser", ""); append("iscreate", "0")
                append("creatertype", "0"); append("isdialog", "1")
                append("${userId}_${firstResult.requestId}_request_submit_token", submitToken.toString())
                append("lastOperateDate", currentDate); append("createdoc", "")
                append("nodeid", "49"); append("workflowid", ctx.workflowId.toString())
                append("isbill", "1"); append("authStr", authStr)
                append("f_weaver_belongto_userid", userId); append("currenttime", currentTime)
                append("f_weaver_belongto_usertype", "0"); append("agentorByAgentId", "-1")
                append("isMultiDoc", ""); append("inputcheck", "")
                append("comemessage", ""); append("lastOperateTime", currentTime)
                append("temphasUseTempletSucceed", ""); append("workflowRequestLogId", "")
                append("edesign_layout", ""); append("requestid", firstResult.requestId.toString())
                append("isremark", "0"); append("creater", userId)
                append("htmlfieldids", ""); append("SubmitToNodeid", "")
                append("isCptwf", "false"); append("isovertime", "")
                append("agentType", "0"); append("authSignatureStr", authSigStr)
                append("nodetype", "0"); append("needoutprint", "")
                append("lastOperator", userId); append("topage", "")
                append("${userId}_${ctx.workflowId}_addrequest_submit_token", addToken.toString())
                append("isFormSignature", "0"); append("remindTypes", "")
                append("fromFlowDoc", ""); append("RejectNodes", "")
                append("linkageUUID", newLinkageUUID)
                append("billid", newParams?.get("billid")?.jsonPrimitive?.content ?: "")
                append("lastnodeid", ""); append("uploadType", "")
                append("isSignMustInput", ""); append("RejectToNodeid", "")
                append("isWorkflowDoc", "false"); append("src", "submit")
                append("annexmaxUploadImageSize", ""); append("takisremark", "0")
                append("workflowtype", "26"); append("remarkLocation", "")
                append("needcheck", ""); append("needcheckLock", "false")
                append("selectfieldvalue", ""); append("RejectToType", "")
                append("currentdate", currentDate); append("needwfback", "0")
                append("isOdocRequest", "0"); append("enableIntervenor", "")
                append("verifyRequiredRange", "field-9999,field7243,")
                append("linkageUnFinishedKey", ""); append("remark", "")
                append("remarkquote", ""); append("actiontype", "requestOperation")
                append("isFirstSubmit", "0"); append("existChangeRange", "")
                // ── 表单字段 ──
                append("field7249", fieldVal("field7249"))
                append("field7502", fieldVal("field7502")); append("field7248", fieldVal("field7248"))
                append("field7501", fieldVal("field7501")); append("field7247", fieldVal("field7247"))
                append("field7505", fieldVal("field7505")); append("field7504", fieldVal("field7504"))
                append("field7242", fieldVal("field7242")); append("field7241", fieldVal("field7241"))
                append("field7240", fieldVal("field7240")); append("field-9", fieldVal("field-9"))
                append("field7246", fieldVal("field7246")); append("field7245", fieldVal("field7245"))
                append("field7564", fieldVal("field7564")); append("field7244", fieldVal("field7244"))
                append("field7243", fieldVal("field7243")); append("field7239", fieldVal("field7239"))
                append("field7536", fieldVal("field7536")); append("field7238", fieldVal("field7238"))
                append("field7237", fieldVal("field7237")); append("field7250", fieldVal("field7250"))
                append("requestname", newMaindata?.get("field-1")?.jsonObject?.get("value")?.jsonPrimitive?.content ?: "")
                append("requestlevel", "0"); append("field-10", "")
                append("chatsType", "-1"); append("messageType", "-1")
                append("mainFieldUnEmptyCount", "12"); append("detailFieldUnEmptyCount", "0")
                append("signatureAttributesStr", newSigAttr)
                append("signatureSecretKey", newSigSecret)
                append("selectNextFlow", "0"); append("openDataVerify", "0")
                append("wfTestStr", "")
            }
        ).bodyAsText()

        val submitJson = submitBody.safeParseJsonObject()
        val data = submitJson["data"]?.jsonObject
        if (data == null) {
            // 提取详细错误信息
            val message = submitJson["message"]?.jsonPrimitive?.content
            val errorMsg = submitJson["errorMsg"]?.jsonPrimitive?.content
            val tips = submitJson["tips"]?.jsonPrimitive?.content
            val detail = errorMsg ?: message ?: tips ?: "未知错误"
            Logger.e(TAG, "reloadAndForward: submit failed, no data object. detail=$detail")
            error("转发失败：$detail")
        }
        
        val resultType = data["type"]?.jsonPrimitive?.content
        if (resultType != "SUCCESS") {
            // 提取详细错误信息
            val msgInfo = data["messageInfo"]?.jsonObject
            val errorMsg = msgInfo?.get("message")?.jsonPrimitive?.content
                ?: data["message"]?.jsonPrimitive?.content
                ?: data["errorMsg"]?.jsonPrimitive?.content
            val detail = errorMsg ?: resultType ?: "提交被拒绝"
            Logger.e(TAG, "reloadAndForward: resultType=$resultType, errorMsg=$errorMsg")
            error("转发失败：$detail")
        }

        val msgInfo = data["messageInfo"]?.jsonObject
        val resultInfo = data["resultInfo"]?.jsonObject
        val newSessionKey = resultInfo?.get("sessionkey")?.jsonPrimitive?.content
            ?: msgInfo?.get("sessionkey")?.jsonPrimitive?.content ?: ""
        val forwardSubmitParams = data["submitParams"]?.jsonObject
        val newSubmitToken = forwardSubmitParams?.get("${userId}_${firstResult.requestId}_request_submit_token")
            ?.jsonPrimitive?.long ?: currentTimeMillis()

        Logger.d(TAG, "reloadAndForward: SUCCESS, newSessionKey=$newSessionKey")
        return SubmitResult(firstResult.requestId, newSessionKey, newSubmitToken)
    }

    // ══════════════════════════════════════
    //  Step 6: 获取下载链接
    // ══════════════════════════════════════

    suspend fun getDownloadInfo(secondResult: SubmitResult): DownloadInfo {
        Logger.d(TAG, "getDownloadInfo: requestId=${secondResult.requestId}")

        val body = client.submitForm(
            url = "$BASE/api/workflow/reqform/loadForm",
            formParameters = Parameters.build {
                append("belongTest", "false"); append("f_weaver_belongto_userid", userId)
                append("f_weaver_belongto_usertype", "0")
                append("isOpenContinuationProcess", "undefined")
                append("isRefresh", "1"); append("isShowChart", "3")
                append("isaffirmance", "0"); append("needRemind", "false")
                append("requestid", secondResult.requestId.toString())
                append("saveType", "undefined"); append("sessionkey", secondResult.sessionKey)
            }
        ).bodyAsText()

        val json = body.safeParseJsonObject()
        val maindata = json["maindata"]?.jsonObject

        // 从 field7564 提取下载链接
        val field7564 = maindata?.get("field7564")?.jsonObject
        val specialobj = field7564?.get("specialobj")?.jsonObject
        val filedatas = specialobj?.get("filedatas")?.jsonArray

        if (filedatas != null && filedatas.isNotEmpty()) {
            val fileData = filedatas[0].jsonObject
            val filename = fileData["filename"]?.jsonPrimitive?.content ?: "成绩单.pdf"
            val loadlink = fileData["loadlink"]?.jsonPrimitive?.content ?: error("下载链接不存在")
            val filesize = fileData["filesize"]?.jsonPrimitive?.content ?: ""

            Logger.d(TAG, "getDownloadInfo: filename=$filename, size=$filesize")
            return DownloadInfo(
                filename = filename,
                downloadUrl = if (loadlink.startsWith("http")) loadlink else "$BASE$loadlink",
                filesize = filesize
            )
        }

        // Fallback: 从 field7244 提取文档 ID
        val field7244 = maindata?.get("field7244")?.jsonObject
        val docId = field7244?.get("value")?.jsonPrimitive?.content
        if (docId != null) {
            val params2 = json["params"]?.jsonObject
            val authStr2 = params2?.get("authStr")?.jsonPrimitive?.content ?: ""
            val authSig2 = params2?.get("authSignatureStr")?.jsonPrimitive?.content ?: ""
            val docName = field7244["specialobj"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "成绩单.pdf"
            val dlUrl = "$BASE/weaver/weaver.file.FileDownload?fileid=$docId&download=1" +
                "&requestid=${secondResult.requestId}&desrequestid=0" +
                "&authStr=$authStr2&authSignatureStr=$authSig2" +
                "&f_weaver_belongto_userid=$userId&f_weaver_belongto_usertype=0&fromrequest=1"
            Logger.d(TAG, "getDownloadInfo: fallback URL with docId=$docId")
            return DownloadInfo(filename = docName, downloadUrl = dlUrl, filesize = "")
        }

        error("无法获取下载链接：成绩单文件尚未生成")
    }

    // ══════════════════════════════════════
    //  Step 7: 下载 PDF
    // ══════════════════════════════════════

    suspend fun downloadPdf(url: String): ByteArray {
        Logger.d(TAG, "downloadPdf: url=${url.take(100)}")
        val resp = client.get(url) {
            header("Referer", "$BASE/spa/workflow/static4form/index.html")
        }
        if (resp.status.value != 200) error("下载失败：HTTP ${resp.status.value}")
        return resp.readBytes()
    }
}
