package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.pay.PaymentCodeSource
import com.xjtu.toolbox.pay.PaymentVoucher
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeLong
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 付款码（`ncard.xjtu.edu.cn`）的 serve 版取数：给 `:web` 用，实现 [PaymentCodeSource]。 */
class CampusPayApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.7）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : PaymentCodeSource {

    /** 见类 KDoc：勾选同步端了（`POST /api/pay/vouchers/select`，契约 §5.7）⇒ 屏上那几行券照旧可勾。 */
    override val canSelectVouchers: Boolean get() = true

    /** 见类 KDoc：serve 这一端没有「本地会话」可查，真正的判据是 `/api/pay/code` 那一枪。 */
    override fun authenticate() = Unit

    override suspend fun getBarCode(): String {
        requireServe()
        val data = client.serveData("获取付款码", baseUrl, CODE_PATH)
        val barCode = data["barCode"].safeString().trim()
        if (barCode.isEmpty()) {
            error(
                "获取付款码：服务端返回的 barCode 是空的（契约 §5.7 的 data.barCode —— " +
                    "空码不能给用户扫，所以这里如实报错而不是画一张空卡）",
            )
        }
        return barCode
    }

    override suspend fun getVouchers(): List<PaymentVoucher> {
        requireServe()
        val data = client.serveData("加载加餐券", baseUrl, VOUCHERS_PATH)
        return data.arr("vouchers").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val showCardId = row["showCardId"].safeString().trim()
            if (showCardId.isEmpty()) return@mapNotNull null
            PaymentVoucher(
                showCardId = showCardId,
                voucherName = row["voucherName"].safeString(),
                typeName = row["typeName"].safeString(),
                // **分**（与 §5.5 的 `/api/coupon*` 同一个单位；除 100 是屏的事）
                amountFen = row["amountFen"].safeLong(),
                // 上游原文的「到哪天为止」（`2026-12-31`），不是 ISO-8601 的时刻
                endDate = row["endDate"].safeString(),
                // 见类 KDoc：`serverFlag`（上游 `flag`）刻意不投影 ⇒ 空串，不编一个默认值
                serverFlag = "",
            )
        }
    }

    /** 见类 KDoc：端点是 `POST /api/pay/vouchers/select`，body 只有一格 `{showCardIds}`（顺序取调用方给的先后，去重去空白；空表也照发——取消全部勾选就是空表）。 */
    override suspend fun updateVoucherStatus(selectedIds: Collection<String>) {
        requireServe()
        val ids = selectedIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val body = buildJsonObject {
            put("showCardIds", buildJsonArray { ids.forEach { add(it) } })
        }.toString()
        val outcome = client.serveAction(baseUrl, VOUCHERS_SELECT_PATH, body)
        if (!outcome.ok) error("同步加餐券勾选：${outcome.message}")
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("付款码取数", "$CODE_PATH + $VOUCHERS_PATH + $VOUCHERS_SELECT_PATH")
    }
}

/** 取码（一串能付钱的数字）。 */
private const val CODE_PATH = "/api/pay/code"

/** 可用加餐券列表（只读）。 */
private const val VOUCHERS_PATH = "/api/pay/vouchers"

/** 同步「这一轮用哪几张加餐券」（**写**：`POST /api/pay/vouchers/select`）。 */
private const val VOUCHERS_SELECT_PATH = "/api/pay/vouchers/select"
