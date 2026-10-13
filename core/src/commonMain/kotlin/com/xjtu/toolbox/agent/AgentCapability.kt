package com.xjtu.toolbox.agent

/**
 * 屁岱里有**只有某些端才真会照着办**的那几档。
 *
 * ## 为什么要有这张清单
 *
 * 两处消费点，一处口径（见 [AgentHost.honored]）：
 *  - **工具表**（[agentToolDefinitions]）按它决定哪几行**画不画**：[ALARM]（`set_alarm`）、
 *    [APP_SETTINGS]（`app_setting`）、[SCHEDULE_EVENT_WRITE]（`add_schedule_event`）这三档
 *    在 `:app` 上是现成的（`AlarmClock` 的 Intent、`CredentialStore`、Room 那张 `custom_courses` 表），
 *    桌面端与将来的第三端没有 ⇒ 模型调了它，回答只能是一句「失败」，
 *    而用户看到的是「这功能时灵时不灵」；
 *  - **屏**上那几档：[IMAGE_INPUT]（输入栏那颗回形针）、[FILE_SAVE]（资料卡那几行下载）。
 *
 * 这与 `SettingsHost.honoredRows` / `ScheduleHost.honored` / `HomeHost.honoredCards` 是同一条纪律 ——
 * **没有的档一个字都不画**。
 *
 * ## 两个字段的分工
 *
 * [tool] 是它在工具表里的名字（**只有工具档才有**，屏上那两档是 null）—— 那张表是这份清单里
 * 工具档**唯一**的机器可读形状，`:core` 的 `AgentToolCatalogTest` 按它逐档核对
 * （改名/漏画都会被钉住）；[label] 只是给人看的一句话，用于宿主实现与测试的报错信息。
 */
enum class AgentCapability(val tool: String?, val label: String) {

    /** 打开系统闹钟设定闹钟（`:app` = `AlarmClock.ACTION_SET_ALARM` 的 Intent）。 */
    ALARM("set_alarm", "系统闹钟"),

    /** 读写本 App 的设置项（`:app` = `CredentialStore` 里那几项白名单）。 */
    APP_SETTINGS("app_setting", "应用设置读写"),

    /** 往「自建日程」那张表里加一条（`:app` = Room 的 `custom_courses`）。 */
    SCHEDULE_EVENT_WRITE("add_schedule_event", "自建日程写入"),

    /**
     * 从系统相册挑图（视觉输入的入口）：`:app` 走 `PickMultipleVisualMedia` + 压缩落盘，
     * 桌面/Web 没有相册 ⇒ 输入栏那颗回形针**不画**（不是画出来点了没反应）。
     */
    IMAGE_INPUT(null, "相册挑图"),

    /**
     * 把工具查到的文件存进「下载」（资料卡那一行的动作）：`:app` = `ZyxfApi.download`。
     * 没有这一档的端，资料卡上的文件行**不画下载动作**（仍然可以点开问屁岱要）。
     */
    FILE_SAVE(null, "存进下载"),

    ;
    companion object {

        /** 这个工具名归哪一档；不在清单里（= 纯逻辑或取数）返回 null。 */
        fun ofTool(tool: String): AgentCapability? = entries.firstOrNull { it.tool == tool }

        /** 进工具表的那几档（[tool] 非空）—— 工具清单的取舍口径就是它。 */
        val toolGated: List<AgentCapability> get() = entries.filter { it.tool != null }
    }
}
