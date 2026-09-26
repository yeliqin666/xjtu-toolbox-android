package com.xjtu.toolbox.emptyroom

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.JsSession
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 空教室：数据源（实时状态 / CDN 课表 / 直查教务）× 校区 × 楼 × 日期，任一变化就取消旧查询重查。
 * 实时状态一个校区一次请求，楼只在本地筛；课表数据按楼查，单楼结果当天内存缓存。
 */
internal class EmptyRoomViewModel(
    context: Context,
    private val sessionManager: SessionManager?,
    private val accountType: AccountType?,
) : ViewModel() {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("empty_room", 0)
    private val api = EmptyRoomApi(this.context)
    private val uncachedApi = EmptyRoomApi()
    private val cache = EmptyRoomCache(this.context)
    private val liveApi = sessionManager?.getSiteOrNull(JsSession.SITE_KEY)?.let { LiveRoomApi(it, cache) }

    var rooms by mutableStateOf<List<RoomInfo>>(emptyList()); private set
    var isLoading by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    /** 联网失败改显示磁盘缓存时的说明；null 表示显示的是实时数据。 */
    var staleNote by mutableStateOf<String?>(null); private set
    var directProgress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var liveSnapshot by mutableStateOf<LiveSnapshot?>(null); private set
    /** 当天课表（CDN），按教室名对上实时状态，画节次条用；拿不到就不画。 */
    var liveSchedule by mutableStateOf<Map<String, RoomInfo>>(emptyMap()); private set

    var source by mutableStateOf(initialSource()); private set
    val isLive get() = source == RoomSource.LIVE

    // 校区按名字记：实时状态只有三个校区，两套列表的下标对不上
    private var campusName by mutableStateOf(prefs.getString(KEY_CAMPUS, null) ?: CAMPUS_BUILDINGS.keys.first())
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

    val availableDates: List<String> = api.getAvailableDates()
    var selectedDate by mutableStateOf(availableDates.firstOrNull().orEmpty()); private set

    /** 单楼结果：key = "source|campus|building|date"，value = (缓存那天, 教室)。 */
    private val buildingCache = mutableMapOf<String, Pair<String, List<RoomInfo>>>()
    private var queryJob: Job? = null
    private var generation = 0

    init { query() }

    private fun initialSource(): RoomSource {
        val saved = RoomSource.entries.firstOrNull { it.key == prefs.getString(SOURCE_PREF_KEY, null) }
        return when {
            saved == null -> RoomSource.LIVE
            saved == RoomSource.DIRECT && accountType == AccountType.POSTGRADUATE -> RoomSource.CDN
            else -> saved
        }
    }

    private fun savedBuildings(campus: String): Set<String> {
        val all = CAMPUS_BUILDINGS[campus].orEmpty()
        return prefs.getString("empty_room_last_buildings_$campus", null)
            ?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() && it in all }?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(all.firstOrNull().orEmpty())
    }

    private fun savedLiveBuildings(campus: String): Set<String> =
        prefs.getString("empty_room_live_buildings_$campus", null)
            ?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun selectSource(value: RoomSource) {
        val next = if (value == RoomSource.DIRECT && accountType == AccountType.POSTGRADUATE) RoomSource.CDN else value
        if (next == source) return
        source = next
        prefs.edit().putString(SOURCE_PREF_KEY, next.key).apply()
        errorMessage = null
        staleNote = null
        campusChanged()
        query()
    }

    fun selectCampus(index: Int) {
        campusName = campusNames.getOrElse(index) { campus }
        prefs.edit().putString(KEY_CAMPUS, campusName).apply()
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
            prefs.edit().putString("empty_room_live_buildings_$campus", liveSelected.joinToString("|")).apply()
        } else {
            selectedBuildings = value
            prefs.edit()
                .putString(KEY_CAMPUS, campus)
                .putString("empty_room_last_buildings_$campus", value.filter { it.isNotBlank() }.joinToString("|"))
                .apply()
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
                Log.d(TAG, "query gen=$gen superseded")
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
            val live = liveApi ?: throw RuntimeException("实时状态暂不可用，可在右上角切换到课表数据")
            val snapshot = withContext(Dispatchers.IO) {
                val mgr = sessionManager ?: throw RuntimeException("实时状态暂不可用")
                if (mgr.credentials == null) throw RuntimeException("实时状态需要先登录统一身份认证，未登录可在右上角切换到 CDN 课表")
                mgr.ensureSite(JsSession.SITE_KEY, userInitiated = true)
                live.fetchCampus(campus, if (force) 0L else LiveRoomApi.FRESH_MS)
            }
            if (!latest()) return
            liveSnapshot = snapshot
            staleNote = null
            // 当天课表只做点缀：拿不到照样显示实时状态
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val schedule = withContext(Dispatchers.IO) {
                runCatching { api.getEmptyRoomsMulti(campus, snapshot.buildings.toSet(), today).associateBy { it.name } }
                    .getOrDefault(emptyMap())
            }
            if (latest()) liveSchedule = schedule
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (latest()) {
                val stale = liveApi?.readStale(campus)
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
        val direct = source == RoomSource.DIRECT
        val sourceKey = if (direct) "direct" else "cdn"
        val active = selectedBuildings.filter { it.isNotEmpty() }.toSet()
        if (active.isEmpty()) {
            rooms = emptyList()
            isLoading = false
            directProgress = null
            return
        }
        // 命中单楼缓存的不再请求
        val cacheDay = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
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
            val (result, fetched) = withContext(Dispatchers.IO) {
                if (direct) {
                    // 入口不先登教务（默认数据源是实时状态），切到直查时才登
                    val mgr = sessionManager ?: throw RuntimeException("直查教务暂不可用，可切换到 CDN 缓存")
                    if (mgr.credentials == null) throw RuntimeException("直查教务需要先登录，未登录可切换到 CDN 缓存")
                    val client = mgr.ensureSite(LoginType.JWXT, userInitiated = true).client
                    val query = if (force) EmptyRoomDirectQuery(client) else EmptyRoomDirectQuery(client, cache)
                    val merged = cachedRows.toMutableList()
                    val fetched = mutableListOf<Pair<String, List<RoomInfo>>>()
                    toFetch.forEachIndexed { idx, building ->
                        if (!latest()) throw CancellationException("superseded")
                        try {
                            val rows = query.queryDay(campus, building, date) { period, total ->
                                if (latest()) {
                                    val progress = (idx * total + period) to (toFetch.size * total)
                                    viewModelScope.launch { if (latest()) directProgress = progress }
                                }
                            }
                            fetched.add(building to rows)
                            merged.addAll(rows)
                        } catch (e: NoDataException) {
                            Log.w(TAG, "direct skip $building: ${e.message}")
                        }
                    }
                    merged.sortedBy { it.name } to fetched
                } else {
                    val cdn = if (force) uncachedApi else api
                    cdn.getEmptyRoomsMulti(campus, active, date) to emptyList<Pair<String, List<RoomInfo>>>()
                }
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
                errorMessage = e.message
                rooms = emptyList()
                staleNote = null
            }
        } catch (e: Exception) {
            // 有缓存就显示缓存（顶部黄条写明原因），没有才整页报错
            if (latest()) {
                val reason = rawError(e)
                errorMessage = if (fallbackToStale(sourceKey, campus, active, date, reason)) null else reason
            }
        } finally {
            if (latest()) {
                isLoading = false
                directProgress = null
            }
        }
    }

    private fun fallbackToStale(sourceKey: String, campus: String, buildings: Collection<String>, date: String, reason: String): Boolean {
        val merged = mutableListOf<RoomInfo>()
        var newest = 0L
        for (b in buildings) {
            val key = "$sourceKey|$campus|$b|$date"
            cache.readRoomListStale(key)?.let { stale ->
                merged.addAll(stale)
                newest = maxOf(newest, cache.savedAt(key))
            }
        }
        if (merged.isEmpty()) {
            staleNote = null
            return false
        }
        rooms = merged.sortedBy { it.name }
        staleNote = "数据可能不是最新 · 缓存于今天 ${hhmm(newest)} · $reason"
        return true
    }

    private fun hhmm(millis: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(millis)

    private companion object {
        const val TAG = "EmptyRoomViewModel"
        const val KEY_CAMPUS = "empty_room_last_campus"
    }
}
