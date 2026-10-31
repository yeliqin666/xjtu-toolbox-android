package com.xjtu.toolbox

import android.app.Application
import android.content.Context
import com.xjtu.toolbox.error.CrashReporter
import com.xjtu.toolbox.notification.AppNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。仅承担"启动即建"职责：
 *
 * - 8.0+ NotificationChannel 必须先于第一条通知注册，否则系统丢弃。
 *   在 [Application.onCreate] 建一次保证比任何业务 push 都早。
 * - 未捕获异常由 [CrashReporter] 落盘，下次启动匿名上报。
 *
 * 其余启动钩子（性能打点 / 渠道开关）保持空。
 */
class XjtuApp : Application() {
    /** 应用级协程作用域：跟随进程存活，给页面销毁后仍要做完的收尾工作（如保存进度）用。 */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 已下线功能留在本机的数据。功能删了，数据留着既无用处也不该留（交晓智会话里是
     * 用户和学校 AI 的对话原文，旧考勤快照里是考勤记录），每次启动顺手清掉；
     * 都不存在时只是一次 listFiles 加两次 exists。
     */
    private fun removeRetiredFeatureData() {
        filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("jiaoxiaozhi_sessions") }
            ?.forEach { dir -> runCatching { dir.deleteRecursively() } }
        // 旧版考勤快照（AttendanceCache，已随旧考勤系统移除），以及不分账号的课表变更快照 / 来源 / 调课理由
        // （现在按账号存，名字后加账号后缀）。后三个旧文件是所有账号共用的，分不清属于谁，直接丢弃：
        // 各账号下次加载课表时静默重建基线，不会误报「课表有变动」。
        listOf(
            "attendance_cache_undergraduate", "attendance_cache_postgraduate",
            "schedule_diff", "schedule_source", "schedule_changes",
        ).forEach { name ->
            if (java.io.File(java.io.File(applicationInfo.dataDir, "shared_prefs"), "$name.xml").exists()) {
                runCatching { deleteSharedPreferences(name) }
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 进程里最早的钩子，早于所有 ContentProvider（WorkManager 自动初始化后可能立刻
        // 跑 Worker）和 onCreate。换包后的旧格式缓存必须在任何代码读到它之前清掉，见方法注释。
        com.xjtu.toolbox.data.DataCache.clearIfPackageChanged(base)
        // 首帧 AppLoginStateViewModel 要读账号表、协议版本；趁装 Provider 的空档在后台先解锁
        com.xjtu.toolbox.data.SecurePrefs.prewarm(
            base,
            com.xjtu.toolbox.account.AccountStore.FILE_NAME,
            com.xjtu.toolbox.data.CredentialStore.FILE_NAME,
        )
        // 统一存档的一次性导入：有档案才跑；坏档案写第一笔之前就失败，顺序必须在换包清理之后。
        com.xjtu.toolbox.archive.AppArchiveUx.maybeImportPendingArchive(base)
    }

    override fun onCreate() {
        super.onCreate()
        com.xjtu.toolbox.platform.initAndroidPlatform(this)
        // 同一件事的 :data 版：会话内核要的密文存储（cookie / 站点快照）按名字从
        // `:data` 的平台缝取，Android 那份 actual 同样需要进程级 Context。
        com.xjtu.toolbox.platform.initDataPlatform(this)
        // 反馈后端的凭据：`:data` 的 FeedbackApi 不读 BuildConfig（`:data` 是 JVM/Android
        // 共用的模块）⇒ 宿主在这里注入一次。必须在下面那发 CrashReporter 上传之前。
        com.xjtu.toolbox.feedback.AppFeedback.install()
        CrashReporter.install(this)
        // 桌面小组件那条缝：InboxStore 在 :core，写一次收纳就该重画一次小组件，
        // 而重画要 Context 与 AppWidgetManager（都在 :app）⇒ 用回调挂上去（见 InboxWidgetHook）。
        com.xjtu.toolbox.inbox.InboxWidgetHook.onTodosChanged = {
            runCatching { com.xjtu.toolbox.widget.TodoWidgetUpdater.requestUpdate(this) }
        }
        // 自建待办的截止提醒：存储在 :shared:model（各端共用），排期要 WorkManager（只在 :app）⇒ 同样挂回调。
        com.xjtu.toolbox.inbox.SelfTodoHooks.onChanged = { account, before, after ->
            com.xjtu.toolbox.inbox.SelfTodoReminders.sync(this, account, before, after)
        }
        // 小游戏全服榜的服务端地址（构建期可覆盖，见 app/build.gradle.kts；空串 = 不出现排行榜）
        com.xjtu.toolbox.game.board.LeaderboardApi.install(BuildConfig.LEADERBOARD_BASE_URL)
        com.xjtu.toolbox.auth.CampusProbe.init(this)
        applicationScope.launch { CrashReporter.uploadPending(this@XjtuApp) }
        applicationScope.launch { removeRetiredFeatureData() }
        AppNotificationChannels.ensureChannels(this)
        // 后台调度要读账号（AccountStore → 加密存储首次打开要走 keystore），不占主线程。
        // 顺带预热了 SecurePrefs 的缓存，首帧里界面再取账号时直接命中。
        applicationScope.launch {
            com.xjtu.toolbox.notification.NoticeWatchScheduler.apply(this@XjtuApp)
            com.xjtu.toolbox.notification.ScheduleWatchScheduler.apply(this@XjtuApp)
            com.xjtu.toolbox.notification.LmsDeadlineScheduler.apply(this@XjtuApp)
            com.xjtu.toolbox.notification.CommunityWatchScheduler.apply(this@XjtuApp)
            com.xjtu.toolbox.agent.PidaiJobScheduler.apply(this@XjtuApp)
        }
    }
}