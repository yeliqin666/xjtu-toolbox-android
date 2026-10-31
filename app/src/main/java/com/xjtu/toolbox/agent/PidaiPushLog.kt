package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/** 把屁岱推送全文追加到「推送记录」会话及其模型历史（#194）。 */
internal object PidaiPushLog {

    /** 通知栏正文预览的最大字数。 */
    private const val PREVIEW_CHARS = 30

    private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

    /** 推送种类，由任务 key 推出：早报、晚间预告或用户交代的定时任务。 */
    fun kindOf(key: String): String = when {
        key == "morning" -> "今日简报"
        key == "evening" -> "明日预告"
        else -> "定时任务"
    }

    /** 气泡首行：标题，第二行日期时间与种类，记录多了也分得清是哪天哪一条。 */
    internal fun header(title: String, kind: String, at: java.time.ZonedDateTime): String =
        "**$title**\n\n_${at.monthValue}月${at.dayOfMonth}日 周${WEEKDAYS[at.dayOfWeek.value - 1]} " +
            "%02d:%02d · ${kind}_".format(at.hour, at.minute)

    /** @return 这条记录的时间戳（点通知时据此定位），写盘失败返回 null。 */
    fun append(title: String, body: String, kind: String): Long? = runCatching {
        val at = System.currentTimeMillis()
        val time = java.time.Instant.ofEpochMilli(at).atZone(com.xjtu.toolbox.util.CampusZoneId)
        // 后台模型输出里的 markdown 链接 / 图片拆掉，气泡里不出现可点的外链
        val content = "${header(title, kind, time)}\n\n${body.replace("](", "] (")}"
        AgentSessionStore().update(AgentPushFocus.SESSION_ID, AgentPushFocus.SESSION_TITLE, lockTitle = true) { old ->
            val history = runCatching { AppJson.parseToJsonElement(old.llmHistory).jsonArray.toList() }.getOrDefault(emptyList()) +
                buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                }
            old.copy(
                messages = old.messages + StoredMessage(role = "assistant", content = content, timestamp = at),
                llmHistory = JsonArray(history).toString(),
            )
        }
        AgentPushFocus.notifyAppended()
        at
    }.getOrNull()

    fun preview(body: String): String {
        val flat = body.replace('\n', ' ').trim()
        if (flat.length <= PREVIEW_CHARS) return flat
        val head = flat.take(PREVIEW_CHARS).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
        return head.trimEnd() + "…点击查看详情"
    }
}
