package com.xjtu.toolbox.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「逐字搬」的钉子（`docs/agent-web-spec.md` §9 未决 #3 的第 3 条）：[ServeAgentTools] 里那几段
 * 与 `:app` 的 `AgentToolRegistry` **逐行同源** —— 差异只有下面 [seams] 里声明的那几处端上缝。
 *
 * ## 为什么是「读源码比对」而不是跑两份实现
 *
 * `:server` 编不到 `:app`（那是一个 Android 应用模块，测试跑不了它的 `Context` / Room），
 * 而这几段的**文案**必须两端一致（丙口径：同一句话）。所以这里比的是**源文件的那几行**：
 * 两个文件里同名函数的函数体逐行比一遍，除了 [seams] 声明的差异必须一字不差。
 *
 * ⚠️ 这条测试**能红**，而且红了就是实事：改了 serve 那份（或 `:app` 那份）而没改另一边 ⇒ 当场红。
 * 反过来，如果某一处差异以后不再需要（比如自建日程也搬进了共享层），那一条 [seams] 就**没人用**，
 * 下面那条「每条差异都得真的用上」的断言会红 —— 逼着把清单删干净，而不是留一堆空话。
 *
 * ## 它比的是什么、不比什么
 *
 * - **比**：从 `fun` 那一行到函数收尾大括号，逐行 `trim()` 之后逐行比（缩进两侧都是 4 空格，
 *   trim 只是让比对不依赖它）；
 * - **不比**：函数上面的 KDoc（两边各写各的）、以及文件里其余的函数；
 * - 参与比对的函数清单写在 [copied]：`get_calendar` / `get_schedule` / `get_exam_schedule` /
 *   `get_empty_rooms` 这四条工具用到的那几段，加上黄页与教师检索那两段
 *  （那两段也是「同一句话」，只是 `:app` 那一头多了 `context`）。
 */
class ServeAgentToolCopyDriftTest {

    /** 一对「`:app` 这么写 / serve 那么写」的行块（块内可以多行，逐行 trim 后比）。 */
    private class Seam(val app: List<String>, val serve: List<String>)

    private val copied = listOf(
        "currentPeriodIndex",
        "ensureSite",
        "ensureSiteKey",
        "normalizeCampus",
        "liveRoomsReply",
        "loginHint",
        "getCurrentTime",
        "cachedTermCode",
        "cachedStartDate",
        "getSchoolCalendar",
        "ensureScheduleLoaded",
        "getSchedule",
        "scheduleChangeNote",
        "getExamSchedule",
        "getEmptyRooms",
        "parseToolDate",
        "searchYellowPage",
        "findFaculty",
    )

    /**
     * **允许的全部差异**（类 KDoc 里那张 1~5 的清单，逐条落成行）。
     *
     * 每一条都必须真的在两个文件里对得上（末条断言），否则它是一条空话。
     */
    private val seams = listOf(
        // 1. 会话那一格：serve 的会话在内核里，没有 `loginState` 那半层
        Seam(
            listOf("loginState.sessionManager?.ensureSite(type, silent = true)"),
            listOf("session.sessionManager.ensureSite(type, silent = true)"),
        ),
        Seam(
            listOf("loginState.sessionManager?.ensureSite(siteKey, silent = true)"),
            listOf("session.sessionManager.ensureSite(siteKey, silent = true)"),
        ),
        Seam(
            listOf("dataCache, JwxtScheduleApi(site), loginState.sessionManager, term,"),
            listOf("dataCache, JwxtScheduleApi(site), session.sessionManager, term,"),
        ),
        // 2. 自建日程那一格：`:app` 读 Room，serve 读 `:core` 的 `CustomCourseStore`
        Seam(
            listOf(
                "val customCourses = runCatching {",
                "com.xjtu.toolbox.data.AppDatabase.getInstance(context)",
                ".customCourseDao()",
                ".getByTerm(com.xjtu.toolbox.account.AccountContext.activeAccountId ?: \"\", termCode)",
                ".map { it.toCourseItem() }",
                "}.getOrDefault(emptyList())",
            ),
            listOf(
                "val customCourses = runCatching {",
                "customCourses.getByTerm(com.xjtu.toolbox.account.AccountContext.activeAccountId ?: \"\", termCode)",
                ".map { it.toCourseItem() }",
                "}.getOrDefault(emptyList())",
            ),
        ),
        // 3. 节假日与课表缓存那一格：`:app` 传 `DataCache(context)`，serve 传自己那份 KV
        Seam(
            listOf("val holidays = runCatching { com.xjtu.toolbox.schedule.HolidayApi.getHolidayDates(com.xjtu.toolbox.data.DataCache(context)) }"),
            listOf("val holidays = runCatching { com.xjtu.toolbox.schedule.HolidayApi.getHolidayDates(dataCache) }"),
        ),
        Seam(
            listOf("dataCache.put(\"agent_exam\", text)"),
            listOf("dataCache.writeJson(\"agent_exam\", text)"),
        ),
        // 4. 空教室那一格：`:app` 传 `EmptyRoomCache(context)` 并读那一屏的「数据源」偏好
        Seam(
            listOf("val snap = LiveRoomApi(site, EmptyRoomCache(context)).fetchCampus(campus)"),
            listOf("val snap = LiveRoomApi(site, null).fetchCampus(campus)"),
        ),
        Seam(
            listOf("val api = EmptyRoomApi(EmptyRoomCache(context))"),
            listOf("val api = EmptyRoomApi(null)"),
        ),
        Seam(
            listOf(
                "val sourcePref = context.getSharedPreferences(\"empty_room\", Context.MODE_PRIVATE)",
                ".getString(\"empty_room_source\", null)",
            ),
            listOf(
                "// serve 没有 App 那一屏的数据源偏好（`empty_room` prefs 在 :app）⇒ 一律走默认那一档",
                "val sourcePref: String? = null",
            ),
        ),
        Seam(
            listOf("EmptyRoomDirectQuery(directClient, EmptyRoomCache(context)).queryDay(targetCampus, b, dateStr)"),
            listOf("EmptyRoomDirectQuery(directClient, null).queryDay(targetCampus, b, dateStr)"),
        ),
        // 5. 校历那一格：`:app` 自己 new，serve 收一格注入
        Seam(
            listOf("val terms = com.xjtu.toolbox.calendar.SchoolCalendarApi().terms()"),
            listOf("val terms = calendar.terms()"),
        ),
        // 取数端口那两格（黄页 / 教师检索）：`:app` 从 `context` 拿，serve 收注入的那一份
        Seam(
            listOf("val data = com.xjtu.toolbox.yellowpage.appYellowPageApi(context).getData()"),
            listOf("val data = yellowPage.getData()"),
        ),
        Seam(
            listOf("com.xjtu.toolbox.faculty.FacultyApi().searchAll("),
            listOf("faculty.searchAll("),
        ),
    )

