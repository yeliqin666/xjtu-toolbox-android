package com.xjtu.toolbox.jwapp

/**
 * 「出新成绩」的去重游标（#428）：记**见过的课程标识集合**（学期 + 课程号），不再只记总门数。
 *
 * 只记总门数时说不出是哪几门出了分，同一轮里撤下一门、又出一门，总数不变就漏报。
 * 记集合之后：
 * - 新出现的标识就是新出分的课，通知能写出课名；
 * - 门数不变的一增一删也认得出来。
 *
 * 首页刷新（前台）和后台的成绩检查共用这一份游标，同一门只报一次：谁先看到谁报。
 * 存储由宿主负责（按账号分开存），这里只做纯计算，方便测试。
 */
object NewGradeCursor {

    /**
     * @param fresh 这次新出现的成绩，按教务返回的顺序。
     * @param seen 推进后的游标，宿主原样存回去。
     * @param baseline 这是第一次建游标：只建基线、不报。
     */
    class Advance(val fresh: List<ScoreItem>, val seen: Set<String>, val baseline: Boolean)

    private val trailingCode = Regex("""\(([A-Za-z0-9]{6,})\)$""")

    /** 去掉教务课程名末尾的「(课程号)」，给通知和收纳页显示用。 */
    fun displayName(raw: String): String = raw.trim().replace(trailingCode, "").trim()

    /** 一门成绩的稳定标识：学期 + 课程号；没有课程号时退回课程名，再不行用记录 id。 */
    fun keyOf(item: ScoreItem, termCode: String = ""): String {
        val term = item.termCode.ifBlank { termCode }
        val course = item.courseCode?.takeIf { it.isNotBlank() }
            ?: displayName(item.courseName).ifBlank { item.id }
        return "$term|$course"
    }

    /**
     * 拿这次取到的全部成绩和上次的游标比。
     *
     * [previous] 为 null（老用户第一次跑新游标、或新账号）时只建基线，不报，免得把历年成绩全报一遍。
     * 游标取并集而不是只留这次的：教务偶尔少返回一个学期，下次补回来时不该当成新出分再报一遍。
     */
    fun advance(previous: Set<String>?, terms: List<TermScore>): Advance {
        val current = LinkedHashMap<String, ScoreItem>()
        terms.forEach { term ->
            term.scoreList.forEach { item -> current.getOrPut(keyOf(item, term.termCode)) { item } }
        }
        if (previous == null) return Advance(emptyList(), current.keys.toSet(), baseline = true)
        val fresh = current.filterKeys { it !in previous }.values.toList()
        return Advance(fresh, previous + current.keys, baseline = false)
    }

    /**
     * 通知正文：只写课名和门数，**不写分数**（锁屏上也看得见）。
     * 例如「高等数学 出分了」「高等数学 等 2 门出分了」。
     */
    fun summary(fresh: List<ScoreItem>): String {
        val first = fresh.firstOrNull()?.let { displayName(it.courseName) }?.ifBlank { null } ?: "有课程"
        return if (fresh.size <= 1) "$first 出分了" else "$first 等 ${fresh.size} 门出分了"
    }
}
