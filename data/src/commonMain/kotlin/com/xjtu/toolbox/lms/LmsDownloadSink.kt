package com.xjtu.toolbox.lms

import java.io.OutputStream

/**
 * 思源学堂**落盘/回放队列的宿主槽位** —— `:core` 的 [LmsSource] 把「取数」与「落到哪儿」切开之后，
 * 这一半由各端自己填（Android 是 MediaStore + 「我的 · 下载管理」，桌面写用户下载目录）。
 *
 * ## 为什么槽位在 `:data` 而不是 `:core`
 *
 * 落盘要写一个流：[write] 的参数是 `OutputStream` —— JVM 类型，`:core` 还要编到 wasm，
 * 放不下；`:data` 只有 jvm + android 两个目标（`data/build.gradle.kts` 的文件头写了原因），
 * 所以收在这里。`:core` 的屏只看得见 [LmsSource] 的那几个高层方法（字节从哪里来、落到哪里去
 * 都是实现方的事），不摸这个槽位。
 *
 * ## 各端的实现
 *
 *  - `AppLmsSink`（`:app`）= 搬迁前写在屏上的 `saveToDownloads` / `LmsBatchDownload.save`
 *    （MediaStore 写「下载/XJTUToolBox/」、`LmsDownloadStore` 登记）+ `media.DownloadManager`
 *    的回放排队，**方法体原样搬出**；
 *  - `DesktopLmsSaver`（`:desktop`）= 写 `~/Downloads/<课程名>/…`；没有下载管理那一屏 ⇒
 *    [supportsVideoQueue] = false、[queueReplayVideos] 不实现（恒 0）。
 */
interface LmsDownloadSink {

    /**
     * 「下载到哪儿」的展示前缀（批量下载对话框与结果卡上那句话）。本端各自报真实位置：
     * Android = `Download/XJTUToolBox/`（与搬迁前那句逐字一致），桌面 = `~/Downloads/`。
     */
    fun publicPathLabel(): String

    /** 本端能不能把「课堂回放」排进下载队列；false 时批量对话框不露出那一档。 */
    val supportsVideoQueue: Boolean

    /**
     * 把 [write] 产出的字节落到本端下载位置并（如有）登记。
     *
     * [folder] 是课程目录下的相对路径（`课程名` 或 `课程名/活动名`；空串 = 直接放下载根目录）。
     * [write] 从上游拉字节（调用方给的 `LmsApi` 那一份取数），本方法只负责「落到哪儿」——
     * 搬迁前的两处落盘（单文件 `saveToDownloads` 的根目录、批量 `LmsBatchDownload.save` 的
     * `下载/XJTUToolBox/<folder>`）在 Android 实现里保持各自的 RELATIVE_PATH 拼法。
     */
    suspend fun save(
        name: String,
        mimeType: String,
        folder: String,
        write: suspend (OutputStream) -> LmsDownloadResult,
    ): LmsDownloadResult

    /** 把回放视频排进本端下载队列，返回排进去的条数；本端不支持时恒 0。 */
    suspend fun queueReplayVideos(
        courseName: String,
        activityTitle: String,
        activityId: Int,
        videos: List<LmsReplayVideo>,
    ): Int

    companion object {
        /**
         * 没有宿主的缺省槽位：任何落盘都失败、不支持回放队列。
         *
         * 只给「屏在但没人给它装配 sink」的中间态兜底 —— 真装机时两端都会装上自己的实现
         *（AppNavHost / ToolboxDesktopApp），走到这里说明装配漏了，行为是「下载失败」而不是崩。
         */
        val Unsupported = object : LmsDownloadSink {
            override fun publicPathLabel(): String = "下载/"
            override val supportsVideoQueue: Boolean = false
            override suspend fun save(
                name: String,
                mimeType: String,
                folder: String,
                write: suspend (OutputStream) -> LmsDownloadResult,
            ): LmsDownloadResult = LmsDownloadResult.Failed
            override suspend fun queueReplayVideos(
                courseName: String,
                activityTitle: String,
                activityId: Int,
                videos: List<LmsReplayVideo>,
            ): Int = 0
        }
    }
}