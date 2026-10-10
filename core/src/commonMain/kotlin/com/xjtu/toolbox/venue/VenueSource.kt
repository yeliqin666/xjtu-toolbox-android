package com.xjtu.toolbox.venue

/**
 * 体育场馆的**取数 + 落盘端口**：屏与 ViewModel 都在 `:core`，两端各自填同一批字段。
 *
 * ## 为什么端口要暴露 [canBook] / [canCancel]
 *
 * 这屏的一半是**写**（预订要过滑块验证码、订单能取消、待支付订单能唤起支付），另一半是读
 *（场馆列表、某天的时段、我的订单）。两端的差别正好落在写那一半：Android 直连
 * `202.117.17.144:8080`（自己的站点会话 + 内置浏览器 + 自动识别验证码）；Web 端走 campus-api 的同源反代，
 * 而 campus-api 的场馆模块是**只读**的（`/api/venue/status` 自己写着 `readOnly:true`）——
 * 抢场要解滑块且属写操作，出范围。
 *
 * 那为什么不干脆把写方法从端口里删掉：**屏只有一份**，写路径的编排（先备参数、再拉验证码、识别、
 * 等到松手时刻、提交、失败重试那套状态机）是共享代码的一部分，删掉就等于删功能（Android 半边会跟着没）。
 * 于是切法是「共享代码保留全部路径 + 本端声明自己能做哪几件」：
 *  - [canBook] = false ⇒ 屏上不出现「确认预订」、时段格子不可勾选、验证码分支永不进入（[captchaView] /
 *    [solveCaptcha] 两个槽位本来就是 null）、预订结果弹窗里的「去支付」也不会出现；
 *  - [canCancel] = false ⇒ 订单卡与订单详情里不出现「取消订单」。
 *
 * 一句话：**点了会失败的按钮，一个都不画**（这条口径与评教的 `canSubmit`、空闲教室的
 * `availableSources` 同源）。写方法本身仍然会被实现方拒绝 —— 只读端抛
 * `RuntimeException("本端只读：campus-api 不提供场馆预订/取消")`，而不是悄悄返回一个假成功。
 *
 * ## 为什么验证码 UI 是槽位而不是端口方法
 *
 * 验证码那两件事都长在 Android 的类型上：滑块控件要 `android.graphics.Bitmap` + 拖动轨迹，
 * 自动识别要 `Bitmap.getPixels` + Base64 解码。它们**不是取数**（不碰网络、不进端口该管的那一层），
 * 而是屏上的两个 UI/算力插槽：`captchaView`（画滑块、产出 [SliderResult]）与 `solveCaptcha`
 *（识别 [CaptchaData]、产出 [SolvedCaptcha]）。所以它们由宿主注入到屏上，Web 传 null。
 *
 * 注入 null 的语义是**本端没有这条路径**，不是"调用会抛"：状态机只在写路径上用它俩，
 * 而写路径又只在 [canBook] = true 时才可达，两条缝对齐。
 *
 * ## 取数实现的 IO 调度由实现方自己负责
 *
 * `:app` 那一份是阻塞式 okhttp，实现里自己 `withContext(Dispatchers.IO)`；Web 走 ktor 的挂起接口，
 * 不需要。VM 只在自己的可取消作用域里调用（与 `CampusCardSource` / `EmptyRoomSource` 同一条约定）。
 */
interface VenueSource {

    /** 本端能不能下单。campus-api 的场馆模块只读 ⇒ Web 是 false（见接口 KDoc）。 */
    val canBook: Boolean

    /** 本端能不能取消订单。理由同上（同一个只读模块）。 */
    val canCancel: Boolean

    /**
     * 支付前要先去这个站点的登录页过一遍（`:app` 的内置浏览器带着 App 的统一认证 cookie，
     * 一般免输密码）。Web 端的「浏览器」就是浏览器本身、也没有这份会话 ⇒ 值给空串，
     * 而且 [canBook] = false 时屏根本走不到支付那一步。
     */
    val browserLoginUrl: String

