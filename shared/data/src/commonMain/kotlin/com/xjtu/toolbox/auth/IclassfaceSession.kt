package com.xjtu.toolbox.auth

import com.xjtu.toolbox.iclassface.IclassfaceLogin
import okhttp3.OkHttpClient

/** 人脸识别签到（`iclassface.xjtu.edu.cn`）的站点会话。 */
class IclassfaceSession : CasSiteSession("iclassface", "快速考勤流水", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        IclassfaceLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.ICLASSFACE_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "iclassface"
    }
}
