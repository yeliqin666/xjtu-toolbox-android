package com.xjtu.toolbox.emptyroom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.util.todayInSystemZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 空教室：数据源（实时状态 / CDN 课表 / 直查教务）× 校区 × 楼 × 日期，任一变化就取消旧查询重查。
 * 实时状态一个校区一次请求，楼只在本地筛；课表数据按楼查，单楼结果当天内存缓存。
 *
 * 从 `:app` 搬进 `:core` 时**编排逻辑一行未改**，只换了三处"住址"：
 *  - 三档取数（含"实时状态要哪个站点会话""直查要不要先登教务"）从 [source] 来 —— 那些是各端能力，
 *    不是屏的逻辑（原来写在 `queryLive`/`queryRooms` 里，现在在 `AppEmptyRoomSource`）；
 *  - 偏好（上次的校区 / 楼 / 数据源）走平台键值缝 [keyValueStore]：Android 侧仍是同一个
 *    SharedPreferences 文件 `empty_room`（键名逐字未动，`AgentTool` 与屏读的还是同一份），Web 侧是 localStorage；
 *  - `java.time` 换成 kotlinx-datetime（"今天"统一用 [todayInSystemZone]），`java.text.SimpleDateFormat` 手写补零。
 */
internal class EmptyRoomViewModel(
    /** 本端的三档取数实现；屏只画 [EmptyRoomSource.availableSources] 里的那几档。 */
    private val source: EmptyRoomSource,
    private val accountType: AccountType?,
) : ViewModel() {
    private val prefs = keyValueStore("empty_room")

    var rooms by mutableStateOf<List<RoomInfo>>(emptyList()); private set
    var isLoading by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    /** 联网失败改显示磁盘缓存时的说明；null 表示显示的是实时数据。 */
    var staleNote by mutableStateOf<String?>(null); private set
    var directProgress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var liveSnapshot by mutableStateOf<LiveSnapshot?>(null); private set
    /** 当天课表（CDN），按教室名对上实时状态，画节次条用；拿不到就不画。 */
    var liveSchedule by mutableStateOf<Map<String, RoomInfo>>(emptyMap()); private set

    /** 当前选中哪一档。叫 sourceKind 而不是 source：`source` 这个名字留给取数端口。 */
    var sourceKind by mutableStateOf(initialSource()); private set
    val isLive get() = sourceKind == RoomSource.LIVE

    // 校区按名字记：实时状态只有三个校区，两套列表的下标对不上
    private var campusName by mutableStateOf(prefs.getString(KEY_CAMPUS) ?: CAMPUS_BUILDINGS.keys.first())
    val campusNames get() = if (isLive) LIVE_CAMPUSES.keys.toList() else CAMPUS_BUILDINGS.keys.toList()
    /** 记住的校区不在当前数据源里时先落到第一个，不改记住的选择。 */
    val campus get() = campusName.takeIf { it in campusNames } ?: campusNames.first()
    val buildings get() = CAMPUS_BUILDINGS[campus].orEmpty()

    var selectedBuildings by mutableStateOf(savedBuildings(campus)); private set
    /** 实时状态的楼单独记（平台的楼和课表的楼不是同一批）；空集合 = 全部楼。 */
    private var liveSelected by mutableStateOf(savedLiveBuildings(campus))
    val liveBuildings get() = liveSnapshot?.takeIf { it.campus == campus }?.buildings.orEmpty()
    /** 平台上已经没有的楼不算数；全筛没了就回到全部。 */
    val liveEffective: Set<String>
        get() = liveSelected.filter { it in liveBuildings }.toSet().takeIf { it.isNotEmpty() && it.size < liveBuildings.size } ?: emptySet()

    val availableDates: List<String> = source.availableDates()
    var selectedDate by mutableStateOf(availableDates.firstOrNull().orEmpty()); private set

    /** 单楼结果：key = "source|campus|building|date"，value = (缓存那天, 教室)。 */
    private val buildingCache = mutableMapOf<String, Pair<String, List<RoomInfo>>>()
    private var queryJob: Job? = null
    private var generation = 0

    init { query() }

    /**
     * 上次记住的档；没记住过就是实时状态（历史默认值）。
     *
     * 两处限制：研究生身份不提供直查教务（沿用原来的判断）；本端没有这一档时落到
     * [EmptyRoomSource.availableSources] 的第一档 —— Web 只有 CDN，于是 Web 一进来就是 CDN；
     * Android 三档齐全，这行不改变任何现有取值。
     */
    private fun initialSource(): RoomSource {
        val saved = RoomSource.entries.firstOrNull { it.key == prefs.getString(SOURCE_PREF_KEY) }
        val wanted = when {
            saved == null -> RoomSource.LIVE
            saved == RoomSource.DIRECT && accountType == AccountType.POSTGRADUATE -> RoomSource.CDN
            else -> saved
        }
        return wanted.takeIf { it in source.availableSources } ?: source.availableSources.first()
    }

    private fun savedBuildings(campus: String): Set<String> {
        val all = CAMPUS_BUILDINGS[campus].orEmpty()
        return prefs.getString("empty_room_last_buildings_$campus")
            ?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() && it in all }?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(all.firstOrNull().orEmpty())
    }

    private fun savedLiveBuildings(campus: String): Set<String> =
        prefs.getString("empty_room_live_buildings_$campus")
            ?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun selectSource(value: RoomSource) {
        val next = if (value == RoomSource.DIRECT && accountType == AccountType.POSTGRADUATE) RoomSource.CDN else value
        if (next == sourceKind) return
        sourceKind = next
        prefs.putString(SOURCE_PREF_KEY, next.key)
        errorMessage = null
        staleNote = null
        campusChanged()
        query()
    }

    fun selectCampus(index: Int) {
        campusName = campusNames.getOrElse(index) { campus }
        prefs.putString(KEY_CAMPUS, campusName)
        campusChanged()
        query()
    }

    /** 换了校区（或数据源导致有效校区变了）：读这个校区记住的楼。 */
    private fun campusChanged() {
        selectedBuildings = savedBuildings(campus)
        liveSelected = savedLiveBuildings(campus)
    }

    /** 选楼弹窗里当前勾选的楼：课表按楼查询，实时按楼本地筛。 */
    val sheetBuildings get() = if (isLive) liveBuildings else buildings
    val sheetSelected get() = if (isLive) liveEffective.ifEmpty { liveBuildings.toSet() } else selectedBuildings

    fun setSheetSelected(value: Set<String>) {
        if (isLive) {
            liveSelected = if (value.size >= liveBuildings.size) emptySet() else value
            prefs.putString("empty_room_live_buildings_$campus", liveSelected.joinToString("|"))
        } else {
            selectedBuildings = value
            prefs.putString(KEY_CAMPUS, campus)
            prefs.putString("empty_room_last_buildings_$campus", value.filter { it.isNotBlank() }.joinToString("|"))
            query()
        }
    }

    fun selectDate(date: String) {
        if (date == selectedDate) return
        selectedDate = date
        if (!isLive) query()
    }

    fun refresh() = query(force = true)

    private fun query(force: Boolean = false) {
        queryJob?.cancel()
        val gen = ++generation
        queryJob = viewModelScope.launch {
            try {
                if (isLive) queryLive(gen, force) else queryRooms(gen, force)
            } catch (_: CancellationException) {
                // 被新查询取代（也是正常路径：连续切楼、切校区都会走到这里），什么都不用做
            }
        }
    }

    private suspend fun queryLive(gen: Int, force: Boolean) {
        fun latest() = gen == generation
        val campus = campus
        isLoading = true
        errorMessage = null
        directProgress = null
        try {
            val snapshot = source.liveSnapshot(campus, force)
            if (!latest()) return
            liveSnapshot = snapshot
            staleNote = null
            // 当天课表只做点缀：拿不到照样显示实时状态
            val today = todayInSystemZone().toString()
            val schedule = runCatching {
                source.rooms(campus, snapshot.buildings.toSet(), today, direct = false, force = false) { _, _ -> }
                    .associateBy { it.name }
            }.getOrDefault(emptyMap())
            if (latest()) liveSchedule = schedule
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (latest()) {
                val stale = source.readStaleLive(campus)
                if (stale != null) {
                    liveSnapshot = stale
                    staleNote = "实时状态没刷出来，下面是 ${hhmm(stale.fetchedAt)} 的状态（${rawError(e)}）"
                    errorMessage = null
                } else {
                    liveSnapshot = null
                    staleNote = null
                    errorMessage = rawError(e)
                }
            }
        } finally {
            if (latest()) isLoading = false
        }
    }

    private suspend fun queryRooms(gen: Int, force: Boolean) {
        fun latest() = gen == generation
        // 连续勾选多个楼时只查最后一次
        delay(350L)
        val campus = campus
        val date = selectedDate
        val direct = sourceKind == RoomSource.DIRECT
        val sourceKey = if (direct) "direct" else "cdn"
        val active = selectedBuildings.filter { it.isNotEmpty() }.toSet()
        if (active.isEmpty()) {
            rooms = emptyList()
            isLoading = false
            directProgress = null
            return
        }
        // 命中单楼缓存的不再请求
        val cacheDay = todayInSystemZone().toString()
        buildingCache.entries.removeAll { it.value.first != cacheDay || it.value.second.isEmpty() }
        val cachedRows = mutableListOf<RoomInfo>()
        val toFetch = mutableListOf<String>()
        for (b in active) {
            val key = "$sourceKey|$campus|$b|$date"
            val hit = buildingCache[key]
            if (!force && hit != null) cachedRows.addAll(hit.second)
            else { buildingCache.remove(key); toFetch.add(b) }
        }
        if (toFetch.isEmpty()) {
            rooms = cachedRows.sortedBy { it.name }
            errorMessage = null
            staleNote = null
            isLoading = false
            directProgress = null
            return
        }

        isLoading = true
        errorMessage = null
        directProgress = null
        try {
            // 直查那一档逐楼查、逐楼报进度（楼之间互不影响：某楼查不到就当它没数据）；
            // 课表那一档一次给全部选中的楼（"某楼不在当天数据里"只是少几间教室，不是整页失败）。
            val (result, fetched) = if (direct) {
                val merged = cachedRows.toMutableList()
                val fetchedRows = mutableListOf<Pair<String, List<RoomInfo>>>()
                toFetch.forEachIndexed { idx, building ->
                    if (!latest()) throw CancellationException("superseded")
                    try {
                        val rows = source.rooms(
                            campus = campus,
                            buildings = setOf(building),
                            date = date,
                            direct = true,
                            force = force,
                            onProgress = { period, total ->
                                if (latest()) {
                                    val progress = (idx * total + period) to (toFetch.size * total)
                                    viewModelScope.launch { if (latest()) directProgress = progress }
                                }
                            },
                        )
                        fetchedRows.add(building to rows)
                        merged.addAll(rows)
                    } catch (_: NoDataException) {
                        // 这栋楼教务那边查不到（楼名对不上、当天没有教室数据）⇒ 跳过它。
                        // 原来这里还有一行 Log.w：搬进 :core 后 VM 不再认识 android.util.Log，
                        // 而这条信息只是排障用的，不值得为它把日志缝也拉进来。
                    }
                }
                merged.sortedBy { it.name } to fetchedRows
            } else {
                source.rooms(campus, active, date, direct = false, force = force) { _, _ -> } to
                    emptyList<Pair<String, List<RoomInfo>>>()
            }
            if (latest()) {
                fetched.forEach { (building, rows) ->
                    if (rows.isNotEmpty()) buildingCache["direct|$campus|$building|$date"] = cacheDay to rows
                }
                rooms = result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoDataException) {
            if (latest()) {
                errorMessage = rawError(e)
                rooms = emptyList()
                staleNote = null
            }
        } catch (e: Exception) {
            // 有缓存就显示缓存（顶部黄条写明原因），没有才整页报错
            if (latest()) {
                val reason = rawError(e)
                errorMessage = if (fallbackToStale(direct, campus, active, date, reason)) null else reason
            }
        } finally {
            if (latest()) {
                isLoading = false
                directProgress = null
            }
        }
    }

    private fun fallbackToStale(direct: Boolean, campus: String, buildings: Collection<String>, date: String, reason: String): Boolean {
        val stale = source.readStaleRooms(campus, buildings.toSet(), date, direct) ?: run {
            staleNote = null
            return false
        }
        rooms = stale.first.sortedBy { it.name }
        staleNote = "数据可能不是最新 · 缓存于今天 ${hhmm(stale.second)} · $reason"
        return true
    }

    /** `SimpleDateFormat("HH:mm")` 是 JVM 专属 ⇒ 手写补零（:core 的既有做法）。 */
    private fun hhmm(millis: Long): String {
        val at = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    }

    private companion object {
        const val KEY_CAMPUS = "empty_room_last_campus"
    }
}
