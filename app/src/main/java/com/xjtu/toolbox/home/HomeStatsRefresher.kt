package com.xjtu.toolbox.home

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.fitness.hasUsableTotal
import com.xjtu.toolbox.fitness.orderedFitnessYears
import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.inbox.OwnInbox
import com.xjtu.toolbox.inbox.SchoolInbox
import com.xjtu.toolbox.lms.LmsCourseSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import com.xjtu.toolbox.nav.AppRoute

/** 首页后台刷新：公开源优先，站点串行静默登录；按源缓存，取到一项就更新一项。 */
object HomeStatsRefresher {

    private const val TAG = "HomeStatsRefresh"

    /** 上一个源刚登录过时，下一个源前的间隔。给统一认证留出喘息，避免被判为异常访问。 */
    private const val GAP_MS = 1_500L

    private const val DAY = 24L * 60 * 60 * 1000L

    private val runLock = Mutex()

    /**
     * 当前这一轮绑定的账号，各源的 fetch 拿它决定落盘的命名空间和是否更新提醒信号。
     * 一轮里各源串行且整轮受 [runLock] 保护，所以一个字段够用。
     */
    @Volatile
    private var roundAccount: String? = null

    /** 这一轮拉数据期间没有切过账号。 */
    private fun roundIsCurrent() = com.xjtu.toolbox.account.AccountContext.activeAccountId == roundAccount

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
     * @param ttlNow 随状态变的 TTL，默认就是 [ttlMs]。
     */
    private class Source(
        val route: AppRoute,
        val ttlMs: Long,
        val loginType: LoginType?,
        val ttlNow: () -> Long = { ttlMs },
        val fetch: suspend (Context, SiteSession?) -> HomeStat?,
    )

    /**
     * 一轮里的先后：整轮串行，排前面的先出结果。
     * 首页最常看的（成绩、课程、图书馆座位、校园卡）在前；不常看或经常连不上的
     * （刷卡记录）靠后；加餐券最后。不在表里的排最末。
     */
    private val runOrder: List<AppRoute> = listOf(
        AppRoute.CampusCard, AppRoute.Attendance, AppRoute.JwappScore, AppRoute.Library, AppRoute.Lms(),
        AppRoute.Judge, AppRoute.Fitness, AppRoute.Notification,
        AppRoute.Iclassface, AppRoute.Coupon, AppRoute.EmptyRoom,
    )

    /**
     * 兴庆校区此刻（当前或下一节）各楼空着几间，取空得最多的三栋。只认 CDN 的当天表；
     * 晚上 22 点后没有节次可看就不出。
     */
    private fun emptyRoomOverview(): HomeStat? {
        val now = com.xjtu.toolbox.util.nowOnCampus()
        val minute = now.hour * 60 + now.minute
        val section = com.xjtu.toolbox.schedule.XjtuTime.getAllTimes()
            .firstOrNull { (_, t) -> t.end.hour * 60 + t.end.minute > minute }?.first ?: return null
        val prefs = com.xjtu.toolbox.platform.keyValueStore("empty_room")
        val campus = prefs.getString("empty_room_last_campus")?.takeIf { it in com.xjtu.toolbox.emptyroom.CAMPUS_BUILDINGS }
            ?: "兴庆校区"
        val buildings = com.xjtu.toolbox.emptyroom.CAMPUS_BUILDINGS[campus].orEmpty()
        val rooms = runCatching {
            com.xjtu.toolbox.emptyroom.EmptyRoomApi().getEmptyRoomsMulti(campus, buildings.toSet())
        }.getOrNull() ?: return null
        val free = rooms.filter { it.status.getOrNull(section - 1) == 0 }
        if (free.isEmpty()) return null
        val favorites = prefs.getString("empty_room_last_buildings_$campus").orEmpty().split('|')
        fun freeUntil(room: com.xjtu.toolbox.emptyroom.RoomInfo): Int =
            (section..room.status.size).takeWhile { room.status[it - 1] == 0 }.lastOrNull() ?: section
        val picks = free.sortedWith(compareByDescending<com.xjtu.toolbox.emptyroom.RoomInfo> { r -> favorites.any { r.name.startsWith(it) && it.isNotBlank() } }
            .thenByDescending { freeUntil(it) }.thenBy { it.name }).take(4)
        return HomeStat(
            picks.first().name,
            "$campus · 第 $section 节起 · 课表空闲",
            picks.map { r -> HomeLine("${r.name} · 空至第 ${freeUntil(r)} 节", "app:${AppRoute.EmptyRoom.id}") },
        )
    }

