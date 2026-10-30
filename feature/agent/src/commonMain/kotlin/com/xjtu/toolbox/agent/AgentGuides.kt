package com.xjtu.toolbox.agent

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.home.AppServices

/**
 * `app_guide` 的「功能与入口」：功能清单从 [AppServices]（首页宫格与全局搜索共用的那份目录）现生成，
 * 新功能只要进了目录屁岱就知道；这里只补目录里没有的外壳结构。
 */
val APP_GUIDE: String by lazy {
    buildString {
        append("App 功能与入口：\n")
        append("- 底栏：默认首页、日程、屁岱、学辅、我的；可在「我的 → 设置 → 底栏」自定义，首页的一级功能都能放上去，屁岱也可移除。\n")
        append("- 首页顶栏：左上扫码登录（扫统一身份认证二维码）、全局搜索、消息收纳（课表变动、考勤、成绩、作业截止、通知、待办等提醒汇总，可在这里记和勾掉待办）。\n")
        append("- 桌面小组件（Android）：日程 2x2 / 4x2、校园卡余额、教务通知、待办。长按桌面空白处添加。\n")
        append("- 屁岱会在底栏头顶冒提醒气泡（上课、余额、考试、新成绩等），屁岱页右上角设置里的「主动提醒」可调频率或关掉。\n")
        append("- 屁岱 → 右上角设置：配置模型服务、API Key、思考模式、联网搜索与远程 MCP。Android 和桌面端支持 Streamable HTTP MCP：开启 MCP，填写服务地址与令牌，测试连接后保存；模型可列出远程工具，每次调用由用户确认。当前是否开启、服务是否可用需查看配置或连接结果。\n")
        append("- 屁岱支持多会话、图片提问、工具结果卡片、记忆偏好；内置工具能否执行以本端实际提供的工具清单为准。\n")
        append("- Android 阅读文章和图片：在微信等应用的「使用其他方式打开」或系统「分享」中选择「屁岱」，自动新开对话阅读链接或识别图片，每次最多 4 张图片。\n")
        append("- 日程页支持手动添加、编辑和删除自建日程，也可让屁岱按明确要求添加；有截止时间、不占时段的事项记进「消息 → 待办」（收纳页右上角 ＋ 手建，可挑颜色、标重要程度）。\n")
        append("- 我的 → 设置：外观、账号与数据管理；下载管理可查看课件和回放。校园账号凭据保存在本机；对话和工具结果会发给用户配置的模型服务，MCP 调用会把确认的参数发给对应服务。\n")
        append("功能目录（名称：说明）：\n")
        AppServices.all.forEach { s ->
            append("- ").append(s.title).append("：").append(s.subtitle)
            when (s.audience) {
                AccountType.UNDERGRADUATE -> append("（本科生）")
                AccountType.POSTGRADUATE -> append("（研究生）")
                else -> {}
            }
            if (!s.showOnHome) append("（首页不单列，全局搜索可达）")
            append('\n')
            s.features.forEach { append("  · ").append(it).append('\n') }
        }
        append("- 选课、交作业、缴学费不在 App 里，要去学校对应系统。")
    }
}

const val XINGQING_GUIDE = """兴庆校区：
- 学生食堂：梧桐苑和康桥苑。梧桐苑三层，二楼是自选餐厅。康桥苑价格更便宜，离东南门和操场近：一楼大众伙食，二楼特色小吃和自选，三楼东苑食堂有点餐、套餐和自助餐，还有超市、奶茶店和瑞幸。
- 图书馆约 23:00 闭馆，二层连廊和流通大厅 24 小时开放；主楼群约 22:30 关门。
- 主楼在校园南部，中、东、西楼在北部。

创新港：
- 食堂：A 区和鸣苑、B 区惠风苑、C 区朗清苑，另有涵英楼食堂、同和苑，都在各自的宿舍园区里。
- 供餐时间：早餐 6:30–9:30，午餐 11:00–13:30，晚餐 17:00–19:30；部分档口有夜宵到 23:00。"""

/** `app_guide` 的三种取法：只要功能入口、只要食堂与开放时间、或两者都给（[topic] 不认识时）。 */
fun agentGuideText(topic: String?): String = when (topic?.trim()?.lowercase()) {
    "app" -> APP_GUIDE
    "campus", "xingqing" -> XINGQING_GUIDE
    else -> APP_GUIDE + "\n\n" + XINGQING_GUIDE
}
