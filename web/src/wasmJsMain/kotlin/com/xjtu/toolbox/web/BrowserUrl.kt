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
