package com.xjtu.toolbox.media

/**
 * 「文件类下载」（电子成绩单 / 思源课件 / 资料站文件）的**宿主端口** —— [DownloadManagerScreen]
 * 上那另外一半列表：它们**不排队、不看进度**，下载完就落到本端的下载位置、由系统文件管理器管，
 * 屏上只列出来 + 能打开 + 能删。
 *
 * ## 各端实现
 *
 *  - **Android（`:app`）**：`AppDownloadedFilesHost` —— 搬迁前屏上直接调的 `LmsDownloadStore`
 *    （SharedPreferences 登记 + MediaStore 里发现真实文件）、`Intent.ACTION_VIEW` 打开、
 *    `contentResolver.delete` 删除，方法体原样搬出；
 *  - **桌面（`:desktop`）**：传 **null**。桌面端的落盘实现（`DesktopLmsSaver` /
 *    `DesktopTranscriptSaver`）只把文件写进 `~/Downloads`，**没有**那张登记表 ⇒ 屏上不出现
 *    「文件类下载」那几栏。这是如实的缺口，不是列表被吞了：GUI 里那一类条目本来就不存在
 *    （用户在文件管理器里看 `~/Downloads`）。TODO：要给桌面做登记表的话，就在这一层加一份
 *    按 `savedAt` 落盘的 JSON（与 `DesktopDownloadTaskStore` 同一个套路）。
 */
interface DownloadedFilesHost {

    /** 当前登记的那些文件（按保存时间倒序）。屏每次刷新都调它（与搬迁前的 `loadTasks()` 同频）。 */
    suspend fun list(): List<DownloadedFile>

    /** 打开一个：Android = `ACTION_VIEW` 交给系统选应用。 */
    fun open(record: DownloadedFile)

    /** 删除一个：Android = 删掉 MediaStore 里那条文件 + 撤销登记。 */
    suspend fun delete(record: DownloadedFile)
}
