package com.xjtu.toolbox.ss

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.SsSession
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeParseJson
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Request

/** 活动列表里的一条。 */
data class SsActivity(
    val id: String,
    val title: String,
    val type: String,
    val org: String,
    val start: String,
    val end: String,
    val status: SsActivityStatus,
    val participants: Int,
    val points: String,
    val cover: String?,
    /** 课程性质（必修 / 选修）与素养能力类别：课外学分认定看这两项。 */
    val nature: String = "",
    val ability: String = "",
) {
    /** 讲座、报告、分享会一类：常能开集体活动证明，活动描述里多半不写。 */
    val likelyGroupProof: Boolean get() = GROUP_PROOF_WORDS.any { it in title }
}

/** 集体活动证明的常见来源（#465）：按这些词搜名称。 */
val GROUP_PROOF_WORDS = listOf("讲座", "报告", "分享", "宣讲", "论坛", "沙龙", "交流会", "座谈")

enum class SsActivityStatus(val label: String) { UPCOMING("未开始"), ONGOING("进行中"), ENDED("已结束") }

/** 报名方式：一键报名在 App 里做，附件 / 团体报名跳网页。 */
enum class SsApplyMode { ONE_CLICK, ATTACHMENT, GROUP }

data class SsActivityDetail(
    val base: SsActivity,
    val contentHtml: String,
    val notice: String,
    val place: String,
    val needApply: Boolean,
    val applyMode: SsApplyMode,
    val applyStart: String,
    val applyEnd: String,
    val quota: Int?,
    val needReview: Boolean,
    val applied: Boolean,
    val canCancel: Boolean,
    val onsiteSignIn: Boolean,
    /** 发布范围，如「院级活动」。 */
    val scope: String = "",
    /** 积分构成，如「浏览 1 · 报名 2 · 签到 5」。 */
    val pointsBreakdown: String = "",
) {
    /** 现在能不能报名；不能时给出原因（小岱报名前也走这一道）。 */
    fun applyBlocker(now: LocalDateTime): String? = when {
        !needApply -> "这个活动不用报名"
        applied -> "已经报过名了"
        base.status == SsActivityStatus.ENDED -> "活动已结束"
        parseSsTime(applyStart)?.let { now < it } == true -> "报名还没开始（$applyStart 开始）"
        parseSsTime(applyEnd)?.let { now > it } == true -> "报名已截止（$applyEnd 截止）"
        applyMode != SsApplyMode.ONE_CLICK -> "这个活动要${if (applyMode == SsApplyMode.GROUP) "团体" else "上传附件"}报名，请到网页里报"
        else -> null
    }
}

/** 我的报名里的一条。 */
data class SsMyApply(val activityId: String, val title: String, val review: String, val activityStatus: String, val start: String)

class SsException(message: String) : RuntimeException(message)

