package com.xjtu.toolbox.web

/**
 * 把地址栏里的 **一次性参数**（今天只有 `token`）抹掉，其余参数与 hash 原样保留。
 *
 * 为什么必须抹：`?token=<令牌>` 是为了「打开就能用」加的一次性入口（见 `ToolboxWebApp` 的启动那一段），
 * 令牌在那一瞬间被读进内存持有者、并顺手换成 cookie；**之后它就没有理由继续留在地址栏里** ——
 * 留在那里会被浏览器记进历史、被截图/录屏带走、被复制粘贴传播。所以换完 cookie 立刻
 * `history.replaceState` 掉（不进历史、不刷新页面）。
 *
 * 用的是 `URL` + `searchParams`（而不是拼字符串）：`+`、`%xx`、多值、空值这些边角都由浏览器按
 * 它自己那套规则处理 —— 与 `browserSearchParam` 的解析口径一致，不各写一套。
 */
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
