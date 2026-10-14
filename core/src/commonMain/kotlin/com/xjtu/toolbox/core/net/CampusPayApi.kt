package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.pay.PaymentCodeSource
import com.xjtu.toolbox.pay.PaymentVoucher
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeLong
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject

/**
 * 付款码（`ncard.xjtu.edu.cn`）的 **serve 版取数**：给 `:web` 用，实现 [PaymentCodeSource]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/pay/code` | [getBarCode]（`authenticate()` 那一枪由本类自己走，见下） |
 * | `GET /api/pay/vouchers` | [getVouchers] |
 *
 * 契约 §5.7；`:server` 那一侧是 `PayRoutes.kt`（薄包装 `:data` 的 `PaymentCodeApi`，
 * 借的是**校园卡**那个会话 —— 付款码的业务请求带的就是校园卡登录时存下的那张 JWT）。
 *
 * ## 两处**如实不画**（都在端口上留了开关）
 *
 * 1. **条码 / 二维码矩阵画不出来**：`qrCodeModules` / `code128Modules` 的 **wasmJs actual 如实返回
 *    `null`**（`core/src/wasmJsMain/.../CodeMatrix.wasmJs.kt`：这一端没有编码器，zxing 只有
 *    JVM/Android 那一份 jar）⇒ 屏上只画付款码**数字**那一档（纯文本，不依赖编码器），两个矩阵
 *    一个都不画 —— 不是画一张假的。这不是本类的事（本类只给那一串数字），记在这里是为了让
 *    「为什么浏览器里看不到码图」有处可查；
 * 2. **勾选加餐券是写路径，不端**：`updateVoucherStatus(ids)`（`POST .../vouchers/updateVoucherStatus`）
 *    会把「这一轮用哪几张」**真的推给上游**（契约 §5.7 的「刻意不端的写路径」）⇒
 *    [canSelectVouchers] 报 `false`，屏上那几行券**不画勾选框、也不可点**，只当只读展示
 *    （[updateVoucherStatus] 自己照旧抛错，见那个方法）。
 *
 * ⚠️ [getBarCode] 拿到的**就是能付钱的东西**（登录者本人的一次性凭据）：它不在 §5.2 那四个红线
 * 字段里，但敏感度不比它们低。serve 的默认姿势（只监听回环 + 令牌闸门）是它的前提 ——
 * `--host 0.0.0.0` 部署必须自己加反代与鉴权（契约 §3.3 / §5.7）。
 *
 * ## [authenticate] 在浏览器这一端**没有本地判据**
 *
 * 端口上那一枪的原意是「实现方在自己的会话里确认拿得到付款码的前置条件」（`:data` 的实现是
 * **本地**看一眼会话里有没有那张校园卡 JWT，不发请求）。serve 形态下那份会话在**服务端进程**里，
 * 页面上没有可看的东西 ⇒ 这里如实什么都不做：真正的判据是 `/api/pay/code` 那一枪本身
 *（没会话时服务端答 `401` + 「请先登录」），屏按 `.onFailure` 显示那句中文短句。
 * 编一个假的本地检查（比如恒真）只会让「认证失败」看起来像是本端已经确认过了。
 *
 * ## 缺字段不编造
 *
 * - `barCode` 缺失 / 空白 ⇒ [getBarCode] **抛错**（`data.barCodeVo.barcode` 那一串空了说明上游
 *   不对劲，画一个空码等于让用户对着空白扫 —— 屏会显示这句错，而不是显示一张空卡）；
 * - `showCardId` 为空的那几行券**不列**（它是勾选与后续动作的句柄，也与 §5.5 加餐券同一条口径）；
 * - `serverFlag`（上游 `flag`）**不投影**（§5.7：`:core` 的屏一处都没读它）⇒ 模型那一格给**空串**，
 *   不是编一个默认值。
 *
 * ## 默认形态（campus-api）
 *
 * 付款码在 campus-api 里没有对应端点（`:app` / 桌面走 `:data` 的 okhttp）⇒ 非 serve 分支一律
 * [serveOnly]（抛错，不返回一份空数据）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
class CampusPayApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.7）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : PaymentCodeSource {

    /** 见类 KDoc：勾选同步是写路径（契约 §5.7 不端）⇒ 屏上那几行券只读，不画勾选框。 */
    override val canSelectVouchers: Boolean get() = false

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

    /**
     * 见类 KDoc：勾选同步是**写路径**（真会改上游状态），serve 契约 §5.7 不端 ⇒ 这里照旧抛错。
     * 正常路径上它不会被调用：屏按 [canSelectVouchers] 把勾选框整个不画。
     */
    override suspend fun updateVoucherStatus(selectedIds: Collection<String>): Unit =
        throw UnsupportedOperationException(
            "serve 契约没有「同步加餐券勾选」的端点：`PaymentCodeSource.updateVoucherStatus`" +
                "（POST .../vouchers/updateVoucherStatus）真会改上游状态，属写路径（docs/api-contract.md §5.7）" +
                "—— 屏上那几行券只读、不画勾选框（PaymentCodeSource.canSelectVouchers = false）",
        )

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("付款码取数", "$CODE_PATH + $VOUCHERS_PATH")
    }
}

/** 取码（一串能付钱的数字）。 */
private const val CODE_PATH = "/api/pay/code"

/** 可用加餐券列表（只读）。 */
private const val VOUCHERS_PATH = "/api/pay/vouchers"
