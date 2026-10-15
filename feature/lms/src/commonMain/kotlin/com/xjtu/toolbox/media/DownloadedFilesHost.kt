package com.xjtu.toolbox.media

/** 「文件类下载」（电子成绩单 / 思源课件 / 资料站文件）的宿主端口。 */
interface DownloadedFilesHost {

    /** 当前登记的那些文件（按保存时间倒序）。 */
    suspend fun list(): List<DownloadedFile>

    /** 打开一个：Android = `ACTION_VIEW` 交给系统选应用。 */
    fun open(record: DownloadedFile)

    /** 删除一个：Android = 删掉 MediaStore 里那条文件 + 撤销登记。 */
    suspend fun delete(record: DownloadedFile)
}
