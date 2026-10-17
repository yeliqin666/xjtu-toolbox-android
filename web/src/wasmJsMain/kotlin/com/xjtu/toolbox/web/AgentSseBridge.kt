@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

/** 浏览器侧的 fetch + SSE 读帧桥：`POST /api/agent/chat` 那条流不走 Ktor。 */
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

/** 开一条 SSE 流。 */
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
