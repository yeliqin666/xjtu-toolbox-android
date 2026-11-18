package com.xjtu.toolbox.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.xjtu.toolbox.data.SecurePrefs

private const val PREFS = "campus_probe"
private const val KEY_TOKEN = "ywtb_token"

/** 安卓：令牌加密存盘；只走流量又没开 VPN 时不可能在校园网里，服务器说校外就不再等探针。 */
fun CampusProbe.init(context: Context) {
    val app = context.applicationContext
    tokenStore = object : CampusProbe.TokenStore {
        override fun load(): String? = SecurePrefs.open(app, PREFS).getString(KEY_TOKEN, null)
        override fun save(token: String?) {
            SecurePrefs.open(app, PREFS).edit()
                .apply { if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token) }
                .apply()
        }
    }
    graceMs = {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val cellularOnly = caps != null &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        if (cellularOnly) 0L else CampusProbe.GRACE_MS
    }
}
