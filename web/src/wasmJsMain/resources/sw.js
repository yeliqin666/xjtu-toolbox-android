/*
 * 西交工具箱（Web）的 Service Worker —— D12 ④ 的最小可行方案里那一段（2026-10-16）。
 *
 * 只做三件**看得见**的事，别的一概不碰：
 *
 *  1. **离线壳**：网络优先（在线时永远拿新的 —— 这一条最要紧，Compose/wasm 的产物一旦被钉在缓存里
 *     就是「装成 App 之后再也收不到更新」），拿不到时才回落到最近一次成功抓到的那一份；
 *  2. **`notificationclick`**：点系统通知 → 把已经开着的那个窗口拉到前台（没有就开一个）。
 *     没有它，`showNotification` 发出去的通知点了什么都不发生；
 *  3. **换代**：`install` 里 `skipWaiting()`、`activate` 里清旧缓存 + `clients.claim()` ——
 *     免得旧 SW 把新产物挡在外面。
 *
 * 不缓存 `/api/…`（那是 serve 的接口：会话、令牌、真支付都在上面，缓存一份毫无意义且危险），
 * 也不碰跨域的请求。
 *
 * ⚠️ 这个文件只在**安全上下文**里跑得起来（HTTPS 或 localhost）—— 注册那一枪在 `pwa.js` 里，
 * 不在安全上下文时它连注册都不试（`docs/desktop-port-plan.md` §16.12）。
 */

const CACHE_NAME = 'xjtu-toolbox-shell-v1';

/** 首屏那几个文件（少一个不致命：allSettled，抓不到的留着下次在线时再抓）。 */
const SHELL = [
  './',
  'index.html',
  'styles.css',
  'toolbox-web.js',
  'canvas-crisp.js',
  'manifest.webmanifest',
];

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => Promise.allSettled(SHELL.map((url) => cache.add(url))))
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  let url;
  try {
    url = new URL(request.url);
  } catch (e) {
    return;
  }
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/')) return;

  event.respondWith((async () => {
    try {
      const response = await fetch(request);
      if (response && response.ok && response.type === 'basic') {
        const copy = response.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(request, copy)).catch(() => {});
      }
      return response;
    } catch (error) {
      const cached = await caches.match(request);
      if (cached) return cached;
      if (request.mode === 'navigate') {
        const shell = await caches.match('index.html');
        if (shell) return shell;
      }
      throw error;
    }
  })());
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of windows) {
      if ('focus' in client) {
        await client.focus();
        return;
      }
    }
    if (self.clients.openWindow) {
      await self.clients.openWindow('./');
    }
  })());
});
