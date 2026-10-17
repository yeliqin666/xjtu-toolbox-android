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

/** `:core` 的 [ScoreReportCache] 在桌面上的实现。 */
class DesktopScoreReportCache(
    private val studentId: String,
    private val store: KeyValueStore,
    /** 旧名（[STORE_NAME]，无账号后缀）的只读兜底：新名里没有时读它一次。 */
    private val legacyStore: KeyValueStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) : ScoreReportCache {

    /** 生产装配：store 名带账号后缀 + 无后缀旧名的只读兜底。 */
    constructor(
        studentId: String,
        accountId: String? = AccountContext.activeAccountId,
        now: () -> Long = { System.currentTimeMillis() },
    ) : this(studentId, keyValueStore(storeName(accountId)), keyValueStore(STORE_NAME), now)

    private val key get() = keyFor(studentId)

    override fun read(): List<ReportedGrade>? {
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
        /** store 名基础部分；完整端内名见 [storeName]。 */
        const val STORE_NAME = "score_report"

        /** 端内名（`score_report<账号后缀>`）。 */
        fun storeName(accountId: String? = AccountContext.activeAccountId): String =
            accountScopedStoreName(STORE_NAME, accountId)

        /** 与 `:app` 的 `DataCache.DEFAULT_TTL_MS` 同一句话（7 天）。 */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000L

        /** 缓存键 `score_report_$studentId`（与 `:app` 相同）。 */
        fun keyFor(studentId: String): String = "score_report_$studentId"
    }
}
