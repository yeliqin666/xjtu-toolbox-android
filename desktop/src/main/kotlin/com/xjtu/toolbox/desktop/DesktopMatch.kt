package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import com.xjtu.toolbox.social.MatchHost
import com.xjtu.toolbox.social.MatchIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 桌面端的匹配交友宿主面（见 [MatchHost]）。 */
internal class DesktopMatchHost(private val auth: DesktopAuth) : MatchHost {

    /** 与 `DesktopScheduleHost.cache` **同一个实现**（同一份键值文件、同一个账号后缀）。 */
    override fun cache(accountId: String?): ScheduleCacheStore =
        DesktopScheduleHost(auth).cache(accountId)

    override fun identity(): MatchIdentity? = null

    override fun transactions(): List<Transaction> = emptyList()

    /** 与 `DesktopScheduleHost.io` 一样：这一端的本地读写就是文件 IO。 */
    override val io: CoroutineDispatcher = Dispatchers.IO
}
