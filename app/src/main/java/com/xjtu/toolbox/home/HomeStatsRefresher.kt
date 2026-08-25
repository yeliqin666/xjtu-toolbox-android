package com.xjtu.toolbox.home

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.fitness.hasUsableTotal
import com.xjtu.toolbox.fitness.orderedFitnessYears
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 首页状态的**主动拉取**调度器。
 *
 * 与「功能页顺手写缓存」（[HomeStats.push]）互补：那条路只有用过的功能才有数据，
 * 这里负责在后台把该刷的刷了，让首页开箱即有内容。
 *
 * ## 三条硬约束
 *
 * 1. **不与登录风控冲突**：每个源之间强制 [GAP_MS] 间隔串行执行，绝不并发登录。
 *    并且与 `CasGate` 双向对齐（此前是各管各的）：
 *    - 开跑前先问 [CasGate.blockedReason]，被挡就**整轮不跑**。原来不问，
 *      于是熔断期间十个源挨个去撞公平锁、每个等满 4 秒，最后全部失败——
 *      既拖时间又把失败时间戳写了一地。
 *    - 单源抛出闸门类异常（退避 / 站点冷却 / 密码失效）时**不算这个源失败**，
 *      不写重试戳并立即中止整轮：这不是源的问题，后面的源同样过不去。
 * 1.5 **后台一律静默登录**（`silent = true`）：撞上短信验证直接跳过。
 *    此前用的是默认 silent=false，也就是说首页在后台刷新时**可能弹出 MFA 对话框、
 *    给用户发一条他没要过的验证码短信**。
 * 2. **按源分级 TTL**：评教/体测一周一次，考勤两天一次，打开即时的（快速流水、
 *    图书馆座位）TTL 为 0 —— 由各自页面自行触发，不在这里定时拉。
 * 3. **静默失败**：任何一个源失败都不影响其他源，也不弹任何提示。首页状态是锦上添花，
 *    不该因为某个校园系统半夜维护就打扰用户。
 */
object HomeStatsRefresher {

    private const val TAG = "HomeStatsRefresh"

    /** 两次拉取之间的间隔。给 CAS 留出喘息，避免被判为异常访问。 */
    private const val GAP_MS = 1_500L

    private const val DAY = 24L * 60 * 60 * 1000L

    private val runLock = Mutex()

    /**
     * 本进程是否还没跑过刷新。冷启动后的第一轮里，**对当前一条内容都没有的源忽略退避**
     * 再试一次。
     *
     * 理由：退避是为了防止反复打挂掉的系统，但代价是"某次取数失败/为空后，用户即使
     * 杀进程重开也要干等几十分钟"——而用户重开 App 恰恰是在期待它重新试。
     * 限定在"当前没有任何内容可显示"的源上，已经有数据的照常按 TTL 走，不会变成每次
     * 冷启动全量重拉。
     */
    @Volatile
    private var firstRunInProcess = true

    /**
     * 一个可刷新的首页状态源。
     *
     * @param ttlMs 多久刷一次。
     * @param loginType 需要哪个站点的会话；null 表示无需登录。
     */
    private class Source(
        val routeKey: String,
        val ttlMs: Long,
        val loginType: LoginType?,
        val fetch: suspend (Context, SiteSession?) -> HomeStat?,
    )

    private val sources: List<Source> = listOf(
        // 评教：一周一次。开没开评教窗口在一周内不会反复变。
        Source(Routes.JUDGE, 7 * DAY, LoginType.JWXT) { _, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                val api = com.xjtu.toolbox.judge.JudgeApi(site)
                val term = runCatching { api.getCurrentTerm() }.getOrNull()
                val todo = api.unfinishedQuestionnaires(term).size
                val done = api.finishedQuestionnaires(term).size
                val all = todo + done
                Log.d(TAG, "judge: term=$term todo=$todo done=$done")
                if (all == 0) null
                else HomeStat(
                    if (todo == 0) "已评完" else "$todo/$all 门待评",
                    if (todo == 0) "本学期评教已完成" else "共 $all 门"
                )
            }
        },

