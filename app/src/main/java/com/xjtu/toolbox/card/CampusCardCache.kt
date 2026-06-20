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

    private fun prefsName(): String = PREFS_PREFIX + AccountContext.safeSuffix()

    fun load(context: Context): CampusCardSnapshot? {
        val raw = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return runCatching { gson.fromJson(raw, CampusCardSnapshot::class.java) }.getOrNull()
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

    /** 删除当前账号的校园卡缓存（切换/删除账号时调用）。 */
    fun clear(context: Context) {
        context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 当前账号命名空间下的校园卡余额/流水缓存 SharedPreferences。 */
    fun cardPrefs(context: Context): android.content.SharedPreferences {
        return context.getSharedPreferences("campus_card" + AccountContext.safeSuffix(), Context.MODE_PRIVATE)
    }
}
