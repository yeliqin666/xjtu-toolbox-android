package com.xjtu.toolbox.agent

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.inbox.InboxRules
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.inbox.SelfTodoRules
import com.xjtu.toolbox.inbox.SelfTodoStore
import com.xjtu.toolbox.notification.ReminderNotifier
import com.xjtu.toolbox.util.nowOnCampus
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.widget.ScheduleWidgetUpdater
import com.xjtu.toolbox.widget.WidgetCourse
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.plus
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

private const val TAG = "PidaiJobs"

/**
 * 小岱的主动任务：早上一份今日简报、晚上一份明日预告（「主动提醒」为「少」时只有早报），
 * 外加用户在对话里交代的定时任务（[PidaiTaskStore]）。结果进「消息收纳」并发通知。
 *
 * 后台**不登录学校系统**：课表、余额读本机缓存，工具只给不用登录的那几件（公开通知、联网、校园地图）。
 * 要用户自己的 API Key，每天最多 [DAILY_BUDGET] 次调用；没配 Key 或「主动提醒」关了就什么都不做。
 */
class PidaiJobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        com.xjtu.toolbox.account.AccountContext.restoreIfNeeded()
        val level = ProactiveRules.currentLevel(app)
        if (level == ProactiveLevel.OFF) return Result.success()
        val engine = AgentInsightEngine(AndroidAgentHost(app, AppLoginState()))
        val now = nowOnCampus()
        val minute = now.hour * 60 + now.minute
        val today = now.date.toString()
        return try {
            if (minute in MORNING && PidaiJobBudget.once(app, "morning", today)) {
                runBrief(app, engine, MORNING_BRIEF, "morning", snapshot(app, tomorrow = false))
            }
            if (level == ProactiveLevel.STANDARD && minute in EVENING && PidaiJobBudget.once(app, "evening", today)) {
                runBrief(app, engine, EVENING_BRIEF, "evening", snapshot(app, tomorrow = true))
            }
            if (now.date.dayOfWeek == kotlinx.datetime.DayOfWeek.SUNDAY && minute in WEEKLY &&
                com.xjtu.toolbox.report.WeeklyReport.isEnabled(app) && PidaiJobBudget.once(app, "weekly", today)
            ) {
                runWeekly(app, engine, today)
            }
            PidaiTaskStore.due(now).forEach { task ->
                PidaiTaskStore.markRun(task.id, Clock.System.now().toEpochMilliseconds())
                if (!PidaiJobBudget.take(app, today)) return@forEach
                runBrief(app, engine, taskSpec(task.prompt), "task:${task.id}", "今天：$today\n任务：${task.prompt}")
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "本轮失败：${e.message}")
            Result.success()
        }
    }

    private suspend fun runBrief(context: Context, engine: AgentInsightEngine, spec: InsightSpec, key: String, input: String) {
        if (key == "morning" || key == "evening") PidaiJobBudget.take(context, nowOnCampus().date.toString())
        val ok = engine.run(spec, input, force = true) as? InsightOutcome.Ok ?: return
        val title = InboxRules.markdownToPlain(ok.json["title"].safeStringOrNull().orEmpty())
        val body = InboxRules.markdownToPlain(ok.json["body"].safeStringOrNull().orEmpty())
        if (title.isEmpty() || body.isEmpty()) return
        post(context, key, title, body)
    }

    /** 周报：数据行照本机缓存原样写，模型只补一句点评；没配 Key、额度用完或失败时用模板句。 */
    private suspend fun runWeekly(context: Context, engine: AgentInsightEngine, today: String) {
        val account = com.xjtu.toolbox.account.AccountContext.activeAccountId?.takeIf { it.isNotBlank() } ?: return
        val facts = com.xjtu.toolbox.report.WeeklyReport.facts(context, account, System.currentTimeMillis()) ?: return
        val data = facts.lines.joinToString("\n")
        val comment = if (!PidaiJobBudget.take(context, today)) null else {
            (engine.run(WEEKLY_COMMENT, data, force = true) as? InsightOutcome.Ok)
                ?.json?.get("comment").safeStringOrNull()
                ?.let(InboxRules::markdownToPlain)?.takeIf { it.isNotBlank() }
        }
        post(context, "weekly", "本周小结", "$data\n小岱：${comment ?: facts.fallbackComment}")
    }

    /** 进收纳箱「小岱简报」、记入推送记录并发通知。 */
    private fun post(context: Context, key: String, title: String, body: String) {
        val at = Clock.System.now().toEpochMilliseconds()
        InboxStore.post(
            InboxItem(
                id = "pidai:$key:${nowOnCampus().date}",
                category = InboxCategories.PIDAI,
                source = "小岱",
                title = title,
                body = body,
                time = at,
                route = com.xjtu.toolbox.nav.AppRoute.Agent.id,
            ),
        )
        // 全文记入「推送记录」，通知栏只放预览（#194）
        val pushAt = PidaiPushLog.append(title, body, PidaiPushLog.kindOf(key))
        ReminderNotifier.notifyPidai(context, key, title, if (pushAt != null) PidaiPushLog.preview(body) else body, pushAt)
    }

    /** 本机缓存里能拿到的：今天（或明天）的课、未办待办、余额。 */
    private fun snapshot(context: Context, tomorrow: Boolean): String {
        val data = runCatching { ScheduleWidgetUpdater.loadTwoDayData(context) }.getOrNull()
        val courses: List<WidgetCourse> = (if (tomorrow) data?.tomorrow else data?.today).orEmpty()
        fun clock(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        return buildString {
            val date = nowOnCampus().date.let { if (tomorrow) it.plusDays(1) else it }
            append("${if (tomorrow) "明天" else "今天"}：$date ${data?.weekText.orEmpty()}\n课：")
            if (courses.isEmpty()) append("没有（${(if (tomorrow) data?.tomorrowEmpty else data?.todayEmpty)?.title.orEmpty()}）\n")
            else courses.forEach { append("\n- ${clock(it.startMinute)}–${clock(it.endMinute)} ${it.name}｜${it.location}") }
            val todos = SelfTodoStore.open().take(8)
            if (todos.isNotEmpty()) {
                append("\n待办：")
                todos.forEach { t -> append("\n- ${t.title}${SelfTodoRules.dueText(t)?.let { "（截止 $it）" }.orEmpty()}") }
            }
            com.xjtu.toolbox.card.CampusCardCache.load(context)?.cardInfo?.balance?.let { append("\n校园卡余额：$it 元") }
        }
    }

    private fun kotlinx.datetime.LocalDate.plusDays(n: Int) = plus(n, kotlinx.datetime.DateTimeUnit.DAY)

    companion object {
        /** 后台每天最多调几次模型（早报、晚报、定时任务合计）。 */
        const val DAILY_BUDGET = 6

        private val MORNING = (6 * 60 + 30) until (10 * 60)
        private val EVENING = (20 * 60 + 30) until (23 * 60)
        /** 周日晚的周报（后台每小时看一次，不精确）。 */
        private val WEEKLY = (20 * 60) until (23 * 60)

        /** 后台能用的工具：都不用登录。 */
        private val BACKGROUND_TOOLS = setOf("get_notifications", "web_search", "web_fetch", "wechat_search", "campus_map", "app_guide", "calculate")

        private val MORNING_BRIEF = InsightSpec(
            id = "pidai_morning",
            tools = setOf("get_notifications", "campus_map", "app_guide"),
            ttlMs = 60 * 60 * 1000L,
            instruction = """
任务：写一条早上的「今日简报」推送。依据给你的今天的课、待办和余额；可以用 get_notifications 看看今天有没有要紧的公开通知，campus_map 算一下课间要不要赶。
- 只说今天要知道、要做的事；先说最要紧的。余额不多才提余额。
- 语气是朋友提醒，不要客套。
只输出：{"title":"不超过 16 字","body":"不超过 120 字，2–4 行，每行一件事"}
            """.trimIndent() + PLAIN_TEXT,
        )

        private val EVENING_BRIEF = InsightSpec(
            id = "pidai_evening",
            tools = setOf("campus_map", "app_guide"),
            ttlMs = 60 * 60 * 1000L,
            instruction = """
任务：写一条晚上的「明日预告」推送：明天第一节几点在哪、要带什么、哪些待办快到期。明天没课就说一声，提醒一件最该做的事。
只输出：{"title":"不超过 16 字","body":"不超过 100 字，2–3 行"}
            """.trimIndent() + PLAIN_TEXT,
        )

        private val WEEKLY_COMMENT = InsightSpec(
            id = "pidai_weekly",
            tools = emptySet(),
            ttlMs = 60 * 60 * 1000L,
            instruction = """
任务：给用户的「小岱周报」写一句点评。给你的是他这周的作业、下周 DDL 和校园卡消费，数字已经写在周报里，不要复述。
- 抓最值得说的一件：没交的作业、扎堆的 DDL、花得明显多或少；都平常就轻松说一句。
- 用你自己的说话风格，像朋友，不说教。
只输出：{"comment":"不超过 40 字，一句话"}
            """.trimIndent() + PLAIN_TEXT,
        )

        private fun taskSpec(prompt: String) = InsightSpec(
            id = "pidai_task",
            tools = BACKGROUND_TOOLS,
            ttlMs = 0,
            instruction = """
任务：执行用户交给你的定时任务，结果会作为推送发给他。后台没有登录学校系统，只能用给你的工具。
没发现值得告诉他的，就输出 {"title":"","body":""}，不要硬凑。
只输出：{"title":"不超过 16 字","body":"不超过 150 字"}
            """.trimIndent() + PLAIN_TEXT,
        )

        /**
         * 推送在通知栏按纯文本显示。另外守住记忆里的偏好：用户说过不要的内容不写，
         * 说过不要这类推送就整条不发（空 title / body 在 [runBrief] 里直接跳过）。
         */
        private const val PLAIN_TEXT = "\ntitle 和 body 都是纯文本：不用 Markdown（**、#、-、反引号、[链接](…)），分行直接换行。" +
            "\n遵守记忆里用户的偏好：他说过不想看的内容不写；他说过不要这类推送，就只输出 {\"title\":\"\",\"body\":\"\"}。"
    }
}

