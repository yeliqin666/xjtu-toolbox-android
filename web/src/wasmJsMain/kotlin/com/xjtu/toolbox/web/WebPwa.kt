@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

/**
 * 浏览器这一端的 **PWA**（D12 ④ 的最小可行方案）：这一份部署里「装成 App」与「系统通知」各自到哪一步。
 *
 * ## 三段的关系（页面上那张卡就是按这三段列的）
 *
 * | 那一段 | 在哪做的 | 屏上怎么看 |
 * |---|---|---|
 * | `manifest.webmanifest`（名称 / 图标 / `display: standalone`） | 静态文件（`resources/` + `index.html` 里的 `<link rel="manifest">`） | 「PWA 可安装（manifest）」那一行 —— **这一份页面里有没有那个 link** |
 * | Service Worker（要注册） | `resources/pwa.js` 在页面加载时注册 `sw.js`（**只在安全上下文里**） | 「Service Worker（离线壳 + 装到桌面）」那一行 + 注册失败时那一句原因 |
 * | 系统通知 | 用户手势里要权限（[requestNotificationPermission]）→ 经 SW `showNotification`（[showTestNotification]） | 「系统通知」那一行 + 两颗按钮 |
 *
 * ## 三处如实（都在屏上写出来，不假装）
 *
 * 1. **Android 小组件在浏览器里没有对应物** —— App 的主屏小组件（课表 / 空教室那两块）在这一端
 *    **不做**，不是「还没做」：浏览器里没有这种载体（与桌面那张 `HostUnavailableScreen` 同一种诚实写法；
 *    见 [widgetNote]）。
 * 2. **后台定时（`periodicSync`）也基本没有** —— Chrome 只在「已经装成 PWA 且站点参与度够」之后才给
 *    `periodicBackgroundSync`，桌面浏览器上甚至不给 ⇒ 不画。
 * 3. **不做自定义的「安装」按钮** —— `beforeinstallprompt` 只被记下来（[installable]），装不装由用户点
 *    浏览器自己那条入口说了算（那一枪我们也不拦：`pwa.js` 里**没有** `preventDefault()`）。
 *
 * 离线壳的策略（网络优先 + 拿不到才回落）写在 `sw.js` 的文件头里；这一端不重复。
 */
internal object WebPwa {

    /** 页面上有没有 `<link rel="manifest">`（有就是「这一份部署带了 manifest」）。 */
    fun manifestHref(): String = pwaManifestHref()

    /** 浏览器有没有 Service Worker 这一档（安全上下文是注册的前提，但不是这一条 —— 见 [registrationError]）。 */
    fun serviceWorkerSupported(): Boolean = pwaServiceWorkerSupported()

    /** 这一份页面里 Service Worker **注册成功了吗**（`pwa.js` 写进全局的那个标志）。 */
    fun serviceWorkerReady(): Boolean = pwaServiceWorkerReady()

    /** 没注册成功的原因（注册没试过 / 不是安全上下文 / 浏览器不给），没有就是空串。 */
    fun registrationError(): String = pwaRegistrationError()

    /**
     * `periodicSync` 这个 **API 表面**在不在 —— **不等于能用**（所以不画成一行 ✓/✗）。
     *
     * 实测（karma 那颗 Chrome 153）：`'periodicSync' in ServiceWorkerRegistration.prototype` 是 **true**，
     * 但普通页面里那个权限（`navigator.permissions.query({ name: 'periodic-background-sync' })`）
     * 是 `denied` —— Chrome 只在「已经装成 PWA **且**站点参与度够」之后才可能给。
     * 所以屏上那条说明写的是「基本不给」，而不是「这个浏览器没有」。
     */
    fun periodicSyncApi(): Boolean = pwaHasPeriodicSync()

    /** 浏览器报「这一个站点可安装」了吗（`beforeinstallprompt` 来过一次）。 */
    fun installable(): Boolean = pwaInstallable()

    fun notificationSupported(): Boolean = pwaHasNotification()

