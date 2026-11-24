package com.xjtu.toolbox.media

/**
 * 「文件类下载」（电子成绩单 / 思源课件 / 资料站文件）的宿主端口。
 *
 * 除了「我的-下载管理」那一屏，思源学堂的附件卡也用它：
 * **一份资料如果已经下过且文件相同，点它就不再重复下载，改成打开**（打开走的正是
 * [open]，与下载管理里点那一条同一条路）。判据见 [findDownloaded]。
 */
interface DownloadedFilesHost {

    /**
     * 这一屏此刻服务的是哪个账号（`null` = 本端只有一份，不随账号变）。
     *
     * 它不参与「列哪些文件」，只给共享屏当**重读的钥匙**：桌面 `DesktopDownloads` 是按账号一份
     * 管理器，换账号之后下载页那份任务列表必须整套重读（`DownloadManagerScreen` 的 `LaunchedEffect`
     * key 里带着它）。不实现这一格的端行为与本槽位出现之前一模一样。
     */
    val accountId: String? get() = null

    /** 当前登记的那些文件（按保存时间倒序）。 */
    suspend fun list(): List<DownloadedFile>

    /**
     * 找一份「已经下过、内容和 [name] / [size] 对得上」的文件；没有返回 null。
     *
     * 思源附件卡据此把「点一下就再下一份」改成「点一下就打开」。判据是**同名 + 同大小**：
     * - 只比名字太松 —— 老师把课件换成新版时文件名常常不变，那时该重新下；
     * - 只比大小会被改过名的同一份文件骗过 —— 同一份附件在不同活动里可以叫不同名字。
     *
     * [size] 为 0（老接口没给字节数）时退化成只比名字；登记里 `size` 为 0 的那条同理。
     *
     * 默认实现返回 null（= 这一端没有登记表，如实说不认识），行为与这个能力出现之前一样：
     * 点了就下。驾了登记表的端（Android / 桌面）覆盖它。
     */
    suspend fun findDownloaded(name: String, size: Long): DownloadedFile? = null

    /** 打开一个：Android = `ACTION_VIEW` 交给系统选应用。 */
    fun open(record: DownloadedFile)

    /** 删除一个：Android = 删掉 MediaStore 里那条文件 + 撤销登记。 */
    suspend fun delete(record: DownloadedFile)
}

/**
 * 一条登记能不能算「就是这份资料」。
 *
 * 抽成纯函数是为了能被单测钉住：这条判据判错的两个方向都有代价 ——
 * 判松了会把「老师换了新版课件」当成已下载，用户点开看到的是旧内容；
 * 判紧了就退回「每次点都下一份重复文件」，正是这次要修的那个毛病。
 */
fun DownloadedFile.matchesUpload(name: String, size: Long): Boolean {
    if (this.name != name) return false
    if (size <= 0 || this.size <= 0) return true
    return this.size == size
}
