@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import kotlin.js.JsAny

/** 浏览器这一端的取景 / 选图那一桥：把「一帧图像」变成 ARGB 像素交给 `:core` 的解码器。 */
internal object WebQrCamera {

    /** 分析用的长边上限（与 `:app` 的相机分析同一档：640）。 */
    const val ANALYSIS_MAX_EDGE = 640

    /** 拉帧的节拍（毫秒）—— 4 fps，见 KDoc 第 2 条。 */
    const val FRAME_INTERVAL_MS = 250

    /** 「这一份部署里浏览器根本不给相机」的原因；给得了就返回 null（纯判断，一个请求都不发）。 */
    fun cameraUnavailableReason(): String? = when {
        !secureContext() -> "这一份部署不是安全上下文（要 HTTPS 或 localhost），浏览器不给相机。"
        !hasGetUserMedia() -> "这个浏览器没有给相机这一档（navigator.mediaDevices.getUserMedia 不在）。"
        else -> null
    }

    fun secureContext(): Boolean = webIsSecureContext()

    fun hasGetUserMedia(): Boolean = webHasGetUserMedia()

    /** 开一路取景（覆盖层是 DOM，见 KDoc 第 1 条）。 */
    fun startCamera(
        key: String,
        reuseStreamKey: String?,
        onCancelled: () -> Unit,
        onError: (String, String) -> Unit,
    ) {
        installArgbHelper()
        cameraStart(key, reuseStreamKey.orEmpty(), ANALYSIS_MAX_EDGE, onCancelled, onError)
    }

    /** 最新一帧的宽（0 = 还没有画面 —— `<video>` 还没出帧时就是这样）。 */
    fun frameWidth(key: String): Int = cameraFrameWidth(key)

    /** 最新一帧的高（0 = 还没有画面）。 */
    fun frameHeight(key: String): Int = cameraFrameHeight(key)

    /** 把最新一帧的 ARGB（`0xAARRGGBB`、行优先）拷进 [pixels]；长度必须 >= 宽*高。 */
    fun grabInto(key: String, pixels: IntArray): Int = cameraGrab(key)?.copyInto(pixels) ?: 0

    /** 收摊（幂等）：停掉这条取景自己开的那个流、去掉覆盖层；取消过 / 关过再关一次什么都不做。 */
    fun closeCamera(key: String) {
        cameraClose(key)
    }

    /** 选一张图（`<input type=file>`）。 */
    fun pickImage(onDone: (width: Int, height: Int, error: String?) -> Unit) {
        installArgbHelper()
        pickImageFile(IMAGE_MAX_EDGE, onDone)
    }

    /** 把刚选那张图的 ARGB 拷进 [pixels]；取一次就清掉。返回拷了几个像素（0 = 没有）。见 [grabInto]。 */
    fun takePickedPixels(pixels: IntArray): Int = pickFrame()?.copyInto(pixels) ?: 0

    /** `getUserMedia` 失败那几种 `error.name` → 一句人话（纯函数，`WebMatchScanTest` 有用例）。 */
    fun cameraFailureReason(name: String, message: String): String = when (name) {
        "NotAllowedError", "SecurityError" ->
            "得先允许用一下相机（地址栏那把锁里能改），改完再点一次。"
        "NotFoundError", "OverconstrainedError", "NotReadableError" ->
            "没找到能用的相机（或它被别的程序占着）。用「从相册选图」也一样。"
        else -> "打不开相机：${message.ifBlank { name }}"
    }

    /** 选一张图时先按长边缩到这个像素数以内（与 `:app` 的 `QrBitmap.MAX_DECODE_EDGE` 同一个数）。 */
    private const val IMAGE_MAX_EDGE = 1600
}

/** JS 那一侧的一帧像素（见 [WebQrCamera.grabInto]：`IntArray` 过不了这道门，就用这个对象）。 */
internal external interface JsPixelFrame : JsAny {

    val length: Int

    fun valueAt(index: Int): Int
}

/** 把一帧里前 [this.size] 个像素读进来（[limit] 是这一帧的像素数）。 */
private fun JsPixelFrame.copyInto(target: IntArray): Int {
    val n = minOf(length, target.size)
    for (i in 0 until n) target[i] = valueAt(i)
    return n
}