/** 「2026-11-20 08:00(:00)」→ 时间；认不出给 null。 */
fun parseSsTime(text: String): LocalDateTime? {
    val d = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2}))?""").find(text)?.groupValues ?: return null
    return runCatching {
        LocalDateTime(d[1].toInt(), d[2].toInt(), d[3].toInt(), d[4].ifEmpty { "0" }.toInt(), d[5].ifEmpty { "0" }.toInt())
    }.getOrNull()
}

/**
 * 智慧社区活动接口（金智 EMAP）：一律 `POST <ctrl>/<fn>.do`，表单 `data=<JSON>`，回 `{code:"0",msg,data}`。
 * 不调 saveAccessRecord / saveBrowseScore（看一眼就记浏览分不合适）。
 */
class SsActivityApi(private val site: SiteSession) {

    private suspend fun call(path: String, data: JsonObject): JsonElement? {
        val req = Request.Builder()
            .url("${SsSession.APP_BASE}/$path")
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", SsSession.APP_INDEX)
            .post(FormBody.Builder().add("data", data.toString()).build())
            .build()
        val text = site.executeWithReAuth(req).use { resp ->
            val body = resp.body.string()
            if (resp.code >= 400) throw SsException("智慧社区返回 HTTP ${resp.code}")
            body
        }
        val root = text.safeParseJson() as? JsonObject ?: throw SsException("智慧社区返回格式异常")
        if (root["code"].safeString() != "0") throw SsException(root["msg"].safeString().ifBlank { "智慧社区请求失败" })
        return root["data"]
    }

    /** 活动列表。[keyword] 按名称搜；[order] 见 [Order]。取不满一页就是到底了。 */
    suspend fun list(keyword: String = "", order: Order = Order.TIME, page: Int = 1, pageSize: Int = 20): List<SsActivity> {
        val query = if (keyword.isBlank()) "[]" else buildJsonArray {
            add(buildJsonArray {
                add(buildJsonObject {
                    put("name", "HDMC"); put("value", keyword.trim()); put("builder", "include"); put("linkOpt", "AND")
                })
            })
        }.toString()
        val data = call("ykHdController/getActivityList.do", buildJsonObject {
            put("querySetting", query)
            put("orderColumns", order.columns)
            put("pageNumber", page)
            put("pageSize", pageSize)
        })
        return rows(data).map { it.toActivity() }
    }

    /**
     * 能开集体活动证明的候选：名称搜不到「集体活动」，按 [GROUP_PROOF_WORDS] 逐个搜再合并，
     * 去掉已结束的，按开始时间排。列表接口一次只认一个名称条件，只能逐个打。
     */
    suspend fun groupProofCandidates(perWord: Int = 10): List<SsActivity> =
        GROUP_PROOF_WORDS.flatMap { list(it, Order.TIME, 1, perWord) }
            .distinctBy { it.id }
            .filter { it.status != SsActivityStatus.ENDED }
            .sortedBy { parseSsTime(it.start) }

    suspend fun detail(id: String): SsActivityDetail {
        val o = single(call("ykHdController/getActivityDetail.do", buildJsonObject { put("HDDM", id) }))
            ?: throw SsException("没找到这个活动")
        return SsActivityDetail(
            base = o.toActivity(),
            contentHtml = o["HDNR"].safeString(),
            notice = o["HDXZ"].safeString(),
            place = listOf(o["JBDD_DISPLAY"].safeString(), o["XXDD"].safeString()).filter { it.isNotBlank() }.joinToString(" "),
            needApply = o["SFXYBM"].flag(),
            applyMode = when (o["BMFS"].safeString()) { "2" -> SsApplyMode.ATTACHMENT; "3" -> SsApplyMode.GROUP; else -> SsApplyMode.ONE_CLICK },
            applyStart = o["BMKSSJ"].safeString(),
            applyEnd = o["BMJSSJ"].safeString(),
            quota = o["BMXZRS"].safeStringOrNull()?.toIntOrNull()?.takeIf { it > 0 },
            needReview = o["BMSH"].flag(),
            applied = o["HASAPPLY"].flag(),
            canCancel = o["SFQXBM"].flag(),
            onsiteSignIn = o["SFXCQD"].flag(),
            scope = o["FBFW_DISPLAY"].safeString(),
            pointsBreakdown = listOf("浏览" to "LLF", "报名" to "BMF", "签到" to "QDF", "机动" to "JDF")
                .mapNotNull { (label, key) -> o[key].safeString().takeIf { it.isNotBlank() && it != "0" }?.let { "$label $it" } }
                .joinToString(" · "),
        )
    }

    /** 一键报名：先过 checkCurUserApply，再保存。截止校验在服务端保存时才做，调用方先看 [SsActivityDetail.applyBlocker]。 */
    suspend fun apply(id: String) {
        val body = buildJsonObject { put("HDDM", id) }
        call("hdController/checkCurUserApply.do", body)
        call("hdController/saveActivityApply.do", body)
    }

    suspend fun cancel(id: String) {
        call("ykHdController/cancelBm.do", buildJsonObject { put("HDDM", id) })
    }

    /** 现场签到 / 签退：输入现场给的 6 位验证码（不做定位）。 */
    suspend fun signIn(id: String, code: String) {
        call("ykHdController/saveCcQdqt.do", buildJsonObject { put("HDDM", id); put("YZM", code.trim()) })
    }

    suspend fun myApplies(page: Int = 1, pageSize: Int = 30): List<SsMyApply> =
        rows(call("myInfoController/getWdbmInfo.do", buildJsonObject { put("PAGENUM", page); put("PAGESIZE", pageSize) }))
            .map {
                SsMyApply(
                    activityId = it["HDDM"].safeString(),
                    title = it["HDMC"].safeString(),
                    review = it["SHZT_DISPLAY"].safeStringOrNull()?.takeIf { s -> s.isNotBlank() }
                        ?: when (it["SHZT"].safeString()) { "" -> ""; "99" -> "已通过"; else -> "审核中" },
                    // 这个接口的 HDZT 是数字：1 未开始 / 2 进行中 / 其余已结束（与官方「我的报名」页一致）
                    activityStatus = when (val z = it["HDZT"].safeString()) {
                        "1" -> SsActivityStatus.UPCOMING
                        "2" -> SsActivityStatus.ONGOING
                        "" -> status(z)
                        else -> if (z.all(Char::isDigit)) SsActivityStatus.ENDED else status(z)
                    }.label,
                    start = it["HDKSSJ"].safeString().ifBlank { it["HDRQ"].safeString() },
                )
            }

    enum class Order(val columns: String) { TIME("-XC_HDSFKS,+HDKSSJ,-CYRS"), HOT("-CYRS"), POINTS("-HDZF") }

    private fun JsonObject.toActivity() = SsActivity(
        id = this["HDDM"].safeString(),
        title = this["HDMC"].safeString(),
        type = this["HDLX_DISPLAY"].safeString(),
        org = this["SSBM_DISPLAY"].safeString(),
        start = this["HDKSSJ"].safeString().ifBlank { this["HDRQ"].safeString() },
        end = this["HDJSSJ"].safeString(),
        status = status(this["HDZT"].safeString()),
        participants = this["CYRS"].safeInt(),
        points = this["HDZF"].safeString(),
        cover = this["ZPURL"].safeStringOrNull()?.takeIf { it.isNotBlank() }?.let { absolute(it) },
        nature = this["KCXZ_DISPLAY"].safeString(),
        ability = this["SYNLDM_DISPLAY"].safeString(),
    )

    private fun absolute(url: String) = if (url.startsWith("http")) url else "https://${SsSession.HOST}${if (url.startsWith("/")) "" else "/"}$url"

    companion object {
        internal fun status(code: String) = when (code.uppercase()) {
            "JXZ" -> SsActivityStatus.ONGOING
            "YJS" -> SsActivityStatus.ENDED
            else -> SsActivityStatus.UPCOMING
        }

        internal fun JsonElement?.flag() = safeString().trim().lowercase() in setOf("1", "true", "是", "y", "yes")

        /** 列表接口的 data 有时直接是数组，有时包一层 `{rows: [...]}`，按第一个数组取。 */
        internal fun rows(data: JsonElement?): List<JsonObject> = when (data) {
            is JsonArray -> data.filterIsInstance<JsonObject>()
            is JsonObject -> (data["rows"] as? JsonArray ?: data.values.firstOrNull { it is JsonArray } as? JsonArray)
                ?.filterIsInstance<JsonObject>().orEmpty()
            else -> emptyList()
        }

        internal fun single(data: JsonElement?): JsonObject? = when (data) {
            is JsonObject -> if ("HDDM" in data || "HDMC" in data) data else rows(data).firstOrNull()
                ?: data.values.firstOrNull { it is JsonObject } as? JsonObject
            is JsonArray -> data.firstOrNull() as? JsonObject
            else -> null
        }
    }
}
