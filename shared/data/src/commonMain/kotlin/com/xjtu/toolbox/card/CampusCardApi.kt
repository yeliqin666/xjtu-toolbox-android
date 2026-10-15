package com.xjtu.toolbox.card

import com.xjtu.toolbox.util.isNumber
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.longValue
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.isPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.Request
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

private const val TAG = "CampusCardApi"


class CampusCardApi(private val site: SiteSession) {

    private val baseUrl = "https://ncard.xjtu.edu.cn"

    private suspend fun execute(request: Request): String =
        site.executeWithReAuth(request).use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }

    /** 获取校园卡信息（余额、状态等） */
    suspend fun getCardInfo(): CardInfo {
        return getCardInfoInternal(allowRetry = true)
    }

    private suspend fun getCardInfoInternal(allowRetry: Boolean): CardInfo {
        val url = "$baseUrl/berserker-app/ykt/tsm/queryCard?synAccessSource=h5"
        val responseBody = execute(Request.Builder().url(url).get().build())
        Log.d(TAG, "getCardInfo: bodyLen=${responseBody.length}")
        if (CampusCardContract.looksLikeMobileRequired(responseBody)) {
            throw RuntimeException("查询校园卡要求使用移动端模式")
        }
        val root = try {
            responseBody.safeParseJsonObject()
        } catch (e: Exception) {
            throw RuntimeException("校园卡返回了异常数据，请稍后重试")
        }
        if (CampusCardContract.businessCode(root) == "401") {
            throw com.xjtu.toolbox.auth.AuthExpiredException("校园卡")
        }
        CampusCardContract.requireSuccess(root, "查询校园卡")
        val data = CampusCardContract.requireDataObject(root, "查询校园卡")
        val cardArr = CampusCardContract.requireArray(data, "card", "查询校园卡")
        if (cardArr.size == 0) throw RuntimeException("查询校园卡返回了空卡片数据")
        val cardEl = cardArr.get(0)
        if (!cardEl.isObject) throw RuntimeException("查询校园卡返回的卡片数据格式错误")
        val card = cardEl.jsonObject
        val elecAmt = CampusCardContract.requireLong(card.get("elec_accamt"), "余额", "查询校园卡")
        val unsettled = CampusCardContract.requireLong(card.get("unsettle_amount"), "未结算金额", "查询校园卡")

        return CardInfo(
            account = site.localToken["card_account"].orEmpty(),
            name = site.localToken["user_name"].orEmpty(),
            studentNo = site.localToken["student_no"].orEmpty(),
            balance = elecAmt / 100.0,
            pendingAmount = unsettled / 100.0,
            lostFlag = card.get("barflag")?.intValue == 1,
            frozenFlag = card.get("freezeflag")?.intValue == 1,
            expireDate = formatExpDate(card.get("expdate")?.stringValue ?: ""),
            cardType = card.get("cardname")?.stringValue?.trim() ?: ""
        )
    }

    companion object {
        /** 食堂档口/品牌名碎片。顺序不重要；「超市」类必须在 classify 里先判。 */
        private val FOOD_MERCHANT_KEYS = arrayOf(
            "食", "餐", "食堂", "面", "饭", "粥", "菜", "吧台", "咖啡",
            "饮", "小面", "米线", "饸络", "凉皮", "卤", "削筋", "称量",
            "自助", "档口", "窗口", "烧烤", "奶茶", "豆浆", "包子", "饺子",
            "炒", "烩", "煮", "蒸", "时光", "美食", "小吃", "麻辣", "烤", "煎",
            "馒头", "饼", "糕", "果汁", "茶", "鸡", "鱼", "肉", "蛋",
            // 2026-08 缓存里漏进「其他」的档口
            "苑", "粉", "粉丝", "瓦罐", "寿司", "日料", "小笼", "馄饨", "汤包",
            // 「饺」而不是「饺子」：珍念水饺、轻食水饺这类写的是「水饺」，
            // 只配「饺子」会整家店漏进「其他」（实测 8 笔 ¥144 就是这么漏的）
            "饺", "自选", "豆花", "豆苗", "江记", "旧迹", "丸子", "肠粉",
            "迈德思客", "麦当劳", "肯德基", "汉堡", "披萨", "必胜客",
            "风味", "拉面", "米皮", "凉粉", "胡辣汤", "砂锅", "麻食",
        )

        /** 餐饮内部的主食分类。 */
        private val FOOD_SUB_RULES: List<Pair<String, Array<String>>> = listOf(
            "饮品" to arrayOf("水吧", "吧台", "咖啡", "奶茶", "茶", "果汁", "豆浆", "饮"),
            "汤粥" to arrayOf("粥", "汤", "瓦罐", "砂锅", "胡辣", "馄饨", "丸子"),
            "饺包" to arrayOf("饺", "包子", "小笼", "生煎", "馒头"),
            "自选" to arrayOf("自选", "自助", "称量", "智盘", "菜组", "小碗菜", "蒸菜"),
            "米饭" to arrayOf("饭", "盖浇", "烧腊", "煲"),
            "面食" to arrayOf("面", "粉", "米线", "饸络", "凉皮", "米皮", "麻食", "削筋"),
            // 小吃排在最后，当兜底用：这里的「卤」「鱼」「炒」不会抢走前面的分类，
            // 卤肉饭/酸菜鱼米饭先被「米饭」接走，五谷鱼粉先被「面食」接走。
            "小吃" to arrayOf(
                "小吃", "肉夹馍", "肠粉", "饼", "糕", "烧烤", "串", "寿司", "日料", "豆花",
                "快餐", "炒", "卤", "鱼", "迈德思客",
            ),
        )
    }

    /** 获取交易流水（分页） */
    suspend fun getTransactions(
        // 日期参数是 kotlinx-datetime 的（:core 的屏与端口都用它）；默认值按学校时区的今天
        // （[todayOnCampus]，不跟运行机器的时区走），减三个月是日历减法。
        startDate: LocalDate = todayOnCampus().minus(3, DateTimeUnit.MONTH),
        endDate: LocalDate = todayOnCampus(),
        page: Int = 1,
        pageSize: Int = 30
    ): Pair<Int, List<Transaction>> = getTransactionsInternal(startDate, endDate, page, pageSize, allowRetry = true)

    private suspend fun getTransactionsInternal(
        startDate: LocalDate,
        endDate: LocalDate,
        page: Int,
        pageSize: Int,
        allowRetry: Boolean
    ): Pair<Int, List<Transaction>> {
        if (page <= 0 || pageSize <= 0) {
            throw RuntimeException("查询校园卡流水的分页参数必须为正数")
        }
        val url = "$baseUrl/berserker-search/search/personal/turnover" +
            "?size=$pageSize&current=$page" +
            "&timeFrom=$startDate&timeTo=$endDate" +
            "&synAccessSource=h5"

        val responseBody = execute(Request.Builder().url(url).get().build())

        Log.d(TAG, "getTransactions: page=$page, bodyLen=${responseBody.length}")
        if (CampusCardContract.looksLikeMobileRequired(responseBody)) {
            throw RuntimeException("查询校园卡流水要求使用移动端模式")
        }

        val root = try {
            responseBody.safeParseJsonObject()
        } catch (e: Exception) {
            throw RuntimeException("交易记录返回了异常数据，请稍后重试")
        }
        if (CampusCardContract.businessCode(root) == "401") {
            throw com.xjtu.toolbox.auth.AuthExpiredException("校园卡")
        }
        CampusCardContract.requireSuccess(root, "查询校园卡流水")
        val data = CampusCardContract.requireDataObject(root, "查询校园卡流水")
        val total = CampusCardContract.requireLong(data.get("total"), "流水总数", "查询校园卡流水").toInt()
        if (total < 0) throw RuntimeException("查询校园卡流水返回的流水总数格式错误")
        val records = CampusCardContract.requireArray(data, "records", "查询校园卡流水")

        val transactions = records.map { recEl ->
            if (!recEl.isObject) throw RuntimeException("查询校园卡流水返回的流水记录格式错误")
            val rec = recEl.jsonObject
            val tranAmt = CampusCardContract.requireLong(rec.get("tranamt"), "流水金额", "查询校园卡流水")
            val icon = rec.get("icon")?.stringValue ?: ""
            val turnoverType = rec.get("turnoverType")?.stringValue?.trim() ?: ""
            val resume = rec.get("resume")?.stringValue?.trim() ?: ""
            // takeIf 不能省：toMerchant 常常是空字符串而不是 null（扫码点餐、充值）。
            val merchant = rec.get("toMerchant")?.stringValue?.trim()?.takeIf { it.isNotBlank() }
                ?: merchantFromResume(resume)
            val typeFrom = CampusCardContract.typeFromOf(rec.get("typeFrom"))
            val toAccount = rec.get("toAccount")
                ?.takeIf { it.isPrimitive && it.jsonPrimitive.isNumber }?.longValue
            val fromAccount = rec.get("fromAccount")?.stringValue?.trim()?.toLongOrNull()
            Transaction(
                time = rec.get("jndatetimeStr")?.stringValue ?: "",
                merchant = merchant,
                amount = CampusCardContract.signedAmountCents(tranAmt, turnoverType, icon, typeFrom, toAccount, fromAccount) / 100.0,
                balance = CampusCardContract.requireLong(rec.get("cardBalance"), "流水余额", "查询校园卡流水") / 100.0,
                type = turnoverType,
                description = resume
            )
        }

        return total to transactions
    }

    private fun formatExpDate(raw: String): String {
        if (raw.length != 8) return raw
        return "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
    }
}
