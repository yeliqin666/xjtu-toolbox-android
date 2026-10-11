package com.xjtu.toolbox.lms

import com.xjtu.toolbox.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 思源学堂批量下载 —— 编排从 `:app` 搬进 `:core`（活动列表页的「批量下载」按钮走它），
 * 两个动作切到 [LmsSource] 端口：拉详情/收集是取数（`:data` 的 `AppLmsSource`）、
 * 落盘与回放排队是宿主槽位（`:app` 的 `AppLmsSink` / 桌面 `DesktopLmsSaver`）。
 *
 * 流程照搬 yan-xiaoo/XJTUToolBox 的 `LMSBatchDownloadThread`（与搬迁前逐行同构）：
 * 选中若干活动 → 逐个拉详情收集附件 → 限并发下载（CONCURRENCY=3）。
 *
 * 和桌面版不同的地方（照旧）：
 * - 不让选目录。Android 上统一落在公共「下载/XJTUToolBox/课程名/」，按活动存放时再分一层活动名，
 *   走 MediaStore，不要存储权限，系统文件管理器里直接看得到（那是 `AppLmsSink` 的事）；
 * - 回放视频不在这里下：动辄几百 MB，交给已有的下载管理（能暂停、断点续传、看进度）——
 *   桌面端没有那个队列 ⇒ `supportsVideoQueue = false`，对话框不露出那一档；
 * - 任务挂在进程级作用域上，离开页面不中断，回到页面接着看进度。
 */
object LmsBatchDownload {

    private const val TAG = "LmsBatchDownload"
    private const val CONCURRENCY = 3

    data class Options(
        /** true：每个活动一个子文件夹；false：全放在课程文件夹下。 */
        val perActivityFolder: Boolean = true,
        /** 活动附件（课件本身、作业题目附件）。 */
        val uploads: Boolean = true,
        /** 我提交的作业文件。 */
        val submissions: Boolean = true,
        /** 老师批阅后的标注文件。 */
        val marked: Boolean = false,
        /** 课程回放：加入本端下载队列（Android 有；桌面不支持 ⇒ 由 [LmsSource.supportsVideoQueue] 挡在对话框外）。 */
        val replays: Boolean = false,
    )