    /** `Notification.permission`：`granted` / `denied` / `default`；不支持时是空串。 */
    fun notificationPermission(): String = pwaNotificationPermission()

    /**
     * 要一次通知权限。**必须由用户手势触发**（浏览器会拒掉不是手势里发出来的那一枪）——
     * 所以它只挂在 `?route=probe` 那张卡的一颗按钮上，不在进屏时自动问。
     */
    fun requestNotificationPermission(onResult: (String) -> Unit) {
        pwaRequestNotification(onResult)
    }

    /** 经 Service Worker 发一条通知（顺带证明 `sw.js` 真活着、`notificationclick` 那一段也在）。 */
    fun showTestNotification(title: String, body: String, onResult: (Boolean, String) -> Unit) {
        pwaShowNotification(title, body, onResult)
    }

    /** 小组件那一档为什么不做（屏上那一行原文）。 */
    const val widgetNote: String =
        "Android 主屏小组件（课表 / 空教室）在浏览器里**没有对应物** ⇒ 这一档不做（不是没做，是没有载体）。" +
            "后台定时（periodicSync）同理：Chrome 桌面普通页面里那个权限就是 denied，只在「已装成 PWA 且站点参与度够」之后才可能给 ⇒ 基本不给。"
}

@JsFun("() => { const l = document.querySelector('link[rel=manifest]'); return l && l.href ? l.href : ''; }")
private external fun pwaManifestHref(): String

@JsFun("() => ('serviceWorker' in window.navigator)")
private external fun pwaServiceWorkerSupported(): Boolean

@JsFun("() => { const s = globalThis.__xjtuToolboxPwa; return !!(s && s.ready); }")
private external fun pwaServiceWorkerReady(): Boolean

@JsFun("() => { const s = globalThis.__xjtuToolboxPwa; return (s && s.error) ? String(s.error) : ''; }")
private external fun pwaRegistrationError(): String

@JsFun("() => ('periodicSync' in (window.ServiceWorkerRegistration ? window.ServiceWorkerRegistration.prototype : {}))")
private external fun pwaHasPeriodicSync(): Boolean

@JsFun("() => { const s = globalThis.__xjtuToolboxPwa; return !!(s && s.installPrompt); }")
private external fun pwaInstallable(): Boolean

@JsFun("() => ('Notification' in window)")
private external fun pwaHasNotification(): Boolean

@JsFun("() => ('Notification' in window) ? String(Notification.permission) : ''")
private external fun pwaNotificationPermission(): String

/**
 * `Notification.requestPermission()` 走回调（`@JsFun` 的互操作里函数与字符串都能过门）。
 *
 * 浏览器不保证一定返回 Promise（老版 Safari 是回调式），所以两边都接上。
 */
@JsFun(
    """
(onResult) => {
  if (!('Notification' in window)) { onResult('unsupported'); return; }
  const done = (value) => onResult(String(value));
  try {
    const result = Notification.requestPermission(done);
    if (result && result.then) { result.then(done, () => done('denied')); }
  } catch (e) {
    done('denied');
  }
}
""",
)
private external fun pwaRequestNotification(onResult: (String) -> Unit)

/** 经 Service Worker 发通知：`registration.showNotification`（没有 SW 就如实说）。 */
@JsFun(
    """
(title, body, onResult) => {
  if (!('serviceWorker' in window.navigator)) { onResult(false, '这个浏览器没有 Service Worker'); return; }
  window.navigator.serviceWorker.ready.then(
    (registration) => registration.showNotification(title, {
      body: body,
      icon: 'icons/icon-192.png',
      tag: 'xjtu-toolbox-test'
    }).then(() => onResult(true, ''), (e) => onResult(false, (e && e.message) ? e.message : String(e))),
    (e) => onResult(false, 'Service Worker 还没就绪：' + ((e && e.message) ? e.message : String(e)))
  );
}
""",
)
private external fun pwaShowNotification(title: String, body: String, onResult: (Boolean, String) -> Unit)
