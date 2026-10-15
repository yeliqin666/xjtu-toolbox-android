package com.xjtu.toolbox.pay

import com.xjtu.toolbox.platform.KeyValueStore

/** 一张可用的加餐券（`ncard` 的 `vouchers/list` 里的 `data[]` 一项）。 */
data class PaymentVoucher(
    val showCardId: String,
    val voucherName: String,
    val typeName: String,
    val amountFen: Long,
    val endDate: String,
    val serverFlag: String
) {
    val amountYuan: Double get() = amountFen / 100.0
}

/** 付款码的取数端口。 */
interface PaymentCodeSource {
    /** 进门第一枪的「认证」检查：实现方在自己的会话里确认「拿得到付款码的前置条件」。 */
    fun authenticate()

    /** 取付款码数字（一串十进制数字，夹具见 `:testkit` 里那份**编出来的**值）。 */
    suspend fun getBarCode(): String

    /** 取可用的加餐券；没有就返回空表。 */
    suspend fun getVouchers(): List<PaymentVoucher>

    /** 这一端能不能勾选加餐券。 */
    val canSelectVouchers: Boolean get() = true

    /** 把「这一轮用哪几张加餐券」同步给上游。 */
    suspend fun updateVoucherStatus(selectedIds: Collection<String>)
}

/** 加餐券勾选的落盘（存储名 `payment_code`，键 `selected_voucher_ids` / `voucher_selection_configured`）。 */
class PaymentSelectionStore(private val store: KeyValueStore) {

    fun selectedIds(): Set<String> {
        val raw = store.getString(KEY_SELECTED_VOUCHERS).orEmpty()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /** 用户是否亲自动过这份勾选（与「一张都没勾」区分开）。 */
    fun isConfigured(): Boolean = store.getBoolean(KEY_SELECTION_CONFIGURED, false)

    fun save(ids: Set<String>) {
        store.putString(KEY_SELECTED_VOUCHERS, ids.joinToString(","))
        store.putBoolean(KEY_SELECTION_CONFIGURED, true)
    }

    private companion object {
        const val KEY_SELECTED_VOUCHERS = "selected_voucher_ids"
        const val KEY_SELECTION_CONFIGURED = "voucher_selection_configured"
    }
}