    sealed interface State {
        data object Idle : State
        data class Collecting(val courseName: String, val done: Int, val total: Int, val current: String) : State
        data class Downloading(
            val courseName: String,
            val done: Int,
            val total: Int,
            val failed: Int,
            val current: String,
        ) : State
        data class Finished(
            val courseName: String,
            val ok: Int,
            val failed: Int,
            /** 学堂已关闭下载（活动结束后 /blob 403）的文件数，单独报，免得用户以为是网不好。 */
            val forbidden: Int,
            val videosQueued: Int,
            val cancelled: Boolean,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // 进程级作用域。搬到 `:core` 后不能用 `Dispatchers.IO`（wasm 没有它）：编排在 Default 上跑，
    // 阻塞式取数/落盘由端口实现各自包 IO（见 [LmsSource] 的 KDoc），行为不变。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun cancel() { job?.cancel() }

    fun dismiss() { if (!isRunning) _state.value = State.Idle }

    fun start(
        source: LmsSource,
        courseName: String,
        activities: List<LmsActivity>,
        options: Options,
    ) {
        if (isRunning || activities.isEmpty()) return
        job = scope.launch {
            val course = sanitize(courseName)
            var cancelled = false
            var ok = 0
            var failed = 0
            var forbidden = 0
            var videos = 0
            try {
                // ① 收集
                val files = mutableListOf<LmsDownloadJob>()
                val videoJobs = mutableListOf<Pair<LmsActivity, List<LmsReplayVideo>>>()
                activities.forEachIndexed { i, brief ->
                    if (!isActive) return@forEachIndexed
                    _state.value = State.Collecting(courseName, i, activities.size, brief.title)
                    val detail = runCatching { source.activityDetail(brief.id, brief) }
                        .onFailure { Log.w(TAG, "detail ${brief.id} failed", it) }
                        .getOrNull() ?: return@forEachIndexed
                    val folder = if (options.perActivityFolder) "$course/${sanitize(detail.title)}" else course
                    collect(detail, folder, options, files)
                    if (options.replays && detail.type == LmsActivityType.LESSON && source.supportsVideoQueue) {
                        val downloadable = detail.replayVideos.filter {
                            it.downloadUrl.isNotBlank() && !it.downloadUrl.contains(".m3u8", true)
                        }
                        if (downloadable.isNotEmpty()) videoJobs += detail to downloadable
                    }
                }

                // 同一个文件夹里重名的加序号，免得后一个把前一个顶掉（系统会自动改名成 xx (1)，但登记的记录会乱）
                val unique = dedupe(files)

                // ② 下载附件
                var done = 0
                val total = unique.size
                _state.value = State.Downloading(courseName, 0, total, 0, "")
                // 并发计数的那把锁：`synchronized` 是 JVM 专属，wasm 编不过 ⇒ 换 Mutex（同一互斥语义）
                val gate = Semaphore(CONCURRENCY)
                val lock = Mutex()
                coroutineScope {
                    unique.map { f ->
                        async {
                            gate.withPermit {
                                if (!isActive) return@withPermit
                                _state.update { s -> if (s is State.Downloading) s.copy(current = f.name) else s }
                                val r = source.saveDownload(f)
                                lock.withLock {
                                    done++
                                    when (r) {
                                        LmsDownloadResult.Ok -> ok++
                                        LmsDownloadResult.Forbidden -> { forbidden++; failed++ }
                                        LmsDownloadResult.Failed -> failed++
                                    }
                                    _state.value = State.Downloading(courseName, done, total, failed, f.name)
                                }
                            }
                        }
                    }.awaitAll()
                }

                // ③ 回放交给本端的下载队列
                videoJobs.forEach { (act, list) ->
                    runCatching {
                        source.queueReplayVideos(courseName, act.title, act.id, list)
                    }.onSuccess { videos += it }
                        .onFailure { Log.w(TAG, "enqueue replay ${act.id} failed", it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
            } finally {
                _state.value = State.Finished(courseName, ok, failed, forbidden, videos, cancelled)
            }
        }
    }

    internal fun collect(detail: LmsActivity, folder: String, options: Options, out: MutableList<LmsDownloadJob>) {
        when (detail.type) {
            LmsActivityType.HOMEWORK -> {
                if (options.uploads) detail.uploads.forEach { out += LmsDownloadJob(it.name, it.type, folder, upload = it) }
                val subs = detail.submissionList
                if (options.submissions && subs != null) {
                    (subs.uploads + subs.list.flatMap { it.uploads })
                        .distinctBy { it.id to it.name }
                        .forEach { out += LmsDownloadJob("提交_${it.name}", it.type, folder, upload = it.copy(attachmentUrl = "")) }
                }
                if (options.marked && subs != null) {
                    subs.list.flatMap { it.uploads }.filter { it.attachmentUrl.isNotBlank() }.forEach {
                        // 搬迁前这批的 mime 是「没有 upload ⇒ 按扩展名猜」，所以这里不把原类型带过去（mimeType 空，
                        // 由落盘实现猜 —— 与 `LmsBatchDownload.save` 里的 `guessMime(f.name)` 同一个口径）。
                        out += LmsDownloadJob(markedName(it.name), "", folder, url = it.attachmentUrl)
                    }
                }
            }
            else -> if (options.uploads) detail.uploads.forEach { out += LmsDownloadJob(it.name, it.type, folder, upload = it) }
        }
    }

    private fun markedName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "批阅_${name.substring(0, dot)}_批阅${name.substring(dot)}" else "批阅_${name}_批阅"
    }

    internal fun dedupe(files: List<LmsDownloadJob>): List<LmsDownloadJob> {
        // 计数不能用 `HashMap.merge`（Java Map 的 JVM 专属方法，wasm 编不过）⇒ 手写同语义的累加
        val seen = HashMap<String, Int>()
        return files.map { f ->
            val name = sanitize(f.name)
            val key = "${f.folder}/$name".lowercase()
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            if (n == 1) f.copy(name = name)
            else {
                val dot = name.lastIndexOf('.')
                f.copy(name = if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)")
            }
        }
    }

    /** 文件名里不能有的字符换成下划线，首尾的空白和点去掉。 */
    internal fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), "_").trim().trim('.').ifBlank { "file" }.take(120)
}