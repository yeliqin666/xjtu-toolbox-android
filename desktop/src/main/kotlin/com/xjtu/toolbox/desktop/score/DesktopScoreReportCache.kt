package com.xjtu.toolbox.desktop.score

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.desktop.accountScopedStoreName
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.score.ScoreReportCache
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * `:core` 的 [ScoreReportCache] 在桌面上的实现 —— 与 `:app` 的 `appScoreReportCache`
 * （`score/ScoreReportApp.kt`）**同一个缓存键**：`score_report_$studentId`。
 *
 * ## 为什么键必须一样
 *
 * 键里带着学号是搬迁前就定下的口径（换账号 / 换学号就得换键，不然会串号）。桌面上这条路由
 * （`AppRoute.ScoreReport`）与 `:app` 是同一份屏、同一份取数，缓存的**身份**也该是同一个 ——
 * 所以这个类不自造键，也不像 Web 那份（`WebScoreReportCache`，键就叫 `all`，浏览器里没有账号）
 * 那样退化成单键。
 *
 * ## 与 `:app` 那份的差异（都在下面这两处，如实）
 *
 * | | `:app`（DataCache） | 桌面（这里） |
 * |---|---|---|
 * | 落点 | `cacheDir` 下按账号隔离的 JSON 文件 | `:core` 平台的键值缝（JVM 侧是一份落盘 Properties） |
 * | 旧值的年龄 | 文件的 `lastModified()` | 值里带着 `at`（键值缝没有 mtime 这一档） |
 *
 * TTL 与 `:app` 同一条：`DataCache.DEFAULT_TTL_MS`（7 天）。过期读到就删掉那个键、返回 null，
 * 于是屏幕照旧去拉一份新的 —— 与 DataCache 过期时删文件是同一个行为。
 *
 * [store] / [legacyStore] 与 [now] 可注入：测试用一次性存储与固定时钟把「过期 / 坏值 / 换学号」三档钉住。
 *
 * ## S7b：端内名带账号后缀
 *
 * 键里那个学号不变（与 `:app` 同键的约束在上面），变的只有 **store 名**：
 * `score_report` → `score_report<账号后缀>`（[storeName]）。加后缀之前那份旧名会被**只读**读一次
 * （[legacyStore]），所以旧缓存不会因为这次改名而丢。
 */
class DesktopScoreReportCache(
    private val studentId: String,
    private val store: KeyValueStore,
    /**
     * S7b 之前那份旧名（[STORE_NAME]，无账号后缀）的**只读**兜底：新名里没有这个键时读它一次
     * （§6.1 第 2 条「旧形状只读」）。默认 null：测试注入 [store] 时不该顺带读到隔离根之外的旧文件。
     */
    private val legacyStore: KeyValueStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) : ScoreReportCache {

    /** 生产装配（S7b）：store 名带账号后缀 + 旧名（无后缀那份）的只读兜底。 */
    constructor(
        studentId: String,
        accountId: String? = AccountContext.activeAccountId,
        now: () -> Long = { System.currentTimeMillis() },
    ) : this(studentId, keyValueStore(storeName(accountId)), keyValueStore(STORE_NAME), now)

    private val key get() = keyFor(studentId)

    override fun read(): List<ReportedGrade>? {
        // S7b：新名里没有这个键时，读一次旧名那份（旧形状只读）
        val raw = store.getString(key) ?: legacyStore?.getString(key) ?: return null
        val obj = runCatching { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val at = (obj["at"] as? JsonPrimitive)?.longOrNull ?: return null
        if (now() - at > TTL_MS) {
            store.remove(key)
            return null
        }
        val grades = obj["grades"]?.jsonArray ?: return null
        return runCatching {
            AppJson.decodeFromJsonElement(ListSerializer(ReportedGrade.serializer()), grades)
        }.getOrNull()
    }

    override fun write(grades: List<ReportedGrade>) {
        val payload = buildJsonObject {
            put("at", now())
            put("grades", AppJson.encodeToJsonElement(ListSerializer(ReportedGrade.serializer()), grades))
        }
        runCatching { store.putString(key, payload.toString()) }
    }

    companion object {
        /** 桌面这一端的 store 名基础部分（S7b 之前没有后缀）；完整端内名见 [storeName]。 */
        const val STORE_NAME = "score_report"

        /** 端内名（`score_report<账号后缀>`）—— 测试清场与「落盘文件在哪儿」共用这一处口径。 */
        fun storeName(accountId: String? = AccountContext.activeAccountId): String =
            accountScopedStoreName(STORE_NAME, accountId)

        /** 与 `:app` 的 `DataCache.DEFAULT_TTL_MS` 同一句话（7 天）。 */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000L

        /**
         * 缓存键：与 `:app` 的 `appScoreReportCache` **逐字相同**（`score_report_$studentId`）。
         * 单独一个函数是为了让测试能钉住这一点，而不是把键抄一遍。
         */
        fun keyFor(studentId: String): String = "score_report_$studentId"
    }
}
