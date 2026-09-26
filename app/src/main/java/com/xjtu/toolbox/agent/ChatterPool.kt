package com.xjtu.toolbox.agent

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * 首页闲话。全部本地短句，不调模型。
 *
 * 字数卡死在 [MAX_CHARS]，气泡禁止省略号——闲话被裁成「早八是一种…」就没了。
 */
internal data class ChatterLine(
    val id: String,
    val text: String,
    val hours: IntRange? = null,
    val months: IntRange? = null,
    val weekdays: Set<DayOfWeek>? = null,
    val weight: Double = 1.0,
    val action: String? = null,
)

/**
 * 闲话用得上的几件真事，由 [ChatterFactsLoader.load] 从本地缓存读出来（不联网、不读数据库）。
 * 读不到就是 null / 空，对应的情景句自然落选。
 *
 * 为什么要它：静态句子对谁都一样，「今天有点难吧」对着一个状态很好的人说就是错位。
 * 关心要落在真事上——今天有体育课、下午连上四节、明天放假——才不像模板。
 */
internal data class ChatterFacts(
    /** 今天的课，按节次排好。 */
    val today: List<Slot> = emptyList(),
    /** 明天第一节课的节次；明天没课为 null。 */
    val tomorrowFirstSection: Int? = null,
    /** 今天是法定假日时的节日名。 */
    val todayHoliday: String? = null,
    /** 最近一个还没到的法定假日：名字和还有几天（1 = 明天）。只看两周内。 */
    val nextHoliday: Pair<String, Int>? = null,
) {
    data class Slot(val name: String, val startSection: Int, val endSection: Int)
}

internal object ChatterPool {

    const val MAX_CHARS = 14

