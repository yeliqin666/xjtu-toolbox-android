/*
 * 注册 Service Worker（只在安全上下文里），结果写进 `globalThis.__xjtuToolboxPwa` 给 `?route=probe` 读。
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
