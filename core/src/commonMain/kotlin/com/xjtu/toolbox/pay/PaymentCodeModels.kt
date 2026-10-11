package com.xjtu.toolbox.pay

import com.xjtu.toolbox.platform.KeyValueStore

/**
 * 一张可用的加餐券（`ncard` 的 `vouchers/list` 里的 `data[]` 一项）。
 *
 * 从 `:app` 的 `pay/PaymentCodeApi.kt` 同包同名字段搬进 `:core`：它现在同时被**取数实现**
 * （`:data` 的 `PaymentCodeApi`）和**屏**（本包的 `PaymentCodeScreen`）用到，而屏在 `:core` ⇒
 * 模型必须住在两边都看得见的那一层。
 */
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

/**
 * 付款码的取数端口 —— 屏幕只认这个接口（照交接文档 §2 的「一屏 = 屏 + 端口 + 各端一个实现」）。
 *
 * 实现：`:data` 的 `PaymentCodeApi`（`ncard.xjtu.edu.cn`，Android 与桌面**同一份**）。
 * `:app` 与 `:desktop` 都直接把那个类当端口传进来，所以两端没有各写一份取数。
 *
 * ⚠️ **IO 调度由实现方负责**：搬迁前 `:app` 的屏在每个调用点外面套了一层
 * `withContext(Dispatchers.IO)`，而 `wasm` 没有 IO 调度器（`commonMain` 里根本没有这个名字）。
 * `PaymentCodeApi` 走的是 `SiteSession.executeWithReAuth`，它内部已经是
 * `withContext(Dispatchers.IO)`（见那个函数的实现）⇒ 拆掉屏上那层包装不改变任何一次调用的
 * 线程语义，只少一层调度。
 */
interface PaymentCodeSource {
    /**
     * 进门第一枪的「认证」检查：实现方在自己的会话里确认「拿得到付款码的前置条件」。
     * 失败（没登录 / 会话失效）抛异常，屏按「认证失败/超时」那一档显示。
     */
    fun authenticate()

    /** 取付款码数字（一串十进制数字，夹具见 `:testkit` 里那份**编出来的**值）。 */
    suspend fun getBarCode(): String

    /** 取可用的加餐券；没有就返回空表。 */
    suspend fun getVouchers(): List<PaymentVoucher>

    /** 把「这一轮用哪几张加餐券」同步给上游。 */
    suspend fun updateVoucherStatus(selectedIds: Collection<String>)
}

/**
 * 加餐券勾选的落盘 —— 与搬迁前 `:app` 那份**同一个存储名、同一批键**
 * （`payment_code` 的 `selected_voucher_ids` / `voucher_selection_configured`）。
 *
 * 存储从 `Context.getSharedPreferences("payment_code")` 换成 [KeyValueStore] 缝：
 *  - Android：`keyValueStore("payment_code")` **就是** `getSharedPreferences("payment_code")`
 *    （同一个文件 ⇒ 老用户勾过的券一张不丢）；
 *  - 桌面：宿主传落盘的键值缝（`secureKeyValueStore("payment_code")`，一份 0600 的 Properties），
 *    所以勾选**真落盘**、下次打开还在。
 *
 * 由宿主构造后传进屏（而不是屏自己去 `keyValueStore(name)`），正是为了让「桌面那份落盘存储」
 * 与「Android 那份 SharedPreferences」能各自接到同一段逻辑上。
 */
class PaymentSelectionStore(private val store: KeyValueStore) {

    fun selectedIds(): Set<String> {
        val raw = store.getString(KEY_SELECTED_VOUCHERS).orEmpty()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /**
     * 用户**是否亲自动过**这份勾选（与「一张都没勾」区分开）。
     * 只有动过才在出码时把选择同步给上游：没动过就同步会把「一张不用」推上去。
     */
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
