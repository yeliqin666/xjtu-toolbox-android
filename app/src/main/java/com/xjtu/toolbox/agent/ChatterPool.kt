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
)

internal object ChatterPool {

    const val MAX_CHARS = 14

    val lines: List<ChatterLine> = listOf(
        // 深夜
        ChatterLine("late_awake", "这个点还没睡啊", hours = 0..4),
        ChatterLine("late_hate", "明天的你会恨现在", hours = 0..4),
        ChatterLine("late_light", "宿舍灯还亮着", hours = 0..4),
        ChatterLine("late_roll", "这个点还在卷啊", hours = 0..4),
        ChatterLine("late_lab", "实验还没做完吧", hours = 0..5),
        ChatterLine("late_dawn", "别刷到天亮了", hours = 1..5),
        ChatterLine("late_breakfast", "别把早饭睡过去", hours = 4..6),
        ChatterLine("late_hot", "热水还没停吧", hours = 0..5),
        // 早
        ChatterLine("am_ba", "早八是一种人格", hours = 6..8),
        ChatterLine("am_soy", "食堂豆浆见", hours = 6..8),
        ChatterLine("am_quilt", "别在被窝里看课表", hours = 6..8),
        ChatterLine("am_gate", "一号门风更大", hours = 7..9),
        ChatterLine("am_bao", "豆浆配包子走起", hours = 6..9),
        ChatterLine("am_pk", "彭康路梧桐在等", hours = 7..9),
        ChatterLine("am_lift", "后悔熬夜了吧！", hours = 7..10),
        ChatterLine("am_third", "第三节才是真困", hours = 9..11),
        ChatterLine("am_coffee", "咖啡比课表诚实", hours = 9..11),
        ChatterLine("am_real8", "第三节才是早八", hours = 9..11),
        ChatterLine("am_pick", "去康桥还是梧桐", hours = 7..10),
        // 午饭
        ChatterLine("noon_kq", "康桥队伍排到哪了", hours = 11..13),
        ChatterLine("noon_milk", "饭点别只喝奶茶", hours = 11..13),
        ChatterLine("noon_pk", "康三渔粉可以有", hours = 11..13),
        ChatterLine("noon_wt", "梧桐苑也在排队", hours = 11..13),
        ChatterLine("noon_full", "康桥一层人满了", hours = 11..13),
        ChatterLine("noon_lp", "凉皮夹馍先顶上", hours = 11..13),
        ChatterLine("noon_cj", "财经食堂大盘鸡", hours = 11..13),
        ChatterLine("noon_hm", "和鸣苑午饭见", hours = 11..13),
        ChatterLine("noon_hf", "惠风苑民族餐走起", hours = 11..13),
        ChatterLine("noon_lq", "朗清苑也行啊", hours = 11..13),
        ChatterLine("noon_nap", "午觉被课表打断", hours = 12..14),
        // 下午
        ChatterLine("pm_room", "空教室才是归宿", hours = 13..17),
        ChatterLine("pm_eyes", "眼睛歇一会儿", hours = 13..17),
        ChatterLine("pm_walk", "站起来走两步", hours = 14..18),
        ChatterLine("pm_qt", "钱图座位还有吗", hours = 13..18),
        ChatterLine("pm_exp", "实验课别摸鱼", hours = 13..18),
        // 晚饭
        ChatterLine("eve_win", "晚饭窗口别犹豫", hours = 17..19),
        ChatterLine("eve_card", "别把饭卡忘食堂", hours = 17..19),
        ChatterLine("eve_ny", "去温泉称称体重", hours = 17..19),
        ChatterLine("eve_wt2", "梧桐苑二楼见", hours = 17..19),
        ChatterLine("eve_night", "夜宵档口还开吗", hours = 17..20),
        ChatterLine("eve_cart", "移动餐车来了没", hours = 19..22),
        ChatterLine("eve_kq3", "梧桐三层自助呢", hours = 17..19),
        // 晚上
        ChatterLine("night_lib", "图书馆座位呢", hours = 19..21),
        ChatterLine("night_lab", "实验报告还活着吗", hours = 19..22),
        ChatterLine("night_water", "先别开电脑，喝口水", hours = 19..22),
        ChatterLine("night_ppt", "PPT还没做完吧", hours = 20..23),
        ChatterLine("night_close", "图书馆该散了", hours = 22..23),
        ChatterLine("night_ba", "明天的早八在盯你", hours = 22..23),
        ChatterLine("night_cy", "畅园夜风有点硬", hours = 19..23),
        ChatterLine("night_jg", "巨构里灯还亮", hours = 19..23),
        ChatterLine("night_bed", "别在床上改PPT", hours = 20..23),
        ChatterLine("night_dorm", "宿舍热水来了吗", hours = 21..23),
        ChatterLine("night_rpt", "报告还差一节", hours = 19..23),
        ChatterLine("night_qian", "钱图的灯还亮着", hours = 18..23),
        // 四季（跨年月份不能写 11..2，那是空区间）
        ChatterLine("spr_fluff", "梧桐絮又开始下", months = 3..5),
        ChatterLine("spr_rain", "小心梧桐河！", months = 3..5),
        ChatterLine("spr_pollen", "花粉战士集合", months = 4..5),
        ChatterLine("spr_spit", "梧桐开始吐絮了", months = 4..5),
        ChatterLine("sum_bath", "去洗个凉水澡！", months = 6..8),
        ChatterLine("sum_ac", "空调房里的夏天", months = 6..8),
        ChatterLine("sum_junxun", "看看小登军训，嘿嘿", months = 8..9),
        ChatterLine("sum_shade", "新港没有树荫啊", months = 6..8),
        ChatterLine("sum_cicada", "蝉比课表准时", months = 6..8),
        ChatterLine("aut_leaf", "期待金色梧桐节呀！", months = 10..11),
        ChatterLine("aut_hot", "开学热还没散", months = 9..10),
        ChatterLine("aut_gold", "彭康路在铺金", months = 10..11),
        ChatterLine("aut_pick", "去捡两片梧桐叶", months = 10..11),
        ChatterLine("aut_yt", "雁塔梧桐道也黄", months = 10..11),
        // 春见樱花节：近年三校区同步，大约三月底到四月初
        ChatterLine("sakura_fest", "春见樱花节来了", months = 3..4),
        ChatterLine("sakura_xq", "兴庆樱花开了没", months = 3..4),
        ChatterLine("sakura_port", "去港上看樱花路", months = 3..4),
        ChatterLine("sakura_yt", "雁塔也能赏樱", months = 3..4),
        ChatterLine("sakura_you", "樱你而美，走啊", months = 3..4),
        ChatterLine("sakura_shot", "别只拍照，看看花", months = 3..4),
        // 选课：学期初主峰，期中还有一轮改课
        ChatterLine("pick_fight_a", "选课像打仗", months = 2..3),
        ChatterLine("pick_fight_b", "选课像打仗", months = 8..9),
        ChatterLine("pick_down_a", "选课系统崩了没", months = 2..3),
        ChatterLine("pick_down_b", "选课系统崩了没", months = 8..9),
        ChatterLine("pick_left_a", "这课还有余量吗", months = 2..3),
        ChatterLine("pick_left_b", "这课还有余量吗", months = 8..9),
        ChatterLine("pick_pe_a", "体育课秒没了", months = 2..3),
        ChatterLine("pick_pe_b", "体育课秒没了", months = 8..9),
        ChatterLine("pick_slip_a", "志愿填报别手滑", months = 2..3),
        ChatterLine("pick_slip_b", "志愿填报别手滑", months = 8..9),
        ChatterLine("pick_add_a", "补退选开始了", months = 2..3),
        ChatterLine("pick_add_b", "补退选开始了", months = 8..9),
        ChatterLine("pick_mid_a", "期中改课别错过", months = 4..5),
        ChatterLine("pick_mid_b", "期中改课别错过", months = 10..11),
        // 保研：夏令营到九推
        ChatterLine("bao_camp", "夏令营材料交了没", months = 6..7),
        ChatterLine("bao_pre", "预推免开始了", months = 8..8),
        ChatterLine("bao_sys", "推免系统开了没", months = 9..9),
        ChatterLine("bao_mail", "套磁邮件发了没", months = 6..8),
        ChatterLine("bao_or", "保研还是考研啊", months = 6..9),
        ChatterLine("bao_nine", "九推别只刷群", months = 9..9),
        ChatterLine("bao_offer", "offer来了敢点吗", months = 7..9),
        // 考研：报名、初试、出分、复试调剂
        ChatterLine("kao_sign", "考研报名别忘了", months = 10..10),
        ChatterLine("kao_eng", "政治英语还活着吗", months = 9..12),
        ChatterLine("kao_paper", "真题别只收藏", months = 9..12),
        ChatterLine("kao_seat", "钱图考研位满了", months = 11..12),
        ChatterLine("kao_exam", "十二月初试加油", months = 12..12),
        ChatterLine("kao_score", "出分了没敢看", months = 2..2),
        ChatterLine("kao_re", "复试名单出来没", months = 3..4),
        ChatterLine("kao_tiao", "调剂系统别挂着", months = 3..4),
        ChatterLine("kao_night", "考研灯比人晚睡", hours = 21..23, months = 9..12),
        // 考试月：春夏小学期尾、秋冬学期尾
        ChatterLine("exam_qt_a", "考试月钱图爆了", months = 6..7),
        ChatterLine("exam_qt_b", "考试月钱图爆了", months = 12..12),
        ChatterLine("exam_qt_c", "考试月钱图爆了", months = 1..1),
        ChatterLine("exam_quiet_a", "期末周食堂好安静", months = 6..7),
        ChatterLine("exam_quiet_b", "期末周食堂好安静", months = 12..12),
        ChatterLine("exam_quiet_c", "期末周食堂好安静", months = 1..1),
        ChatterLine("exam_outline_a", "复习大纲找到没", months = 6..7),
        ChatterLine("exam_outline_b", "复习大纲找到没", months = 12..12),
        ChatterLine("exam_outline_c", "复习大纲找到没", months = 1..1),
        ChatterLine("exam_warn_a", "挂科预警响了没", months = 6..7),
        ChatterLine("exam_warn_b", "挂科预警响了没", months = 12..12),
        ChatterLine("exam_warn_c", "挂科预警响了没", months = 1..1),
        ChatterLine("exam_all_a", "通宵自习点名了", hours = 22..23, months = 6..7),
        ChatterLine("exam_all_b", "通宵自习点名了", hours = 22..23, months = 12..12),
        ChatterLine("exam_all_c", "通宵自习点名了", hours = 22..23, months = 1..1),
        ChatterLine("exam_paper_a", "卷子比人先到", months = 6..7),
        ChatterLine("exam_paper_b", "卷子比人先到", months = 12..12),
        ChatterLine("exam_paper_c", "卷子比人先到", months = 1..1),
        ChatterLine("exam_makeup_a", "补考报名看见没", months = 8..9),
        ChatterLine("exam_makeup_b", "补考报名看见没", months = 2..3),
        ChatterLine("exam_judge_a", "评教开了再出分", months = 6..7),
        ChatterLine("exam_judge_b", "评教开了再出分", months = 12..12),
        ChatterLine("exam_judge_c", "评教开了再出分", months = 1..1),
        // 小学期：盛夏还在上课，不是假期
        ChatterLine("short_not", "小学期不是假期", months = 7..7),
        ChatterLine("short_table", "小学期课表看了没", months = 6..7),
        ChatterLine("short_july", "七月还在上课啊", months = 7..7),
        ChatterLine("short_lab", "小学期实验更狠", months = 7..7),
        ChatterLine("short_exam", "小学期也有考试", months = 7..8),
        ChatterLine("short_fake", "短学期名不副实", months = 6..8),
        ChatterLine("short_ac", "小学期空调全开", months = 7..7),
        // 寒假
        ChatterLine("vac_count", "寒假倒计时开始", months = 1..1),
        ChatterLine("vac_train", "火车票抢到了没", months = 1..1),
        ChatterLine("vac_heat", "宿舍暖气还在供", months = 1..2),
        ChatterLine("vac_home", "回家先别开电脑", months = 1..2),
        ChatterLine("vac_hw", "假期作业埋了吗", months = 1..2),
        ChatterLine("vac_back", "过年回学校了没", months = 2..2),
        ChatterLine("vac_bus", "寒假班车还开吗", months = 1..2),
        ChatterLine("vac_lib", "钱图寒假开门吗", months = 1..2),
        ChatterLine("win_heat_a", "暖气比课表暖", months = 11..12),
        ChatterLine("win_heat_b", "暖气比课表暖", months = 1..2),
        ChatterLine("win_snow_a", "下雪了么？", months = 11..12),
        ChatterLine("win_snow_b", "下雪了么？", months = 1..2),
        ChatterLine("win_main_a", "主楼暖气开得太大", months = 12..12),
        ChatterLine("win_main_b", "主楼暖气开得太大", months = 1..2),
        ChatterLine("win_two_a", "主楼里外两重天", months = 12..12),
        ChatterLine("win_two_b", "主楼里外两重天", months = 1..2),
        ChatterLine("win_port_a", "新港比兴庆更冷", months = 11..12),
        ChatterLine("win_port_b", "新港比兴庆更冷", months = 1..2),
        ChatterLine("win_glove_a", "手套比课表重要", months = 12..12),
        ChatterLine("win_glove_b", "手套比课表重要", months = 1..2),
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
        ChatterLine("app_jxz", "交晓智也会办事"),
        ChatterLine("app_ask", "搜索栏直接问我"),
        ChatterLine("app_replay", "回放能下到本地"),
        ChatterLine("app_acct", "多账号互不串号"),
        ChatterLine("app_face", "刷脸流水也能查"),
        ChatterLine("app_open", "全校开课都能搜"),
        ChatterLine("app_fit", "体测成绩藏这儿"),
        ChatterLine("app_adv", "导员电话在我的"),
        ChatterLine("app_zy", "仲英资料能下载"),
        ChatterLine("app_tap", "点我不是摆设哦"),
        ChatterLine("app_seat", "图书馆座位也能看"),
        ChatterLine("app_order", "场馆订单能取消"),
        ChatterLine("app_night", "网页也能夜间读"),
        ChatterLine("app_judge_a", "评教入口在学业里", months = 6..7),
        ChatterLine("app_judge_b", "评教入口在学业里", months = 12..12),
        ChatterLine("app_judge_c", "评教入口在学业里", months = 1..1),
        ChatterLine("app_coupon_a", "加餐券别忘领", months = 1..2),
        ChatterLine("app_coupon_b", "加餐券别忘领", months = 4..5),
        ChatterLine("app_coupon_c", "加餐券别忘领", months = 9..10),
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
        ChatterLine("mood_bored", "闲着也是闲着"),
        ChatterLine("mood_poke", "别老戳我头"),
        ChatterLine("mood_again", "又是你啊"),
        ChatterLine("mood_quiet", "今天话有点少"),
        ChatterLine("mood_ok", "还行吧，今天"),
        ChatterLine("mood_hmm", "在想事情？"),
        ChatterLine("mood_slow", "不着急，慢慢来"),
        ChatterLine("mood_enough", "差不多就行了"),
        ChatterLine("mood_hard", "今天有点难吧"),
        ChatterLine("mood_fine", "没搞砸就算赢"),
        ChatterLine("mood_rest", "歇会儿不丢人"),
        ChatterLine("mood_stand", "腰还好吗"),
        ChatterLine("mood_blink", "眨眨眼，别干着"),
        ChatterLine("mood_shoulder", "肩膀松一下"),
        ChatterLine("mood_deep", "深呼吸一次"),
        ChatterLine("mood_water2", "水杯是不是空了"),
        ChatterLine("mood_phone", "手机有点烫吧"),

        ChatterLine("chat_morning", "早，睡够了吗", hours = 6..9),
        ChatterLine("chat_wake", "醒了就先别看手机", hours = 6..9),
        ChatterLine("chat_slow_am", "今早不用太拼", hours = 6..9),
        ChatterLine("chat_facewash", "洗把脸清醒点", hours = 6..9),
        ChatterLine("chat_sun", "今天太阳挺好的", hours = 8..16),
        ChatterLine("chat_window", "往窗外看两秒", hours = 8..17),
        ChatterLine("chat_afternoon", "下午最难熬", hours = 14..16),
        ChatterLine("chat_doze", "困了就趴会儿", hours = 13..15),
        ChatterLine("chat_halfday", "半天已经过去了", hours = 12..14),
        ChatterLine("chat_sunset", "天要黑了", hours = 17..19),
        ChatterLine("chat_lamp", "灯开亮点，护眼", hours = 18..23),
        ChatterLine("chat_evening", "晚上安静一些", hours = 19..22),
        ChatterLine("chat_today_end", "今天到这儿就行", hours = 21..23),
        ChatterLine("chat_sleep", "早点睡，真的", hours = 22..23),
        ChatterLine("chat_tomorrow", "明天再说吧", hours = 22..23),
        ChatterLine("chat_still_up", "还醒着呢", hours = 0..3),
        ChatterLine("chat_quiet_night", "这会儿宿舍最静", hours = 0..3),
        ChatterLine("chat_nobody", "楼里就剩你了吧", hours = 0..4),
        ChatterLine("chat_dawn2", "天快亮了", hours = 4..6),
        ChatterLine("chat_earlybird", "起这么早啊", hours = 5..7),

        ChatterLine("life_walk", "出去走一圈吧"),
        ChatterLine("life_music", "听首歌再说"),
        ChatterLine("life_call", "多久没给家里打电话了"),
        ChatterLine("life_friend", "找个人说说话"),
        ChatterLine("life_alone", "一个人也挺好"),
        ChatterLine("life_socks", "袜子该洗了"),
        ChatterLine("life_desk", "桌子有点乱了"),
        ChatterLine("life_bottle", "空瓶子攒一堆了"),
        ChatterLine("life_sheet", "床单该换了吧"),
        ChatterLine("life_hair", "头发该剪了"),
        ChatterLine("life_shoes", "鞋带开了没"),
        ChatterLine("life_umbrella", "伞放包里没"),
        ChatterLine("life_charge", "充电宝充了吗"),
        ChatterLine("life_key", "钥匙带了吗"),
        ChatterLine("life_wallet", "手机电量还行吗"),

        ChatterLine("mind_dontrush", "别跟别人比进度"),
        ChatterLine("mind_small", "先做最小的那件"),
        ChatterLine("mind_start", "开个头就不难了"),
        ChatterLine("mind_notall", "不用全做完"),
        ChatterLine("mind_bad", "状态差是正常的"),
        ChatterLine("mind_pause", "卡住就先放着"),
        ChatterLine("mind_kind", "对自己别太狠"),
        ChatterLine("mind_far", "路还长着呢"),
        ChatterLine("mind_worth", "今天也算数"),
        ChatterLine("mind_tired", "累就是累，别嘴硬"),

        ChatterLine("wx_cold", "外面比看着冷", months = 11..12),
        ChatterLine("wx_cold2", "外面比看着冷", months = 1..2),
        ChatterLine("wx_wind", "今天风大，抓紧帽子", months = 11..12),
        ChatterLine("wx_hot", "热得人不想动", months = 6..8),
        ChatterLine("wx_dry", "西安太干了", months = 10..12),
        ChatterLine("wx_dry2", "西安太干了", months = 1..3),
        ChatterLine("wx_haze", "今天空气一般", months = 11..12),
        ChatterLine("wx_haze2", "今天空气一般", months = 1..2),
        ChatterLine("wx_nice", "这天气不出门可惜", months = 4..5),
        ChatterLine("wx_nice2", "这天气不出门可惜", months = 9..10),
        // 随时 / 地点
        ChatterLine("any_eat", "精勤求学，先吃饭"),
        ChatterLine("any_drink", "饮水思源，先喝水"),
        ChatterLine("any_port", "新港的风好硬..."),
        ChatterLine("any_cat", "兴庆的猫还在吗"),
        ChatterLine("any_lang", "仲英连廊吹吹风"),
        ChatterLine("any_tower", "腾飞塔看见你了"),
        ChatterLine("any_ddl", "作业在截止线里游"),
        ChatterLine("any_self", "这天气适合自习"),
        ChatterLine("any_xiqian", "西迁路上吹吹风"),
        ChatterLine("any_siyuan", "思源碑前站一会"),
        ChatterLine("any_square", "四大发明广场转"),
        ChatterLine("any_bus", "班车座位还在吗"),
        ChatterLine("any_stay", "去港还是留兴庆"),
        ChatterLine("any_wall", "没有围墙的大学"),
        ChatterLine("any_hy", "涵英楼好认路吗"),
        ChatterLine("any_mail", "记得取快递！！"),
        ChatterLine("any_judge", "评教窗口别忘了"),
        ChatterLine("any_pick", "选课像打仗"),
        ChatterLine("any_app", "交大美餐打开没"),
        ChatterLine("any_xqg", "兴庆宫在围墙外"),
        ChatterLine("any_sp", "沙坡到了就是家"),
        ChatterLine("any_qj", "曲江也算半个家"),
        ChatterLine("any_yt", "雁塔的医学生在吗"),
        ChatterLine("any_zy", "仲英的猫也在吗"),
        ChatterLine("any_nanyang", "南洋公学还在碑上"),
        ChatterLine("any_move2", "第二次西迁进行中"),
        ChatterLine("any_jg48", "巨构里别迷路"),
        ChatterLine("any_sail", "风帆广场吹吹风"),
        ChatterLine("any_sy", "饮水记得思源"),
        ChatterLine("any_kqwt", "康桥梧桐今晚自习"),
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

    /** 上课还早 / 今天没课：补两句情景闲话，仍然不超过字数。 */
    fun situational(
        now: LocalDateTime,
        nextCourseName: String?,
        minutesToClass: Long?,
    ): List<ChatterLine> = buildList {
        if (minutesToClass != null && minutesToClass in 31..180) {
            add(ChatterLine("sit_soon", "下节课还早，先喘口气"))
        }
        if (minutesToClass != null && minutesToClass > 180) {
            add(ChatterLine("sit_later", "今天后面还有课"))
        }
        val weekday = now.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        if (nextCourseName == null && weekday && now.hour in 8..21) {
            add(ChatterLine("sit_free", "今天没课？行啊"))
        }
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
    ): ChatterLine? {
        val pool = eligible(now) + situational(now, nextCourseName, minutesToClass)
        if (pool.isEmpty()) return null
        val recent = recentIds.toSet()
        val fresh = pool.filter { it.id !in recent }.ifEmpty { pool }

        val (promo, life) = fresh.partition { it.id.startsWith(PROMO_PREFIX) }
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
        return group.random()
    }
}
