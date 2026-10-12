package com.xjtu.toolbox.schedule

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.home.HomeSignals
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.inbox.OwnInbox
import com.xjtu.toolbox.lms.LmsDue
import com.xjtu.toolbox.lms.LmsDueStore
import com.xjtu.toolbox.widget.ScheduleWidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Android 侧的日程页宿主面：`:core` 的 `ScheduleScreen` 只认 [ScheduleHost]，这里把
 * `:app` 那几样平台能力接上。**每一个动作的本体与搬迁前逐字相同**，只是从「屏自己摸」变成「宿主给」。
 *
 * | 槽位 | 这一端的实现 |
 * |---|---|
 * | [cache] | `DataCache`（目录名 / 文件名 / 账号后缀 / 原子写入全是原来那一份） |
 * | [customCourses] | [RoomCustomCourseStore] —— **同一张表** `custom_courses`、同一个 DAO |
 * | [login] | `LocalAppLoginState`（`AppLoginState` 里那一份） |
 * | [session] | `:data` 的 [DataScheduleSession]（`SessionManager` + `JwxtScheduleApi`） |
 * | [holidays] / [courseLinks] | `:data` 的 [DataHolidaySource] / [DataCourseLinkAccess] |
 * | [dueHomeworks] | `LmsDueStore.load` |
 * | [roomSeatCount] | `:data` 的 `EmptyRoomApi.getRoomSeatCount` |
 * | [shareFile] | 写进 `cacheDir/exports` 再用 `FileProvider` + `ACTION_SEND`（原 `ScheduleExport.shareTextFile`） |
 * | [onScheduleSynced] | `HomeSignals.scheduleVersion++` + `ScheduleWidgetUpdater.requestUpdate` |
 * | [postScheduleChange] | `InboxStore.post(OwnInbox.scheduleChange(...))` |
 * | [isOnline] | `ConnectivityManager` 的 `NET_CAPABILITY_INTERNET` |
 * | [attendanceBadgeEnabled] | `CredentialStore.scheduleAttendanceBadge` |
 *
 * [honored] 列的是**这一端真会照着办**的全部 11 项（每一项都有上面那张表里的宿主面在读）。
 */
internal class AppScheduleHost(
    private val context: Context,
    private val loginState: AppLoginState,
) : ScheduleHost {

    private val app get() = context.applicationContext

    /** 与搬迁前同一个池：日程页的缓存读写一直是 `Dispatchers.IO`。 */
    override val io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    override val honored: Set<ScheduleCapability> = ScheduleCapability.entries.toSet()

    override fun cache(accountId: String?): ScheduleCacheStore = DataCache(app, accountId)

    override val customCourses: CustomCourseStore =
        RoomCustomCourseStore(AppDatabase.getInstance(app).customCourseDao())

    override val login: ScheduleLogin = object : ScheduleLogin {
        override val accountId: String get() = loginState.accountId
        override val studentId: String get() = loginState.activeUsername
        override val hasCredentials: Boolean get() = loginState.hasCredentials
    }

    override val session: ScheduleSession = DataScheduleSession(
        manager = { loginState.sessionManager },
        credentials = { loginState.savedUsername.takeIf { it.isNotEmpty() }?.let { it to loginState.savedPassword } },
    )

    override val holidays: HolidaySource = DataHolidaySource()

    override val courseLinks: CourseLinkAccess = DataCourseLinkAccess(
        manager = loginState.sessionManager,
        accountType = loginState.accountType,
    )

    override suspend fun dueHomeworks(accountId: String): List<LmsDue> = LmsDueStore.load(app, accountId)

    override suspend fun roomSeatCount(location: String): Int? =
        com.xjtu.toolbox.emptyroom.EmptyRoomApi().getRoomSeatCount(location)

    override val attendanceBadgeEnabled: Boolean get() = CredentialStore(app).scheduleAttendanceBadge

    override fun isOnline(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun onScheduleSynced() {
        // 首页 Hero 的「下一项安排」直接读课表缓存，只认这个版本号；
        // 桌面小组件自己不会醒 —— 搬迁前这两句就在日程页里，一行未变。
        HomeSignals.scheduleVersion++
        ScheduleWidgetUpdater.requestUpdate(app)
    }

    override fun shareFile(content: String, fileName: String, mimeType: String): Boolean {
        return try {
            val dir = File(app.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(Intent.createChooser(intent, "导出 $fileName"))
            true
        } catch (e: Exception) {
            // 原来这一句就是「导出日历」失败时的那条提示（`FriendlyError.of(e, "导出日历")`）。
            com.xjtu.toolbox.platform.showBriefMessage(FriendlyError.of(e, "导出日历"))
            false
        }
    }

    override fun postScheduleChange(message: String) {
        InboxStore.post(OwnInbox.scheduleChange(message))
    }
}

/**
 * `CustomCourseStore` 的 Android 实现 —— 一行行转发给原来的 `CustomCourseDao`。
 *
 * **同一个库、同一张表 `custom_courses`、同一批列名**（`:app` 的 `AppDatabase` 注册的还是
 * `CustomCourseEntity`），所以老用户的自建日程一条都不会丢；DAO 那句 SQL 的过滤与排序也逐字未动。
 */
internal class RoomCustomCourseStore(private val dao: CustomCourseDao) : CustomCourseStore {

    override fun observeByTerm(accountId: String, termCode: String): Flow<List<CustomCourse>> =
        dao.observeByTerm(accountId, termCode).map { rows -> rows.map { it.toCustomCourse() } }

    override suspend fun getByTerm(accountId: String, termCode: String): List<CustomCourse> =
        dao.getByTerm(accountId, termCode).map { it.toCustomCourse() }

    override suspend fun getConflicts(
        accountId: String,
        termCode: String,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
    ): List<CustomCourse> = dao.getConflicts(accountId, termCode, dayOfWeek, startSection, endSection)
        .map { it.toCustomCourse() }

    override suspend fun insert(course: CustomCourse): Long = dao.insert(course.toEntity())

    override suspend fun update(course: CustomCourse) = dao.update(course.toEntity())

    override suspend fun delete(course: CustomCourse) = dao.delete(course.toEntity())

    override suspend fun getAll(accountId: String): List<CustomCourse> =
        dao.getAll(accountId).map { it.toCustomCourse() }

    override suspend fun deleteByAccount(accountId: String) = dao.deleteByAccount(accountId)
}
