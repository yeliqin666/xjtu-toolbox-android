package com.xjtu.toolbox.social

import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 匹配交友的**宿主面** —— 「这一端有什么」，而不是「这一端是不是 Android」。
 *
 * ## 三件东西走这里（其余一律留在共享屏里）
 *
 * 1. **本地落盘**：[cache]。这一屏一条请求都不发，能比什么完全取决于别的页面留下的缓存
 *    —— 课表、考试、教材都在课表族缓存里，而那份缓存在各端是不同的东西（`:app` = `DataCache`
 *    的目录，桌面 = 一份 0600 的键值文件）。这与 `ScheduleHost.cache` 是**同一份**，
 *    口径也相同（见 `:core` 的 `ScheduleHost`）；
 * 2. **只有某些端才有的数据源**：[identity]（学工系统档案）与 [transactions]（校园卡消费）。
 *    两样在 `:app` 上分别来自 `HelloProfileStore` 与 `CampusCardCache`；桌面端没有那两份缓存
 *    ⇒ 如实返回 null / 空表，屏上对应的那一维就禁用并说明原因（不是拿空数据凑一维出来）。
 *
 * ## 语义债
 *
 * 这些动作的本体与搬迁前逐字相同，只是从「直接摸 `Context`」变成「问宿主」：
 * [cache] 就是原来那个 `DataCache(ctx)`、[identity] 就是 `HelloProfileStore.cached(ctx)`、
 * [transactions] 就是 `CampusCardCache.load(ctx)?.transactions`。三个成员都不是新语义。
 */
interface MatchHost {

    /** 课表族的本地缓存（与日程页**同一份**）。[accountId] 为 null 表示「当前账号」。 */
    fun cache(accountId: String?): ScheduleCacheStore

    /**
     * 学工系统的个人档案里，匹配交友用得上的那几项。`null` = 这一端没有这份档案
     * （桌面端没有学工缓存）⇒ 界面把「年级 专业 校区 生源地」那一维禁用并说明。
     */
    fun identity(): MatchIdentity?

    /**
     * 校园卡流水快照里**已经落盘**的那些记录。空表 = 这一端没有这份缓存
     * （没进过校园卡页，或桌面端压根没有那份落盘）⇒ 饭点与食堂那一维禁用。
     */
    fun transactions(): List<Transaction>

    /**
     * 阻塞 IO 的调度器。
     *
     * 为什么要有这一格：这三件都是**同步**的本地读写，而 `:core` 的 commonMain 里没有
     * `Dispatchers.IO`（wasm 目标更没有）。`:app` 与桌面传 `Dispatchers.IO`
     * （与搬迁前那个池相同），没接的端走 `Dispatchers.Default` —— 与 `ScheduleHost.io`
     * 同一条口径。
     */
    val io: CoroutineDispatcher get() = Dispatchers.Default
}

/**
 * 匹配交友用得上的那几项学籍信息。
 *
 * 为什么不让 `:core` 直接用 `:app` 的 `HelloProfile`：那个类带着学号明文之外的一堆字段
 * （辅导员电话、校园卡号、头像 URL……）与 `Context` 侧的取数，屏一个都不用；而且它是
 * `@Serializable` 的落盘模型（`:app` 的 `DataCache` 里存的就是它），搬它等于把那份缓存
 * 的形状也搬进共享层。这里只留**屏真读的那几项**，由 `:app` 的适配器从 `HelloProfile` 里挑。
 *
 * ⚠️ [sno] 只用来推生源地省份（`ProvinceCode`），**不会**进分享码 —— 见 `MatchProfile.build`。
 */
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

/**
 * 扫码那一档 —— 相机实时取景、以及「从相册（桌面是文件）选一张图识别」。
 *
 * 两件都长在平台类型上（Android 是 `QrScannerView` + `ImageDecoder`），所以切成这个接口交给端上。
 * 这一屏**离线**，扫码是唯一的输入通道（另一条是自己动手粘一段文字码），所以它不是「锦上添花」：
 * 没接这一档的端（桌面没有相机）那一档**一个字都不画**，并如实说明缺的是相机，
 * 而不是画两颗点了没反应的按钮。
 */
interface MatchScanner {

    /** 打开取景，扫到就返回。用户取消 / 没扫到分别见 [MatchScanOutcome]。 */
    suspend fun scan(): MatchScanOutcome

    /** 让用户选一张图（相册 / 文件），从里面读二维码。 */
    suspend fun fromImage(): MatchScanOutcome
}

/**
 * 一次扫码 / 选图的结果。
 *
 * @param code 拿到的那段码；空串 = 这次没拿到码
 * @param reason 该对用户说的一句话（比如「图里没找到二维码」）。两个都空 = 用户主动取消，
 *   屏上什么都不说（与搬迁前「相册返回 null 直接 return」同一条口径）
 */
data class MatchScanOutcome(val code: String = "", val reason: String = "")