    /**
     * 两个文件里同名函数的函数体逐行比 —— 差异只允许是 [seams]。
     *
     * 顺带钉两件「这条测试不是空对空」的事：所有函数两边都找得到、而且总行数是个像样的量。
     */
    @Test
    fun `逐字搬的那几段与 app 那份逐行同源，差异只有清单里声明过的那几处`() {
        val app = functionBodies(appSource(), copied)
        val serve = functionBodies(serveSource(), copied)
        assertEquals(copied.toSet(), app.keys, ":app 那边这几段没全找到（源文件路径或函数名漂了）")
        assertEquals(copied.toSet(), serve.keys, "serve 那边这几段没全找到（函数被改名/删掉了？）")

        val used = mutableSetOf<Int>()
        var compared = 0
        for (name in copied) {
            compared += assertSameExceptSeams(name, app.getValue(name), serve.getValue(name), used)
        }
        assertTrue(compared > 300, "只比了 $compared 行 —— 提取器出了毛病（这条测试要真的比到那 18 段）")

        assertEquals(
            seams.indices.toSet(),
            used,
            "差异清单里有没被用到的条目（说明那处差异已经不需要了，删掉它）",
        )
    }

    /**
     * 逐行比一段，返回比过的行数。
     *
     * 算法：两条游标同步前进；当前位置上先试「某条差异的行块是否两边同时命中」，命中就一起跳过；
     * 否则要求这一行的 trim 结果相同。
     */
    private fun assertSameExceptSeams(
        name: String,
        app: List<String>,
        serve: List<String>,
        used: MutableSet<Int>,
    ): Int {
        var i = 0
        var j = 0
        var compared = 0
        while (i < app.size && j < serve.size) {
            val hit = seams.indexOfFirst { seam ->
                app.subList(i, minOf(i + seam.app.size, app.size)) == seam.app &&
                    serve.subList(j, minOf(j + seam.serve.size, serve.size)) == seam.serve
            }
            if (hit >= 0) {
                used += hit
                i += seams[hit].app.size
                j += seams[hit].serve.size
                continue
            }
            assertEquals(
                app[i],
                serve[j],
                "$name：函数体第 ${i + 1} / ${j + 1} 行两边不一样 —— " +
                    "要么把两边改回一致，要么把它作为一处端上缝登记进 $seams 上面那张清单",
            )
            compared++
            i++
            j++
        }
        assertEquals(app.size - i, serve.size - j, "$name：函数体长度对不上（差在末几行）")
        assertEquals(0, app.size - i, "$name：函数体没比到底（剩下 ${app.size - i} 行）")
        return compared
    }

    // ── 源文件与提取器 ────────────────────────────────────────────────────

    private fun appSource(): String =
        File(repoRoot(), "app/src/main/java/com/xjtu/toolbox/agent/AgentTool.kt").readText()

    private fun serveSource(): String =
        File(repoRoot(), "server/src/main/kotlin/com/xjtu/toolbox/server/ServeAgentTools.kt").readText()

    /**
     * 仓库根目录：从工作目录往上找 `settings.gradle.kts`（Gradle 的测试工作目录按模块而变，
     * 写死相对路径在别的跑法下会红 —— 与 `:desktop:test` 的 `DesktopRouteCoverageJvmTest` 同一手法）。
     */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir ?: error("找不到仓库根目录（settings.gradle.kts）：工作目录是 ${File(".").absolutePath}")
    }

    /** 从一份 Kotlin 源码里取出这些函数的函数体（从 `fun` 那一行到收尾大括号），逐行 trim。 */
    private fun functionBodies(source: String, names: List<String>): Map<String, List<String>> {
        val lines = source.lines()
        return names.associateWith { name ->
            val start = lines.indexOfFirst { line ->
                Regex("""^\s*(private\s+)?(suspend\s+)?fun $name\b""").containsMatchIn(line)
            }
            assertTrue(start >= 0, "源文件里找不到 fun $name")
            if (!lines[start].contains('{')) {
                return@associateWith listOf(lines[start].trim())
            }
            var depth = 0
            var end = -1
            for (j in start until lines.size) {
                depth += lines[j].count { it == '{' } - lines[j].count { it == '}' }
                if (depth == 0) {
                    end = j
                    break
                }
            }
            assertTrue(end > start, "fun $name 的大括号没配平（第 ${start + 1} 行起）")
            lines.subList(start, end + 1).map { it.trim() }
        }
    }
}