/** 后台调用的每日额度与「今天跑过没有」。 */
internal object PidaiJobBudget {
    private fun prefs(context: Context) = context.getSharedPreferences("pidai_jobs", Context.MODE_PRIVATE)

    /** [kind] 今天第一次走到这里返回 true（并记下）。 */
    fun once(context: Context, kind: String, today: String): Boolean {
        val p = prefs(context)
        if (p.getString("done_$kind", null) == today) return false
        p.edit().putString("done_$kind", today).apply()
        return true
    }

    /** 记一次调用；今天额度用完返回 false。 */
    fun take(context: Context, today: String): Boolean {
        val p = prefs(context)
        val used = if (p.getString("day", null) == today) p.getInt("used", 0) else 0
        if (used >= PidaiJobWorker.DAILY_BUDGET) return false
        p.edit().putString("day", today).putInt("used", used + 1).apply()
        return true
    }
}

object PidaiJobScheduler {
    private const val UNIQUE = "pidai_jobs"

    /** 「主动提醒」关掉或没有账号时取消；否则每小时看一次（系统按批次合并，不精确）。 */
    fun apply(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (ProactiveRules.currentLevel(app) == ProactiveLevel.OFF) {
            wm.cancelUniqueWork(UNIQUE)
            return
        }
        val request = PeriodicWorkRequestBuilder<PidaiJobWorker>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
