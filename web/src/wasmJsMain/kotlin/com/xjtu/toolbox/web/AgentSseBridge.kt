@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

/**
 * 浏览器侧的 **fetch + SSE 读帧桥**（D10 的 S-d）：`POST /api/agent/chat` 那条流**不走 Ktor**。
 *
 * ## 为什么不走 Ktor 的 Js 引擎
 *
 * `:core` 的 wasm 客户端是 `Js.create()`（`core/.../core/net/ToolboxEngine.wasmJs.kt`），而「fetch 引擎
 * 会不会把响应体当流给出来」是**引擎的实现细节**，本仓一条先例都没有（设计稿 §3.1 的未核对 #2）。
 * 这一族要的是「逐帧上屏」，所以要一个看得见的流：浏览器自带的
 * `fetch(...)` + `resp.body.getReader()` + `TextDecoder`。JS 那一段只做「把字节块原样交出来」，
 * 剩下的（帧怎么切、事件怎么映射）全在 Kotlin 这一侧 —— 那一半是纯函数，有 `:web` 的用例钉着。
 *
 * ## `@JsFun` 把 Kotlin 回调传给 JS：探针结论
 *
 * 设计稿 §4.5 要求先写一条最小探针再落地（未核对 #3：本仓此前三处 `@JsFun` 都是无参 / 纯字符串）。
 * 探针在 `:web:wasmJsTest` 的 `AgentJsFunCallbackProbeTest`（真浏览器里跑）：**能用** ——
 * JS 侧收到的 `onFrame` / `onDone` / `onError` 就是可以直接调用的函数，同步调用与
 * `Promise.then` 里的**异步**调用都真的落回 Kotlin 这一侧。所以这一族用的是**回调推送**的形态，
 * 不需要退化成「JS 攒缓冲 + Kotlin 轮询拉」那种等价形态。
 *
 * ## 三条约定（回调里那两个字段是给人查的，不是给屏看的）
 *
 * - **开门阶段的失败是信封**（`400/401/404/409/502`，契约 §5.11）：HTTP 状态不是 2xx 时，
 *   把 `{"kind":"http","status":<码>,"body":"<原文>"}` 交给 [onError]（`body` 就是那份信封 JSON）；
 * - **流开始之后「什么都不发」也是正常的**：被停止 / 客户端断开 / 写帧失败 ⇒ 服务端一个终止事件都不发、
 *   直接关流 ⇒ [onDone]（消费者按「（已停止）」处理，见 [AgentStreamState.stopped]）；
 * - **[abort] 是我方主动取消**（切会话 / 新建 / 点停止）：`AbortController.abort()` ⇒ fetch 那一枪
 *   落到 `catch`，那时 `signal.aborted` 为真 ⇒ 报 `{"kind":"abort"}`（与「网络断」分得开）。
 *
 * 令牌照既有纪律（契约 §3.2）：`ServeTokenHolder` 只在内存里，这里带 `Authorization: Bearer`，
 * 同时 `credentials: 'same-origin'` 让 cookie 那条路也成立 —— 两条路任一过得了闸门就行。
 */
internal object AgentSseBridge {

    /** 开一条流并把结果按 [onFrame] / [onDone] / [onError] 回调回来（同步返回，流在后台跑）。 */
    fun start(
        key: String,
        url: String,
        token: String?,
        body: String,
        onFrame: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        agentSseStart(key, url, token.orEmpty(), body, onFrame, onDone, onError)
    }

    /** 取消某一条流（幂等：不在跑的 key 什么都不做）。 */
    fun abort(key: String) {
        agentSseAbort(key)
    }
}

/**
 * 开一条 SSE 流。
 *
 * JS 那一段把 `AbortController` 按 [key] 存进 `globalThis.__xjtuToolboxAgentStreams`，
 * 于是 Kotlin 这一侧随时能 [agentSseAbort] 它（`@JsFun` 传不出对象句柄，就用一个 key 换手）。
 */
@JsFun(
    """
(key, url, token, body, onFrame, onDone, onError) => {
  const streams = globalThis.__xjtuToolboxAgentStreams || (globalThis.__xjtuToolboxAgentStreams = {});
  const controller = new AbortController();
  streams[key] = controller;
  const finish = () => { try { delete streams[key]; } catch (e) { } };
  const headers = { 'Content-Type': 'application/json' };
  if (token) { headers['Authorization'] = 'Bearer ' + token; }
  fetch(url, {
    method: 'POST',
    headers: headers,
    body: body,
    credentials: 'same-origin',
    cache: 'no-store',
    signal: controller.signal
  }).then(async (resp) => {
    if (!resp.ok) {
      let text = '';
      try { text = await resp.text(); } catch (e) { text = ''; }
      finish();
      onError(JSON.stringify({ kind: 'http', status: resp.status, body: text }));
      return;
    }
    if (!resp.body) {
      finish();
      onError(JSON.stringify({ kind: 'network', message: '这一份浏览器没给出流式响应体（resp.body 为空）' }));
      return;
    }
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) { break; }
      if (chunk.value && chunk.value.length) { onFrame(decoder.decode(chunk.value, { stream: true })); }
    }
    finish();
    onDone();
  }).catch((e) => {
    finish();
    const message = String((e && e.message) || e);
    if (controller.signal.aborted) { onError(JSON.stringify({ kind: 'abort', message: message })); }
    else { onError(JSON.stringify({ kind: 'network', message: message })); }
  });
}
""",
)
external fun agentSseStart(
    key: String,
    url: String,
    token: String,
    body: String,
    onFrame: (String) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
)

/** 取消某一条流（见 [agentSseStart] 的 KDoc：用 key 换手，`@JsFun` 传不出对象句柄）。 */
@JsFun(
    """
(key) => {
  const streams = globalThis.__xjtuToolboxAgentStreams;
  const controller = streams && streams[key];
  if (controller) { controller.abort(); }
}
""",
)
external fun agentSseAbort(key: String)