    val lines: List<ChatterLine> = listOf(
        // 深夜
        ChatterLine("late_awake", "这个点还没睡啊", hours = 0..4),
        ChatterLine("late_hate", "明天的你会恨现在", hours = 0..4),
        ChatterLine("late_light", "宿舍灯还亮着", hours = 0..4),
        ChatterLine("late_roll", "这个点还在卷啊", hours = 0..4),
        ChatterLine("late_dawn", "别刷到天亮了", hours = 1..5),
        ChatterLine("late_breakfast", "别把早饭睡过去", hours = 4..6),
        // 早
        ChatterLine("am_ba", "早八是一种人格", hours = 6..8),
        ChatterLine("am_soy", "食堂豆浆见", hours = 6..8),
        ChatterLine("am_quilt", "别在被窝里看课表", hours = 6..8),
        ChatterLine("am_bao", "豆浆配包子走起", hours = 6..9),
        ChatterLine("am_pk", "彭康路梧桐在等", hours = 7..9),
        ChatterLine("am_third", "第三节才是真困", hours = 9..11),
        ChatterLine("am_pick", "去康桥还是梧桐", hours = 7..10),
        // 午饭
        ChatterLine("noon_kq", "康桥队伍排到哪了", hours = 11..13),
        ChatterLine("noon_milk", "饭点别只喝奶茶", hours = 11..13),
        ChatterLine("noon_full", "康桥一层人满了", hours = 11..13),
        ChatterLine("noon_lp", "凉皮夹馍先顶上", hours = 11..13),
        ChatterLine("noon_hm", "和鸣苑午饭见", hours = 11..13),
        ChatterLine("noon_hf", "惠风苑民族餐走起", hours = 11..13),
        // 下午
        ChatterLine("pm_room", "空教室才是归宿", hours = 13..17),
        ChatterLine("pm_walk", "站起来走两步", hours = 14..18),
        ChatterLine("pm_exp", "实验课别摸鱼", hours = 13..18),
        // 晚饭
        ChatterLine("eve_win", "晚饭窗口别犹豫", hours = 17..19),
        ChatterLine("eve_card", "别把饭卡忘食堂", hours = 17..19),
        ChatterLine("eve_wt2", "梧桐苑二楼见", hours = 17..19),
        ChatterLine("eve_kq3", "康桥三楼有自助餐", hours = 17..19),
        // 晚上
        ChatterLine("night_close", "图书馆该散了", hours = 22..23),
        ChatterLine("night_ba", "明天的早八在盯你", hours = 22..23),
        ChatterLine("night_cy", "畅园夜风有点硬", hours = 19..23),
        ChatterLine("night_bed", "别在床上改PPT", hours = 20..23),
        ChatterLine("night_rpt", "报告还差一节", hours = 19..23),
        // 四季（跨年月份不能写 11..2，那是空区间）
        ChatterLine("spr_fluff", "梧桐絮又开始下", months = 3..5),
        ChatterLine("spr_rain", "小心梧桐河！", months = 3..5),
        ChatterLine("spr_pollen", "花粉战士集合", months = 4..5),
        ChatterLine("sum_bath", "去洗个凉水澡！", months = 6..8),
        ChatterLine("sum_junxun", "看看小登军训，嘿嘿", months = 8..9),
        ChatterLine("sum_shade", "新港没有树荫啊", months = 6..8),
        ChatterLine("aut_leaf", "期待金色梧桐节呀！", months = 10..11),
        ChatterLine("aut_hot", "开学热还没散", months = 9..10),
        ChatterLine("aut_gold", "彭康路在铺金", months = 10..11),
        ChatterLine("aut_pick", "去捡两片梧桐叶", months = 10..11),
        // 春见樱花节：兴庆、雁塔，大约三月底到四月初（创新港没有樱花）
        ChatterLine("sakura_fest", "春见樱花节来了", months = 3..4),
        ChatterLine("sakura_xq", "兴庆樱花开了没", months = 3..4),
        ChatterLine("sakura_shot", "别只拍照，看看花", months = 3..4),
        // 选课：学期初主峰，期中还有一轮改课
        ChatterLine("pick_fight_a", "选课像打仗", months = 2..3),
        ChatterLine("pick_fight_b", "选课像打仗", months = 8..9),
        ChatterLine("pick_pe_a", "体育课秒没了", months = 2..3),
        ChatterLine("pick_pe_b", "体育课秒没了", months = 8..9),
        ChatterLine("pick_slip_a", "志愿填报别手滑", months = 2..3),
        ChatterLine("pick_slip_b", "志愿填报别手滑", months = 8..9),
        ChatterLine("pick_add_a", "补退选开始了", months = 2..3),
        ChatterLine("pick_add_b", "补退选开始了", months = 8..9),
        ChatterLine("pick_mid_a", "期中改课别错过", months = 4..5),
        ChatterLine("pick_mid_b", "期中改课别错过", months = 10..11),
        // 保研：夏令营到九推
        ChatterLine("bao_pre", "预推免开始了", months = 8..8),
        ChatterLine("bao_nine", "九推别只刷群", months = 9..9),
        // 考研：报名、初试、出分、复试调剂
        ChatterLine("kao_sign", "考研报名别忘了", months = 10..10),
        ChatterLine("kao_paper", "真题别只收藏", months = 9..12),
        ChatterLine("kao_seat", "钱图考研位满了", months = 11..12),
        ChatterLine("kao_exam", "十二月初试加油", months = 12..12),
        // 考试月：春夏小学期尾、秋冬学期尾
        ChatterLine("exam_qt_a", "考试月钱图爆了", months = 6..7),
        ChatterLine("exam_qt_b", "考试月钱图爆了", months = 12..12),
        ChatterLine("exam_qt_c", "考试月钱图爆了", months = 1..1),
        ChatterLine("exam_quiet_a", "期末周食堂好安静", months = 6..7),
        ChatterLine("exam_quiet_b", "期末周食堂好安静", months = 12..12),
        ChatterLine("exam_quiet_c", "期末周食堂好安静", months = 1..1),
        // 小学期：盛夏还在上课，不是假期
        ChatterLine("short_not", "小学期不是假期", months = 7..7),
        ChatterLine("short_july", "七月还在上课啊", months = 7..7),
        ChatterLine("short_lab", "小学期实验更狠", months = 7..7),
        // 寒假
        ChatterLine("vac_count", "寒假倒计时开始", months = 1..1),
        ChatterLine("vac_train", "车票抢到了没", months = 1..1),
        ChatterLine("vac_heat", "宿舍暖气还在供", months = 1..2),
        ChatterLine("win_snow_a", "下雪了么？", months = 11..12),
        ChatterLine("win_snow_b", "下雪了么？", months = 1..2),
        ChatterLine("win_main_a", "主楼暖气开得太大", months = 12..12),
        ChatterLine("win_main_b", "主楼暖气开得太大", months = 1..2),
        ChatterLine("win_two_a", "主楼里外两重天", months = 12..12),
        ChatterLine("win_two_b", "主楼里外两重天", months = 1..2),
        // 周节奏
        ChatterLine("mon_ba", "周一的早八最真", hours = 6..10, weekdays = setOf(DayOfWeek.MONDAY)),
        ChatterLine("wed_home", "周三晚上想回家", hours = 18..22, weekdays = setOf(DayOfWeek.WEDNESDAY)),
        ChatterLine("fri_fake", "周五的课像假的", hours = 8..17, weekdays = setOf(DayOfWeek.FRIDAY)),
        ChatterLine("sat_qt", "周六还来钱图啊", hours = 9..18, weekdays = setOf(DayOfWeek.SATURDAY)),
        ChatterLine("sun_anx", "周日晚上开始焦虑", hours = 18..23, weekdays = setOf(DayOfWeek.SUNDAY)),
        ChatterLine("wkd_table", "周末还翻课表啊", hours = 8..18, weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
        // 本 App：偏冷门、意想不到的能力
        ChatterLine("app_alarm", "问我能帮设闹钟"),
        ChatterLine("app_cal", "考试能丢进日历"),
        ChatterLine("app_yp", "黄页能查校内电话"),
        ChatterLine("app_qr", "电脑登录可扫这儿"),
        ChatterLine("app_book", "教材能在这儿读"),
        ChatterLine("app_pin", "长按图标有捷径"),
        ChatterLine("app_share", "通知能直接分享"),
        ChatterLine("app_color", "主题色能跟壁纸"),
        ChatterLine("app_code", "场馆验证码我会认"),
        ChatterLine("app_pay", "付款码就在这儿", hours = 7..13),
        ChatterLine("app_pay2", "付款码就在这儿", hours = 17..19),
        ChatterLine("app_teacher", "按方向能搜老师"),
        ChatterLine("app_ask", "搜索栏直接问我"),
        ChatterLine("app_replay", "回放能下到本地"),
        ChatterLine("app_acct", "多账号互不串号"),
        ChatterLine("app_face", "刷脸流水在这能查"),
        ChatterLine("app_open", "全校开课都能搜"),
        ChatterLine("app_fit", "体测成绩藏这儿"),
        ChatterLine("app_adv", "导员电话在「我的」页"),
        ChatterLine("app_zy", "仲英资料能下载"),
        ChatterLine("app_tap", "点我不是摆设哦"),
        ChatterLine("app_seat", "图书馆座位能在这看"),
        ChatterLine("app_order", "场馆订单能取消"),
        ChatterLine("app_night", "网页能开夜间模式"),
        ChatterLine("app_judge_a", "评教入口在学业里", months = 6..7),
        ChatterLine("app_judge_b", "评教入口在学业里", months = 12..12),
        ChatterLine("app_judge_c", "评教入口在学业里", months = 1..1),
        ChatterLine("app_coupon_a", "加餐券别忘领", months = 1..2),
        ChatterLine("app_coupon_b", "加餐券别忘领", months = 4..5),
        ChatterLine("app_coupon_c", "加餐券别忘领", months = 9..10),
        ChatterLine("app_widget", "课表能放到桌面"),
        ChatterLine("app_widget_card", "余额小组件放桌面"),
        ChatterLine("app_widget_notice", "通知能放桌面组件"),
        ChatterLine("app_add_event", "要加日程跟我说"),
        ChatterLine("app_balance", "查余额直接问我"),
        ChatterLine("app_where", "问我下节课在哪"),
        ChatterLine("app_spend", "钱花哪了看消费分析"),
        ChatterLine("app_transcript", "成绩单能一键申请"),
        ChatterLine("app_leave", "请假在考勤页里"),
        ChatterLine("app_webvpn", "校外进内网用WebVPN"),
        ChatterLine("app_exampaper", "历年卷去学辅站找", months = 5..7),
        ChatterLine("app_exampaper_b", "历年卷去学辅站找", months = 11..12),
        ChatterLine("app_fresh", "刚解放的教室最空"),
        // ── 闲聊：不推荐功能、不提醒事务，就是搭句话 ──
        //
        // 补这一批的原因：原有 190 条生活句里，吃饭 / 自习 / 期末考研保研占了绝大多数，
        // 全是"事务性"的——它更像个待办提醒器，不像个会闲着没事贫两句的室友。
        // 下面这些刻意不带任何行动号召，也不指向 App 的任何功能。
        //
        // 另外这批**大量带 hours 限定**是有意的：无条件句会永远躺在候选池里，
        // 把有时段的生活句挤掉（这正是宣传句之前实际占比 30% 的原因）。
        ChatterLine("mood_hi", "欸，你来了"),
        ChatterLine("mood_here", "我一直在这儿"),
        ChatterLine("mood_nothing", "没事，就看看你"),
        ChatterLine("mood_poke", "别老戳我头"),
        ChatterLine("mood_again", "又是你啊"),
        ChatterLine("mood_quiet", "今天话有点少"),
        ChatterLine("mood_hmm", "在想事情？"),
        ChatterLine("mood_water2", "水杯是不是空了"),

        ChatterLine("chat_morning", "早，睡够了吗", hours = 6..9),
        ChatterLine("chat_slow_am", "今早不用太拼", hours = 6..9),
        ChatterLine("chat_facewash", "洗把脸清醒点", hours = 6..9),
        ChatterLine("chat_sun", "今天太阳挺好的", hours = 8..16),
        ChatterLine("chat_window", "往窗外看两秒", hours = 8..17),
        ChatterLine("chat_afternoon", "下午最难熬", hours = 14..16),
        ChatterLine("chat_doze", "困了就趴会儿", hours = 13..15),
        ChatterLine("chat_sleep", "早点睡，真的", hours = 22..23),
        ChatterLine("chat_still_up", "还醒着呢", hours = 0..3),
        ChatterLine("chat_quiet_night", "这会儿宿舍最静", hours = 0..3),
        ChatterLine("chat_dawn2", "天快亮了", hours = 4..6),
        ChatterLine("chat_earlybird", "起这么早啊", hours = 5..7),

        ChatterLine("life_call", "多久没给家里打电话了"),
        ChatterLine("life_friend", "找个人说说话"),

        ChatterLine("mind_small", "先做最小的那件"),
        ChatterLine("mind_tired", "累就是累，别嘴硬"),

        ChatterLine("wx_hot", "热得人不想动", months = 6..8),
        ChatterLine("wx_dry", "西安太干了", months = 10..12),
        ChatterLine("wx_dry2", "西安太干了", months = 1..3),
        ChatterLine("wx_nice", "这天气不出门可惜", months = 4..5),
        ChatterLine("wx_nice2", "这天气不出门可惜", months = 9..10),
        // 随时 / 地点
        ChatterLine("any_eat", "精勤求学，先吃饭"),
        ChatterLine("any_drink", "饮水思源，先喝水"),
        ChatterLine("any_port", "新港的风好硬"),
        ChatterLine("any_lang", "仲英连廊吹吹风"),
        ChatterLine("any_tower", "腾飞塔看见你了"),
        ChatterLine("any_siyuan", "思源碑前站一会"),
        ChatterLine("any_square", "去四大发明广场转转"),
        ChatterLine("any_mail", "记得取快递！！"),
        ChatterLine("any_sp", "沙坡到了就是家"),
        ChatterLine("any_jg48", "巨构里别迷路"),
        // ── 屁岱自己的小性格：它是个会眨眼的小机器人，有点贫，有点黏人 ──
        ChatterLine("me_poke", "被你戳醒了"),
        ChatterLine("me_hungry", "我也想去吃梧桐", hours = 11..13),
        ChatterLine("me_dinner", "我闻不到饭香，你替我吃", hours = 17..19),
        ChatterLine("me_nosleep", "我不用睡觉，但你要", hours = 0..3),
        ChatterLine("me_nosleep2", "我不用睡觉，但你要", hours = 22..23),
        ChatterLine("me_duty", "今天我值班"),
        ChatterLine("me_blink", "我在练习眨眼"),
        ChatterLine("me_daze", "被你发现我在发呆"),
        ChatterLine("me_think", "我正在假装思考"),
        ChatterLine("me_legs", "我没有腿，但想去散步"),
        ChatterLine("me_battery", "我的电量永远满格"),
        ChatterLine("me_memory", "我记性可好了"),
        ChatterLine("me_nopeek", "我可没偷看你课表"),
        ChatterLine("me_praise", "今天也想被你夸"),
        ChatterLine("me_either", "有事叫我，没事也行"),
        ChatterLine("me_obedient", "今天也是乖巧的一天"),
        ChatterLine("me_exam_q", "今天被问了好多次考试", months = 6..7),
        ChatterLine("me_exam_q2", "今天被问了好多次考试", months = 12..12),
        ChatterLine("me_exam_q3", "今天被问了好多次考试", months = 1..1),
        ChatterLine("me_rain", "下雨我就不出门了"),
        ChatterLine("me_shy", "别盯着我看，怪害羞的"),
        ChatterLine("me_tired", "我想放假"),
        // ── 逗一下：有点欠，但不刻薄 ──
        ChatterLine("tease_fish", "摸鱼被我抓到了", hours = 9..17),
        ChatterLine("tease_delay", "你是不是在拖延"),
        ChatterLine("tease_hw", "作业写完了吗，我不信", hours = 19..23),
        ChatterLine("tease_back", "又来找我玩啦"),
        ChatterLine("tease_study", "看我干嘛，去学习", hours = 8..22),
        ChatterLine("tease_again", "今天第几次戳我了"),
        ChatterLine("tease_bed", "躺床上说要学习的是你吧", hours = 21..23),
        ChatterLine("tease_ddl", "ddl 是第一生产力对吧"),
        // ── 抛个问题，勾人点开对话 ──
        ChatterLine("ask_room", "猜猜今天空教室多吗", hours = 8..20),
        ChatterLine("ask_week", "想知道这周还剩几节课吗"),
        ChatterLine("ask_hard", "问我点难的试试"),
        ChatterLine("ask_trivia", "考考我交大冷知识"),
        ChatterLine("ask_review", "要不要我帮你排复习", months = 5..7),
        ChatterLine("ask_review2", "要不要我帮你排复习", months = 11..12),
        ChatterLine("ask_where", "让我猜猜你下节课在哪"),
    )

    init {
        val dup = lines.groupingBy { it.id }.eachCount().filter { it.value > 1 }
        require(dup.isEmpty()) { "闲话 id 重复: $dup" }
        val long = lines.filter { it.text.length > MAX_CHARS }
        require(long.isEmpty()) { "闲话超 $MAX_CHARS 字: ${long.map { it.id to it.text.length }}" }
        val emptyMonth = lines.filter { it.months != null && it.months.isEmpty() }
        require(emptyMonth.isEmpty()) { "月份区间为空: ${emptyMonth.map { it.id }}" }
    }

    fun eligible(now: LocalDateTime): List<ChatterLine> {
        val hour = now.hour
        val month = now.monthValue
        val dow = now.dayOfWeek
        return lines.filter { line ->
            (line.hours == null || hour in line.hours) &&
                (line.months == null || month in line.months) &&
                (line.weekdays == null || dow in line.weekdays)
        }
    }

    /**
     * 情景闲话：按真实的课表、节假日说话。权重调高，有真事可说时优先于泛泛的句子。
     * 仍然不超过字数；读不到数据的那几类自然落选。
     */
    fun situational(
        now: LocalDateTime,
        nextCourseName: String?,
        minutesToClass: Long?,
        facts: ChatterFacts? = null,
    ): List<ChatterLine> = buildList {
        val w = 3.0
        if (minutesToClass != null && minutesToClass in 31..180) {
            add(ChatterLine("sit_soon", "下节课还早，先喘口气", weight = w))
        }
        if (minutesToClass != null && minutesToClass > 180) {
            add(ChatterLine("sit_later", "今天后面还有课"))
        }
        val weekday = now.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val today = facts?.today.orEmpty()
        if (facts != null && today.isEmpty() && facts.todayHoliday == null && weekday && now.hour in 8..21) {
            add(ChatterLine("sit_free", "今天没课？行啊", weight = w))
        }
        if (facts == null) return@buildList

        // 课表：节次换成钟点粗算，冬夏作息差半小时，这里只用来判断「还剩几节」够了
        val sectionNow = sectionAt(now)
        val remaining = today.filter { it.endSection >= sectionNow }
        if (today.any { "体育" in it.name } && remaining.any { "体育" in it.name } && now.hour in 6..17) {
            add(ChatterLine("sit_pe", "体育课别忘了带鞋", weight = w))
        }
        val afternoon = (5..8).all { s -> today.any { s in it.startSection..it.endSection } }
        if (afternoon && now.hour in 7..15) {
            add(ChatterLine("sit_pm4", "下午四节连上，撑住", weight = w))
        }
        val total = today.sumOf { it.endSection - it.startSection + 1 }
        if (total >= 8 && !afternoon && now.hour in 7..15) {
            add(ChatterLine("sit_full", "今天课排得好满", weight = w))
        }
        if (remaining.size == 1 && today.size >= 2 && now.hour in 12..20) {
            add(ChatterLine("sit_last", "就剩最后一节了", weight = w))
        }
        if (today.isNotEmpty() && remaining.isEmpty() && now.hour in 12..21) {
            add(ChatterLine("sit_done", "今天的课上完了", weight = w))
        }
        if (facts.tomorrowFirstSection == 1 && now.hour in 20..23) {
            add(ChatterLine("sit_tmr8", "明早第一节就有课", weight = w))
        }
        // 节假日
        facts.todayHoliday?.let { add(ChatterLine("sit_holiday", "${it}快乐，歇着吧".take(MAX_CHARS), weight = w)) }
        facts.nextHoliday?.let { (name, days) ->
            if (days == 1 && now.hour >= 12) add(ChatterLine("sit_hol_tmr", "明天放假，今晚随便", weight = w))
            if (days in 2..7) add(ChatterLine("sit_hol_soon", "离${name}还有${days}天".take(MAX_CHARS), weight = w))
        }
    }

    /** 此刻大约在第几节（下课间隙算下一节）。只用来判断「还剩几节」，精度够用。 */
    private fun sectionAt(now: LocalDateTime): Int {
        val m = now.hour * 60 + now.minute
        val starts = listOf(480, 540, 610, 670, 840, 900, 970, 1030, 1150, 1210, 1270)
        return (starts.indexOfFirst { m < it + 50 } + 1).takeIf { it > 0 } ?: 12
    }

    /** 「本 App 会什么」这类自荐句的前缀。它们是宣传，不是闲话，得单独限额。 */
    private const val PROMO_PREFIX = "app_"

    /**
     * 宣传句在总量里只占 14%，听起来很克制——但它们几乎都**不带时间条件**，
     * 于是永远躺在候选池里；而生活句被小时/月份切掉大半。实测任一时刻的可选池里
     * 宣传句占到 28%~32%，也就是屁岱每开口三次就有一次在讲功能。这个比例太吵了。
     *
     * 所以抽签前先按前缀分成两组，用固定概率决定这次从哪组抽，让实际配比回到设计意图。
     */
    private const val PROMO_CHANCE = 0.15

    fun pick(
        now: LocalDateTime,
        recentIds: List<String>,
        nextCourseName: String?,
        minutesToClass: Long?,
        skinLines: List<ChatterLine> = emptyList(),
        skinMix: Double = 0.0,
        facts: ChatterFacts? = null,
    ): ChatterLine? {
        val recent = recentIds.toSet()
        val builtInPool = eligible(now) + situational(now, nextCourseName, minutesToClass, facts)
        val eligibleSkin = skinLines.filter { line ->
            (line.hours == null || now.hour in line.hours) &&
                (line.months == null || now.monthValue in line.months) &&
                (line.weekdays == null || now.dayOfWeek in line.weekdays)
        }
        val builtIn = builtInPool.filter { it.id !in recent }.ifEmpty { builtInPool }
        val skin = eligibleSkin.filter { it.id !in recent }.ifEmpty { eligibleSkin }
        if (builtIn.isEmpty() && skin.isEmpty()) return null

        val chooseSkin = when {
            builtIn.isEmpty() -> true
            skin.isEmpty() -> false
            else -> Math.random() < skinMix.coerceIn(0.0, 1.0)
        }
        if (chooseSkin) return weightedPick(skin)

        val (promo, life) = builtIn.partition { it.id.startsWith(PROMO_PREFIX) }
        // 想抽的那组空了就退回另一组，别因为限额把话说没了。
        val group = when {
            life.isEmpty() -> promo
            promo.isEmpty() -> life
            Math.random() < PROMO_CHANCE -> promo
            else -> life
        }
        if (group.isEmpty()) return null

        // 原来是 slot = (hour*60+min)/3 取模的**确定性**索引：同一个三分钟窗口里
        // 反复触发只会沿着列表顺序往下走，相邻的句子会连着蹦出来，很容易被看出规律。
        // 换成真随机；防重复交给上面的 recent 过滤，那才是它该负责的事。
        return weightedPick(group)
    }

    private fun weightedPick(lines: List<ChatterLine>): ChatterLine? {
        if (lines.isEmpty()) return null
        val total = lines.sumOf { it.weight.coerceAtLeast(0.0) }
        if (total <= 0.0) return lines.random()
        var slot = Math.random() * total
        for (line in lines) {
            slot -= line.weight.coerceAtLeast(0.0)
            if (slot <= 0.0) return line
        }
        return lines.last()
    }
}
