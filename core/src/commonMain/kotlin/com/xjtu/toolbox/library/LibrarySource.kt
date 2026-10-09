package com.xjtu.toolbox.library

/**
 * 图书馆座位那一屏的**取数 + 落盘 + 写操作端口**：屏与 ViewModel 都在 `:core`，
 * 两端各自填同一批字段（Android = `:app` 的 `AppLibrarySource`，Web = [com.xjtu.toolbox.core.net.CampusLibraryApi]）。
 *
 * ## 为什么端口要暴露 [canBook]
 *
 * 这屏的一半是**写**：预约座位、已有预约时换座、签到/中途离开/中途返回/退座（都是 `/my/` 上的动作），
 * 还有「切校区」——它写的是账号资料里的 `rplace`（学校侧的持久状态），不是一次查询参数。
 * 另一半是读：校区、某层的区域列表、某区域的座位实时状态、我的预约。
 *
 * campus-api 的图书馆模块是**只读**的，它的 `/api/library/campus` 自己写着「不提供切校区（那是改账号资料的写操作）」——
 * 也就是说「只读」在这条切片上把**切校区也一并封了**。所以 Web 端 [canBook] = false，屏据此：
 *  - 座位格不可点选、没有「预约」「换座」按钮；
 *  - 「我的预约」卡上不出现签到 / 中途离开 / 中途返回 / 取消预约（退座）这些动作按钮；
 *  - 校区切换那一档整个不画（切不了，也不能拿它当"看一眼别的校区"的入口）；
 *  - 扫码进来的「预约座位」确认框不出现。
 *
 * 一句话：**点了会失败的按钮，一个都不画**（口径与场馆的 `canBook`/`canCancel`、评教的 `canSubmit`、
 * 空闲教室的 `availableSources` 同源）。写方法本身仍然会被只读端拒绝 —— 抛异常，而不是悄悄返回一个假成功。
 *
 * 为什么不干脆把写方法从端口里删掉：**屏只有一份**，写路径的编排（已有预约时先确认换座、
 * 跨校区不能直接换只能先取消、动作做完再复核「我的预约」）是共享代码的一部分，
 * 删掉就等于删功能（Android 那半边会跟着没）。切法是「共享代码保留全部路径 + 本端声明自己能做哪几件」。
 *
 * ## 为什么端口要暴露 [hasSeatPlan]
 *
 * 平面图（座位图）由两样东西拼出来：学校 `/qseatuist` 给的**每个座位的矩形**，
 * 以及四张底图/状态图（`区域码.jpg` = 底图、`-book/-inside/-leave/blanket.jpg` = 每种状态的整图，
 * 按矩形裁一块贴上去）。campus-api **既没有布局端点、也没有图片端点** ⇒ Web 端 [hasSeatPlan] = false，
 * 屏据此把「平面图」这一档整个不出现（视图默认落到列表）。不画一张假的座位图，
 * 也不拿列表冒充平面图 —— 布局与底图这两样上游确实没有。
 *
 * ## 为什么图片是「字节」而不是「解好的图」
 *
 * [planBase] / [planTiles] 只给**原始字节**：解码是平台能力（Android = `BitmapFactory`，
 * Web/桌面 = skiko，见 `:core/platform/ImageDecode.kt`），而「底图 + 每种状态裁哪一块贴到哪」
 * 是纯逻辑。所以「取字节」留在这个端口上（各端取数不同）、「解码一张图」留给平台缝、
 * 「组装 [PlanImages]」留在共享代码里（`LibrarySeatPlan.decodePlanImages`）。
 *
 * ## 收藏为什么留在实现方
 *
 * `:app` 的座位收藏落盘用的是 **`SharedPreferences.getStringSet`**（文件 `library_favorites`、
 * 键 `favorite_seats`、值是一个字符串集合），而 `:core` 的 `KeyValueStore` 只有
 * `getString/getInt/getBoolean` —— 三端接口里没有集合这一档。用 `getString` 去读一个 `StringSet`
 * 会直接 `ClassCastException`；换个新键名就等于把老收藏丢了。所以「收藏存在哪儿」留给实现方：
 * Android 包住原来那份 `SharedPreferences`（文件与键名逐字未动 ⇒ 老收藏不丢），Web 用 `localStorage`。
 * 两者都是**同步**读写的，所以这两个方法不是挂起的 —— 搬迁前 ViewModel 也是在构造时同步读那一次。
 *
 * ## 取数实现的 IO 调度由实现方自己负责
 *
 * `:app` 那一份是阻塞式 okhttp（原来 ViewModel 每个调用点都 `withContext(Dispatchers.IO)`），
 * 现在由 `AppLibrarySource` 自己包 IO；Web 走 ktor 的挂起接口，不需要。VM 只在自己的
 * 可取消作用域里调用（与 `VenueSource` / `CampusCardSource` 同一条约定）。
 */
