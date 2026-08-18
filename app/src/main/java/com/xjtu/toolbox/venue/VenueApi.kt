package com.xjtu.toolbox.venue

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.VenueLogin
import com.xjtu.toolbox.auth.XJTULogin
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 体育场馆预约 API（202.117.17.144:8080，接口都在 `/web/` 下）。
 *
 * 关键接口：
 * - 场馆列表 `product/productData.html`（分页 JSON，每页 8 条）
 * - 可订时段 `product/findOkArea.html`、已占用 `product/findLockArea.html`
 * - 下单 `order/tobook.html`（一次可提交多个时段）
 * - 订单 `yyuser/searchorder.html`、取消 `order/delorder.html`
 * - 滑块验证码 `GET /gen`，注意它在根路径，不在 `/web/` 下
 *
 * 之前打的是 80 端口那套 `/xjtu/…` 部署，会话时常失效、订单接口取不到数据。
 * 两套是并存的不同部署，不是同一批接口。
 *
 * 支付页仍在 80 端口，且要浏览器自身的会话，只能拉起系统浏览器。
 */
class VenueApi(private val site: SiteSession) {

    /** 非开放时段等情况服务端返回 HTML 提示页而非 JSON，把提示语抽出来当消息用。 */
    class VenueApiException(message: String) : RuntimeException(message)

    companion object {
        private const val TAG = "VenueApi"

        /** 站点根，滑块验证码 `/gen` 在这一层 */
        private const val ROOT = VenueLogin.BASE_URL

        /** 业务接口前缀 */
        private const val BASE = "$ROOT/web"

        /** 支付页在 80 端口的另一套站点上 */
        const val PAYMENT_BASE = VenueLogin.PAY_BASE_URL
        const val BROWSER_LOGIN_URL = VenueLogin.VENUE_OAUTH_URL

        private const val MAX_ORDER_PAGES = 100
        private const val VENUE_PAGE_SIZE = 8
        private val gson = Gson()

        /**
         * 服务端偶尔用 GBK 编码返回中文，OkHttp 按 latin-1 解会得到每个字节一个字符
         * 的乱码。字符全落在 0x80..0xFF 时按 GBK 重解一次。
         */
        private fun String.fixGbk(): String {
            if (isEmpty() || none { it.code in 0x80..0xFF }) return this
            return runCatching {
                String(toByteArray(Charsets.ISO_8859_1), charset("GBK"))
            }.getOrDefault(this)
        }
    }

    private fun request(url: String, referer: String = "$BASE/index.html"): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")

    private fun ajaxRequest(url: String, referer: String): Request.Builder =
        request(url, referer).header("X-Requested-With", "XMLHttpRequest")

    private fun execute(builder: Request.Builder) =
        runBlocking { site.executeWithReAuth(builder.build()) }

    /** 取 JSON 文本；拿到 HTML 说明服务端在讲人话（多半是「未到预订时间」）。 */
    private fun fetchJson(builder: Request.Builder): String {
        val response = execute(builder)
        val code = response.code
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val body = response.body?.string().orEmpty()
        response.close()

        if (code !in 200..299) throw RuntimeException("请求失败（HTTP $code）")
        if (XJTULogin.isAuthFailureResponse(body)) throw AuthExpiredException("体育场馆")

        val trimmed = body.trimStart()
        val looksHtml = "json" !in contentType && (trimmed.startsWith("<") || "html" in contentType)
        if (looksHtml) throw VenueApiException(extractNotice(body))
        return body
    }

    /** 从提示页里挖出给人看的那句话，优先 title。 */
    private fun extractNotice(html: String): String {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return "服务暂时不可用"
        val title = doc.title().trim().fixGbk()
        if (title.isNotBlank()) return title
        val text = doc.body()?.text()?.trim()?.fixGbk().orEmpty()
        return text.take(80).ifBlank { "服务暂时不可用" }
    }

    // ─── 数据模型 ─────────────────────────────────────────

    /** 场馆（从 product/index.html 解析） */
    data class Venue(
        val id: Int,
        val name: String,
        val address: String? = null,
        val iconType: String? = null,   // icon-badminton, icon-tennis, ...
        /** 可提前几天预订 */
        val advanceDay: Int = 7,
        /** 一次最多订几个时段 */
        val advanceNum: Int = 8,
    )