    // ─── 读（两端都有）─────────────────────────────────────────

    /** 场馆列表。`:app` 是分页拉到不足一页为止（每页 8 条）；实现方自己决定拉几页。 */
    suspend fun venues(): List<Venue>

    /**
     * [serviceId] 这个场馆 [date]（`YYYY-MM-DD`）这一天的时段。
     *
     * 口径与 `:app` 一致：可订与已占用两路合并后按 (时段, 场地名) 排序 ——
     * 只拿可订那一半，UI 就分不出「满了」和「没这个时段」。
     */
    suspend fun slots(serviceId: Int, date: String): List<AreaSlot>

    /** 我的订单，[page] 从 1 开始。 */
    suspend fun orders(page: Int, pageSize: Int): OrderPage

    /**
     * 支付页 URL。**只读端不会走到**（[canBook] = false ⇒ 屏上不画支付入口），
     * 但接口里留着它 —— 它是「订单怎么付」这件事的定义，与写路径一起属于共享代码。
     */
    fun paymentUrl(orderId: String): String

    // ─── 写（[canBook] / [canCancel] 为 false 时抛）──────────────

    /**
     * 滑块验证码。注意 `:app` 那个部署里它在站点**根路径** `/gen`（不在 `/web/` 下）。
     *
     * 只读端抛异常；屏不会走到（写路径的入口都被 [canBook] 挡着）。
     */
    suspend fun captcha(serviceId: Int): CaptchaData

    /**
     * 把选中的时段拼成待提交订单参数。
     *
     * `:app` 这套部署**没有**「先换服务端 `_param`」那一步，所以它是纯函数；
     * 保留这个方法只为让 UI 的两段式流程（先备好订单、再要验证码）不用改。
     */
    fun prepareOrder(serviceId: Int, selections: List<AreaSlot>): PendingOrder

    /**
     * 提交预订。[sliderTrackJson] 是 [SliderResult.toJson] 的结果（轨迹 + 两个时刻）。
     *
     * `:app` 那侧有两个已知怪癖都靠「原样重试同一份请求」解决（首次 POST 可能 404、
     * 同一个验证码首次提交可能被判「验证码有误」）—— 那套重试写在实现里，一行未动。
     */
    suspend fun submitBooking(
        serviceId: Int,
        pendingOrder: PendingOrder,
        captchaId: String,
        sliderTrackJson: String,
    ): BookingResult

    /** 取消订单。 */
    suspend fun cancelOrder(orderId: String): OrderActionResult

    // ─── 收藏（本端的落盘）─────────────────────────────────────

    /**
     * 已收藏的场馆 id。屏进场读一次、切换后自己更新。
     *
     * **落盘现在在共享层**：搬迁前它只能留给各端实现（那会儿 `:core` 的 `KeyValueStore` 只有
     * `getString/getInt/getBoolean` 三档，而这里落盘用的是 `SharedPreferences.getStringSet` ——
     * 用 `getString` 去读一个集合键会直接 `ClassCastException`，换个新键名又等于把老收藏丢了）。
     * `KeyValueStore` 补上集合那一档之后，它像座位收藏那样收回了 `:core` 的 `VenueFavorites`
     * （文件 `venue_favorites`、键 `favorite_venue_ids` 逐字未动）。
     *
     * 两个方法仍留在端口上而不是让屏直调 `VenueFavorites`：这是屏与「各端落盘」之间的原缝
     *（将来若要按账号分文件，换的是实现而不是屏），且 `:app` 的导航层与 Web 的装配本来就
     *对着这条端口——搬动不再动它们。
     */
    suspend fun favorites(): Set<Int>

    /** 切换收藏，返回切换后的状态（与 `VenueFavorites.toggle` 同一个返回值语义）。 */
    suspend fun toggleFavorite(venueId: Int): Boolean
}
