package com.xjtu.toolbox.util

import com.xjtu.toolbox.BuildConfig

/**
 * 全应用更新日志的唯一数据源。
 *
 * 三处展示均派生自此处：
 *   1) 启动时本地 What's New 弹窗（堆叠展示自上次已见之后的全部新版本）
 *   2) 设置页 → 关于 → 更新日志（倒序列出全部历史）
 *   3) CI 工作流解析此文件，将当前版本条目渲染为 markdown 注入 Gitee Release body
 *      —— 解析依赖固定文本格式，请勿改动条目缩进或字段写法。
 *
 * ⚠️ 发版前必须为新 versionName 在最前面追加条目；编译期会校验。
 */
data class VersionChangelog(
    val items: List<Pair<String, String>>,
    val issues: List<String> = emptyList()
)

object AppChangelog {

    /**
     * 倒序排列：第一个元素是最新版本。
     * 新增版本只在最前面追加即可。
     */
    val ENTRIES: List<Pair<String, VersionChangelog>> = listOf(
        "4.71" to VersionChangelog(
            items = listOf(
                "🔔" to "教务通知可在设置里选来源，系统通知和桌面小组件共用；后台按省电策略检查",
                "🧹" to "去掉空教室和问屁岱桌面小组件，通知小组件跟着所选来源更新",
                "📖" to "教材中心点进书改为底部弹出，顶栏和其他页一样用大标题",
                "📚" to "教材全文库顶栏改为大标题",
                "🪟" to "短确认和说明改成 Dialog，列表和长内容仍用 BottomSheet",
                "🎨" to "跟随系统取色不再把顶栏白底和页面灰底糊成同色",
                "🏟️" to "场馆验证码默认自动识别，失败仍可手滑",
                "⬆️" to "启动检查更新失败后下次打开还会再试",
                "📝" to "成绩没有分项细则时不再误报掉登录",
                "⬇️" to "思源学堂已结束的活动会说明无法下载，不再空转重登",
                "🔍" to "主页搜索和宫格共用一份服务目录，研究生考勤这类入口不会再漏搜",
                "🎓" to "本科生只看到本科考勤，研究生只看到研考勤",
                "🔄" to "空教室直查教务时显示更新进度和转动圆圈",
                "🎨" to "设置页顶栏和下面的灰底对齐，不再切开",
                "👤" to "我的页头像旁不再重复班级和专业；未登录标题改回岱宗盒子"
            )
        ),
        "4.7" to VersionChangelog(
            items = listOf(
                "📖" to "新增教材全文库，可按分类或书名找教材并在线阅读",
                "🔗" to "教材中心的「本地全文」可直接打开阅读器",
                "🔖" to "阅读器记住上次读到第几页，书架列出最近在读",
                "🤏" to "阅读器支持捏合缩放、双击适配、左右或上下翻页",
                "🗂️" to "全校课程查询的课程详情重新排版",
                "↩️" to "教材中心返回不再丢搜索结果和滚动位置",
                "🎬" to "课堂回放详情与下载管理重做",
                "📢" to "通知新增仲英书院、实践教学中心、电信学部等来源",
                "🏃" to "体测不再默认选中还没开测的学年",
                "📌" to "桌面快捷方式图标补上底色，浅色壁纸下不再糊成一片",
                "🧹" to "仲英学辅页去掉多余的下载入口"
            )
        ),
        "4.61" to VersionChangelog(
            items = listOf(
                "🎨" to "设置可开跟随系统取色，主题色跟着壁纸走"
            )
        ),
        "4.6" to VersionChangelog(
            items = listOf(
                "👨‍🏫" to "新增教师主页，可查全校老师",
                "🔍" to "首页支持搜索",
                "🧩" to "新增桌面小组件",
                "📌" to "长按图标直达常用功能",
                "📤" to "通知和屁岱回复可分享",
                "📚" to "思源学堂可看作业与下载回放",
                "🎬" to "修好课堂回放放不出来",
                "↩️" to "返回上一层不再重新加载",
                "🏃" to "体测默认选中本学年",
                "🔒" to "切换账号数据不再串号"
            )
        ),
        "4.5.5" to VersionChangelog(
            items = listOf(
                "🧮" to "成绩页和屁岱算出的 GPA 现在一致",
                "🛡️" to "屁岱读网页更稳，不安全的链接会被拦住",
                "🔄" to "屁岱默认换成更新的 DeepSeek 模型"
            )
        ),
        "4.5.4" to VersionChangelog(
            items = listOf(
                "🤖" to "屁岱设置页输入不再卡顿",
                "💬" to "交晓智偶发丢消息已修好",
                "🎨" to "课程、通知、空教室、考勤的提示样式更统一"
            )
        ),
        "4.5.3" to VersionChangelog(
            items = listOf(
                "🧾" to "场馆可以查看和取消「我的订单」",
                "💳" to "预约成功后能继续去支付",
                "✅" to "提交预约前会先确认，避免选错时段"
            )
        ),
        "4.5.2" to VersionChangelog(
            items = listOf(
                "🏟️" to "场馆验证码可选用自动识别，失败仍可手滑",
                "📅" to "修复第一次打开课表被误报成网络错误",
                "📚" to "教材还能看下学期的"
            )
        ),
        "4.5.1" to VersionChangelog(
            items = listOf(
                "🧠" to "屁岱长对话不再动不动被截断"
            )
        ),
        "4.5" to VersionChangelog(
            items = listOf(
                "🎓" to "「我的」能看学籍档案，以及辅导员、班主任联系方式",
                "📊" to "首页能看到下节课、余额、出勤、待评教、体测和作业",
                "🔔" to "屁岱会提醒余额不足、新成绩、快上课，可在设置里关",
                "📥" to "仲英学辅资料站支持下载，下载管理可多选删除",
                "🪟" to "修复添加账号、编辑日程等弹窗点了没反应",
                "🏟️" to "场馆预订改回手动滑动验证，减少预约失败",
                "📱" to "移动交大校外也能进，网页里可以用相机",
                "🔍" to "修好屁岱搜不到结果、通知查不了、短学期缺课的问题",
                "🎫" to "加餐券滑到底会自动加载更多"
            )
        ),
        "4.1.4" to VersionChangelog(
            items = listOf(
                "🏫" to "空教室选楼不再跳动，创新港楼名已更正",
                "👤" to "多账号和登录状态会在首页用角标提示"
            )
        ),
        "4.1.3" to VersionChangelog(
            items = listOf(
                "🏠" to "首页可在设置里换成图标宫格主题",
                "📌" to "长按首页入口可固定到收藏，再长按取消",
                "⚙️" to "卡片主题可关掉「常用功能」推荐"
            )
        ),
        "4.1.2" to VersionChangelog(
            items = listOf(
                "👤" to "支持添加、切换、改密、删除多个账号",
                "🔒" to "各账号的课表、成绩、对话、校园卡互不串号"
            )
        ),
        "4.1.1" to VersionChangelog(
            items = listOf(
                "🎫" to "加餐券支持一键领取",
                "💳" to "付款码可以勾选加餐券抵扣",
                "🤖" to "节假日屁岱会提醒领加餐券"
            )
        ),
        "4.1" to VersionChangelog(
            items = listOf(
                "🤖" to "屁岱可切换亲切 / 专业两种回复风格",
                "⏰" to "屁岱能帮你设系统闹钟和日历提醒",
                "🔎" to "联网搜索会带上原文链接，方便接着看",
                "🏫" to "空教室筛选更好用，屁岱也会按位置推荐自习点"
            )
        ),
        "4.0" to VersionChangelog(
            items = listOf(
                "🧠" to "屁岱能直接查课表、成绩、考试、空教室、校园卡、通知等",
                "🤝" to "接入学校交晓智，可以单独聊，也可以给屁岱当校内知识来源",
                "📅" to "日程能切学期；自己加的日程不会被刷新冲掉",
                "📱" to "移动交大可在应用内打开，跑操等页面能用定位",
                "⬆️" to "检查更新默认走 Gitee，设置里可换成 GitHub"
            )
        ),
        "4.0-beta2" to VersionChangelog(
            items = listOf(
                "⚡" to "屁岱支持边生成边显示，也可以随时停",
                "📅" to "可以直接问假期、考试周、老师和上课地点",
                "☎️" to "新增校园黄页，能搜电话、一键拨号",
                "💠" to "交晓智支持多会话和换模型",
                "🎛️" to "可以关掉联网、校园卡、成绩等能力",
                "🪧" to "课表、成绩、教室卡片会跟着会话保存"
            )
        ),
        "4.0-beta" to VersionChangelog(
            items = listOf(
                "🔎" to "屁岱可以联网搜索、读网页",
                "📣" to "能查通知、校园卡一周流水、图书馆座位和预约",
                "📚" to "修好图书馆换座、取消预约不生效"
            )
        ),
        "3.8.5" to VersionChangelog(
            items = listOf(
                "📝" to "屁岱回复支持标题、加粗、列表等格式",
                "⌨️" to "聊天键盘不再把输入框顶飞",
                "📊" to "成绩页补回「成绩报表」入口"
            )
        ),
        "3.8.4" to VersionChangelog(
            items = listOf(
                "💬" to "屁岱支持多段对话，可新建、切换、改名、删除",
                "🐛" to "修好发消息必报错",
                "🗂️" to "没网时成绩、考勤、校园卡会显示缓存，并标出时间"
            )
        ),
        "3.8.3" to VersionChangelog(
            items = listOf(
                "🤖" to "校园助手「屁岱」上线，对话里就能查课表、成绩、空教室、考勤和校园卡",
                "🪧" to "回复会带上课表、成绩、教室卡片",
                "🔑" to "配置里可以一键拉取模型列表"
            )
        ),
        "3.8.2" to VersionChangelog(
            items = listOf(
                "📚" to "图书馆座位和换座更可靠",
                "📅" to "日程会按日期自动用夏令或冬令作息",
                "⚡" to "校园卡和考勤先出缓存，再在后台刷新",
                "📥" to "下载记录能认出已经下过的思源课件"
            )
        ),
        "3.8.1" to VersionChangelog(
            items = listOf(
                "🏠" to "首页改成仪表盘：一张卡看下节课和余额",
                "✅" to "考勤更稳，登录过期会自动恢复",
                "📚" to "图书馆约座失败后会自动重新登录",
                "🏫" to "空教室可以用节次滑条筛选"
            )
        ),
        "3.8.0" to VersionChangelog(
            items = listOf(
                "✨" to "首页、思源学堂、校园卡和设置页焕新",
                "🏫" to "空教室能搜教学楼",
                "📥" to "思源课件改在应用内下载",
                "🎨" to "换了新图标，修好评教按钮和搜索栏"
            )
        ),
        "3.7.0" to VersionChangelog(
            items = listOf(
                "✨" to "首页加入兴庆主楼视觉，入口更好找",
                "🏫" to "空教室筛选和结果更直观",
                "💺" to "图书馆约座、余量和地图更清楚",
                "⬆️" to "设置里可以直接下载安装新版本"
            )
        ),
        "3.6.1" to VersionChangelog(
            items = listOf(
                "🌐" to "教务、思源、回放校外也能直连，不必先登 WebVPN",
                "🏫" to "空教室恢复 CDN / 直查，结果可以分享",
                "🎓" to "设置里可选本科生 / 研究生账号类型"
            )
        ),
        "3.6.0" to VersionChangelog(
            items = listOf(
                "🏫" to "空教室缓存不会再把空结果记成「今天没教室」",
                "🔄" to "空教室可以手动刷新",
                "📱" to "重新支持 Android 12 和鸿蒙兼容环境",
                "🚧" to "本科评教维护期间暂时关闭入口"
            )
        ),
        "3.5.1" to VersionChangelog(
            items = listOf(
                "💯" to "修好校园网下成绩查不出来",
                "🔐" to "两步验证改在应用内完成，不用跳浏览器",
                "📚" to "教材页第一次打开不再卡在「尚未登录」",
                "📱" to "默认进「日程」，底栏样式可在设置里改"
            )
        ),
        "3.5.0" to VersionChangelog(
            items = listOf(
                "🔐" to "自动登录遇到验证码时会弹窗，填完就能继续",
                "🌐" to "校外使用教务、思源、场馆、教材会自动走 WebVPN",
                "💳" to "修好付款码消费被标成收入",
                "📚" to "教材中心只保留搜索和书目"
            )
        ),
        "3.4.1" to VersionChangelog(
            items = listOf(
                "🔑" to "修好考勤登不上",
                "⬆️" to "应用内更新更稳，不必再跳浏览器",
                "🗓️" to "课表底部多余空白已去掉"
            )
        ),
        "3.4.0" to VersionChangelog(
            items = listOf(
                "🗓️" to "课表空时段会自动压矮，看起来更紧凑",
                "🏠" to "首页服务按使用习惯置顶",
                "✏️" to "添加日程不再被键盘挡住",
                "🎫" to "加餐券改成下拉刷新",
                "⚙️" to "修好设置里有的弹窗不出现"
            )
        ),
        "3.3.0" to VersionChangelog(
            items = listOf(
                "🎫" to "可以查看加餐券余额、有效期和使用状态",
                "🏠" to "首页加了加餐券入口",
                "🗓️" to "课表缓存和节假日显示更稳"
            )
        ),
        "3.2.0" to VersionChangelog(
            items = listOf(
                "🗓️" to "课表能显示节假日，导出时也可以排除",
                "🏫" to "空教室会记住上次选的校区和楼"
            )
        ),
        "3.1.0" to VersionChangelog(
            items = listOf(
                "💳" to "校园卡余额和流水恢复正常",
                "📚" to "新增电子教材中心",
                "🎓" to "新增 NeoSchool（拔尖计划）课件下载"
            )
        ),
        "3.0.2" to VersionChangelog(
            items = listOf(
                "🗓️" to "添加了日程"
            )
        ),
        "3.0.1" to VersionChangelog(
            items = listOf(
                "🎬" to "课程回放可以下载"
            )
        ),
        "3.0" to VersionChangelog(
            items = listOf(
                "🧭" to "教务 Tab 改成日程，首页和小组件都进「我的日程」",
                "💳" to "校园卡小组件更紧凑，金额不容易溢出去",
                "✅" to "从小组件进日程会自动刷新，不再一直停在旧缓存"
            ),
            issues = listOf(
                "升级后如果小组件显示异常，删掉重新添加即可"
            )
        ),
        "2.8.1" to VersionChangelog(
            items = listOf(
                "🏟️" to "场馆可以收藏，双击卡片就能加或取消",
                "📌" to "收藏的场馆会排在前面"
            )
        ),
        "2.8.0" to VersionChangelog(
            items = listOf(
                "💳" to "新增校园卡桌面小组件：余额、今日消费和三餐",
                "🔄" to "可以在应用里直接下载安装新版本",
                "🗓️" to "新增校历",
                "🐛" to "修好小组件加不上、会崩溃"
            )
        ),
        "2.7.1" to VersionChangelog(
            items = listOf(
                "🧩" to "新增日程桌面小组件（2×2 / 4×2）",
                "🐛" to "修好日程小组件布局和数据"
            ),
            issues = listOf(
                "入馆后可能仍显示「取消预约」"
            )
        ),
        "2.7.0" to VersionChangelog(
            items = listOf(
                "🔍" to "可以按课程名、教师、院系查全校课程",
                "🏠" to "首页、教务、工具重新分区",
                "🎬" to "修好思源学堂视频横屏闪退",
                "🐛" to "修好全校课程偶发闪退"
            )
        ),
        "2.6.0" to VersionChangelog(
            items = listOf(
                "📖" to "新增思源学堂：课程、作业、课件、回放",
                "📝" to "作业能看提交记录、分数和评语",
                "🎬" to "课堂回放支持多机位下载"
            )
        ),
        "2.5.1" to VersionChangelog(
            items = listOf(
                "🔙" to "修好按返回直接回桌面"
            )
        ),
        "2.5.0" to VersionChangelog(
            items = listOf(
                "🎓" to "新增课程回放",
                "🏟️" to "新增体育场馆预订",
                "🚫" to "去掉定时抢座，降低风险",
                "🔄" to "修好视频播放"
            )
        ),
        "2.3.2" to VersionChangelog(
            items = listOf(
                "🎉" to "正式版来了",
                "📸" to "可以下载成绩单，未评教的成绩也能查",
                "💳" to "图书馆能推荐座位、看地图选座",
                "🏠" to "界面改成 HyperOS 风格"
            )
        )
    )

