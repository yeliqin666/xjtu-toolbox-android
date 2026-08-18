package com.xjtu.toolbox.jiaocai1

import android.content.Context

/** 版式偏好，不是进度，不必进 Room。 */
object Jiaocai1Prefs {
    private const val NAME = "jiaocai1_reader_prefs"
    private const val KEY_VERTICAL = "vertical_scroll"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun verticalScroll(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VERTICAL, false)

    fun setVerticalScroll(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VERTICAL, value).apply()
    }
}