        // 体测：一周一次。成绩一学期才更新一次，一周已经很勤了。
        Source(Routes.FITNESS, 7 * DAY, LoginType.FITNESS) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                val api = com.xjtu.toolbox.fitness.FitnessApi(site)
                val years = runCatching { api.getYears() }.getOrNull().orEmpty()
                val ordered = com.xjtu.toolbox.fitness.orderedFitnessYears(years)

                var picked: Pair<String, com.xjtu.toolbox.fitness.FitnessScore>? = null
                for ((i, y) in ordered.take(3).withIndex()) {
                    if (i > 0) delay(GAP_MS)
                    val s = runCatching { api.getScore(y.yearNum) }.getOrNull() ?: continue
                    if (s.hasUsableTotal()) { picked = y.name to s; break }
                }
                picked?.let { (name, s) ->
                    HomeStat(
                        s.totalScore,
                        listOfNotNull(
                            name.takeIf { it.isNotBlank() },
                            s.totalGrade.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                    )
                }
            }
        },

        // 思源学堂：一天一次。要逐门课查活动，比别的源贵，别刷太勤。
        Source(Routes.LMS, 1 * DAY, LoginType.LMS) { _, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) { lmsLatest(site) }
        },

        // 快速考勤流水：最新一条刷卡记录。用户要求"打开时立即请求"，
        // 所以 TTL 给到 10 分钟——首页每次进来基本都会重取，又不至于同一次浏览里反复打。
        Source(Routes.ICLASSFACE, 10 * 60 * 1000L, LoginType.ICLASSFACE) { _, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                val records = runCatching {
                    com.xjtu.toolbox.iclassface.IclassfaceApi(site).fetchRecords()
                }.getOrNull().orEmpty()
                Log.d(TAG, "iclassface: 今日 ${records.size} 条")
                records.firstOrNull()?.let { r ->
                    HomeStat(r.time, listOfNotNull(r.location.takeIf { it.isNotBlank() }).joinToString())
                } ?: HomeStat("今日未刷卡", "还没有签到记录")
            }
        },

        // 考勤：两天一次。
        Source(Routes.ATTENDANCE, 2 * DAY, LoginType.ATTENDANCE) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) { attendanceWeeklyRate(ctx, site) }
        },

        // 成绩：一天一次。成绩出分是低频事件，但"出了没出"用户很在意，一天一查是平衡点。
        //
        // 这里除了给首页写摘要，还负责**算出"新增了几门"**供屁岱主动提醒使用：
        // 把总门数记在一个独立的游标里，与上次对比。游标只在成功取到数据时前移，
        // 否则一次失败会把基线冲掉，之后永远判不出"新增"。
        Source(Routes.JWAPP_SCORE, 1 * DAY, LoginType.JWAPP) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                val terms = runCatching { com.xjtu.toolbox.jwapp.JwappApi(site).getGrade() }
                    .onFailure { Log.w(TAG, "score: getGrade 失败 ${it.message}") }
                    .getOrNull().orEmpty()
                val total = terms.sumOf { it.scoreList.size }
                Log.d(TAG, "score: ${terms.size} 个学期，共 $total 门")
                if (total == 0) return@withContext null

                val newCount = HomeStats.bumpScoreCursor(ctx, total)
                // 累加而非覆盖：气泡可能还没冒出来就又刷了一轮，直接覆盖会把待提醒的数吞掉。
                // 冒过一次后由 MainActivity 清零。
                if (newCount > 0) {
                    HomeStats.setPendingNewScores(ctx, HomeStats.pendingNewScores(ctx) + newCount)
                }
                // 最新学期挑分数最高/最近的一条做明细意义不大，直接报本学期门数与新增。
                val latestTerm = terms.maxByOrNull { it.termCode }
                HomeStat(
                    if (newCount > 0) "$newCount 门新成绩" else "${latestTerm?.scoreList?.size ?: total} 门",
                    if (newCount > 0) "共 $total 门 · 有更新"
                    else latestTerm?.termName?.let { "$it 学期" } ?: "共 $total 门"
                )
            }
        },

        // 校园卡：30 分钟一次。
        //
        // 收编进来之前，校园卡走的是 MainActivity 里一条完全独立的路：只在 ON_RESUME、
        // 且 `getSiteOrNull("campus_card")?.hasLogin == true` 时才刷——**从不主动登录**。
        // 后果是只要用户没手动进过校园卡页，余额缓存永远是空的，
        // 首页余额位常年空白，屁岱那条"余额不足"提醒也永远触发不了（balance 恒为 null）。
        // 收进来后自动获得串行、间隔、失败退避、冷启动重试这一整套。
        Source(Routes.CAMPUS_CARD, 30 * 60 * 1000L, LoginType.CAMPUS_CARD) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                val ok = com.xjtu.toolbox.refreshCampusCardCache(ctx, site)
                if (!ok) return@withContext null
                val prefs = com.xjtu.toolbox.card.CampusCardCache.cardPrefs(ctx)
                val balance = prefs.getFloat("card_balance_cache", -1f)
                if (balance < 0f) return@withContext null
                val spend = prefs.getFloat("card_today_spend_cache", -1f)
                Log.d(TAG, "campus_card: balance=$balance todaySpend=$spend")
                HomeStat(
                    "¥%.2f".format(balance),
                    if (spend > 0f) "今日已花 ¥%.2f".format(spend) else "余额",
                )
            }
        },

        // 图书馆：15 分钟一次。
        //
        // TTL 比别的源短，因为这里的状态**是有时效的**——"待入馆"要在限定时间内签到，
        // "临时离馆"超时会被释放座位。半小时才刷一次的话，等首页显示出来往往已经过期了。
        Source(Routes.LIBRARY, 15 * 60 * 1000L, LoginType.LIBRARY) { _, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                val b = com.xjtu.toolbox.library.LibraryApi(site).getMyBooking()
                if (b == null) {
                    HomeSignals.libraryUrgentAction = null
                    return@withContext null
                }
                // 待办判据取归一化后的操作按钮，不去猜状态文本（那是页面原文）。
                HomeSignals.libraryUrgentAction = b.actionUrls.keys
                    .firstOrNull { it in com.xjtu.toolbox.library.LibraryApi.URGENT_ACTIONS }
                Log.d(
                    TAG,
                    "library: seat=${b.seatId} area=${b.area} status=${b.statusText} " +
                        "actions=${b.actionUrls.keys} urgent=${HomeSignals.libraryUrgentAction}",
                )
                // 首页这两行照搬图书馆页「当前预约」卡片的排版：主行座位号，副行「区域 · 状态」。
                HomeStat(
                    b.seatId?.takeIf { it.isNotBlank() } ?: "已预约",
                    listOfNotNull(
                        b.area?.takeIf { it.isNotBlank() },
                        b.statusText?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { null },
                )
            }
        },

        // 教务通知：来源跟用户在设置里勾的一致，小组件和系统通知共用。
        // 不需要登录，4 小时一次；真正抓取和去重交给 NoticeWatchSync。
        Source(Routes.NOTIFICATION, 4 * 60 * 60 * 1000L, null) { ctx, _ ->
            if (!com.xjtu.toolbox.notification.NoticeWatchStore.isEnabled(ctx)) {
                val cached = com.xjtu.toolbox.notification.NoticeWatchStore.lastTitles(ctx)
                val top = cached.firstOrNull() ?: return@Source null
                return@Source HomeStat(top.take(16), cached.getOrNull(1)?.take(16))
            }
            val result = com.xjtu.toolbox.notification.NoticeWatchSync.sync(
                ctx,
                notify = true,
                force = false,
            )
            Log.d(TAG, "notice: titles=${result.titles.size} new=${result.newCount} cache=${result.usedCache}")
            val top = result.newestTitle ?: result.titles.firstOrNull() ?: return@Source null
            HomeStat(
                top.take(16),
                result.titles.getOrNull(1)?.take(16) ?: "所选来源最新通知",
            )
        },

        // 校园黄页：不需要登录，数据几乎不变，一周一次足够。
        // 只取用户点名的教务处与保卫处两条。
        Source(Routes.YELLOW_PAGE, 7 * DAY, null) { ctx, _ ->
            withContext(Dispatchers.IO) {
                val data = com.xjtu.toolbox.yellowpage.YellowPageApi(ctx).getData()
                val wanted = listOf("教务处", "保卫处")
                val hits = data.departments.filter { d -> wanted.any { d.name.contains(it) } }
                    .sortedBy { d -> wanted.indexOfFirst { d.name.contains(it) } }
                Log.d(TAG, "yellow_page: 部门总数=${data.departments.size} 命中=${hits.map { it.name }}")
                if (hits.isEmpty()) null
                else HomeStat(
                    hits.first().let { "${shortName(it.name)} ${it.phoneItems.firstOrNull().orEmpty()}" },
                    hits.getOrNull(1)?.let { "${shortName(it.name)} ${it.phoneItems.firstOrNull().orEmpty()}" }
                )
            }
        },
    )

    /**
     * 思源学堂最新的作业/资料两条。
     *
     * 两个坑：
     * 1. **学期要自己挑最新的**。思源里 2025-2026 春季学期比秋季更"新"，但课程列表并不
     *    按此排序，直接遍历全部课程会把上学年的旧作业也捞进来。这里按
     *    `academicYear.sort` + `semester.sort` 选出最大的一档，只看该学期的课。
     * 2. **活动确实带时间戳**（`LmsActivity.updatedAt` / `createdAt`），所以能排序取最新——
     *    此前不确定有没有时间字段，看模型确认是有的。
     *
     * 成本控制：逐门课查活动是 N 次请求，这里只取该学期前 [LMS_MAX_COURSES] 门，
     * 且整个源一天只刷一次。
     */
    private fun lmsLatest(site: SiteSession): HomeStat? {
        val api = com.xjtu.toolbox.lms.LmsApi(site)
        val courses = runCatching { api.getMyCourses() }
            .onFailure { Log.w(TAG, "lms: getMyCourses 失败 ${it.message}") }
            .getOrNull().orEmpty()
        Log.d(TAG, "lms: 课程 ${courses.size} 门")
        if (courses.isEmpty()) return null

        // 最新学期 = (学年 sort, 学期 sort) 字典序最大的那一档
        val newest = courses.maxOf { it.academicYear.sort.toLong() * 1000 + it.semester.sort }
        val inTerm = courses.filter {
            it.academicYear.sort.toLong() * 1000 + it.semester.sort == newest
        }.take(LMS_MAX_COURSES)
        Log.d(TAG, "lms: 最新学期 sort=$newest，取 ${inTerm.size} 门：${inTerm.map { it.name }}")

        val wanted = setOf(
            com.xjtu.toolbox.lms.LmsActivityType.HOMEWORK,
            com.xjtu.toolbox.lms.LmsActivityType.MATERIAL,
        )
        val all = inTerm.flatMap { c ->
            runCatching { api.getCourseActivities(c.id) }.getOrNull().orEmpty().map { c.name to it }
        }
        Log.d(TAG, "lms: 活动共 ${all.size} 条，类型分布=${all.groupingBy { it.second.type }.eachCount()}")
        // 不再要求 published：活动列表接口返回的本就是学生可见的内容，而该字段在**列表**响应里
        // 常常缺失（详情接口才有），safeBoolean() 于是一律得到 false，把所有活动都滤没了。
        val acts = all.filter { it.second.type in wanted }
        Log.d(TAG, "lms: 命中作业/资料 ${acts.size} 条")
        if (acts.isEmpty()) return null

        // 排序时间要逐级兜底：**部分作业既没有 updated_at 也没有 created_at**
        // （用户实测遇到过），只用这两个字段的话它们会以空串排到最后，永远选不中。
        // 作业还有截止/开始时间可用，最后才退回空串。
        val latest = acts.sortedByDescending { (_, a) ->
            a.updatedAt.ifBlank { a.createdAt }
                .ifBlank { a.endTime.orEmpty() }
                .ifBlank { a.startTime.orEmpty() }
        }.take(2)

        fun label(pair: Pair<String, com.xjtu.toolbox.lms.LmsActivity>): String {
            val (course, a) = pair
            val kind = if (a.type == com.xjtu.toolbox.lms.LmsActivityType.HOMEWORK) "作业" else "资料"
            return "$kind · ${a.title.ifBlank { course }.take(14)}"
        }
        return HomeStat(label(latest[0]), latest.getOrNull(1)?.let(::label))
    }

    private const val LMS_MAX_COURSES = 6

    private fun shortName(full: String) = when {
        full.contains("教务") -> "教务处"
        full.contains("保卫") -> "保卫处"
        else -> full.take(6)
    }

    /**
     * 跑一轮刷新。只处理已过期的源，逐个串行，源之间留 [GAP_MS]。
     * 同一时刻只允许一轮（[runLock]），防止反复进出首页把请求叠起来。
     */
    suspend fun refreshDue(
        context: Context,
        manager: SessionManager?,
        accountType: AccountType = AccountType.UNDERGRADUATE,
    ) {
        if (manager?.credentials == null) {
            Log.d(TAG, "skip: no credentials (manager=${manager != null})")
            return
        }
        if (!runLock.tryLock()) {
            Log.d(TAG, "skip: another refresh in flight")
            return
        }
        try {
            // 闸门在熔断/退避里就整轮别跑。不写任何时间戳，等它放行后下一次进首页照常重试。
            com.xjtu.toolbox.auth.CasGate.blockedReason()?.let { why ->
                Log.d(TAG, "skip: CasGate blocked ($why)")
                return
            }
            val stamps = HomeStats.stamps(context)
            val now = System.currentTimeMillis()
            var first = true
            val coldStart = firstRunInProcess
            firstRunInProcess = false
            val existing = HomeStats.collect(context, null).keys
            Log.d(TAG, "start; coldStart=$coldStart 已有内容=$existing stamps=${stamps.mapValues { (now - it.value) / 60000 }} (分钟前)")
            // 短 TTL 的先跑。整轮是串行 + 1.5s 间隔，源多了一轮要走十几秒；
            // 把"经常到期"的（校园卡 30min、图书馆 15min、刷卡记录 10min）排在
            // 一周才刷一次的评教/体测后面，等于让最该新鲜的数据等最不着急的。
            for (s in sources.sortedBy { it.ttlMs }) {
                if (s.loginType == LoginType.ATTENDANCE && accountType != AccountType.UNDERGRADUATE) continue
                if (s.loginType == LoginType.ICLASSFACE && accountType != AccountType.UNDERGRADUATE) continue
                val last = stamps[s.routeKey] ?: 0L
                val hasContent = s.routeKey in existing
                if (now - last < s.ttlMs && !(coldStart && !hasContent)) {
                    Log.d(TAG, "${s.routeKey}: 未到期，跳过（距上次 ${(now - last) / 60000} 分钟，TTL ${s.ttlMs / 60000} 分钟）")
                    continue
                }
                if (coldStart && !hasContent && now - last < s.ttlMs) {
                    Log.d(TAG, "${s.routeKey}: 冷启动且暂无内容，忽略退避重试")
                }
                if (!first) delay(GAP_MS)
                first = false
                try {
                    // silent = true：后台绝不弹 MFA、不发短信，撞上就抛 MfaRequiredException。
                    val site = s.loginType?.let { manager.ensureSite(it.siteKey(), silent = true) }
                    val stat = s.fetch(context, site)
                    HomeStats.push(context, s.routeKey, stat?.value, stat?.detail)
                    if (stat == null) HomeStats.markEmpty(context, s.routeKey, s.ttlMs)
                    else HomeStats.markFetched(context, s.routeKey)
                    Log.d(TAG, "${s.routeKey} -> ${stat?.value ?: "无数据（1 小时后重试）"}")
                } catch (e: com.xjtu.toolbox.auth.CasGate.ThrottledException) {
                    Log.d(TAG, "abort round: CasGate throttled (${e.message})")
                    return
                } catch (e: com.xjtu.toolbox.auth.PasswordInvalidatedException) {
                    Log.d(TAG, "abort round: password invalidated")
                    return
                } catch (e: com.xjtu.toolbox.auth.LoginCooldownException) {
                    // 站点级 60 秒冷却：只是这个源暂时进不去，别的源照跑，也别写失败戳
                    // （60 秒后本来就能重试，写成 30 分钟反而更糟）。
                    Log.d(TAG, "${s.routeKey}: 站点冷却中，跳过不计失败")
                } catch (e: com.xjtu.toolbox.auth.MfaRequiredException) {
                    // 需要短信验证。后台不碰，等用户主动进那个功能页时自然会走完整流程。
                    // 同样不写失败戳：这不是故障，是"现在不该由我来做"。
                    Log.d(TAG, "${s.routeKey}: 需短信验证，后台跳过")
                } catch (e: Exception) {
                    // 半小时后重试，不按正常 TTL 锁死——故障多是暂时的（网关抖动、系统维护），
                    // 按 2 天/7 天锁住会让"修好了却还是不显示"。
                    HomeStats.markFailed(context, s.routeKey, s.ttlMs)
                    Log.w(TAG, "${s.routeKey} refresh failed (retry in 30min): ${e.message}")
                }
            }
        } finally {
            runLock.unlock()
        }
    }

    /**
     * 本周出勤率。直接用考勤系统自己的「本周考勤统计」接口，不自己按日期聚合——
     * 学校对"本周"的定义（教学周、跨周考试等）以它为准。
     */
    private fun attendanceWeeklyRate(ctx: android.content.Context, site: SiteSession): HomeStat? {
        val stats = runCatching {
            com.xjtu.toolbox.attendance.AttendanceApi(site).getKqtjCurrentWeek()
        }.getOrNull().orEmpty()
        Log.d(TAG, "attendance: 本周统计 ${stats.size} 门课")
        if (stats.isEmpty()) return null
        val total = stats.sumOf { it.total }
        Log.d(TAG, "attendance: total=$total normal=${stats.sumOf { it.actualCount }}")
        if (total == 0) return null
        val ok = stats.sumOf { it.actualCount }
        val abnormal = stats.sumOf { it.abnormalCount }
        val rate = ok * 100 / total

        // 异常数**增加**才提醒，不是"有异常"就提醒：一次缺勤会挂在统计里一整周，
        // 每轮刷新都当新事报一遍就成了骚扰。游标只在成功取到数据时前移，
        // 否则一次失败会把基线冲掉，之后永远判不出增量。
        val prefs = ctx.getSharedPreferences("attendance_watch", android.content.Context.MODE_PRIVATE)
        val seen = prefs.getInt("abnormal_seen", -1)
        if (seen >= 0 && abnormal > seen) {
            val worst = stats.filter { it.abnormalCount > 0 }.maxByOrNull { it.abnormalCount }
            HomeSignals.attendanceAlert = if (worst != null) {
                "${worst.subjectName}的考勤有异常了"
            } else {
                "本周考勤多了 ${abnormal - seen} 次异常"
            }
        }
        prefs.edit().putInt("abnormal_seen", abnormal).apply()

        return HomeStat(
            "$rate%",
            if (abnormal > 0) "本周 $ok/$total 次正常 · $abnormal 次异常" else "本周 $ok/$total 次正常"
        )
    }
}
