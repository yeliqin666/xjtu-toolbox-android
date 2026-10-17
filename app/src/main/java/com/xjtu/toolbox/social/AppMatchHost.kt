package com.xjtu.toolbox.social

import android.content.Context
import com.xjtu.toolbox.card.CampusCardCache
import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.hello.HelloProfile
import com.xjtu.toolbox.hello.HelloProfileStore
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 匹配交友在 Android 上的宿主面 —— 这一端有什么（见 `:core` 的 [MatchHost]）。 */
internal class AppMatchHost(private val context: Context) : MatchHost {

    /** 与 `AppScheduleHost.cache` 同一个 `DataCache`（`accountId` 为 null = 当前账号）。 */
    override fun cache(accountId: String?): ScheduleCacheStore = DataCache(context, accountId)

    override fun identity(): MatchIdentity? = HelloProfileStore.cached(context)?.toMatchIdentity()

    override fun transactions(): List<Transaction> =
        CampusCardCache.load(context)?.transactions.orEmpty()

    override val io: CoroutineDispatcher = Dispatchers.IO
}

/** `HelloProfile` → [MatchIdentity]：只挑匹配交友真读的那几项。 */
private fun HelloProfile.toMatchIdentity(): MatchIdentity = MatchIdentity(
    name = name,
    sno = sno,
    grade = grade,
    professionName = professionName,
    departmentName = departmentName,
    academyName = academyName,
    campusName = campusName,
    className = className,
)
