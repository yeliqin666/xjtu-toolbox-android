package com.xjtu.toolbox.social

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** 一位好友的课表：匹配页扫到带「完整课表」的码时存下。 */
@Serializable
data class FriendTimetable(
    val id: String,
    val name: String,
    /** 课表所属学期，只叠到同一学期的日程上。 */
    val term: String,
    val courses: List<CourseItem>,
)

/**
 * 存在本机的好友课表，按账号分开，不上传。日程周视图可以挑一位叠上去对比。
 * 对方课表变了就再扫一次，同名同学期的覆盖旧的。
 */
object FriendTimetables {
    private val store by lazy { keyValueStore("friend_timetables") }

    private fun listKey() = "list" + AccountContext.safeSuffix()
    private fun overlayKey() = "overlay" + AccountContext.safeSuffix()

    /** 每写一次加一：界面以它为键重新读（键值存储没有监听）。 */
    var revision by mutableIntStateOf(0)
        private set

    fun all(): List<FriendTimetable> = store.getString(listKey())
        ?.let { runCatching { AppJson.decodeFromString<List<FriendTimetable>>(it) }.getOrNull() }
        .orEmpty()

    fun save(name: String, term: String, courses: List<CourseItem>): FriendTimetable {
        val list = all()
        val id = list.firstOrNull { it.name == name && it.term == term }?.id
            ?: ("f" + Clock.System.now().toEpochMilliseconds())
        val entry = FriendTimetable(id, name, term, courses)
        write(list.filterNot { it.id == id } + entry)
        return entry
    }

    fun delete(id: String) {
        write(all().filterNot { it.id == id })
        if (overlayId == id) overlayId = null
    }

    /** 日程周视图叠的是哪一位；null = 不叠。 */
    var overlayId: String?
        get() = store.getString(overlayKey())
        set(value) {
            store.putString(overlayKey(), value)
            revision++
        }

    private fun write(list: List<FriendTimetable>) {
        store.putString(listKey(), AppJson.encodeToString(list))
        revision++
    }
}