/** 见 [WebQrCamera] 的 KDoc（两面共用的一段换算，装一次）。 */
@JsFun(
    """
() => {
  if (globalThis.__xjtuToolboxArgbFromCanvas) { return; }
  // 把一块 canvas 读成 ARGB（0xAARRGGBB 的 Int32Array，行优先）—— 取景与选图共用这一段。
  globalThis.__xjtuToolboxArgbFromCanvas = (canvas) => {
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    const rgba = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
    const n = canvas.width * canvas.height;
    const argb = new Int32Array(n);
    for (let i = 0, j = 0; i < n; i++, j += 4) {
      argb[i] = (255 << 24) | (rgba[j] << 16) | (rgba[j + 1] << 8) | rgba[j + 2];
    }
    return argb;
  };
  // 按长边缩到 maxEdge 以内的尺寸（取景与选图同一个规矩）
  globalThis.__xjtuToolboxScaledSize = (w, h, maxEdge) => {
    const scale = Math.min(1, maxEdge / Math.max(w, h));
    return [Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))];
  };
  // 一帧 → 递给 Kotlin 的那个对象（Int32Array 不能直接过门，见 WebQrCamera 的 KDoc）
  globalThis.__xjtuToolboxFrameOf = (argb) => ({ length: argb.length, valueAt: (i) => argb[i] });
}
""",
)
internal external fun installArgbHelper()

/** 开一路取景（JS 那一半）。 */
@JsFun(
    """
(key, reuseStreamKey, maxEdge, onCancelled, onError) => {
  const cameras = globalThis.__xjtuToolboxCameras || (globalThis.__xjtuToolboxCameras = {});
  const streams = globalThis.__xjtuToolboxCameraStreams || (globalThis.__xjtuToolboxCameraStreams = {});

  const cleanup = () => {
    const s = cameras[key];
    if (!s) { return; }
    delete cameras[key];
    if (s.video) {
      try { s.video.pause(); s.video.srcObject = null; } catch (e) { }
      s.video.remove();
    }
    if (s.hint) { s.hint.remove(); }
    if (s.cancel) { s.cancel.remove(); }
    // 只停自己开的那条流：复用别人给的（测试那条）不由这里管
    if (s.ownStream && s.stream) {
      try { s.stream.getTracks().forEach((t) => t.stop()); } catch (e) { }
    }
  };
  const fail = (name, message) => { cleanup(); onError(name || '', message || ''); };

  const open = (stream, ownStream) => {
    const video = document.createElement('video');
    video.setAttribute('playsinline', '');
    video.muted = true;
    video.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;object-fit:cover;background:#000;z-index:2147483000;';
    const hint = document.createElement('div');
    hint.textContent = '对准对方屏幕上的二维码';
    hint.style.cssText = 'position:fixed;left:0;right:0;bottom:104px;text-align:center;color:#fff;' +
      'font:14px/1.5 sans-serif;text-shadow:0 1px 3px #000;z-index:2147483001;';
    const cancel = document.createElement('button');
    cancel.textContent = '取消';
    cancel.style.cssText = 'position:fixed;left:16px;right:16px;bottom:36px;padding:12px;border:0;border-radius:10px;' +
      'background:rgba(255,255,255,.16);color:#fff;font:15px sans-serif;z-index:2147483001;';
    cancel.onclick = () => { cleanup(); onCancelled(); };
    document.body.appendChild(video);
    document.body.appendChild(hint);
    document.body.appendChild(cancel);

    cameras[key] = { video, hint, cancel, canvas: document.createElement('canvas'), stream, ownStream, maxEdge };
    video.srcObject = stream;
    const played = video.play();
    if (played && played.then) {
      played.then(() => { }).catch((e) => fail('NotReadableError', (e && e.message) ? e.message : ''));
    }
  };

  if (reuseStreamKey) {
    const stream = streams[reuseStreamKey];
    if (!stream) { onError('NotFoundError', '测试给的那条流不在：' + reuseStreamKey); return; }
    open(stream, false);
    return;
  }

  if (!window.navigator.mediaDevices || !window.navigator.mediaDevices.getUserMedia) {
    onError('SecurityError', 'navigator.mediaDevices.getUserMedia 不在（非安全上下文？）');
    return;
  }
  window.navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false })
    .then((stream) => open(stream, true))
    .catch((e) => fail((e && e.name) ? e.name : '', (e && e.message) ? e.message : ''));
}
""",
)
private external fun cameraStart(
    key: String,
    reuseStreamKey: String,
    maxEdge: Int,
    onCancelled: () -> Unit,
    onError: (String, String) -> Unit,
)