    private val sources: List<Source> = listOf(
        Source(AppRoute.YellowPage, 7 * DAY, null) { ctx, _ ->
            val data = com.xjtu.toolbox.yellowpage.appYellowPageApi(ctx, roundAccount).getData()
            HomeStat("${data.departments.size} 个机构", "常用校内电话")
        },
        Source(AppRoute.SchoolCalendar, 1 * DAY, null) { _, _ ->
            val terms = com.xjtu.toolbox.calendar.SchoolCalendarApi().terms()
            val today = com.xjtu.toolbox.util.todayOnCampus()
            val term = terms.firstOrNull { it.currentWeek(today) > 0 } ?: terms.firstOrNull { it.startDate > today }
                ?: return@Source null
            val events = term.events.filter { it.endDate >= today }.sortedBy { it.startDate }.take(4)
            HomeStat(term.currentWeek(today).takeIf { it > 0 }?.let { "第 $it 周" } ?: "学期未开始", term.termName,
                events.map { HomeLine("${it.startDate.monthNumber}/${it.startDate.dayOfMonth} · ${it.name}") })
        },
        Source(AppRoute.Community, 30 * 60 * 1000L, null) { _, _ ->
            val session = com.xjtu.toolbox.community.SecureGithubSession.get()
            if (session.login.value == null) return@Source null
            val page = session.repository.list(com.xjtu.toolbox.community.CommunityRepo.OWNER, com.xjtu.toolbox.community.CommunityRepo.NAME, null,
                com.xjtu.toolbox.community.DiscussionQuery(sort = com.xjtu.toolbox.community.DiscussionSort.CREATED)).getOrThrow()
            val posts = page.items.sortedByDescending { it.createdAt }.take(4)
            posts.firstOrNull()?.let { first -> HomeStat(first.title, "社区最新发布",
                posts.map { HomeLine(it.title, it.url) }) }
        },
        // 评教：一周一次。开没开评教窗口在一周内不会反复变。
        Source(AppRoute.Judge, 7 * DAY, LoginType.JWXT) { _, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                val api = com.xjtu.toolbox.judge.JudgeApi(site)
                val term = runCatching { api.getCurrentTerm() }.getOrNull()
                val todo = api.unfinishedQuestionnaires(term).size
                val done = api.finishedQuestionnaires(term).size
                val all = todo + done
                Log.d(TAG, "judge: term=$term todo=$todo done=$done")
                InboxStore.setTodos(
                    InboxCategories.JUDGE,
                    if (todo > 0) listOf(OwnInbox.todo(InboxCategories.JUDGE, "judge", "评教", "还有 $todo 门课没评教", AppRoute.Judge.id)) else emptyList(),
                    roundAccount,
                )
                if (all == 0) null
                else HomeStat(
                    if (todo == 0) "已评完" else "$todo/$all 门待评",
                    if (todo == 0) "本学期评教已完成" else "共 $all 门"
                )
            }
        },

        // 体测：一周一次。成绩一学期才更新一次，一周已经很勤了。
        Source(AppRoute.Fitness, 7 * DAY, LoginType.FITNESS) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                val api = com.xjtu.toolbox.fitness.FitnessApi(site)
                val years = runCatching { api.years() }.getOrNull().orEmpty()
                val ordered = com.xjtu.toolbox.fitness.orderedFitnessYears(years)

