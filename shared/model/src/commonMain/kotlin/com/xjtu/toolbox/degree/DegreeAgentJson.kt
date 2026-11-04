package com.xjtu.toolbox.degree

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 给小岱的 JSON。原始响应太大又带学号，但方案树各专业写法不一，精简不能丢信息：
 * - 总览：每个节点都在，不按专业写死字段，放不下的只给数量，并说明怎么展开；
 * - 展开：该组的原始字段全部透传（只剔除隐私和空值），模型自己读。
 */
object DegreeAgentJson {
    private const val MAX_NOTES = 6000
    private const val MAX_LISTED_REMAINING = 12

    /** 学号、姓名、操作人这类，给模型没有用。 */
    private val PRIVATE_KEYS = Regex("^(XH|XM|XSXM|SFZH|ZJHM|SJH|LXDH|EMAIL|CZR|CZRXM|CZIP|SHR|SHRXM|WID|CSRQ|STUDENTID|USERID|USERNAME)$", RegexOption.IGNORE_CASE)

    private fun clean(raw: JsonObject): JsonObject = JsonObject(
        raw.filter { (key, value) ->
            !PRIVATE_KEYS.matches(key) && value !is JsonNull && (value as? JsonPrimitive)?.content?.isNotBlank() != false
        },
    )

    private fun num(value: Double): JsonElement =
        if (value == kotlin.math.floor(value)) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    private fun overviewNode(n: DegreeNode): JsonObject = buildJsonObject {
        put("id", n.id)
        put("name", n.name)
        put("required", num(n.required))
        put("earned", num(n.earned))
        put("counted", num(n.counted))
        put("satisfied", n.satisfied)
        n.minCourses?.let { put("minCourses", it) }
        n.requirementText?.let { put("requirementText", it) }
        n.officialTaken?.let { put("officialTaken", num(it)) }
        n.officialPassed?.let { put("officialPassed", it) }
        if (n.differsFromOfficial) put("differsFromOfficial", true)
        if (n.courses.isNotEmpty()) {
            val passed = n.courses.filter { it.status == DegreeCourseStatus.PASSED }
            val remaining = n.courses - passed.toSet()
            putJsonArray("passed") {
                passed.forEach { c ->
                    add(c.name + "(${c.credit})" + if (c.userConfirmed) "[用户确认抵扣]" else if (c.matchedByName) "[按课程名匹配]" else "")
                }
            }
            put("remainingCount", remaining.size)
            if (remaining.size <= MAX_LISTED_REMAINING) {
                putJsonArray("remaining") {
                    remaining.forEach { c ->
                        add(c.name + "(${c.credit})" + when (c.status) {
                            DegreeCourseStatus.FAILED -> "[未通过]"
                            DegreeCourseStatus.IN_PROGRESS -> "[在修或待出成绩]"
                            else -> ""
                        })
                    }
                }
            }
        }
        if (n.children.isNotEmpty()) put("children", JsonArray(n.children.map(::overviewNode)))
    }

    /** @param otherPlan 看的是别的方案（规划转专业）：数字是用户的成绩对照这个方案的估算。 */
    fun overview(p: DegreeProgress, otherPlan: Boolean = false): String = buildJsonObject {
        put("plan", p.planName)
        put("required", num(p.required))
        put("counted", num(p.counted))
        p.officialTaken?.let { put("officialTaken", num(it)) }
        p.officialEarned?.let { put("officialEarned", num(it)) }
        p.calculatedAt?.let { put("reportCalculatedAt", it) }
        put(
            "howToRead",
            if (otherPlan) "这不是用户现在的方案，是用用户的成绩对照它的估算，没有教务报告。课程号或课程名对不上的课不计入，实际能否认定以学院转专业文件为准。用 group 参数传节点 id 可看该组全部原始字段。"
            else "counted/earned 是按课程重算的；official* 是教务报告原值，可能算错（要求为 0 的子模块会被算成 0）。两者不同时都告诉用户，以教务为准。用 group 参数传节点 id 可看该组全部原始字段。",
        )
        put("nodes", JsonArray(p.nodes.map(::overviewNode)))
        putJsonArray("unplaced") {
            p.unplaced.forEach { c ->
                add(buildJsonObject {
                    put("code", c.code)
                    put("name", c.name)
                    put("credit", num(c.credit))
                    put("passed", c.passed)
                    c.score?.let { put("score", it) }
                    c.term?.let { put("term", it) }
                    if (c.inProgress) put("inProgress", true)
                })
            }
        }
        if (p.inProgressCount > 0) {
            put("inProgressCourses", p.inProgressCount)
            put("inProgressCredits", num(p.inProgressCredits))
            put("inProgressNote", "最近两个学期课表里还没出成绩的课，可能在修，也可能已结课等成绩，不计入 earned/counted，课程后标了 [在修或待出成绩]。学分为 0 的是没对上方案、不知道学分。")
        }
        put(
            "unplacedNote",
            if (otherPlan) "这些课在这个方案里没对上，转过去可能用不上，也可能可以申请认定。可以按名称和学分给建议，但只是建议，要向学院核实。"
            else "这些课没归进方案，可能是换过课程号。可以按名称和学分建议它可能抵哪一组，但只是建议：要用户在学业进度页自己确认，并向教务核实。",
        )
        p.gpaEstimate?.let { put("gpaEstimate", (it * 100).toLong() / 100.0) }
        put("failedCourses", buildJsonArray { p.failedCourses.forEach { add(it) } })
        if (p.warnings.isNotEmpty()) put("warnings", buildJsonArray { p.warnings.forEach { add(it) } })
        if (p.notes.isNotEmpty()) {
            put("planNotes", p.notes.take(MAX_NOTES))
            if (p.notes.length > MAX_NOTES) put("planNotesTruncated", true)
        }
        // 保研、挂科、书院淘汰规则还没收录：宁可说没有，也不能让模型凭印象讲
        put("rules", buildJsonArray { })
        put("rulesNote", "保研、挂科、书院分流淘汰等规则尚未收录。被问到时如实说没有这份规则，让用户查学院当年文件，不要凭印象回答。")
    }.toString()

    /** 展开：匹配 id 或名称的节点，连子节点一起，课程和课组字段全部透传。 */
    fun detail(p: DegreeProgress, group: String): String? {
        val q = group.trim()
        val hits = p.nodes.flatMap { it.flattened }.filter { it.id == q }
            .ifEmpty { p.nodes.flatMap { it.flattened }.filter { q.isNotEmpty() && q in it.name } }
        if (hits.isEmpty()) return null
        return buildJsonObject {
            put("groups", JsonArray(hits.map(::detailNode)))
        }.toString()
    }

    private fun detailNode(n: DegreeNode): JsonObject = buildJsonObject {
        put("id", n.id)
        put("name", n.name)
        put("required", num(n.required))
        put("earned", num(n.earned))
        put("satisfied", n.satisfied)
        put("groupFields", clean(n.raw))
        put("courses", JsonArray(n.courses.map { c ->
            buildJsonObject {
                put("code", c.code)
                put("name", c.name)
                put("credit", num(c.credit))
                put("status", c.statusText.ifEmpty { c.status.name })
                c.score?.let { put("score", it) }
                c.term?.let { put("term", it) }
                c.plannedTerm?.let { put("plannedTerm", it) }
                c.substitute?.let { put("substitute", it) }
                if (c.userConfirmed) put("userConfirmed", true)
                if (c.matchedByName) put("matchedByName", true)
                put("fields", clean(c.raw))
            }
        }))
        if (n.children.isNotEmpty()) put("children", JsonArray(n.children.map(::detailNode)))
    }
}