/** 最新一帧的宽（0 = 还没出画面）。见 [WebQrCamera.frameWidth]。 */
@JsFun("(key) => { const s = (globalThis.__xjtuToolboxCameras || {})[key]; return (s && s.video) ? (s.video.videoWidth || 0) : 0; }")
private external fun cameraFrameWidth(key: String): Int

/** 最新一帧的高（0 = 还没出画面）。见 [WebQrCamera.frameHeight]。 */
@JsFun("(key) => { const s = (globalThis.__xjtuToolboxCameras || {})[key]; return (s && s.video) ? (s.video.videoHeight || 0) : 0; }")
private external fun cameraFrameHeight(key: String): Int

/** 把最新一帧画到 canvas 上、读成 ARGB，包成 [JsPixelFrame] 交给 Kotlin（这帧还没准备好就返回 null）。 */
@JsFun(
    """
(key) => {
  const s = (globalThis.__xjtuToolboxCameras || {})[key];
  if (!s || !s.video) { return null; }
  const vw = s.video.videoWidth || 0, vh = s.video.videoHeight || 0;
  if (!vw || !vh) { return null; }
  const size = globalThis.__xjtuToolboxScaledSize(vw, vh, s.maxEdge);
  const w = size[0], h = size[1];
  s.canvas.width = w;
  s.canvas.height = h;
  s.canvas.getContext('2d', { willReadFrequently: true }).drawImage(s.video, 0, 0, w, h);
  return globalThis.__xjtuToolboxFrameOf(globalThis.__xjtuToolboxArgbFromCanvas(s.canvas));
}
""",
)
private external fun cameraGrab(key: String): JsPixelFrame?

/** 收摊（幂等）：见 [WebQrCamera.closeCamera]。 */
@JsFun(
    """
(key) => {
  const s = (globalThis.__xjtuToolboxCameras || {})[key];
  if (!s) { return; }
  if (s.video) {
    try { s.video.pause(); s.video.srcObject = null; } catch (e) { }
    s.video.remove();
  }
  if (s.hint) { s.hint.remove(); }
  if (s.cancel) { s.cancel.remove(); }
  if (s.ownStream && s.stream) {
    try { s.stream.getTracks().forEach((t) => t.stop()); } catch (e) { }
  }
  delete globalThis.__xjtuToolboxCameras[key];
}
""",
)
private external fun cameraClose(key: String)

/** `<input type=file>` 选一张图→ 长边缩到 [maxEdge] → 那一帧存在一个全局里，等 Kotlin 用 [pickFrame] 取走（`IntArray` 过不了互操作那道门，见 [WebQrCamera] 的 KDoc）。 */
@JsFun(
    """
(maxEdge, onDone) => {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = 'image/*';
  input.style.cssText = 'position:fixed;left:-10000px;top:0;';
  let settled = false;
  const finish = (w, h, err) => {
    if (settled) { return; }
    settled = true;
    input.remove();
    onDone(w, h, err);
  };
  input.oncancel = () => finish(0, 0, null);
  input.onchange = () => {
    const file = input.files && input.files[0];
    if (!file) { finish(0, 0, null); return; }
    createImageBitmap(file).then((bmp) => {
      const size = globalThis.__xjtuToolboxScaledSize(bmp.width, bmp.height, maxEdge);
      const w = size[0], h = size[1];
      const canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = h;
      canvas.getContext('2d', { willReadFrequently: true }).drawImage(bmp, 0, 0, w, h);
      if (bmp.close) { bmp.close(); }
      globalThis.__xjtuToolboxPickedFrame = globalThis.__xjtuToolboxFrameOf(globalThis.__xjtuToolboxArgbFromCanvas(canvas));
      finish(w, h, null);
    }).catch((e) => finish(0, 0, '这张图打不开：' + ((e && e.message) ? e.message : e)));
  };
  document.body.appendChild(input);
  input.click();
}
""",
)
private external fun pickImageFile(maxEdge: Int, onDone: (Int, Int, String?) -> Unit)

/** 刚选那张图的那一帧（取一次就清掉），见 [WebQrCamera.takePickedPixels]。 */
@JsFun(
    """
() => {
  const picked = globalThis.__xjtuToolboxPickedFrame;
  if (!picked) { return null; }
  delete globalThis.__xjtuToolboxPickedFrame;
  return picked;
}
""",
)
private external fun pickFrame(): JsPixelFrame?

@JsFun("() => window.isSecureContext === true")
private external fun webIsSecureContext(): Boolean

@JsFun("() => !!(window.navigator.mediaDevices && window.navigator.mediaDevices.getUserMedia)")
private external fun webHasGetUserMedia(): Boolean