interface LibrarySource {

    /** 本端能不能预约/换座/执行座位动作，以及切换账号校区。campus-api 的图书馆模块只读 ⇒ Web 是 false（见接口 KDoc）。 */
    val canBook: Boolean

    /** 本端有没有「座位布局 + 平面图底图」这两个端点。campus-api 没有 ⇒ Web 是 false（见接口 KDoc）。 */
    val hasSeatPlan: Boolean

    // ─── 读（两端都有）─────────────────────────────────────────

    /** 账号当前所在校区（学校页 `select#rplace` 的选中项）。认不出返回 null（原样保留 App 的语义：不猜一个校区出来）。 */
    suspend fun campus(): LibraryCampus?

    /**
     * 切换账号校区。**这会改用户在图书馆系统里的个人资料**（`rplace` 是账号级字段），
     * 所以只能由用户主动触发。
     *
     * 只读端（[canBook] = false）屏上不画校区切换；真被调到抛异常（见接口 KDoc）。
     */
    suspend fun switchCampus(campus: LibraryCampus): Boolean

    /**
     * 拉一层的区域列表：**区域码 → 中文名**（顺序即学校给的顺序）。
     *
     * 为什么是「一层一层拉」而不是一张静态表：区域码在兴庆是手抄的十三条，雁塔与创新港抄不出来，
     * 学校改了名写死的表就要发版。`qspace` 跟着账号当前校区走，所以查别的校区之前必须先切过去。
     */
    suspend fun areas(floorCode: String): Map<String, String>

    /** 某区域的座位实时状态。口径与 `:app` 一致：排序、`available` 的含义、附带的区域统计都对齐。 */
    suspend fun seats(areaCode: String): SeatResult

    /** 某区域平面图上每个座位的矩形与状态（学校 `/qseatuist`）。没有布局端点的端抛异常（见 [hasSeatPlan]）。 */
    suspend fun seatLayout(areaCode: String): SeatLayout

    /**
     * 平面图的**底图字节**（`区域码.jpg`；整层图就是 `楼层码.jpg`），带本端自己的缓存。
     * 拿不到（没这个区域、上游没这张图）返回 null —— 屏那边「没底图就没法画」。
     *
     * 只返回字节、不解码：见接口 KDoc（解码是平台缝）。没有图片端点的端抛异常（见 [hasSeatPlan]）。
     */
    suspend fun planBase(areaCode: String): ByteArray?

    /**
     * 平面图的**状态贴图字节**：状态码 → 该状态的整图（`-book` / `-inside` / `-leave` / `blanket`）。
     *
     * 与 [planBase] 分两次取是**刻意**的：底图先到就能画，四张状态图随后补。
     * 一起并发拉会占满 WebVPN 的连接，紧接着点「预约」要排在图片后面（原来 ViewModel 里就是这么排的）。
     */
    suspend fun planTiles(areaCode: String): Map<Int, ByteArray>

    /**
     * 我的预约。**成功且值为 null** 表示页面明确说了「没有预约」；所有候选地址都没给出能认的页面时返回失败
     * —— 动作后的复核不能把「没查到」当成「已取消」（原样保留 `LibraryApi.fetchMyBooking` 的语义）。
     */
    suspend fun myBooking(): Result<MyBookingInfo?>