    /** 当前版本对应的 changelog（找不到时返回 null）。 */
    val current: VersionChangelog?
        get() = ENTRIES.firstOrNull { it.first == BuildConfig.VERSION_NAME }?.second

    /**
     * 返回 `(lastSeen, current]` 区间内的所有 changelog 条目（最新在前）。
     * 若 lastSeen 为 null 或大于等于 current，则返回空列表。
     */
    fun since(lastSeen: String?): List<Pair<String, VersionChangelog>> {
        val current = BuildConfig.VERSION_NAME
        if (lastSeen == current) return emptyList()
        if (lastSeen == null) {
            return ENTRIES.firstOrNull { it.first == current }?.let(::listOf).orEmpty()
        }
        // ENTRIES 已按版本号倒序，截取从最前到 lastSeen（不含）的部分
        val result = mutableListOf<Pair<String, VersionChangelog>>()
        for ((ver, log) in ENTRIES) {
            // 跳过比 current 还新的版本（一般不会出现，防御性）
            if (compareVersions(ver, current) > 0) continue
            // 遇到 lastSeen 停止（不包含 lastSeen 自己）
            if (lastSeen != null && compareVersions(ver, lastSeen) <= 0) break
            result += ver to log
        }
        return result
    }

    /** 编译期校验：当前 versionName 必须存在条目。 */
    init {
        require(ENTRIES.any { it.first == BuildConfig.VERSION_NAME }) {
            "⚠️ 版本 ${BuildConfig.VERSION_NAME} 没有对应的更新日志！请在 AppChangelog.ENTRIES 顶部追加条目。"
        }
    }

    /** 简单的语义版本比较（按点分段，逐段数字比较）。 */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
