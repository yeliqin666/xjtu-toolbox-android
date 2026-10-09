package com.xjtu.toolbox.auth

import com.xjtu.toolbox.fitness.FitnessLogin
import com.xjtu.toolbox.fitness.FitnessProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * ## 为什么它在 `:data`（而不是留在 `Sites.kt`）
 *
 * 它原来是 `Sites.kt` 里的一个类。`Sites.kt` 里的站点**逐个搬进 `:data`**（图书馆第一个、体测第二个）
 * —— 搬走一个，桌面端就多一屏真数据的可能（体测的屏与端口早在 `:core`，只差取数与会话）。
 * 它依赖 `FitnessLogin` 与 `FitnessProtocol`（两个原本都在 `:app/fitness/`，本次一起搬进来），
 * 所以三个文件是一组。搬动本身只换了目录：类名、包名、类体逐字未变（`:app` 里
 * `register(...FitnessSession())` / `FitnessApi(site)` 那些全限定调用点一行未改）。
 *
 * 体测查询的站点会话。钉死直连（`mustUseWebVpn = false`），与 jwxt/jwapp/lms/class 同策略——
 * 这些域名公网可达，多绕一层 WebVPN 网关只会更慢。
 *
 * 2026-08-01 排查记录：校外点体测必失败，直连 `tyxylp.xjtu.edu.cn` 秒回 **HTTP 502**。
 * 一度据此推断"校外不可达、应改走 WebVPN"，遂改为跟随全局模式——**实测证伪**：
 * 走 WebVPN（从校园网内部发起）拿到的仍是同一个 502。两条独立路径同样结果，
 * 说明反向代理是通的、接不到后端，即体测应用自身故障，与访问路径无关。故已改回直连。
 */
class FitnessSession : CasSiteSession("fitness", "体测查询", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        FitnessLogin(
            session = client,
            visitorId = visitorId,
            cachedRsaKey = cachedRsaKey
        )

    override fun onLoginSuccess(login: XJTULogin) {
        val fitness = login as? FitnessLogin ?: return
        val launch = fitness.launch ?: return
        FitnessProtocol.writeTokens(localToken, launch)
    }

    // 原来这里是 `Sites.kt` 底部的**文件私有** `withIo { }`（= `withContext(Dispatchers.IO)`）。
    // 搬出来之后它不可见了，而为一个调用点复制一个私有 helper 不如直接写等价的那句（语义逐字相同）。
    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val session = FitnessProtocol.sessionFromTokens(localToken) ?: return@withContext false
        val referer = localToken["referer_url"] ?: FitnessProtocol.H5_HOME_URL
        FitnessProtocol.requestUserInfo(client, session, referer) != null
    }
}
