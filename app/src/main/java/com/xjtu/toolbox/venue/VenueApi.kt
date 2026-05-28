package com.xjtu.toolbox.venue

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.XJTULogin
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * 体育场馆预订 API
 *
 * 基于 http://202.117.17.144 的 HTML + JSON 混合接口
 */
class VenueApi(private val site: SiteSession) {

    companion object {
        private const val TAG = "VenueApi"
        private const val BASE = "http://202.117.17.144:8071"
        private val gson = Gson()
    }

    private fun request(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Referer", BASE)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")

    private fun execute(builder: Request.Builder) =
        runBlocking { site.executeWithReAuth(builder.build()) }

    // ─── 数据模型 ─────────────────────────────────────────

    /** 场馆（从 product/index.html 解析） */
    data class Venue(
        val id: Int,
        val name: String,
        val address: String? = null,
        val iconType: String? = null    // icon-badminton, icon-tennis, ...
    )

    /** 场地 + 时段（从 findOkArea JSON） */
    data class AreaSlot(
        val areaId: Long,          // stockdetailids (e.g. 4099045)
        val areaName: String,       // 场地1, 场地2...
        val stockId: Long,          // 用于 stock 参数 (e.g. 428121)
        val timeSlot: String,       // 18:00-19:00
        val price: Double,
        val date: String,           // 2026-03-03
        val status: Int,            // 1=可预订
        val allCount: Int,
        val usingNum: Int,
        val serviceid: String
    ) {
        val isAvailable: Boolean get() = status == 1
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

    /** 预订结果 */
    data class BookingResult(
        val success: Boolean,
        val orderId: String? = null,
        val price: Double = 0.0,
        val message: String = ""
    )

    // ─── API 方法 ─────────────────────────────────────────

    /**
     * 获取场馆列表（从 index.html 解析）
     */
    fun fetchVenueList(): List<Venue> {
        val response = execute(request("$BASE/product/index.html"))
        val html = response.body?.string() ?: throw RuntimeException("获取场馆列表失败")
        response.close()
        if (XJTULogin.isAuthFailureResponse(html)) {
            throw AuthExpiredException("体育场馆")
        }

        val doc = Jsoup.parse(html, BASE)
        val venues = mutableListOf<Venue>()

        // HTML 结构: <li><a href="show.html?id=55"></a><dl><dt><i class="icon icon-tennis ..."></i></dt><dd><h5>场馆名</h5><div class="address">地址:...</div>...</dd></dl></li>
        // <a> 标签里没有文字，场馆名在同级 <dl>→<dd>→<h5> 中
        doc.select("li:has(a[href*=show.html?id=])").forEach { li ->
            val a = li.selectFirst("a[href*=show.html?id=]") ?: return@forEach
            val href = a.attr("href")
            val idMatch = Regex("""id=(\d+)""").find(href) ?: return@forEach
            val id = idMatch.groupValues[1].toIntOrNull() ?: return@forEach

            val name = li.selectFirst("h5")?.text()?.trim()
            if (name.isNullOrBlank()) return@forEach

            val address = li.selectFirst(".address")?.text()?.trim()
                ?.removePrefix("地址:")?.trim()
            val iconClass = li.selectFirst("i.icon")?.className() ?: ""
            val iconType = Regex("""icon-(\w+)""").find(iconClass)?.groupValues?.get(1)

            venues.add(Venue(id, name, address, iconType))
        }

        Log.d(TAG, "fetchVenueList: ${venues.size} venues found")
        if (venues.isEmpty()) {
            Log.w(TAG, "fetchVenueList: empty, title=${doc.title()}, body=${doc.body()?.text()?.take(300)}")
            throw RuntimeException("场馆列表为空或页面结构已变化，请稍后重试")
        }
        return venues
    }

    /**
     * 获取指定场馆某日的可预约场地/时段
     */
    fun fetchAvailableSlots(serviceid: Int, date: String): List<AreaSlot> {
        val url = "$BASE/product/findOkArea.html?s_date=$date&serviceid=$serviceid&_=${System.currentTimeMillis()}"
        val response = execute(request(url))
        val body = response.body?.string() ?: return emptyList()
        response.close()

        return parseAreaSlots(body, date, serviceid.toString())
    }

    /**
     * 获取已锁定（不可预约）的场地/时段
     */
    fun fetchLockedSlots(serviceid: Int, date: String): List<AreaSlot> {
        val url = "$BASE/product/findLockArea.html?s_date=$date&serviceid=$serviceid&_=${System.currentTimeMillis()}"
        val response = execute(request(url))
        val body = response.body?.string() ?: return emptyList()
        response.close()

        return parseAreaSlots(body, date, serviceid.toString())
    }

    /**
     * 生成滑动验证码
     */
    fun generateCaptcha(): CaptchaData {
        val url = "$BASE/gen"
        val response = execute(request(url))
        val body = response.body?.string() ?: throw RuntimeException("获取验证码失败")
        response.close()

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
     * 提交预订
     *
     * @param serviceid 场馆 ID
     * @param selections 选中的 AreaSlot 列表
     * @param captchaId 验证码 ID (from generateCaptcha)
     * @param sliderTrackJson 滑动轨迹 JSON 字符串
     */
    fun submitBooking(
        serviceid: Int,
        selections: List<AreaSlot>,
        captchaId: String,
        sliderTrackJson: String
    ): BookingResult {
        // 构造 stock 和 stockdetail 映射
        val stockMap = JsonObject()
        val stockDetailMap = JsonObject()
        val stockDetailIds = mutableListOf<String>()

        for (slot in selections) {
            stockMap.addProperty(slot.stockId.toString(), "1")
            stockDetailMap.addProperty(slot.stockId.toString(), slot.areaId.toString())
            stockDetailIds.add(slot.areaId.toString())
        }

        val param = JsonObject().apply {
            addProperty("activityPrice", 0)
            add("activityStr", null)
            addProperty("address", serviceid.toString())
            add("dates", null)
            add("extend", null)
            addProperty("flag", "0")
            add("isBulkBooking", null)
            addProperty("isbookall", "0")
            addProperty("isfreeman", "0")
            addProperty("istimes", "1")
            add("mercacc", null)
            add("merccode", null)
            add("order", null)
            add("orderfrom", null)
            add("remark", null)
            add("serviceid", null)
            addProperty("shoppingcart", "0")
            add("sno", null)
            add("stock", stockMap)
            add("stockdetail", stockDetailMap)
            addProperty("stockdetailids", stockDetailIds.joinToString(","))
            add("stockid", null)
            addProperty("subscriber", "0")
            add("time_detailnames", null)
            add("userBean", null)
            add("venueReason", null)
        }

        // 构造 yzm: {trackJson}synjones{captchaId}synjoneshttp://202.117.17.144:8071
        val yzm = "${sliderTrackJson}synjones${captchaId}synjoneshttp://202.117.17.144:8071"

        val formBody = FormBody.Builder()
            .add("param", gson.toJson(param))
            .add("yzm", yzm)
            .add("json", "true")
            .build()

        val response = execute(request("$BASE/order/book.html").post(formBody))
        val responseBody = response.body?.string() ?: "{}"
        response.close()

        Log.d(TAG, "submitBooking: response=$responseBody")

        return try {
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val result = json.get("result")?.asString
            val message = json.get("message")?.asString ?: ""
            val objElem = json.get("object")
            val obj = if (objElem != null && objElem.isJsonObject) objElem.asJsonObject else null

            if (result == "2" || obj?.has("orderid") == true) {
                val orderId = obj?.get("orderid")?.asString ?: ""
                val price = obj?.get("price")?.asDouble ?: 0.0
                BookingResult(true, orderId, price, message.ifEmpty { "预订成功，请尽快前往「移动交通大学」App 完成支付" })
            } else {
                BookingResult(false, message = message.ifEmpty { "预订失败：${responseBody.take(200)}" })
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitBooking: parse error", e)
            BookingResult(false, message = "预订失败: ${e.message}")
        }
    }

    // ─── 内部辅助 ─────────────────────────────────────────

    private fun parseAreaSlots(jsonStr: String, date: String, serviceid: String): List<AreaSlot> {
        val result = mutableListOf<AreaSlot>()
        try {
            val json = gson.fromJson(jsonStr, JsonObject::class.java)
            val arr = json.getAsJsonArray("object") ?: return emptyList()

            for (element in arr) {
                val obj = element.asJsonObject
                val areaId = obj.get("id")?.asLong ?: continue
                val areaName = obj.get("sname")?.asString ?: "场地"
                val stockId = obj.get("stockid")?.asLong ?: continue
                val stock = obj.getAsJsonObject("stock") ?: continue

                val timeNo = stock.get("time_no")?.asString ?: continue
                val price = stock.get("price")?.asDouble ?: 0.0
                val status = stock.get("status")?.asInt ?: 0
                val allCount = stock.get("all_count")?.asInt ?: 0
                val usingNum = stock.get("using_num")?.asInt ?: 0

                result.add(
                    AreaSlot(
                        areaId = areaId,
                        areaName = areaName,
                        stockId = stockId,
                        timeSlot = timeNo,
                        price = price,
                        date = date,
                        status = status,
                        allCount = allCount,
                        usingNum = usingNum,
                        serviceid = serviceid
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseAreaSlots error", e)
        }
        return result
    }
}
