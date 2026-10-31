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

/** Android 侧的日程页宿主面。 */
internal class AppScheduleHost(
    private val context: Context,
    private val loginState: AppLoginState,
) : ScheduleHost {

    private val app get() = context.applicationContext

    /** 日程页的缓存读写走 `Dispatchers.IO`。 */
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

    override suspend fun dueHomeworks(accountId: String): List<LmsDue> = LmsDueStore.load(accountId)

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
            // 从 Application 启动：NEW_TASK 要加在真正启动的选择器上，加在里层 intent 上不算
            app.startActivity(Intent.createChooser(intent, "导出 $fileName").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            com.xjtu.toolbox.platform.showBriefMessage(FriendlyError.of(e, "导出日历"))
            false
        }
    }

    override fun postScheduleChange(message: String) {
        InboxStore.post(OwnInbox.scheduleChange(message))
    }
}

/** `CustomCourseStore` 的 Android 实现，转发给 `CustomCourseDao`。 */
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
