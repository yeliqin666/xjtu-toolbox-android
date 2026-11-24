@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

/** 把地址栏里的 一次性参数（今天只有 `token`）抹掉，其余参数与 hash 原样保留。 */
@JsFun(
    """
    () => {
      const u = new URL(window.location.href);
      u.searchParams.delete('token');
      window.history.replaceState(null, '', u.pathname + u.search + u.hash);
    }
    """
)
external fun browserStripTokenParam()

/** 切页时往浏览器历史里压一条 `?route=`（与当前地址相同就不压）。其余参数原样保留，`token` 一并抹掉。 */
@JsFun(
    """
    (route) => {
      const u = new URL(window.location.href);
      u.searchParams.delete('token');
      u.searchParams.set('route', route);
      const next = u.pathname + u.search + u.hash;
      if (next !== window.location.pathname + window.location.search + window.location.hash) {
        window.history.pushState(null, '', next);
      }
    }
    """
)
external fun browserPushRoute(route: String)

/** 开场那一页：改写当前这条历史，不新压一条（否则第一下返回键原地不动）。 */
@JsFun(
    """
    (route) => {
      const u = new URL(window.location.href);
      u.searchParams.delete('token');
      u.searchParams.set('route', route);
      window.history.replaceState(null, '', u.pathname + u.search + u.hash);
    }
    """
)
external fun browserReplaceRoute(route: String)

@JsFun(
    """
    (cb) => {
      window.addEventListener('popstate', () => {
        const route = new URL(window.location.href).searchParams.get('route') || '';
        cb(route);
      });
    }
    """
)
private external fun browserAddPopStateListener(cb: (String) -> Unit)

private var popStateHandler: ((String) -> Unit)? = null
private var popStateRegistered = false

/** 浏览器返回 / 前进键回调（传 null 解除）；监听只挂一次。 */
fun browserListenPopState(handler: ((String) -> Unit)?) {
    popStateHandler = handler
    if (!popStateRegistered && handler != null) {
        popStateRegistered = true
        browserAddPopStateListener { route -> popStateHandler?.invoke(route) }
    }
}
