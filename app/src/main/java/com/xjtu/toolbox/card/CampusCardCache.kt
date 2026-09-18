package com.xjtu.toolbox.card

import android.content.Context
import com.google.gson.Gson
import com.xjtu.toolbox.account.AccountContext
import java.time.LocalDate

data class CampusCardSnapshot(
    val cardInfo: CardInfo,
    val transactions: List<Transaction>,
    val rangeStart: String,
    val rangeEnd: String,
    val savedAt: Long
)

object CampusCardCache {
    private const val PREFS_PREFIX = "campus_card_data_cache"
    private const val KEY = "snapshot"
    private val gson = Gson()

    private fun prefsName(accountId: String? = AccountContext.activeAccountId): String =
        PREFS_PREFIX + AccountContext.suffixFor(accountId)

    fun load(context: Context): CampusCardSnapshot? {
        val raw = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return runCatching {
            gson.fromJson(raw, CampusCardSnapshot::class.java)?.let { snapshot ->
                // cardInfo 本身也可能因缺字段被 Gson 置空；整份快照没有卡信息就没意义，当无缓存处理。
                val cardInfo = (snapshot.cardInfo as CardInfo?)?.sanitized() ?: return@let null
                snapshot.copy(
                    cardInfo = cardInfo,
                    transactions = (snapshot.transactions as List<Transaction>?)
                        ?.map { it.sanitized() }
                        ?: emptyList(),
                )
            }
        }.getOrNull()
    }

    fun save(
        context: Context,
        cardInfo: CardInfo,
        transactions: List<Transaction>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate
    ) {
        val snapshot = CampusCardSnapshot(
            cardInfo = cardInfo,
            transactions = transactions,
            rangeStart = rangeStart.toString(),
            rangeEnd = rangeEnd.toString(),
            savedAt = System.currentTimeMillis()
        )
        context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, gson.toJson(snapshot))
            .apply()
    }

    /** 删除指定账号（默认当前账号）的校园卡缓存。删除账号时传被删的那个。 */
    fun clear(context: Context, accountId: String? = AccountContext.activeAccountId) {
        context.getSharedPreferences(prefsName(accountId), Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 当前账号命名空间下的校园卡余额/流水缓存 SharedPreferences。 */
    fun cardPrefs(context: Context): android.content.SharedPreferences {
        return context.getSharedPreferences("campus_card" + AccountContext.safeSuffix(), Context.MODE_PRIVATE)
    }
}
