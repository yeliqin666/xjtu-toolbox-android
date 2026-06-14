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
        "3.8.3" to VersionChangelog(
            items = listOf(
                "🤖" to "校园助手「屁岱」上线：可直接在对话里查课表、成绩GPA、空闲教室、考勤、考试和校园卡",
                "🪧" to "助手回复附带课表、成绩、教室等卡片，关键信息一眼可见",
                "🔑" to "助手配置支持一键拉取模型列表，选择填入无需手敲"
            )
        ),
        "3.8.2" to VersionChangelog(
            items = listOf(
                "📚" to "修复图书馆座位查询和预约状态读取，区域余量与换座流程更可靠",
                "📅" to "日程会按课程日期自动使用夏季或冬季作息时间",
                "⚡" to "校园卡和考勤支持快速显示缓存，并在后台更新最新记录",
                "📥" to "修复下载记录遗漏思源课件的问题，已有文件也能重新识别",
                "✨" to "优化首页图标、场景文案、校园网设置弹窗和更新日志显示"
            )
        ),
        "3.8.1" to VersionChangelog(
            items = listOf(
                "🏠" to "首页重构为校园仪表盘：一张卡看下一节课和余额，常用功能、场景入口、更多服务三层分明",
                "🛡️" to "登录全面限速防风控：凭据提交全局排队限频，失败自动退避，保护账号安全",
                "✅" to "考勤查询更稳定：瞬时故障自动重试，登录过期静默恢复",
                "📚" to "图书馆修复区域余量显示，预约、换座和签到失败后会自动重新登录",
                "🎬" to "课程回放界面焕新，与思源学堂风格统一",
                "⬆️" to "升级 MIUIX 0.9.2，空闲教室用上节次区间滑条"
            )
        ),
        "3.8.0" to VersionChangelog(
            items = listOf(
                "✨" to "首页、思源学堂、校园卡和设置页统一焕新，界面更清爽、更有校园特色",
                "🔄" to "图书馆、通知和校园卡改进下拉刷新，减少重复按钮和界面干扰",
                "🏫" to "空闲教室支持搜索教学楼，直查进度和筛选信息更清楚",
                "📥" to "思源课件改为应用内下载，并与课堂回放统一管理",
                "🧭" to "未登录或功能暂不可用时不再自动跳转页面",
                "🎨" to "更换全新应用图标，并修复评教按钮、搜索栏等显示问题"
            )
        ),
        "3.7.0" to VersionChangelog(
            items = listOf(
                "✨" to "首页焕新：加入兴庆校区主楼视觉，快捷入口和服务区更有层次、更好看",
                "🏫" to "空闲教室重新设计筛选和结果展示，找教室更直观",
                "📅" to "考勤页面优化学期、周次和统计布局，操作更顺手",
                "💺" to "图书馆约座页面焕新，推荐座位、区域余量和地图入口更清晰",
                "⬆️" to "修复更新日志异常，并支持在设置中直接下载和安装新版本",
                "🛡️" to "优化登录和请求节奏，减少频繁请求带来的账号风险"
            )
        ),
        "3.6.1" to VersionChangelog(
            items = listOf(
                "🌐" to "【网络】教务、移动教务、思源学堂与课程回放改为公网直连服务，不再强制触发校园网探测或 WebVPN 登录",
                "🏫" to "【空闲教室】恢复 CDN / 直查双模式，支持落盘缓存、手动刷新绕过缓存与首次 CDN 查询说明",
                "🎓" to "【账号类型】设置页新增本科生 / 研究生账号类型选择，研究生默认使用 CDN 查询空闲教室",
                "🔐" to "【认证】优化 WebVPN 会话有效性校验与多身份账号选择流程，降低误判登录态的概率",
                "📤" to "【空闲教室】新增当前筛选结果文本分享"
            )
        ),
        "3.6.0" to VersionChangelog(
            items = listOf(
                "🏫" to "【空闲教室】修复跨天/空结果缓存误判导致不再查询的问题，仅当天有效数据才会命中缓存",
                "🔄" to "【空闲教室】新增手动刷新，重试会强制绕过缓存重新直查教务系统",
                "🛡️" to "【空闲教室】优化并发查询保护，旧查询不会再覆盖新结果或污染缓存",
                "📱" to "【兼容性】最低系统版本回到 Android 12 / API 31，恢复对 HarmonyOS 兼容环境的支持",
                "✨" to "【UI】移除需要 API 32 的模糊玻璃依赖，浮动导航栏改用兼容背景样式",
                "🚧" to "【本科评教】教务后端维护期间暂时关闭入口，避免进入后反复报错"
            )
        ),
        "3.5.1" to VersionChangelog(
            items = listOf(
                "💯" to "【成绩查询】修复校园网直连模式下 401 Authentication error（http→https 重定向丢 token），现在走 https 单跳一次拿回",
                "🔐" to "【认证】新版 CAS Safety Verify 二次安全验证全面接入：JWXT/JWAPP 等敏感系统访问时弹 MFA dialog，App 内完成短信验证不再跳浏览器",
                "🚀" to "【启动】仅保留 JWXT 探针自动登录（课表核心），其余子系统按需懒加载，冷启动不再批量触发 MFA",
                "🏫" to "【空闲教室】按教学楼粒度缓存查询结果，重复勾选不重查；新查询自动取消旧查询，进度条不再交替闪烁",
                "📚" to "【教材】修复首次切换到「教材」tab 卡在「尚未登录」需要重启 App 才能加载的问题",
                "🌐" to "【WebVPN 转换】打开链接前自动检测 WebVPN session 有效性，失效在 App 内重新登录，不再跳到网页内输账号密码",
                "📱" to "【UI】默认改为经典底栏 + 默认进入「日程」Tab，可在设置里改回",
                "🛡️" to "【稳定性】网络抖动/切换不再触发后台自动重登所有子系统；JWAPP 业务级 token 失效自动重新认证；修复进-退-进死循环",
                "🔒" to "【权限精简】移除冗余的 CHANGE_WIFI_STATE 声明（应用从不修改 WiFi 状态），规避部分严格安卓沙盒环境拒装"
            )
        ),
        "3.5.0" to VersionChangelog(
            items = listOf(
                "🔐" to "【MFA】自动登录遇到两步验证时弹出验证码对话框，即时完成验证继续使用功能",
                "🌐" to "【网络】教务/思源学堂/场馆/教材中心在校外自动启用 WebVPN 通道",
                "💳" to "【校园卡】修复付款码消费被错误标注为收入的问题",
                "📚" to "【教材中心】精简为搜索与书目查看，移除阅览和下载",
                "⬆️" to "【UI】miuix 升级至 0.9.1，修复登录态过期时的错误提示"
            )
        ),
        "3.4.1" to VersionChangelog(
            items = listOf(
                "🔑" to "【考勤】修复登录 URL 协议错误（http → https），解决考勤无法登录的问题",
                "⬆️" to "【更新】应用内下载始终可用：Gitee Release 附件缺失时自动 fallback 到直链，移除跳转浏览器按钮",
                "🗓️" to "【课表】修复浮动胶囊 dock 下三个 Tab 底部出现固定空白的问题，现在与其他页面一致自然延伸"
            )
        ),
        "3.4.0" to VersionChangelog(
            items = listOf(
                "🗓️" to "【课表】时间轴空时段自动纵向压缩，切周时先恢复均匀再平滑压缩，左侧时间标签同步收缩",
                "🗓️" to "【课表】学期总览也启用空时段压缩，长时间无课的小时区段不再占满高度",
                "🏠" to "【首页】「全部服务」改为真正的瀑布流布局，卡片高低错落更紧凑美观",
                "🏠" to "【首页】快捷入口与服务卡提示根据使用习惯动态变化，常用服务自动置顶",
                "✏️" to "【自建日程】添加日程弹窗全面 MIUIX 化：精简 NumberPicker、移除备注输入框避免键盘遮挡，结束时间随开始自动调整",
                "🎫" to "【加餐券】移除右上角刷新按钮，改用 MIUIX 下拉刷新，空状态/错误状态也可下拉重试",
                "⚙️" to "【设置】修复弹窗在某些情况下不显示的问题，清除缓存后大小立即刷新",
                "📝" to "【更新日志】统一三处来源：启动弹窗、设置页与云端 Release Notes 全部从同一份数据派生，并支持跨版本堆叠展示"
            )
        ),
        "3.3.0" to VersionChangelog(
            items = listOf(
                "🎫" to "【加餐券】新增电子加餐券查询，支持余额、有效期、使用状态与分页列表",
                "🔐" to "【认证】加餐券接入统一 CAS 会话和自动登录，支持 JWT 失效重试",
                "🏠" to "【首页】全部服务新增加餐券入口，工具页同步提供校园服务入口",
                "⚙️" to "【设置】统一二级页面风格，修复中文文案和 LMS 下载路径重叠问题",
                "🗓️" to "【课表】新增优化缓存读取，改进节假日过滤和小组件稳定性"
            )
        ),
        "3.2.0" to VersionChangelog(
            items = listOf(
                "🗓️" to "【课表】新增节假日显示，支持将节假日自动从时间线中过滤，并在导出时进行排除",
                "🔧" to "【自建课程】增强周次解析与假期冲突检测逻辑",
                "🏫" to "【空教室】支持校区与教学楼选择记忆"
            )
        ),
        "3.1.0" to VersionChangelog(
            items = listOf(
                "💳" to "校园卡迁移至新平台 ncard.xjtu.edu.cn，JWT 认证替代旧接口，余额与流水恢复正常",
                "📚" to "新增电子教材中心：搜索书目、在线阅览与 PDF 下载",
                "🎓" to "新增 NeoSchool（拔尖计划）：课程列表、章节、课件与资源下载"
            )
        ),
        "3.0.2" to VersionChangelog(
            items = listOf(
                "🗓️" to "添加了日程功能"
            )
        ),
        "3.0.1" to VersionChangelog(
            items = listOf(
                "🎬" to "新增课程回放下载功能"
            )
        ),
        "3.0" to VersionChangelog(
            items = listOf(
                "🧭" to "导航结构升级：教务 Tab 重构为日程 Tab，首页/小组件统一直达「我的日程」",
                "🗓️" to "日程页重构：支持嵌入式无边界头部，学期/Tab 交互与刷新状态提示优化",
                "🧩" to "小组件体系稳定化：日程与校园卡回退 RemoteViews 链路，兼容更多 OEM 桌面",
                "💳" to "校园卡小组件 2×2 紧凑重排，金额显示与三餐布局优化，减少溢出与加载异常",
                "✅" to "日程链路修复：从小组件进入后登录恢复可自动在线刷新，不再长期停留离线缓存",
                "🛠️" to "评教、成绩、场馆、主题与多处页面细节修复，整体体验与稳定性提升"
            ),
            issues = listOf(
                "少数桌面宿主对旧小组件实例缓存较重，升级后建议删除并重新添加校园卡/日程小组件"
            )
        ),
        "2.8.1" to VersionChangelog(
            items = listOf(
                "🏟️" to "新增场馆收藏功能，支持收藏常用场馆",
                "✨" to "支持双击场馆卡片快速收藏/取消收藏",
                "🎬" to "新增收藏动画与提示反馈，交互更顺滑",
                "📌" to "场馆列表支持按收藏状态优先排序",
                "📝" to "补充版本号与更新日志，完善发版信息"
            )
        ),
        "2.8.0" to VersionChangelog(
            items = listOf(
                "💳" to "新增校园卡桌面小组件（4×2）：余额、今日消费及早/午/晚三餐明细",
                "🔄" to "应用内更新：直接下载并安装新版 APK（基于 Gitee Releases）",
                "🗓️" to "新增校历",
                "🐛" to "修复所有小组件崩溃/无法添加问题（RemoteViews 兼容性）",
                "👤" to "边边角角修复与优化"
            )
        ),
        "2.7.1" to VersionChangelog(
            items = listOf(
                "🧩" to "新增日程桌面小组件（2×2 / 4×2 两种规格，支持当日安排一览）",
                "🐛" to "修复日程小组件布局与数据加载问题",
                "🔏" to "APK 签名由 v2 升级为 v2+v3，增强安全性与支持未来密钥轮换"
            ),
            issues = listOf(
                "入馆后可能错误显示「取消预约」按钮"
            )
        ),
        "2.7.0" to VersionChangelog(
            items = listOf(
                "🔍" to "新增全校课程查询：按课程名、教师、院系等多维度检索",
                "🏠" to "首页/教务/工具 Tab 重新分区，更加合理",
                "👤" to "「我的」页大幅重构：全新关于区域、开源社区入口、开发计划",
                "🎬" to "修复思源学堂视频播放闪退（横屏 Activity 重建问题）",
                "📊" to "成绩查询页新增免责声明提示",
                "🐛" to "修复全校课程 API 解析异常导致的闪退",
                "👍" to "出勤记录文案修正、多处 UI 细节优化"
            )
        ),
        "2.6.0" to VersionChangelog(
            items = listOf(
                "📖" to "新增思源学堂（LMS）功能：课程、作业、课件、课堂回放",
                "📝" to "作业详情：查看提交记录、评分、教师评语",
                "🎬" to "课堂回放：支持多机位视频下载",
                "📎" to "课件附件：一键下载课程资料",
                "👍" to "UI 优化与多处细节改进"
            )
        ),
        "2.5.1" to VersionChangelog(
            items = listOf(
                "🔙" to "修复所有界面按返回直接回桌面的严重 Bug",
                "🧹" to "移除多余的 NavigationEvent 依赖"
            )
        ),
        "2.5.0" to VersionChangelog(
            items = listOf(
                "🎓" to "新增课程回放功能（教学平台 TronClass）",
                "🏟️" to "新增体育场馆预订",
                "📜" to "用户协议更新",
                "📚" to "图书馆状态优化",
                "🔄" to "视频播放器修复",
                "🚫" to "移除定时抢座功能，避免风险",
                "👍" to "大量 UI 优化与 Bug 修复"
            ),
            issues = listOf(
                "通知推送功能有待优化"
            )
        ),
        "2.3.2" to VersionChangelog(
            items = listOf(
                "🎉" to "正式版来了！感谢参与内测的山东老乡！",
                "🔐" to "接入学工系统，可查看详细信息",
                "📸" to "新增成绩单下载，绕开限制；成绩查询纳入未评教成绩",
                "💳" to "图书馆智能座位推荐、地图选座 V2",
                "🏠" to "UI 改版，使用 MIUIX 开源的 HyperOS 设计语言",
                "👍" to "大量 Bug 修复与人性化改进"
            ),
            issues = listOf(
                "图书馆定时抢座功能待修复",
                "通知推送功能有待优化",
                "校园卡登录偶尔失败（教务 Token 获取）"
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