    /** 一个时段下的一个可选场地单元（从 findtime.html + seat/seat.html 合并得出） */
    data class AreaSlot(
        val areaDetailId: Long,   // 场地明细ID，提交订单 stockdetailids 用；无细分场地时退化为 stockId
        val areaName: String,     // "场地1"/"场地2"/...；无细分场地时为 "预订"；已满时为 "已满"
        val stockId: Long,        // 库存ID，提交订单 stock map 的 key，同一时段下所有场地共享
        val timeSlot: String,     // 18:00-19:00
        val price: Double,
        val date: String,         // 2026-03-03
        val allCount: Int,        // 该时段总容量（时段级，非逐场地）
        val usingNum: Int,        // 已用（时段级）
        val surplus: Int,         // 剩余（时段级）——服务端只在这个粒度给出占用数据
        val serviceid: String
    ) {
        val isAvailable: Boolean get() = surplus > 0
    }

    /** 验证码数据 */
    data class CaptchaData(
        val id: String,
        val backgroundImage: String,  // data:image/jpeg;base64,...
        val sliderImage: String,      // data:image/png;base64,...
        val bgWidth: Int,
        val bgHeight: Int,
        val sliderWidth: Int,
        val sliderHeight: Int
    )

    /** 服务端在 order/show.html 步骤生成的待提交订单参数（必须原样带回，不能自拼） */
    data class PendingOrder(private val paramJson: String) {
        internal fun rawParamJson(): String = paramJson
    }

    /** 预订结果 */
    data class BookingResult(
        val success: Boolean,
        val orderId: String? = null,
        val price: Double = 0.0,
        val message: String = ""
    )

    /** 一个订单明细（一个日期/时段/场地）。 */
    data class OrderDetail(
        val date: String,
        val timeSlot: String,
        val areaName: String,
        val price: Double,
        val serviceId: String,
        val serviceName: String
    )

    /** 订单信息。状态值与场馆服务端保持一致：0 预订中、1 预订成功、2 预订取消。 */
    data class OrderInfo(
        val orderId: String,
        val status: Int,
        val createdAt: String,
        val price: Double,
        val details: List<OrderDetail>
    ) {
        val statusText: String
            get() = when (status) {
                0 -> "预订中"
                1 -> "预订成功"
                2 -> "预订取消"
                else -> "未知状态($status)"
            }

        val venueName: String
            get() = details.firstOrNull { it.serviceName.isNotBlank() }?.serviceName.orEmpty()

        val firstDate: String
            get() = details.firstOrNull()?.date.orEmpty()

        /** 待支付订单可直接唤起支付引导。 */
        val canPay: Boolean get() = status == 0

        /** 服务端允许对预订中/预订成功订单发起取消。 */
        val canCancel: Boolean get() = status == 0 || status == 1
    }

    /** 订单分页响应。服务端不同部署可能返回数组或带 rows/object 的对象，统一成此模型。 */
    data class OrderPage(
        val orders: List<OrderInfo>,
        val page: Int,
        val pageSize: Int,
        val total: Int? = null,
        val hasMore: Boolean = false
    )

    /** 取消订单/其它订单操作的统一结果。 */
    data class OrderActionResult(
        val success: Boolean,
        val message: String
    )

    private data class TimeSlotInfo(
        val timeNo: String,
        val stockId: Long,
        val price: Double,
        val allCount: Int,
        val usingNum: Int,
        val surplus: Int
    )

    // ─── API 方法 ─────────────────────────────────────────

