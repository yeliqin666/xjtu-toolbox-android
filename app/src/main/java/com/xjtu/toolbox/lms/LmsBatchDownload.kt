package com.xjtu.toolbox.lms

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/**
 * 思源学堂批量下载。流程照搬 yan-xiaoo/XJTUToolBox 的 `LMSBatchDownloadThread`：
 * 选中若干活动 → 逐个拉详情收集附件 → 限并发下载。
 *
 * 和桌面版不同的地方：
 * - 不让选目录。Android 上统一落在公共「下载/XJTUToolBox/课程名/」，按活动存放时再分一层活动名，
 *   走 MediaStore，不要存储权限，系统文件管理器里直接看得到；
 * - 回放视频不在这里下：动辄几百 MB，交给已有的下载管理（能暂停、断点续传、看进度）；
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
        /** 课程回放：加入下载管理队列。 */
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun cancel() { job?.cancel() }

    fun dismiss() { if (!isRunning) _state.value = State.Idle }

    /** 一个要下的文件。 */
    private data class FileJob(val name: String, val folder: String, val upload: LmsUpload?, val url: String?)

    fun start(
        context: Context,
        api: LmsApi,
        courseName: String,
        activities: List<LmsActivity>,
        options: Options,
    ) {
        if (isRunning || activities.isEmpty()) return
        val app = context.applicationContext
        job = scope.launch {
            val course = sanitize(courseName)
            var cancelled = false
            var ok = 0
            var failed = 0
            var forbidden = 0
            var videos = 0
            try {
                // ① 收集
                val files = mutableListOf<FileJob>()
                val videoJobs = mutableListOf<Pair<LmsActivity, List<LmsReplayVideo>>>()
                activities.forEachIndexed { i, brief ->
                    if (!isActive) return@forEachIndexed
                    _state.value = State.Collecting(courseName, i, activities.size, brief.title)
                    val detail = runCatching { api.getActivityDetail(brief.id, brief) }
                        .onFailure { Log.w(TAG, "detail ${brief.id} failed", it) }
                        .getOrNull() ?: return@forEachIndexed
                    val folder = if (options.perActivityFolder) "$course/${sanitize(detail.title)}" else course
                    collect(detail, folder, options, files)
                    if (options.replays && detail.type == LmsActivityType.LESSON) {
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
                val gate = Semaphore(CONCURRENCY)
                val lock = Any()
                coroutineScope {
                    unique.map { f ->
                        async {
                            gate.withPermit {
                                if (!isActive) return@withPermit
                                _state.update { s -> if (s is State.Downloading) s.copy(current = f.name) else s }
                                val r = save(app, api, f)
                                synchronized(lock) {
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

                // ③ 回放交给下载管理
                videoJobs.forEach { (act, list) ->
                    runCatching {
                        com.xjtu.toolbox.media.DownloadManager.getInstance(app).enqueueDownloads(
                            courseName = courseName,
                            activityTitle = act.title,
                            activityId = act.id,
                            videos = list.map {
                                com.xjtu.toolbox.media.DownloadManager.DownloadItem(
                                    cameraType = if (it.label.contains("instructor", true)) "instructor" else "encoder",
                                    url = it.downloadUrl,
                                )
                            },
                        )
                    }.onSuccess { videos += list.size }
                        .onFailure { Log.w(TAG, "enqueue replay ${act.id} failed", it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
            } finally {
                _state.value = State.Finished(courseName, ok, failed, forbidden, videos, cancelled)
            }
        }
    }

    private fun collect(detail: LmsActivity, folder: String, options: Options, out: MutableList<FileJob>) {
        when (detail.type) {
            LmsActivityType.HOMEWORK -> {
                if (options.uploads) detail.uploads.forEach { out += FileJob(it.name, folder, it, null) }
                val subs = detail.submissionList
                if (options.submissions && subs != null) {
                    (subs.uploads + subs.list.flatMap { it.uploads })
                        .distinctBy { it.id to it.name }
                        .forEach { out += FileJob("提交_${it.name}", folder, it.copy(attachmentUrl = ""), null) }
                }
                if (options.marked && subs != null) {
                    subs.list.flatMap { it.uploads }.filter { it.attachmentUrl.isNotBlank() }.forEach {
                        out += FileJob(markedName(it.name), folder, null, it.attachmentUrl)
                    }
                }
            }
            else -> if (options.uploads) detail.uploads.forEach { out += FileJob(it.name, folder, it, null) }
        }
    }

    private fun markedName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "批阅_${name.substring(0, dot)}_批阅${name.substring(dot)}" else "批阅_${name}_批阅"
    }

    private fun dedupe(files: List<FileJob>): List<FileJob> {
        val seen = HashMap<String, Int>()
        return files.map { f ->
            val name = sanitize(f.name)
            val key = "${f.folder}/$name".lowercase()
            val n = seen.merge(key, 1, Int::plus)!!
            if (n == 1) f.copy(name = name)
            else {
                val dot = name.lastIndexOf('.')
                f.copy(name = if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)")
            }
        }
    }

    /** 文件名里不能有的字符换成下划线，首尾的空白和点去掉。 */
    internal fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>| -]+"""), "_").trim().trim('.').ifBlank { "file" }.take(120)

    private suspend fun save(context: Context, api: LmsApi, f: FileJob): LmsDownloadResult {
        val mime = f.upload?.type?.takeIf { '/' in it } ?: guessMime(f.name)
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${LmsDownloadStore.RELATIVE_PATH}/${f.folder}")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return LmsDownloadResult.Failed
        return try {
            val result = resolver.openOutputStream(uri)?.use { out ->
                when {
                    f.upload != null -> api.downloadUpload(f.upload, out)
                    f.url != null -> if (api.downloadToStream(f.url, out)) LmsDownloadResult.Ok else LmsDownloadResult.Failed
                    else -> LmsDownloadResult.Failed
                }
            } ?: LmsDownloadResult.Failed
            if (result == LmsDownloadResult.Ok) {
                cv.clear()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
                LmsDownloadStore.add(
                    context,
                    LmsDownloadRecord(f.name, mime, uri.toString(), System.currentTimeMillis(), LmsDownloadStore.CATEGORY_LMS),
                )
            } else {
                resolver.delete(uri, null, null)
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "save ${f.name} failed", e)
            runCatching { resolver.delete(uri, null, null) }
            LmsDownloadResult.Failed
        }
    }

    private fun guessMime(name: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}