                var picked: Pair<String, com.xjtu.toolbox.fitness.FitnessScore>? = null
                for ((i, y) in ordered.take(3).withIndex()) {
                    if (i > 0) delay(GAP_MS)
                    val s = runCatching { api.score(y.yearNum) }.getOrNull() ?: continue
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
        Source(AppRoute.Lms(), 1 * DAY, LoginType.LMS) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) { lmsLatest(site, ctx) }
        },

        // 快速考勤流水：最新一条刷卡记录。用户要求"打开时立即请求"，
        // 所以 TTL 给到 10 分钟——首页每次进来基本都会重取，又不至于同一次浏览里反复打。
        Source(AppRoute.Iclassface, 10 * 60 * 1000L, LoginType.ICLASSFACE) { _, site ->
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

        // 加餐券：6 小时一次。
        //
        // 两件事有时限、错过就作废：**待领取**（不领就没有）和**即将到期**（不用就浪费）。
        // 这也是为什么它值得主动拉——不像成绩那样"早晚会知道"，这个过期就真没了。
        // 一天多次但不至于太频，6 小时足够在到期前那几天提醒到人。
        Source(AppRoute.Coupon, 6 * 60 * 60 * 1000L, LoginType.COUPON) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) { couponStatus(ctx, site) }
        },


        // 考勤：两天一次。本研统一。
        Source(AppRoute.Attendance, 2 * DAY, LoginType.ATTENDANCE) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) { attendanceWeeklyRate(ctx, site) }
        },

        // 成绩：一天一次。成绩出分是低频事件，但"出了没出"用户很在意，一天一查是平衡点。
        //
        // 这里除了给首页写摘要，还负责**算出"新增了几门"**供屁岱主动提醒使用：
        // 把总门数记在一个独立的游标里，与上次对比。游标只在成功取到数据时前移，
        // 否则一次失败会把基线冲掉，之后永远判不出"新增"。
        Source(AppRoute.JwappScore, 1 * DAY, LoginType.JWAPP) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                val terms = runCatching { com.xjtu.toolbox.jwapp.JwappApi(site).getGrade() }
                    .onFailure { Log.w(TAG, "score: getGrade 失败 ${it.message}") }
                    .getOrNull().orEmpty()
                val total = terms.sumOf { it.scoreList.size }
                Log.d(TAG, "score: ${terms.size} 个学期，共 $total 门")
                if (total == 0) return@withContext null

                val newCount = HomeStats.bumpScoreCursor(ctx, total, roundAccount)
                // 累加而非覆盖：气泡可能还没冒出来就又刷了一轮，直接覆盖会把待提醒的数吞掉。
                // 冒过一次后由 MainActivity 清零。
                if (newCount > 0) {
                    HomeStats.setPendingNewScores(ctx, HomeStats.pendingNewScores(ctx, roundAccount) + newCount, roundAccount)
                    InboxStore.post(OwnInbox.grade(newCount, total), roundAccount)
                }
                // 只报本学期：教务的学期信息和日程页缓存都是同一套学期号，本学期还没出分就如实说没有，
                // 不拿上学期的顶上。取不到当前学期号时才退回「有成绩的最新学期」。
                val currentTerm = runCatching { com.xjtu.toolbox.jwapp.JwappApi(site).getCurrentTerm() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: com.xjtu.toolbox.schedule.ScheduleCache.readCurrentTerm(com.xjtu.toolbox.data.DataCache(ctx, roundAccount))
                val shown = if (currentTerm != null) terms.firstOrNull { it.termCode == currentTerm } else terms.maxByOrNull { it.termCode }
                val shownCount = shown?.scoreList?.size ?: 0
                // 小组件明细：最近出分的几门（取不到本学期就用有成绩的最新学期）
                val recent = (shown ?: terms.maxByOrNull { it.termCode })?.scoreList.orEmpty()
                    .take(4).map { com.xjtu.toolbox.home.HomeLine("${it.courseName.take(10)}  ${it.score}") }
                when {
                    newCount > 0 -> HomeStat("$newCount 门新成绩", "共 $total 门 · 有更新", recent)
                    shownCount > 0 -> HomeStat("$shownCount 门", "${shown?.termName.orEmpty()} 学期", recent)
                    currentTerm != null -> HomeStat("暂无", "本学期成绩未出", recent)
                    else -> HomeStat("$total 门", "共 $total 门", recent)
                }
            }
        },

        // 校园卡：30 分钟一次。会主动登录，余额缓存才不会因没进过校园卡页而一直为空。
        Source(AppRoute.CampusCard, 30 * 60 * 1000L, LoginType.CAMPUS_CARD) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                val ok = com.xjtu.toolbox.card.refreshCampusCardCache(ctx, site)
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

        // 图书馆：平时 10 分钟一次，有待签到 / 待返回时 2 分钟一次。
        //
        // 状态**是有时效的**——"待入馆"要在限定时间内签到，"临时离馆"超时会被释放座位；
        // 学校推的座位消息收纳不收，全靠这里及时。切回前台也会触发一轮（见 MainScreen）。
        Source(
            AppRoute.Library, 10 * 60 * 1000L, LoginType.LIBRARY,
            ttlNow = {
                val pending = com.xjtu.toolbox.inbox.InboxStore.load(roundAccount).todos[com.xjtu.toolbox.inbox.InboxCategories.LIBRARY]
                if (pending.isNullOrEmpty()) 10 * 60 * 1000L else 2 * 60 * 1000L
            },
        ) { ctx, site ->
            site ?: return@Source null
            withContext(Dispatchers.IO) {
                // 查失败算这个源失败，不能当成「没有预约」把待办和提醒清掉
                val b = com.xjtu.toolbox.library.LibraryApi(com.xjtu.toolbox.library.AppLibrarySession(site)).fetchMyBooking().getOrThrow()
                // 在图书馆自助机上约的座位不会经过本 App 的图书馆页，首页这一轮是唯一能发现它的地方。
                com.xjtu.toolbox.library.LibraryStatus.publish(ctx, b, roundAccount)
                if (b == null) return@withContext null
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

        // 空教室：读 CDN 的当天占用表（不用登录），算出此刻各楼空着几间。不必及时，两小时一次。
        Source(AppRoute.EmptyRoom, 10 * 60 * 1000L, null) { _, _ ->
            withContext(Dispatchers.IO) { emptyRoomOverview() }
        },

        // 教务通知：来源跟用户在设置里勾的一致，小组件和系统通知共用。
        // 不需要登录，4 小时一次；真正抓取和去重交给 NoticeWatchSync。
        Source(AppRoute.Notification, 4 * 60 * 60 * 1000L, null) { ctx, _ ->
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
    )

    /**
     * 思源学堂最新的作业/资料两条。
     *
     * 两个坑：
     * 1. **学期要自己挑最新的**。思源里 2025-2026 春季学期比秋季更"新"，但课程列表并不
     *    按此排序，直接遍历全部课程会把上学年的旧作业也捞进来。这里按 [lmsTermOrder]
     *    选出最大的那一档，只看该学期的课。
     * 2. **活动确实带时间戳**（`LmsActivity.updatedAt` / `createdAt`），所以能排序取最新——
     *    此前不确定有没有时间字段，看模型确认是有的。
     *
     * 成本：该学期每门课一次活动请求（实测 11 门），外加未交作业各一次提交态查询；整个源一天只刷一次。
     * 不能只取前几门：待办和日程页的作业截止也从这一轮出，课程列表又不按重要性排，
     * 截断后排在后面的课（#121 里的英语排第 9）作业就永远进不了待办。
     */
    private suspend fun lmsLatest(site: SiteSession, ctx: Context): HomeStat? {
        val api = com.xjtu.toolbox.lms.LmsApi(site)
        val courses = runCatching { api.getMyCourses() }
            .onFailure { Log.w(TAG, "lms: getMyCourses 失败 ${it.message}") }
            .getOrNull().orEmpty()
        Log.d(TAG, "lms: 课程 ${courses.size} 门")
        if (courses.isEmpty()) return null

        // 最新学期 = 学期 code 最大的一档。为什么不能用 sort，见 [lmsTermOrder] 的注释。
        val inTerm = newestTermCourses(courses)
        Log.d(TAG, "lms: 最新学期 ${inTerm.firstOrNull()?.semester?.code}，取 ${inTerm.size} 门：${inTerm.map { it.name }}")

        val perCourse = com.xjtu.toolbox.lms.LmsDueCollector.activities(api, inTerm)
        // 待办和日程页「接下来」读的截止缓存（LmsDueStore）也从这一轮写，它们自己从不发请求
        com.xjtu.toolbox.lms.LmsDueCollector.collect(ctx, api, perCourse, roundAccount.orEmpty(), ::roundIsCurrent)

        val wanted = setOf(
            com.xjtu.toolbox.lms.LmsActivityType.HOMEWORK,
            com.xjtu.toolbox.lms.LmsActivityType.MATERIAL,
        )
        val all = perCourse.flatMap { (c, acts) -> acts.map { Triple(c.id, c.name, it) } }
        Log.d(TAG, "lms: 活动共 ${all.size} 条，类型分布=${all.groupingBy { it.third.type }.eachCount()}")
        // 不再要求 published：活动列表接口返回的本就是学生可见的内容，而该字段在**列表**响应里
        // 常常缺失（详情接口才有），safeBoolean() 于是一律得到 false，把所有活动都滤没了。
        val acts = all.filter { it.third.type in wanted }
        Log.d(TAG, "lms: 命中作业/资料 ${acts.size} 条")
        if (acts.isEmpty()) return HomeStat("${inTerm.size} 门课程", "本学期暂无作业或资料",
            inTerm.take(4).map { HomeLine(it.name, "app:${AppRoute.Lms(courseId = it.id).id}") })

        // 排序时间要逐级兜底：**部分作业既没有 updated_at 也没有 created_at**
        // （用户实测遇到过），只用这两个字段的话它们会以空串排到最后，永远选不中。
        // 作业还有截止/开始时间可用，最后才退回空串。截止优先读列表里的 deadline，
        // 与详情页、截止提醒同一口径（end_time 可能比真截止早）。
        val latest = acts.sortedByDescending { (_, _, a) ->
            a.updatedAt.ifBlank { a.createdAt }
                .ifBlank { a.deadline.orEmpty() }
                .ifBlank { a.endTime.orEmpty() }
                .ifBlank { a.startTime.orEmpty() }
        }.take(4)

        fun label(triple: Triple<Int, String, com.xjtu.toolbox.lms.LmsActivity>): String {
            val (_, course, a) = triple
            val kind = if (a.type == com.xjtu.toolbox.lms.LmsActivityType.HOMEWORK) "作业" else "资料"
            return "$kind · ${a.title.ifBlank { course }.take(14)}"
        }
        return HomeStat(
            label(latest[0]),
            latest.getOrNull(1)?.let(::label),
            latest.map { t -> HomeLine("${t.second.take(6)} · ${t.third.title.ifBlank { "未命名" }}", "app:${AppRoute.Lms(t.first, t.third.id).id}") },
        )
    }

    /** 手机当前连着能上网的网络。不要求系统验证通过：校园网没过认证页时也算有网，失败照常计。 */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 跑一轮刷新。只处理已过期的源，逐个串行；上一个源发生了登录才留 [GAP_MS]。
     * 同一时刻只允许一轮（[runLock]），防止反复进出首页把请求叠起来。
     */
    suspend fun refreshDue(
        context: Context,
        manager: SessionManager?,
        accountType: AccountType = AccountType.UNDERGRADUATE,
    ) {
        if (!runLock.tryLock()) {
            Log.d(TAG, "skip: another refresh in flight")
            return
        }
        try {
            // 认证退避只影响需登录的源，公开信息照常刷新。
            val canLogin = manager?.credentials != null && com.xjtu.toolbox.auth.CasGate.blockedReason() == null
            // 整轮绑定发起时的账号。SessionManager 切账号是原地重配而不是换实例，
            // 一轮十几秒里若切了账号，后面拉到的是新账号的数据；此时整轮作废、什么都不写。
            val roundAccount = com.xjtu.toolbox.account.AccountContext.activeAccountId
            this.roundAccount = roundAccount
            fun accountChanged() = !roundIsCurrent()
            val stamps = HomeStats.stamps(context, sources.map { it.route }, roundAccount)
            val now = System.currentTimeMillis()
            // 上一个源是否可能刚和统一认证打过交道（登录成功算；取站点出异常时保守地也算），是的话下一个源前留间隔
            var justLoggedIn = false
            val coldStart = firstRunInProcess
            firstRunInProcess = false
            val existing = HomeStats.collect(context, null).keys
            Log.d(TAG, "start; coldStart=$coldStart 已有内容=$existing stamps=${stamps.mapValues { (now - it.value) / 60000 }} (分钟前)")
            for (s in sources.sortedWith(compareBy<Source> { it.loginType != null }.thenBy { runOrder.indexOf(it.route).let { i -> if (i < 0) Int.MAX_VALUE else i } })) {
                if (s.loginType != null && !canLogin) continue
                if (s.loginType == LoginType.ICLASSFACE && accountType != AccountType.UNDERGRADUATE) continue
                // 首页评教统计走的是本科教务评教；研究生评教在 gste，要单独登录，不在后台刷
                if (s.route == AppRoute.Judge && accountType != AccountType.UNDERGRADUATE) continue
                val last = stamps[s.route.id] ?: 0L
                val hasContent = s.route.id in existing
                val ttl = s.ttlNow()
                if (now - last < ttl && !(coldStart && !hasContent)) {
                    Log.d(TAG, "${s.route.id}: 未到期，跳过（距上次 ${(now - last) / 60000} 分钟，TTL ${ttl / 60000} 分钟）")
                    continue
                }
                if (coldStart && !hasContent && now - last < ttl) {
                    Log.d(TAG, "${s.route.id}: 冷启动且暂无内容，忽略退避重试")
                }
                if (justLoggedIn) delay(GAP_MS)
                if (accountChanged()) {
                    Log.d(TAG, "abort round: account switched")
                    return
                }
                val siteKey = s.loginType?.siteKey()
                val epochBefore = siteKey?.let { manager?.getSiteOrNull(it)?.loginEpoch }
                justLoggedIn = siteKey != null
                try {
                    // silent = true：后台绝不弹 MFA、不发短信，撞上就抛 MfaRequiredException。
                    val site = siteKey?.let { manager?.ensureSite(it, silent = true) }
                    justLoggedIn = site != null && site.loginEpoch != epochBefore
                    val stat = s.fetch(context, site)
                    if (accountChanged()) {
                        Log.d(TAG, "abort round: account switched during ${s.route.id}")
                        return
                    }
                    HomeStats.push(context, s.route, stat?.value, stat?.detail, roundAccount, stat?.lines.orEmpty())
                    if (stat == null) HomeStats.markEmpty(context, s.route, s.ttlMs, roundAccount)
                    else HomeStats.markFetched(context, s.route, roundAccount)
                    HomeSignals.bumpStatsVersion()
                    Log.d(TAG, "${s.route.id} -> ${stat?.value ?: "无数据（1 小时后重试）"}")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 发起这轮的页面没了，不是源的问题，别写失败戳
                    throw e
                } catch (e: com.xjtu.toolbox.auth.CasGate.ThrottledException) {
                    Log.d(TAG, "abort round: CasGate throttled (${e.message})")
                    return
                } catch (e: com.xjtu.toolbox.auth.PasswordInvalidatedException) {
                    Log.d(TAG, "abort round: password invalidated")
                    return
                } catch (e: com.xjtu.toolbox.auth.LoginCooldownException) {
                    // 站点级 60 秒冷却：只是这个源暂时进不去，别的源照跑，也别写失败戳
                    // （60 秒后本来就能重试，写成 30 分钟反而更糟）。
                    Log.d(TAG, "${s.route.id}: 站点冷却中，跳过不计失败")
                } catch (e: com.xjtu.toolbox.auth.MfaRequiredException) {
                    // 需要短信验证。后台不碰，等用户主动进那个功能页时自然会走完整流程。
                    // 同样不写失败戳：这不是故障，是"现在不该由我来做"。
                    Log.d(TAG, "${s.route.id}: 需短信验证，后台跳过")
                } catch (e: Exception) {
                    // 手机这会儿没网（切网、进电梯）不是源的问题：不写失败戳、整轮停下，网络回来时再跑
                    if (!isOnline(context)) {
                        Log.d(TAG, "abort round: offline (${s.route.id}: ${e.message})")
                        return
                    }
                    // 半小时后重试，不按正常 TTL 锁死——故障多是暂时的（网关抖动、系统维护），
                    // 按 2 天/7 天锁住会让"修好了却还是不显示"。
                    if (!accountChanged()) HomeStats.markFailed(context, s.route, s.ttlMs, roundAccount)
                    Log.w(TAG, "${s.route.id} refresh failed (retry in 30min): ${e.message}")
                }
            }
            // 学校消息、事务中心待办、预约：都挂在一网通办上，30 分钟一次
            if (!accountChanged() && canLogin && SchoolInbox.isDue(roundAccount)) {
                try {
                    SchoolInbox.refresh(manager.ensureSite(LoginType.YWTB.siteKey(), silent = true), roundAccount)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "inbox: ${e.message}")
                }
            }
            // 学籍档案：缓存新鲜时不发请求
            if (!accountChanged() && canLogin) {
                com.xjtu.toolbox.hello.HelloProfileStore.ensure(context, manager, silent = true)
            }
        } finally {
            runLock.unlock()
        }
    }


    /**
     * 本周出勤率。直接用考勤系统自己的「本周考勤统计」接口，不自己按日期聚合——
     * 学校对"本周"的定义（教学周、跨周考试等）以它为准。
     */
    /**
     * 加餐券：待领取张数 + 最近到期日。
     *
     * 待领取排在到期前面：没领的券点一下就有，是纯白捡；快到期的券要专门跑一趟去花，
     * 优先级本来就低一档。
     *
     * 到期只在 [COUPON_EXPIRY_WARN_DAYS] 天内才写提醒信号——一个月后到期的券天天念叨，
     * 到真该用的时候人已经免疫了。
     */
    private suspend fun couponStatus(ctx: android.content.Context, site: SiteSession): HomeStat? {
        val api = com.xjtu.toolbox.coupon.CouponApi(site)
        val available = runCatching {
            api.queryCoupons(com.xjtu.toolbox.coupon.CouponFilter.AVAILABLE, pageSize = 20)
        }.getOrNull()
        val usable = runCatching {
            api.queryCoupons(com.xjtu.toolbox.coupon.CouponFilter.USABLE, pageSize = 20)
        }.getOrNull()
        if (available == null && usable == null) return null

        val pending = available?.records?.size ?: 0
        val today = java.time.LocalDate.now(com.xjtu.toolbox.util.CampusZoneId)
        // endDate 是 "2026-01-31" 这类文本，解析不出就不参与到期判断——
        // 猜错日期去催用户用券，比不催更糟。
        val soonest = usable?.records.orEmpty()
            .mapNotNull { r -> runCatching { java.time.LocalDate.parse(r.endDate.take(10)) }.getOrNull() }
            .filter { !it.isBefore(today) }
            .minOrNull()
        val daysLeft = soonest?.let {
            java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt()
        }
        Log.d(TAG, "coupon: 待领取 $pending 张，可用 ${usable?.records?.size ?: 0} 张，最近到期 $soonest")

        InboxStore.setTodos(
            InboxCategories.COUPON,
            listOfNotNull(
                OwnInbox.todo(InboxCategories.COUPON, "coupon:pending", "加餐券", "有 $pending 张加餐券没领", AppRoute.Coupon.id)
                    .takeIf { pending > 0 },
                soonest?.takeIf { daysLeft != null && daysLeft <= COUPON_EXPIRY_WARN_DAYS }?.let { day ->
                    OwnInbox.todo(
                        InboxCategories.COUPON, "coupon:expiring", "加餐券",
                        if (daysLeft == 0) "有加餐券今天到期" else "有加餐券 $daysLeft 天后到期", AppRoute.Coupon.id,
                        expiresAt = day.plusDays(1).atStartOfDay(com.xjtu.toolbox.util.CampusZoneId).toInstant().toEpochMilli(),
                    )
                },
            ),
            roundAccount,
        )
        // 拉取期间切了账号：这份结果属于上一个账号，不能写成新账号的提醒
        if (roundIsCurrent()) HomeSignals.couponAlert = when {
            pending > 0 -> "有 $pending 张加餐券没领"
            daysLeft != null && daysLeft <= COUPON_EXPIRY_WARN_DAYS ->
                if (daysLeft == 0) "有加餐券今天就到期了" else "有加餐券还有 $daysLeft 天到期"
            else -> null
        }

        val usableCount = usable?.records?.size ?: 0
        if (pending == 0 && usableCount == 0) return HomeStat("暂无", "没有可领或可用的券")
        return HomeStat(
            if (pending > 0) "$pending 张待领" else "$usableCount 张可用",
            listOfNotNull(
                if (pending > 0 && usableCount > 0) "另有 $usableCount 张可用" else null,
                daysLeft?.let { if (it == 0) "今天到期" else "最近 $it 天后到期" },
            ).joinToString(" · ").ifBlank { null },
        )
    }

    /** 到期提醒的提前量。太早提醒等于没提醒，到真该用的时候人已经免疫了。 */
    private const val COUPON_EXPIRY_WARN_DAYS = 3

    private suspend fun attendanceWeeklyRate(ctx: android.content.Context, site: SiteSession): HomeStat? {
        val stats = com.xjtu.toolbox.attendance.AttendanceApi(site).getKqtjCurrentWeek()
        Log.d(TAG, "attendance: 本周统计 ${stats.size} 门课")
        if (stats.isEmpty()) return HomeStat("本周暂无", "暂无考勤统计")
        val total = stats.sumOf { it.total }
        Log.d(TAG, "attendance: total=$total normal=${stats.sumOf { it.actualCount }}")
        if (total == 0) return HomeStat("本周暂无", "本周尚无应到记录")
        val ok = stats.sumOf { it.actualCount }
        val abnormal = stats.sumOf { it.abnormalCount }
        val rate = ok * 100 / total

        // 异常数**增加**才提醒，不是"有异常"就提醒：一次缺勤会挂在统计里一整周，
        // 每轮刷新都当新事报一遍就成了骚扰。游标只在成功取到数据时前移，
        // 否则一次失败会把基线冲掉，之后永远判不出增量。
        // 按账号分开存，否则切账号后拿别人的异常数当基线
        val prefs = ctx.getSharedPreferences(
            "attendance_watch${com.xjtu.toolbox.account.AccountContext.suffixFor(roundAccount)}",
            android.content.Context.MODE_PRIVATE,
        )
        val seen = prefs.getInt("abnormal_seen", -1)
        if (seen >= 0 && abnormal > seen && roundIsCurrent()) {
            val worst = stats.filter { it.abnormalCount > 0 }.maxByOrNull { it.abnormalCount }
            val alert = if (worst != null) {
                "${worst.subjectName}的考勤有异常了"
            } else {
                "本周考勤多了 ${abnormal - seen} 次异常"
            }
            HomeSignals.attendanceAlert = alert
            InboxStore.post(OwnInbox.attendance(alert), roundAccount)
        }
        prefs.edit().putInt("abnormal_seen", abnormal).apply()

        return HomeStat(
            "$rate%",
            if (abnormal > 0) "本周 $ok/$total 次正常 · $abnormal 次异常" else "本周 $ok/$total 次正常",
            stats.sortedByDescending { it.abnormalCount }.take(4).map {
                HomeLine("${it.subjectName} · " + if (it.abnormalCount > 0) "迟到 ${it.lateCount} / 缺勤 ${it.absenceCount}" else "${it.actualCount}/${it.total} 次正常")
            },
        )
    }
}

