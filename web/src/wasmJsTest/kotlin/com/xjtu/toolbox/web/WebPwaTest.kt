package com.xjtu.toolbox.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PWA在这一端能在测试浏览器里验的那几件。 */
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
        // 不断言这一档能不能用（要装成 PWA 且参与度够，没验过），只钉屏上那句说明。
        assertTrue(WebPwa.widgetNote.contains("基本不给"), "说明里要如实写 Chrome 桌面那一条：${WebPwa.widgetNote}")
        // 形状检查：karma 的 Chrome 上 `ServiceWorkerRegistration.prototype` 有 `periodicSync`。
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
