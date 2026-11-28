package com.xjtu.toolbox.ss

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.nowOnCampus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SsActivityViewModel(site: SiteSession) : ViewModel() {
    private val api = SsActivityApi(site)

    var keyword by mutableStateOf("")
    var order by mutableStateOf(SsActivityApi.Order.TIME); private set
    var activities by mutableStateOf<List<SsActivity>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var endReached by mutableStateOf(false); private set
    private var page = 1
    private var loadJob: Job? = null

    var mine by mutableStateOf<List<SsMyApply>?>(null); private set
    var mineError by mutableStateOf<String?>(null); private set

    /** 打开着的详情；[detailLoading] 时详情还在拉。 */
    var detailId by mutableStateOf<String?>(null); private set
    var detail by mutableStateOf<SsActivityDetail?>(null); private set
    var detailError by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var toast by mutableStateOf<String?>(null)

    private val expired = Channel<Unit>(Channel.CONFLATED)
    val authExpired = expired.receiveAsFlow()

    init { reload() }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun message(e: Throwable): String {
        if (e is AuthExpiredException) expired.trySend(Unit)
        return e.message?.takeIf { it.isNotBlank() } ?: "加载失败"
    }

    /** 「集体活动」：按讲座类关键词合并搜出未结束的活动（#465 的本意），一次给全、不分页。 */
    var groupProof by mutableStateOf(false); private set

    fun sortBy(o: SsActivityApi.Order) { if (o != order || groupProof) { order = o; groupProof = false; reload() } }

    fun showGroupProof() { if (!groupProof) { groupProof = true; keyword = ""; reload() } }

    fun search() { groupProof = false; reload() }

    fun reload(silent: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (silent) refreshing = true else loading = true
            error = null
            runCatching { io { if (groupProof) api.groupProofCandidates() else api.list(keyword, order, 1, PAGE) } }
                .onSuccess { activities = it.distinctBy { a -> a.id }; page = 1; endReached = groupProof || it.size < PAGE }
                .onFailure { error = message(it) }
            loading = false; refreshing = false
        }
    }

    fun loadMore() {
        if (endReached || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            runCatching { io { api.list(keyword, order, page + 1, PAGE) } }
                .onSuccess { more ->
                    page++
                    endReached = more.size < PAGE
                    val seen = activities.mapTo(HashSet()) { it.id }
                    activities = activities + more.filter { seen.add(it.id) }
                }
                .onFailure { toast = message(it) }
        }
    }

    var mineRefreshing by mutableStateOf(false); private set

    /** [refresh] 为下拉刷新：已有列表时不清空，只转圈。 */
    fun loadMine(refresh: Boolean = false) {
        viewModelScope.launch {
            if (refresh) mineRefreshing = true
            mineError = null
            runCatching { io { api.myApplies() } }
                .onSuccess { mine = it }
                .onFailure { mineError = message(it) }
            mineRefreshing = false
        }
    }

    /** 去网页办理（团体报名、附件报名，或在网页上取消）的活动；回到本页时据此重拉状态（#553）。 */
    private var webPendingId: String? = null

    fun markWebPending() {
        webPendingId = detailId
    }

    fun refreshAfterWeb() {
        val id = webPendingId ?: return
        webPendingId = null
        if (mine != null) loadMine(refresh = true)
        viewModelScope.launch {
            runCatching { io { api.detail(id) } }.onSuccess { record(it); if (detailId == id) detail = it }
        }
    }

    fun open(id: String) {
        detailId = id; detail = null; detailError = null
        viewModelScope.launch {
            runCatching { io { api.detail(id) } }
                .onSuccess { record(it); if (detailId == id) detail = it }
                .onFailure { if (detailId == id) detailError = message(it) }
        }
    }

    fun close() { detailId = null; detail = null }

    private fun act(done: String, block: suspend (String) -> Unit) {
        val id = detailId ?: return
        viewModelScope.launch {
            busy = true
            runCatching { io { block(id) } }
                .onSuccess {
                    toast = done
                    runCatching { io { api.detail(id) } }.onSuccess { if (detailId == id) detail = it }
                    loadMine()
                }
                .onFailure { toast = message(it) }
            busy = false
        }
    }

    fun apply() {
        val d = detail ?: return
        d.applyBlocker(nowOnCampus())?.let { toast = it; return }
        act("报名成功${if (d.needReview) "，等待审核" else ""}") { api.apply(it) }
    }

    fun cancel() = act("已取消报名") { api.cancel(it) }

    fun signIn(code: String) = act("签到 / 签退成功") { api.signIn(it, code) }

    /** 封面缩略图：按地址缓存解码结果（含取不到的 null），最多留 120 张。 */
    private val covers = object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 120
    }

    /** 列表卡片按详情补的信息：列表接口不带正文，明写的标签和是否要报名只能从详情拿。 */
    data class CardInfo(val explicitTags: List<String>, val needApply: Boolean)

    val cardInfo = mutableStateMapOf<String, CardInfo>()
    private val prefetchGate = Semaphore(3)
    private val prefetching = HashSet<String>()

    /** 卡片出现时调：后台拉一次详情（不调浏览记分接口），最多同时 3 个。 */
    fun prefetch(id: String) {
        if (id in cardInfo || !prefetching.add(id)) return
        viewModelScope.launch {
            prefetchGate.withPermit {
                runCatching { io { api.detail(id) } }.onSuccess { record(it) }
            }
            prefetching.remove(id)
        }
    }

    private fun record(d: SsActivityDetail) {
        cardInfo[d.base.id] = CardInfo(d.explicitTags, d.needApply)
    }

    fun cachedCover(url: String): ImageBitmap? = covers[url]

    suspend fun cover(url: String): ImageBitmap? {
        if (covers.containsKey(url)) return covers[url]
        val bitmap = io { api.image(url)?.let(::decodeThumb) }
        covers[url] = bitmap
        return bitmap
    }

    /** 按长边约 480px 降采样，列表里的小图不必解整张原图。 */
    private fun decodeThumb(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
    }

    companion object { const val PAGE = 20 }
}
