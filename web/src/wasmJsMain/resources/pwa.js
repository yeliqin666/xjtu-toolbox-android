/*
 * 注册 Service Worker —— D12 ④（PWA）里那一步。
 *
 * 为什么单独一个 `.js` 文件而不是塞进 wasm 那一侧：**注册要趁早**（页面一加载就注册，用户在装成
 * App 之前的那几秒里也该有这一层），而且这一段完全不碰 Kotlin 的状态 —— 它只把「注册好没好」写进
 * `globalThis.__xjtuToolboxPwa`，屏上那张 PWA 卡（`?route=probe`）读的就是这几个字段。
 *
 * 三条如实：
 *  - **只在安全上下文里注册**（HTTPS 或 localhost）：不满足时连试都不试，把原因写进 `error`；
 *  - **离线壳在网络优先那一侧**（见 `sw.js`）：在线时永远拿新的产物，不然「装成 App」就等于「再也
 *    收不到更新」；
 *  - **`beforeinstallprompt` 只是抓住事件**（一起写进这个全局），装不装由用户点浏览器自己的那一下
 *    说了算 —— 我们不做自定义的安装按钮（假入口）。
 */
(function () {
  'use strict';
  var state = globalThis.__xjtuToolboxPwa = globalThis.__xjtuToolboxPwa || {
    ready: false,
    error: null,
    installPrompt: false
  };

  if (!window.isSecureContext) {
    state.error = '不是安全上下文（要 HTTPS 或 localhost）';
    return;
  }
  if (!('serviceWorker' in navigator)) {
    state.error = '这个浏览器没有 Service Worker';
    return;
  }

  window.addEventListener('beforeinstallprompt', function (event) {
    // 不 preventDefault：浏览器自己那条「安装」入口留着，这里只把「可安装」这件事记下来给屏上那张卡
    state.installPrompt = true;
  });
  window.addEventListener('appinstalled', function () {
    state.installPrompt = false;
    state.installed = true;
  });

  navigator.serviceWorker.register('sw.js', { scope: './' }).then(
    function () {
      state.ready = true;
      state.error = null;
    },
    function (error) {
      state.ready = false;
      state.error = (error && error.message) ? error.message : String(error);
    }
  );
})();
