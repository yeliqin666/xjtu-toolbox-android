package com.xjtu.toolbox.attendance

import com.xjtu.toolbox.auth.AccessMode
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 考勤门户 kq.xjtu.edu.cn 的本研合并课表（`/sa/auth/portal/semester`）。
 *
 * 门户把本科（bk-kq）、研究生（yjs-kq）两套后端的**当前学期**课表并排返回，不能指定学期；
 * 行字段与业务站课表同名，照门户前端 `portalView.js` 的读法取。
 *
 * 门户认自己的 HttpOnly cookie 会话，不认业务令牌。会话没有或过期时，用考勤站点同一个
 * cookie 存储从门户入口走一轮统一认证（考勤站点刚登录过，免密），在门户回调上换票建立会话。
 */
internal object KqPortal {

    /** 一侧（scope 为 BK 本科 / YJS 研究生）的当前学期课表。 */
    data class Side(val scope: String, val termCode: String, val rows: List<KqTimetableRow>)

    suspend fun currentSemester(site: SiteSession): List<Side> = withContext(Dispatchers.IO) {
        val data = fetchSemester(site) ?: run {
            openSession(site)
            fetchSemester(site)
        } ?: throw IOException("考勤门户建立会话后仍读不到课表")
        parse(data)
    }

    /** 本科、研究生两侧里可用的那些；某侧没上线、没开通就不在结果里。 */
    internal fun parse(data: JsonObject): List<Side> = listOf("undergraduate", "graduate").mapNotNull { key ->
        val side = KqHttp.obj(data.get(key)) ?: return@mapNotNull null
        if (!KqHttp.bool(side, "available")) return@mapNotNull null
        val payload = KqHttp.obj(side.get("payload")) ?: return@mapNotNull null
        val semester = KqHttp.obj(payload.get("semester")) ?: KqHttp.obj(payload.get("teachingContext"))
            ?: return@mapNotNull null
        Side(
            scope = KqHttp.str(side, "scope"),
            termCode = TermCodeMapper.termCodeOf(KqHttp.str(semester, "academicYear"), KqHttp.str(semester, "semesterName")),
            rows = KqHttp.rows(payload.get("courses")).mapNotNull(KqTimetableRow::of),
        )
    }

    /** 会话无效时门户会跳去登录页或回非 0 code，都返回 null。 */
    private fun fetchSemester(site: SiteSession): JsonObject? {
        site.client.newCall(portalRequest(site, "auth/portal/semester").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = runCatching { resp.body.string().safeParseJsonObject() }.getOrNull() ?: return null
            return if (json.codeIsZero()) KqHttp.obj(json.get("data")) else null
        }
    }

    private fun openSession(site: SiteSession) {
        val landing = site.client.newCall(
            Request.Builder().url(proxied(site, AttendanceLogin.LOGIN_URL)).get().build(),
        ).execute().use { it.request.url }
        val plain = WebVpnUtil.getOriginalUrl(landing.toString())?.toHttpUrlOrNull() ?: landing
        val requestId = plain.queryParameter("loginRequestId")
        val ticket = plain.queryParameter("ticket")
        if (requestId.isNullOrBlank() || ticket.isNullOrBlank()) throw IOException("考勤门户免密认证没有完成")
        val payload = buildJsonObject {
            put("loginRequestId", requestId)
            put("ticket", ticket)
        }
        site.client.newCall(
            portalRequest(site, "auth/cas/exchange").post(payload.toString().toRequestBody(JSON)).build(),
        ).execute().use { resp ->
            val json = runCatching { resp.body.string().safeParseJsonObject() }.getOrNull()
            if (!resp.isSuccessful || json?.codeIsZero() != true) throw IOException("考勤门户换票失败 (HTTP ${resp.code})")
        }
    }

    private fun portalRequest(site: SiteSession, path: String) = Request.Builder()
        .url(proxied(site, "${AttendanceLogin.BASE_URL}/$path"))
        .header("Accept", "application/json")
        .header(AttendanceLogin.SYSTEM_HEADER, AttendanceLogin.SYSTEM_VALUE)

    private fun proxied(site: SiteSession, url: String) =
        AttendanceLogin.proxied(url, site.currentAccessMode == AccessMode.WEBVPN)

    private fun JsonObject.codeIsZero() = get("code")?.takeIf { !it.isNull }?.let { runCatching { it.intValue }.getOrNull() } == 0

    private val JSON = "application/json".toMediaType()
}
