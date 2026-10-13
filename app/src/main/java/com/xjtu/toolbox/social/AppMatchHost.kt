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

/**
 * 匹配交友在 Android 上的**宿主面** —— 这一端有什么（见 `:core` 的 [MatchHost]）。
 *
 * 三件东西与搬迁前**逐字相同**，只是从屏里挪到了这里：
 *
 * | 那一件 | 搬迁前写在屏里 | 这里 |
 * |---|---|---|
 * | 课表族缓存 | `MatchData.read(ctx)` 里的 `DataCache(ctx)` | 同一个 `DataCache`（**按账号那一层没动**，`:app` 的日程页读的也是它） |
 * | 学工档案 | `HelloProfileStore.cached(ctx)` | 同一个对象；只把屏真读的那几项挑成 [MatchIdentity]（见它的 KDoc） |
 * | 校园卡消费 | `DiningHabit.readCached*` 里的 `CampusCardCache.load(ctx)` | 同一个 `CampusCardCache`（同一份 SharedPreferences 快照；没进过校园卡页就是空表） |
 *
 * 调度器照 `:app` 的 `AppScheduleHost` 覆写回 `Dispatchers.IO`（搬迁前那三处读缓存就是它）。
 * 扫码（相机 / 相册）不在这一格里：它是**屏的一层覆盖**，见 [AppMatchScreen] 里的 [MatchScanner] 实现。
 */
internal class AppMatchHost(private val context: Context) : MatchHost {

    /** 与 `AppScheduleHost.cache` 同一个 `DataCache`（`accountId` 为 null = 当前账号）。 */
    override fun cache(accountId: String?): ScheduleCacheStore = DataCache(context, accountId)

    override fun identity(): MatchIdentity? = HelloProfileStore.cached(context)?.toMatchIdentity()

    override fun transactions(): List<Transaction> =
        CampusCardCache.load(context)?.transactions.orEmpty()

    override val io: CoroutineDispatcher = Dispatchers.IO
}

/**
 * `HelloProfile` → [MatchIdentity]：只挑匹配交友真读的那几项。
 *
 * 字段名与来源一一对应（`name` / `sno` / `grade` / `professionName` / `departmentName` /
 * `academyName` / `campusName` / `className`），**没有做任何加工**——搬迁前 `MatchProfile.build`
 * 就是直接读 `HelloProfile` 上这几个字段的，所以行为不变。
 */
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