    /** 场馆列表。分页拉到返回不足一页为止。 */
    fun fetchVenueList(): List<Venue> {
        val venues = mutableListOf<Venue>()
        var page = 1
        while (page <= 20) {
            val url = "$BASE/product/productData.html" +
                "?page=$page&rows=$VENUE_PAGE_SIZE&merccode=100001&remark=defaultProList"
            val body = fetchJson(ajaxRequest(url, "$BASE/index.html"))
            val array = runCatching { JsonParser.parseString(body) }
                .getOrNull()?.takeIf { it.isJsonArray }?.asJsonArray
                ?: break
            if (array.size() == 0) break
            array.forEach { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val id = readInt(item, "id")
                if (id <= 0) return@forEach
                venues += Venue(
                    id = id,
                    name = readString(item, "name").orEmpty().fixGbk(),
                    address = readString(item, "address")?.fixGbk(),
                    iconType = readString(item, "icon"),
                    advanceDay = readInt(item, "advanceday").takeIf { it > 0 } ?: 7,
                    advanceNum = readInt(item, "advancenum").takeIf { it > 0 } ?: 8,
                )
            }
            if (array.size() < VENUE_PAGE_SIZE) break
            page++
        }
        Log.d(TAG, "fetchVenueList: ${venues.size} 个场馆")
        return venues
    }

    /**
     * 某天的时段。findOkArea 给可订的，findLockArea 给已被占的，
     * 两者合并后 UI 才能把「满了」和「没有这个时段」区分开。
     */
    fun fetchAvailableSlots(serviceid: Int, date: String): List<AreaSlot> {
        val ok = fetchSlots("findOkArea", serviceid, date)
        val locked = runCatching { fetchSlots("findLockArea", serviceid, date) }
            .getOrDefault(emptyList())
        return (ok + locked).sortedWith(compareBy({ it.timeSlot }, { it.areaName }))
    }