    /**
     * 查单个座位空不空（桌面二维码上那个 `/qavail/` 页），一次请求，比拉整层座位表快得多。
     *
     * 这一枪不碰座位系统以外的任何东西，所以它不是「取数切片」而是「扫码那条路要用的一次查询」；
     * 没有 `/qavail/` 这一档的端（Web）抛异常 —— 而 Web 也走不到（扫码/屁岱/首页把
     * [LibraryFocus] 塞进来的这条入口本来就只在 App 上）。
     */
    suspend fun seatAvailability(qr: LibrarySeatQr): LibrarySeatStatus

    // ─── 本端已知的旁证（缓存读，不挂起）─────────────────────────

    /**
     * 区域码 → 空座/总数（最近一次 [areas] / [seats] 拿到的那些）。
     *
     * 屏用它只做一件事：把**明确关闭**的区域（有统计且 `total == 0`）从区域标签里滤掉；
     * 统计没到或学校没给的照常列出。原来是 `LibraryApi.cachedAreaStats` 这个属性。
     */
    fun areaStats(): Map<String, AreaStats>

    /** 区域码 → 中文名。认不出时原样返回区域码（原来 `LibraryApi.areaNameOf` 的兜底）。 */
    fun areaNameOf(areaCode: String): String

    /** 区域码 → 所在楼层码。不知道返回 null（原来 `LibraryApi.floorOfArea`）。 */
    fun floorOfArea(areaCode: String): String?

    /**
     * 这个区域名**确定**不属于当前校区吗？用来拦下跨校区换座（预约绑在账号的 `rplace` 上，
     * 换到另一个校区的座位服务端不会照办，只会把请求晾在那儿直到超时）。
     *
     * 只有把本校区的楼层都看过之后才敢下结论——没看过就说"不认识"，那是在拿无知当证据。
     */
    fun isForeignArea(areaName: String?): Boolean

    /**
     * 把一个校区所有楼层的区域名学一遍（[isForeignArea] 与 [areaNameOf] 靠它）。
     * 代价是每校区 2~4 个 JSON 请求，切校区后跑一次；顺带让翻楼层时区域标签立刻就有。
     */
    suspend fun warmCampusAreas(campus: LibraryCampus)

    // ─── 收藏（本端落盘，见接口 KDoc）────────────────────────────

    /** 已收藏的座位号。屏进场读一次、切换后自己更新。 */
    fun favorites(): Set<String>

    /** 切换收藏，返回切换后的集合（实现方那份落盘才是准的）。 */
    fun toggleFavorite(seatId: String): Set<String>

    // ─── 写（[canBook] = false 时抛）──────────────────────────────

    /**
     * 预约座位。[autoSwap] 为 true 时，若账号已有预约，则改用换座端点直接换过去
     * （那套「先探测已有预约、再换座」的编排在实现里）。
     */
    suspend fun bookSeat(seatId: String, areaCode: String, autoSwap: Boolean): BookResult

    /**
     * 换座（`/updateseat/`）。**以换座后的实际预约状态判定成功**，不靠重定向或文案猜测
     * ——这段复核逻辑在实现里，一行未改。
     */
    suspend fun swapSeat(seatId: String, areaCode: String): BookResult

    /** 执行一个座位动作：[actionUrl] 是「我的预约」页上那个按钮的真实地址（签到/中途离开/中途返回/取消都走这一个）。 */
    suspend fun action(actionUrl: String): BookResult

    /**
     * 离开本页时把账号校区切回去（[campus] = 进页面时的那个校区）。
     *
     * 为什么不让 ViewModel 自己搓一个协程发这一枪：`onCleared()` 那一刻**页面自己的作用域已经取消了**，
     * 这一枪必须在不随页面取消的作用域里打完，否则用户看一眼别的校区、账号资料就一直停在那儿。
     * 所以实现方自带那个作用域（Android = `LibraryApi.restoreScope`），切成功才回调 [onRestored]
     * （那边原来用它清掉 `pending_home_campus` 这个偏好）。只读端不切校区 ⇒ 空实现。
     */
    fun restoreCampus(campus: LibraryCampus, onRestored: () -> Unit)
}
