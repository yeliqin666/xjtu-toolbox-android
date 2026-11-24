package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.degree.DegreeCurrentCourse
import com.xjtu.toolbox.degree.DegreeGrade
import com.xjtu.toolbox.degree.DegreePlanPage
import com.xjtu.toolbox.degree.DegreePlanSummary
import com.xjtu.toolbox.degree.DegreeRaw
import com.xjtu.toolbox.degree.DegreeSource
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject

/** 学业进度的 serve 版取数（`/api/degree`）：给 `:web` 用。campus-api 里没有这条链。 */
class CampusDegreeApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : DegreeSource {

    override suspend fun fetch(): DegreeRaw {
        requireServe()
        return client.serveData("加载学业进度", baseUrl, PROGRESS_PATH).toDegreeRaw()
    }

    override suspend fun recalculate(): Boolean {
        requireServe()
        val outcome = client.serveAction(baseUrl, RECALCULATE_PATH, "{}")
        if (!outcome.ok) error(outcome.message)
        return outcome.data?.get("done").safeBoolean()
    }

    override suspend fun searchPlans(keyword: String, grade: String): DegreePlanPage {
        requireServe()
        val data = client.serveData(
            "搜索培养方案",
            baseUrl,
            PLANS_PATH,
            listOf("keyword" to keyword.trim(), "grade" to grade.trim()),
        )
        val plans = data.arr("plans").orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.let {
                DegreePlanSummary(
                    code = it["code"].safeString(),
                    name = it["name"].safeString(),
                    college = it["college"].safeString(),
                    grade = it["grade"].safeString(),
                )
            }
        }
        return DegreePlanPage(plans, data["total"].safeStringOrNull()?.toIntOrNull() ?: plans.size)
    }

    override suspend fun fetchPlan(plan: DegreePlanSummary): DegreeRaw {
        requireServe()
        return client.serveData(
            "加载培养方案",
            baseUrl,
            PLAN_PATH,
            listOf("code" to plan.code, "name" to plan.name, "college" to plan.college, "grade" to plan.grade),
        ).toDegreeRaw()
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("学业进度", "GET /api/degree/progress 等")
    }

    private companion object {
        const val PROGRESS_PATH = "/api/degree/progress"
        const val PLANS_PATH = "/api/degree/plans"
        const val PLAN_PATH = "/api/degree/plan"
        const val RECALCULATE_PATH = "/api/degree/recalculate"
    }
}

private fun JsonObject.toDegreeRaw() = DegreeRaw(
    planCode = this["planCode"].safeString(),
    planName = this["planName"].safeString(),
    required = this["required"].safeDoubleOrNull(),
    notes = this["notes"].safeString(),
    groups = arr("groups").orEmpty().mapNotNull { it as? JsonObject },
    courses = arr("courses").orEmpty().mapNotNull { it as? JsonObject },
    report = this["report"] as? JsonObject,
    grades = arr("grades").orEmpty().mapNotNull { element ->
        (element as? JsonObject)?.let {
            DegreeGrade(
                code = it["code"].safeString(),
                name = it["name"].safeString(),
                credit = it["credit"].safeDouble(),
                score = it["score"].safeString(),
                passed = it["passed"].safeBoolean(),
                gpa = it["gpa"].safeDoubleOrNull(),
                term = it["term"].safeString(),
            )
        }
    },
    grade = this["grade"].safeStringOrNull(),
    currentCourses = arr("currentCourses").orEmpty().mapNotNull { element ->
        (element as? JsonObject)?.let {
            DegreeCurrentCourse(it["code"].safeString(), it["name"].safeString(), it["term"].safeString())
        }
    },
)
