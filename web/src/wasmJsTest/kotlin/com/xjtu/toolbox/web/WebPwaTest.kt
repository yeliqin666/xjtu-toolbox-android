package com.xjtu.toolbox.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PWA（D12 ④）在这一端**能在测试浏览器里验的那几件**。
 *
 * ## 验了什么
 *
 * 这个测试源集跑在**真浏览器**里（karma + Chrome headless，地址是 `localhost` ⇒ 安全上下文）：
 * `?route=probe` 那张 PWA 卡读的就是这几个 getter，所以这里钉的是「卡上那三行读的是**真事实**」。
 *
 * ## **没验**什么（如实，不假装）
 *
 * - **Service Worker 真的注册成功**：这一条在 CI 里没验到 —— karma 的这一页是测试包自己的页面，
 *   **没有**我们的 `index.html`（也就没有 `<link rel="manifest">`、没跑 `pwa.js`）。所以下面最后一条
 *   反过来把「这一份页面没有 manifest ⇒ 那一行是 ✗」钉住（那张卡读的是**这一份页面**的事实）。
 *   真注册那一跳的证据只有两条：① `sw.js` / `pwa.js` / `manifest.webmanifest` / `icons/…` 真随
 *   `wasmJsProcessResources` 进产物（列目录即见）；② 手工在真浏览器里核过 manifest 的 mime 与 JSON
 *   （见 `docs/desktop-port-plan.md` §16.12）—— 注册结果本身**没人见过**。
 * - **系统通知真弹出来**：同上（要用户手势 + 一个真的通知权限，测试环境里给不了）。
 * - 本仓另外记着一条**采集坑**：agent 自己开的那颗浏览器里 `window.isSecureContext` 是 `undefined`、
 *   `navigator.serviceWorker.register()` 的 Promise 一直挂着（§16.11 记过它画不出渐变）⇒ **别拿它当
 *   判据**，要看安全上下文请用 karma 这颗（`WebBrowserCapabilitiesTest` 已经钉住它是 true）。
 */
class WebPwaTest {

    @Test
    fun `Service Worker 与系统通知在这一份部署里都在`() {
        assertTrue(WebPwa.serviceWorkerSupported(), "localhost 上 navigator.serviceWorker 应当在")
        assertTrue(WebPwa.notificationSupported(), "localhost 上 Notification 应当在")
        assertTrue(
            WebPwa.notificationPermission() in setOf("default", "granted", "denied"),
            "Notification.permission 应当是一个真值，实际是「${WebPwa.notificationPermission()}」",
        )
    }

    @Test
    fun `后台定时那一档：只报 API 表面，屏上说明如实写着「基本不给」`() {
        // ⚠️ 这里**不**主张「这一档能用/不能用」：Chrome 桌面把 periodicSync 挂在
        // `ServiceWorkerRegistration.prototype` 上（实测 true），但普通页面里那个权限是 denied ——
        // 「能不能真用」要「装成 PWA 且站点参与度够」，本仓**没验过**（也不打算做）。
        // 所以屏上那条说明写的是「基本不给」，这条用例钉的就是那句话。
        assertTrue(WebPwa.widgetNote.contains("基本不给"), "说明里要如实写 Chrome 桌面那一条：${WebPwa.widgetNote}")
        // 这一条是**形状探针**：karma 那颗 Chrome 的 `ServiceWorkerRegistration.prototype` 上有
        // `periodicSync`（实测 true）。哪天它没了/换了形状，说明这一档的说明该重写 —— 那正是这条的意义。
        assertTrue(
            WebPwa.periodicSyncApi(),
            "Chrome 桌面把 `periodicSync` 挂在 `ServiceWorkerRegistration.prototype` 上（实测）；" +
                "这条红了 = 它改形状了，回去重读 §16.12 里那一档的说明",
        )
    }

    @Test
    fun `小组件那一档的说明里写着「没有对应物」`() {
        assertTrue(WebPwa.widgetNote.contains("没有对应物"), "说明里要写清「不是没做，是浏览器里没有这个载体」")
        assertTrue(WebPwa.widgetNote.contains("periodicSync"), "后台定时那条也要一起写清")
    }

    @Test
    fun `那张卡读的是「这一份页面」的事实（karma 这一页没带 manifest ⇒ 会如实显示 ✗）`() {
        assertTrue(
            WebPwa.manifestHref().isEmpty(),
            "karma 的测试页不是我们的 index.html ⇒ 没有 manifest link；" +
                "有值说明这一页里被人塞了一个 —— 那这一条就该跟着改",
        )
        assertFalse(WebPwa.serviceWorkerReady(), "测试页不跑 pwa.js ⇒ 没有注册过")
        assertTrue(WebPwa.registrationError().isEmpty(), "没试过注册时不该编一个错出来")
    }
}
