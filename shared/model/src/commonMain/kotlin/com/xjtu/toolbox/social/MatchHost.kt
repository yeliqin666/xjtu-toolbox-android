package com.xjtu.toolbox.social

import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 匹配交友的宿主面 —— 「这一端有什么」，而不是「这一端是不是 Android」。 */
interface MatchHost {

    /** 课表族的本地缓存（与日程页**同一份**）。[accountId] 为 null 表示「当前账号」。 */
    fun cache(accountId: String?): ScheduleCacheStore

    /** 学工系统的个人档案里，匹配交友用得上的那几项。 */
    fun identity(): MatchIdentity?

    /** 校园卡流水快照里已经落盘的那些记录。 */
    fun transactions(): List<Transaction>

    /** 阻塞 IO 的调度器。 */
    val io: CoroutineDispatcher get() = Dispatchers.Default
}

/** 匹配交友用得上的那几项学籍信息。 */
data class MatchIdentity(
    val name: String = "",
    val sno: String = "",
    val grade: Int = 0,
    val professionName: String = "",
    val departmentName: String = "",
    val academyName: String = "",
    val campusName: String = "",
    val className: String = "",
)

/** 扫码那一档 —— 相机实时取景、以及「从相册（桌面是文件）选一张图识别」。 */
interface MatchScanner {

    /** 打开取景，扫到就返回。用户取消 / 没扫到分别见 [MatchScanOutcome]。 */
    suspend fun scan(): MatchScanOutcome

    /** 让用户选一张图（相册 / 文件），从里面读二维码。 */
    suspend fun fromImage(): MatchScanOutcome
}

/** 一次扫码 / 选图的结果。 */
data class MatchScanOutcome(val code: String = "", val reason: String = "")
