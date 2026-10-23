package com.xjtu.toolbox.agent

/** 屁岱里有只有某些端才真会照着办的那几档。 */
enum class AgentCapability(val tool: String?, val label: String) {

    /** 打开系统闹钟设定闹钟（`:app` = `AlarmClock.ACTION_SET_ALARM` 的 Intent）。 */
    ALARM("set_alarm", "系统闹钟"),

    /** 读写本 App 的设置项（`:app` = `CredentialStore` 里那几项白名单）。 */
    APP_SETTINGS("app_setting", "应用设置读写"),

    /** 往「自建日程」那张表里加一条（`:app` = Room 的 `custom_courses`）。 */
    SCHEDULE_EVENT_WRITE("add_schedule_event", "自建日程写入"),

    /** 从系统相册挑图（视觉输入的入口）：`:app` 走 `PickMultipleVisualMedia` + 压缩落盘，桌面/Web 没有相册。 */
    IMAGE_INPUT(null, "相册挑图"),

    /**
     * 把工具查到的文件存进「下载」（资料卡那一行的动作）：`:app` = `ZyxfApi.download`。
     *
     * [tool] 是 `save_file` 而不是 null：这一档既画一个工具、又画资料卡上那一行，
     * 两处必须同一个开关（否则就是"工具能调、按钮不画"或反过来）。于是 `save_file` 也进了
     * 工具表的门：没列这一档的端，模型调过来会被 `honors()` 挡成 not_found，
     * 而不是半执行一遍再在某处抛错。
     */
    FILE_SAVE("save_file", "存进下载"),

    ;
    companion object {

        /** 这个工具名归哪一档；不在清单里（= 纯逻辑或取数）返回 null。 */
        fun ofTool(tool: String): AgentCapability? = entries.firstOrNull { it.tool == tool }

        /** 进工具表的那几档（[tool] 非空）。 */
        val toolGated: List<AgentCapability> get() = entries.filter { it.tool != null }
    }
}
