package com.xjtu.toolbox.media

/** 「文件类下载」（电子成绩单 / 思源课件 / 资料站文件）的宿主端口。 */
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

    /** 打开一个：Android = `ACTION_VIEW` 交给系统选应用。 */
    fun open(record: DownloadedFile)

    /** 删除一个：Android = 删掉 MediaStore 里那条文件 + 撤销登记。 */
    suspend fun delete(record: DownloadedFile)
}