    private fun fetchSlots(action: String, serviceid: Int, date: String): List<AreaSlot> {
        val url = "$BASE/product/$action.html?s_date=$date&serviceid=$serviceid"
        val body = fetchJson(ajaxRequest(url, "$BASE/product/show.html?id=$serviceid"))
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return emptyList()
        val items = root.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("object")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()

        return items.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val stock = item.get("stock")?.asObjectOrNull()
            val allCount = readInt(stock, "all_count")
            val usingNum = readInt(stock, "using_num")
            // status: 1 可订，其余为已订/锁定
            val status = readInt(item, "status")
            AreaSlot(
                areaDetailId = readInt(item, "id").toLong(),
                areaName = readString(item, "sname").orEmpty().fixGbk().ifBlank { "预订" },
                stockId = readInt(item, "stockid").toLong(),
                timeSlot = readString(stock, "time_no").orEmpty(),
                price = readDouble(stock, "price"),
                date = date,
                allCount = allCount,
                usingNum = usingNum,
                surplus = if (status == 1) (allCount - usingNum).coerceAtLeast(1) else 0,
                serviceid = serviceid.toString(),
            )
        }
    }

    /** 滑块验证码。注意在站点根路径，不带 `/web/`。 */
    fun generateCaptcha(serviceid: Int): CaptchaData {
        val body = fetchJson(ajaxRequest("$ROOT/gen", "$BASE/product/show.html?id=$serviceid"))
        val json = gson.fromJson(body, JsonObject::class.java)
        val captcha = json.getAsJsonObject("captcha")
        return CaptchaData(
            id = json.get("id").asString,
            backgroundImage = captcha.get("backgroundImage").asString,
            sliderImage = captcha.get("sliderImage").asString,
            bgWidth = captcha.get("backgroundImageWidth").asInt,
            bgHeight = captcha.get("backgroundImageHeight").asInt,
            sliderWidth = captcha.get("sliderImageWidth").asInt,
            sliderHeight = captcha.get("sliderImageHeight").asInt
        )
    }

    /**
     * 这套部署没有「先换服务端 _param」那一步，选中的时段直接拼进下单参数。
     * 保留本方法只为让 UI 的两段式流程（先备好订单、再要验证码）不用改。
     */
    fun prepareOrder(serviceid: Int, selections: List<AreaSlot>): PendingOrder {
        require(selections.isNotEmpty()) { "请先选择时段" }
        return PendingOrder(buildBookingParam(serviceid, selections))
    }

    /**
     * stockdetail 的 key 是库存 ID、value 是场地 ID，同一库存下多个场地用逗号连接
     * ——顺序反了服务端不会报错，只会订不上。
     */
    private fun buildBookingParam(serviceid: Int, selections: List<AreaSlot>): String {
        val stockDetail = linkedMapOf<String, String>()
        selections.forEach { slot ->
            val key = slot.stockId.toString()
            val area = slot.areaDetailId.toString()
            stockDetail[key] = stockDetail[key]?.let { "$it,$area" } ?: area
        }
        val param = JsonObject().apply {
            add("stockdetail", JsonObject().apply {
                stockDetail.forEach { (k, v) -> addProperty(k, v) }
            })
            addProperty("venueReason", "")
            addProperty("fileUrl", "")
            addProperty("address", serviceid.toString())
        }
        return gson.toJson(param)
    }

    /**
     * 提交预订。
     *
     * 服务端有两个已知怪癖，都靠重试同一份请求解决：首次 POST 可能直接返回 404；
     * 即便 200，同一个 yzm 首次提交也可能被误判成「验证码有误」。
     */
    fun submitBooking(
        serviceid: Int,
        pendingOrder: PendingOrder,
        captchaId: String,
        sliderTrackJson: String
    ): BookingResult {
        // 固定拼接格式：{轨迹JSON}synjones{验证码ID}synjones{固定文本}。
        // 末尾这段写的是 8071 端口，是服务端签名用的常量，不是真实访问端口。
        val yzm = "${sliderTrackJson}synjones${captchaId}synjoneshttp://202.117.17.144:8071"
        val referer = "$BASE/product/show.html?id=$serviceid"

        var lastMessage = "预订失败"
        repeat(3) { attempt ->
            val form = FormBody.Builder()
                .add("param", pendingOrder.rawParamJson())
                .add("yzm", yzm)
                .add("json", "true")
                .build()
            val response = execute(
                ajaxRequest("$BASE/order/tobook.html", referer)
                    .header("Origin", ROOT)
                    .post(form)
            )
            val code = response.code
            val body = response.body?.string().orEmpty()
            response.close()

            if (code !in 200..299) {
                Log.w(TAG, "submitBooking: 第 ${attempt + 1} 次 HTTP $code，重试")
                return@repeat
            }
            if (XJTULogin.isAuthFailureResponse(body)) throw AuthExpiredException("体育场馆")

            val obj = runCatching { JsonParser.parseString(body) }
                .getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            val result = readString(obj, "result").orEmpty()
            val message = readString(obj, "message").orEmpty().fixGbk()
            val orderObj = obj?.get("object")?.asObjectOrNull()
            val orderId = readString(orderObj, "orderid").orEmpty()

            if (orderId.isNotBlank() || result == "2") {
                return BookingResult(
                    success = true,
                    orderId = orderId.ifBlank { null },
                    price = readDouble(orderObj, "price"),
                    message = message.ifBlank { "预订成功" }
                )
            }
            lastMessage = message.ifBlank { "预订失败（$result）" }
            if (result == "100" && message.contains("验证码")) {
                Log.w(TAG, "submitBooking: 第 ${attempt + 1} 次被判验证码有误，原样重试")
                return@repeat
            }
            return BookingResult(false, message = lastMessage)
        }
        return BookingResult(false, message = lastMessage)
    }

    // ─── 订单 ─────────────────────────────────────────────────────────

    /**
     * 分页查询「我的订单」。
     *
     * PR #54 使用过带 `/web` 前缀的旧部署地址；当前移动端场馆站点的实际
     * contextPath 是根路径，因此这里按现有抓包使用 `/yyuser/...`。响应在
     * 不同版本服务端上既可能是 JSON 数组，也可能包在 `rows`/`object` 中，
     * 解析器会统一兼容。
     */
    fun fetchOrders(page: Int = 1, pageSize: Int = 20): OrderPage {
        require(page >= 1) { "订单页码必须从 1 开始" }
        require(pageSize in 1..100) { "订单分页大小无效" }

        val url = "$BASE/yyuser/searchorder.html" +
            "?page=$page&rows=$pageSize&status=&iscomment=" +
            "&stockSDate=&stockEDate=&_=${System.currentTimeMillis()}"
        val response = execute(ajaxRequest(url, "$BASE/yyuser/searchorder.html"))
        val body = response.body?.string().orEmpty()
        val code = response.code
        response.close()

        if (code !in 200..299) {
            throw RuntimeException("加载订单失败（HTTP $code）")
        }
        if (XJTULogin.isAuthFailureResponse(body)) {
            throw AuthExpiredException("体育场馆")
        }
        // 没有订单时服务端会返回空数组；空 body 也按空页处理，避免把「暂无订单」
        // 错误地显示成网络故障。
        if (body.isBlank()) return OrderPage(emptyList(), page, pageSize, total = 0, hasMore = false)

        return parseOrderPage(body, page, pageSize)
    }

    /** 与旧客户端命名保持兼容，供其它入口按需读取单页订单。 */
    fun getOrders(page: Int = 1, pageSize: Int = 20): OrderPage =
        fetchOrders(page, pageSize)

    /** 拉取全部订单；保留分页 API 供页面按需加载。 */
    fun fetchAllOrders(pageSize: Int = 20): List<OrderInfo> {
        val result = mutableListOf<OrderInfo>()
        var page = 1
        while (page <= MAX_ORDER_PAGES) {
            val current = fetchOrders(page, pageSize)
            result += current.orders
            if (!current.hasMore || current.orders.isEmpty()) break
            page++
        }
        return result.sortedWith(
            compareByDescending<OrderInfo> { it.createdAt.ifBlank { "0000-00-00 00:00:00" } }
                .thenByDescending { it.orderId }
        )
    }

    /** 取消订单。服务端成功码通常是 `1`，同时兼容旧部署的布尔/文本返回值。 */
    fun cancelOrder(orderId: String): OrderActionResult {
        require(orderId.isNotBlank()) { "订单号不能为空" }
        val form = FormBody.Builder()
            .add("orderid", orderId)
            .add("json", "true")
            .build()
        val response = execute(
            ajaxRequest("$BASE/order/delorder.html", "$BASE/yyuser/searchorder.html")
                .post(form)
        )
        val body = response.body?.string().orEmpty()
        val code = response.code
        response.close()

        if (code !in 200..299) {
            return OrderActionResult(false, "取消订单失败（HTTP $code）")
        }
        if (XJTULogin.isAuthFailureResponse(body)) {
            throw AuthExpiredException("体育场馆")
        }

        val root = runCatching { JsonParser.parseString(body) }.getOrNull()
        val obj = root?.takeIf { it.isJsonObject }?.asJsonObject
        val result = readString(obj, "result", "code", "success").orEmpty().lowercase()
        val message = readString(obj, "message", "msg", "notice")
            ?.takeIf { it.isNotBlank() }
            ?: if (result in setOf("1", "true", "success", "ok")) "取消成功" else "取消失败"
        val success = result in setOf("1", "true", "success", "ok") ||
            (result == "100" && message.contains("成功"))
        return OrderActionResult(success, message)
    }

    /** 支付页面 URL（订单支付需要在系统浏览器中完成 CAS 会话接力）。 */
    fun paymentUrl(orderId: String): String =
        "$PAYMENT_BASE/pay/show.html?id=${java.net.URLEncoder.encode(orderId, Charsets.UTF_8.name())}"

    /** PR #54 中使用的命名别名。 */
    fun payUrl(orderId: String): String = paymentUrl(orderId)

    private fun parseOrderPage(body: String, page: Int, pageSize: Int): OrderPage {
        val root = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            val text = Jsoup.parse(body).text().trim()
            throw RuntimeException(text.takeIf { it.isNotBlank() } ?: "订单接口返回格式异常", e)
        }

        val array = findOrderArray(root)
        if (array == null) {
            // 某些部署在没有订单时返回 `{object:null}`，与空数组等价。
            if (root.isJsonObject && root.asJsonObject.entrySet().all { it.value.isJsonNull }) {
                return OrderPage(emptyList(), page, pageSize, total = 0, hasMore = false)
            }
            throw RuntimeException("订单接口返回格式异常")
        }

        val orders = array.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseOrder)
        }.filter { it.orderId.isNotBlank() }
        val total = findTotal(root)
        val hasMore = total?.let { page * pageSize < it } ?: (orders.size >= pageSize)
        return OrderPage(orders, page, pageSize, total, hasMore)
    }

    private fun parseOrder(item: JsonObject): OrderInfo {
        val details = mutableListOf<OrderDetail>()
        val detailsElement = firstElement(item, "orderdetail", "orderDetail", "details", "items")
        val detailElements = when {
            detailsElement?.isJsonArray == true -> detailsElement.asJsonArray.toList()
            detailsElement?.isJsonObject == true -> listOf(detailsElement)
            else -> emptyList()
        }
        detailElements.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val detail = element.asJsonObject
            val stock = firstElement(detail, "stock")?.asObjectOrNull()
            val stockDetail = firstElement(detail, "stockdetail", "stockDetail")?.asObjectOrNull()
            val service = firstElement(detail, "service", "venue", "product")?.asObjectOrNull()
            details += OrderDetail(
                date = readString(stock, "s_date", "sDate", "date", "stockSDate").orEmpty(),
                timeSlot = readString(stock, "time_no", "timeNo", "time", "timeSlot").orEmpty(),
                areaName = readString(stockDetail, "sname", "name", "areaName", "area").orEmpty(),
                price = readDouble(detail, "price", "amount", "money"),
                serviceId = readString(detail, "serviceid", "serviceId", "id").orEmpty(),
                serviceName = readString(service, "name", "serviceName", "servicename").orEmpty()
            )
        }
        return OrderInfo(
            orderId = readString(item, "orderid", "orderId", "id").orEmpty(),
            status = readInt(item, "status", "orderStatus", "state"),
            createdAt = readString(item, "createdate", "createDate", "created_at", "orderDate").orEmpty(),
            price = readDouble(item, "price", "amount", "money"),
            details = details
        )
    }

    /** 在数组/rows/object/data/list 等常见包装中寻找订单数组。 */
    private fun findOrderArray(element: JsonElement?, depth: Int = 0): JsonArray? {
        if (element == null || element.isJsonNull || depth > 4) return null
        if (element.isJsonArray) {
            val array = element.asJsonArray
            // 空数组本身就是合法的「暂无订单」响应；非空数组则避免误把
            // orderdetail/其它业务数组当成订单列表。
            if (array.size() == 0 || array.any { candidate ->
                    candidate.isJsonObject && firstElement(
                        candidate.asJsonObject,
                        "orderid", "orderId", "orderStatus", "createdate", "createDate"
                    ) != null
                }) return array
            return array.asSequence()
                .mapNotNull { child -> findOrderArray(child, depth + 1) }
                .firstOrNull()
        }
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val preferred = listOf("object", "rows", "data", "list", "orders", "orderList")
        preferred.forEach { key ->
            val child = obj.get(key)
            val found = findOrderArray(child, depth + 1)
            if (found != null) return found
        }
        obj.entrySet().forEach { (_, child) ->
            val found = findOrderArray(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun findTotal(element: JsonElement?, depth: Int = 0): Int? {
        if (element == null || element.isJsonNull || depth > 3) return null
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        listOf("total", "totalCount", "records", "count").forEach { key ->
            val value = obj.get(key)
            if (value != null && !value.isJsonNull) {
                readInt(value)?.let { return it }
            }
        }
        listOf("object", "data", "result").forEach { key ->
            findTotal(obj.get(key), depth + 1)?.let { return it }
        }
        return null
    }

    private fun firstElement(obj: JsonObject?, vararg keys: String): JsonElement? {
        if (obj == null) return null
        keys.forEach { key ->
            val direct = obj.get(key)
            if (direct != null && !direct.isJsonNull) return direct
        }
        obj.entrySet().forEach { (key, value) ->
            if (!value.isJsonNull && keys.any { it.equals(key, ignoreCase = true) }) return value
        }
        return null
    }

    private fun readString(obj: JsonObject?, vararg keys: String): String? =
        readString(firstElement(obj, *keys))

    private fun readString(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        return runCatching { element.asString }.getOrNull()?.trim()
    }

    private fun readInt(obj: JsonObject?, vararg keys: String): Int =
        readInt(firstElement(obj, *keys)) ?: 0

    private fun readInt(element: JsonElement?): Int? {
        val raw = readString(element) ?: return null
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
    }

    private fun readDouble(obj: JsonObject?, vararg keys: String): Double =
        readDouble(firstElement(obj, *keys))

    private fun readDouble(element: JsonElement?): Double {
        val raw = readString(element).orEmpty()
            .replace(",", "")
            .replace("¥", "")
            .replace("￥", "")
        return raw.toDoubleOrNull() ?: 0.0
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

}
