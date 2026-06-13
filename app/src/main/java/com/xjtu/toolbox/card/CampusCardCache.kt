package com.xjtu.toolbox.card

import android.content.Context
import com.google.gson.Gson
import java.time.LocalDate

data class CampusCardSnapshot(
    val cardInfo: CardInfo,
    val transactions: List<Transaction>,
    val rangeStart: String,
    val rangeEnd: String,
    val savedAt: Long
)

object CampusCardCache {
    private const val PREFS = "campus_card_data_cache"
    private const val KEY = "snapshot"
    private val gson = Gson()

    fun load(context: Context): CampusCardSnapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, gson.toJson(snapshot))
            .apply()
    }
}
