package com.xjtu.toolbox.lms

import java.io.OutputStream

/** 思源学堂落盘/回放队列的宿主槽位。 */
interface LmsDownloadSink {

    /** 「下载到哪儿」的展示前缀（批量下载对话框与结果卡上那句话）。 */
    fun publicPathLabel(): String

    /** 本端能不能把「课堂回放」排进下载队列；false 时批量对话框不露出那一档。 */
    val supportsVideoQueue: Boolean

    /** 把 [write] 产出的字节落到本端下载位置并（如有）登记。 */
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
        /** 没有宿主的缺省槽位：任何落盘都失败、不支持回放队列。 */
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