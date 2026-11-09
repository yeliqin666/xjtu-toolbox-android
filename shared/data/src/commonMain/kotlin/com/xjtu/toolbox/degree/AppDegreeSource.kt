package com.xjtu.toolbox.degree

import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.jwapp.JwappApi
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Request

/**
 * 教务的培养方案（`xsfacx` + `jwpubapp/pyfa`）和学业完成（`xywccx`）。
 *
 * 学号、姓名只用来拼请求，不进 [DegreeRaw]；响应也不落盘。
 * 「重新计算」（`bysc.do`）只在用户点按钮时调，平时读现成报告。
 */
class AppDegreeSource(
    private val sessionManager: SessionManager,
    /** 最近两个有课学期的课表。课表缓存在各端自己那层，拿不到也不影响其他。 */
    private val currentCourses: suspend () -> List<DegreeCurrentCourse> = { emptyList() },
) : DegreeSource {

    private data class Plan(val code: String, val name: String, val required: Double?, val grade: String?, val studentId: String, val studentName: String)

    private suspend fun site(): SiteSession = sessionManager.ensureSite(JWXT, silent = true)

    private suspend fun post(site: SiteSession, app: String, url: String, form: Map<String, String> = emptyMap()): JsonObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url("$BASE/$url")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$BASE/sys/$app/*default/index.do")
            .post(body)
            .build()
        return site.executeWithReAuth(request).use { response ->
            // 只带接口名和状态码，不让上游内容和个人信息进错误文案
            if (!response.isSuccessful) throw RuntimeException("教务接口 ${url.substringAfterLast('/')} HTTP ${response.code}")
            response.body?.string().orEmpty().safeParseJsonObject()
        }
    }

    /** 先切到学生身份再进应用页：没选身份时进页面会被教务 403。 */
    private suspend fun enterApp(site: SiteSession, app: String) {
        val roleId = runCatching {
            site.executeWithReAuth(Request.Builder().url("$BASE/sys/homeapp/api/home/currentUser.do").get().build()).use { response ->
                val groups = response.body?.string().orEmpty().safeParseJsonObject()["datas"]?.jsonObject?.get("userGroups") as? JsonArray
                groups?.map { it.jsonObject }?.firstOrNull { it["roleName"].safeStringOrNull() == "学生" }?.get("roleId").safeStringOrNull()
            }
        }.getOrNull()
        if (roleId != null) post(site, app, "sys/jwpubapp/pub/setJwCommonAppRole.do", mapOf("ROLEID" to roleId))
        site.executeWithReAuth(Request.Builder().url("$BASE/sys/$app/*default/index.do").get().build()).close()
    }

    private fun rows(root: JsonObject, module: String): List<JsonObject> {
        if (root["code"].safeStringOrNull() != "0") throw RuntimeException("教务接口 $module 返回异常")
        val module_ = root["datas"]?.jsonObject?.get(module)?.jsonObject
        return (module_?.get("rows") as? JsonArray).orEmpty().map { it.jsonObject }
    }

    private suspend fun plan(site: SiteSession): Plan {
        enterApp(site, PLAN_APP)
        val all = rows(post(site, PLAN_APP, "sys/xsfacx/modules/pyfacxepg/grpyfacx.do"), "grpyfacx")
        // 辅修、微专业也在这里；第一版只看主修
        val row = all.firstOrNull { it["XDLXDM"].safeStringOrNull() == "01" } ?: all.firstOrNull()
            ?: throw RuntimeException("没有查到你的培养方案")
        return Plan(
            code = row["PYFADM"].safeStringOrNull().orEmpty(),
            name = row["PYFAMC"].safeStringOrNull().orEmpty(),
            required = row["ZSYQXF"].safeDoubleOrNull(),
            grade = row["XZNJ"].safeStringOrNull(),
            studentId = row["XH"].safeStringOrNull().orEmpty(),
            studentName = row["XM"].safeStringOrNull().orEmpty(),
        )
    }

    override suspend fun fetch(): DegreeRaw = withContext(Dispatchers.IO) {
        val site = site()
        val plan = plan(site)
        val byPlan = mapOf("PYFADM" to plan.code)
        val groups = rows(post(site, PLAN_APP, "sys/jwpubapp/modules/pyfa/kzcx.do", byPlan), "kzcx")
        val courses = rows(post(site, PLAN_APP, "sys/jwpubapp/modules/pyfa/kzkccx.do", byPlan), "kzkccx")
        val notes = optional("方案说明") {
            val row = rows(post(site, PLAN_APP, "sys/jwpubapp/modules/pyfa/qxpyfacx.do", byPlan), "qxpyfacx").firstOrNull()
            listOf("KZZD1", "KZZD2", "FATS", "XDYQ").mapNotNull { row?.get(it).safeStringOrNull()?.takeIf { s -> s.isNotBlank() } }.joinToString("\n")
        }.orEmpty()
        DegreeRaw(
            planCode = plan.code,
            planName = plan.name,
            required = plan.required,
            notes = notes,
            groups = groups,
            courses = courses,
            report = optional("完成报告") { report(site, plan) },
            grades = optional("成绩") { grades() }.orEmpty(),
            grade = plan.grade,
            currentCourses = optional("课表") { currentCourses() }.orEmpty(),
        )
    }

    override suspend fun searchPlans(keyword: String, grade: String): DegreePlanPage = withContext(Dispatchers.IO) {
        val site = site()
        enterApp(site, CATALOG_APP)
        val setting = buildJsonArray {
            if (keyword.isNotEmpty()) {
                add(buildJsonArray {
                    add(buildJsonObject { put("name", "PYFAMC"); put("value", keyword); put("builder", "include"); put("linkOpt", "OR") })
                })
            }
            add(buildJsonObject { put("name", "NJDM"); put("linkOpt", "AND"); put("builder", "equal"); put("value", grade) })
            // 99 = 已发布；转专业只看主修
            add(buildJsonObject { put("name", "FAZTDM"); put("linkOpt", "AND"); put("builder", "equal"); put("value", "99") })
            add(buildJsonObject { put("name", "XDLXDM"); put("linkOpt", "AND"); put("builder", "equal"); put("value", "01") })
        }
        val root = post(site, CATALOG_APP, "sys/qxfacx/modules/pyfacxepg/qxpyfacxzl.do", mapOf(
            "querySetting" to setting.toString(),
            "*order" to "-NJDM,+DWDM,+ZYDM",
            "pageSize" to PLAN_PAGE_SIZE.toString(),
            "pageNumber" to "1",
        ))
        val total = root["datas"]?.jsonObject?.get("qxpyfacxzl")?.jsonObject?.get("totalSize").safeDoubleOrNull()?.toInt()
        val plans = rows(root, "qxpyfacxzl").mapNotNull { row ->
            val code = row["PYFADM"].safeStringOrNull() ?: return@mapNotNull null
            DegreePlanSummary(
                code = code,
                name = row["PYFAMC"].safeStringOrNull().orEmpty(),
                college = row["DWDM_DISPLAY"].safeStringOrNull().orEmpty(),
                grade = row["NJDM"].safeStringOrNull().orEmpty(),
            )
        }
        DegreePlanPage(plans, total ?: plans.size)
    }

    override suspend fun fetchPlan(plan: DegreePlanSummary): DegreeRaw = withContext(Dispatchers.IO) {
        val site = site()
        enterApp(site, CATALOG_APP)
        val byPlan = mapOf("PYFADM" to plan.code)
        val detail = optional("方案说明") { rows(post(site, CATALOG_APP, "sys/jwpubapp/modules/pyfa/qxpyfacx.do", byPlan), "qxpyfacx").firstOrNull() }
        DegreeRaw(
            planCode = plan.code,
            planName = plan.name,
            required = detail?.get("ZSYQXF").safeDoubleOrNull(),
            notes = listOf("KZZD1", "KZZD2", "FATS", "XDYQ").mapNotNull { detail?.get(it).safeStringOrNull()?.takeIf { s -> s.isNotBlank() } }.joinToString("\n"),
            groups = rows(post(site, CATALOG_APP, "sys/jwpubapp/modules/pyfa/kzcx.do", byPlan), "kzcx"),
            courses = rows(post(site, CATALOG_APP, "sys/jwpubapp/modules/pyfa/kzkccx.do", byPlan), "kzkccx"),
            report = null,
            grades = optional("成绩") { grades() }.orEmpty(),
            grade = plan.grade,
            currentCourses = optional("课表") { currentCourses() }.orEmpty(),
        )
    }

    /** 参考数据拿不到不算失败：树还在，页面照样能看。 */
    private suspend fun <T> optional(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what 取不到：${e.message}")
        null
    }

    private suspend fun report(site: SiteSession, plan: Plan): JsonObject? {
        enterApp(site, REPORT_APP)
        val param = buildJsonObject {
            put("fromPage", "grxyjcbg")
            put("XH", plan.studentId)
            put("XM", plan.studentName)
            put("PYFADM", plan.code)
            put("PYFAMC", plan.name)
            put("BYNJDM", "-")
            put("SCLBDM", CHECK_TYPE)
        }.toString()
        val root = post(site, REPORT_APP, "sys/xywccx/api/grbg/queryXyzhbx.do", mapOf("requestParamStr" to param))
        if (root["code"].safeStringOrNull() != "0") return null
        return root["data"] as? JsonObject
    }

    private suspend fun grades(): List<DegreeGrade> {
        val site = sessionManager.ensureSite(JWAPP, silent = true)
        return JwappApi(site).getGrade(null).flatMap { term ->
            term.scoreList.map { s ->
                DegreeGrade(
                    code = s.courseCode.orEmpty(),
                    name = s.courseName,
                    credit = s.coursePoint,
                    score = s.score,
                    passed = s.passFlag,
                    gpa = s.gpa,
                    term = s.termCode,
                )
            }
        }
    }

    override suspend fun recalculate(): Boolean = withContext(Dispatchers.IO) {
        val site = site()
        val plan = plan(site)
        enterApp(site, REPORT_APP)
        post(site, REPORT_APP, "sys/xywccx/modules/xywccx/bysc.do", mapOf(
            "PYFADM" to plan.code, "BYNJDM" to "-", "SCLBDM" to CHECK_TYPE, "XH" to plan.studentId,
        ))
        repeat(POLL_TIMES) {
            delay(POLL_INTERVAL_MS)
            val row = rows(post(site, REPORT_APP, "sys/xywccx/modules/xywccx/byscjd.do", mapOf("ZXJDKEY" to "BYSC_${plan.studentId}")), "byscjd").firstOrNull()
            val done = row?.get("YWCS").safeDoubleOrNull()
            val total = row?.get("ZS").safeDoubleOrNull()
            if (done != null && total != null && total > 0 && done >= total) return@withContext true
        }
        false
    }

    private companion object {
        const val TAG = "AppDegreeSource"
        const val BASE = "https://jwxt.xjtu.edu.cn/jwapp"
        const val JWXT = "jwxt"
        const val JWAPP = "jwapp"
        const val PLAN_APP = "xsfacx"
        const val REPORT_APP = "xywccx"
        const val CATALOG_APP = "qxfacx"
        const val PLAN_PAGE_SIZE = 40
        /** 抓包里页面固定传的检测类别。 */
        const val CHECK_TYPE = "04"
        const val POLL_TIMES = 30
        const val POLL_INTERVAL_MS = 2_000L
    }
}