/**
 * 选出"最新学期"的全部课程。
 *
 * 认不出学期的课（`academic_year` / `semester` 为 null）不参与，也不会被当成最新；
 * 一门都认不出时退化成不筛——宁可多查几门，也别让首页和待办空着。
 */
internal fun newestTermCourses(courses: List<LmsCourseSummary>): List<LmsCourseSummary> {
    fun order(c: LmsCourseSummary) = lmsTermOrder(c.semester.code, c.academicYear.code)
    val newest = courses.mapNotNull(::order).maxOrNull() ?: return courses
    return courses.filter { order(it) == newest }
}

/**
 * 思源学期排序键：`2026-1` → `202601`，越大越新；认不出返回 null。
 *
 * 为什么不用 `academicYear.sort` + `semester.sort`：实测 `academic_year.sort` 恒为 0
 * （字段在，值不区分学年），退化后只剩 `semester.sort`，而它的顺序是**越旧越大**
 * （2025-1=5 > 2025-2=4 > 2026-1=1），于是"最新学期"恰好选中一年前那个。
 * `semester.name` / `real_name` 实测全是 null，`code` 是唯一单调可比的锚点。
 */
internal fun lmsTermOrder(semesterCode: String?, academicYearCode: String?): Int? {
    val year = semesterCode?.substringBefore('-')?.trim()?.toIntOrNull()
        ?: academicYearCode?.substringBefore('-')?.trim()?.toIntOrNull()
        ?: return null
    val term = semesterCode?.substringAfter('-', "")?.trim()?.toIntOrNull() ?: 0
    return year * 100 + term
}
