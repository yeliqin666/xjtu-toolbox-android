@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import kotlin.js.JsAny

/**
 * 浏览器这一端的**取景 / 选图那一桥**（D12 ③）：把「一帧图像」变成 ARGB 像素交给 `:core` 的解码器。
 *
 * ## 它在这一条链里的位置
 *
 * | 从哪来 | 怎么变成像素 | 谁来解码 |
 * |---|---|---|
 * | 相机（`getUserMedia` → `<video>` → 按需 `drawImage` 到 canvas） | 这个文件里那段 JS（[startCamera] / [grabInto]） | `:core` 的 `qrDecodeArgb`（wasm 上是纯 Kotlin 那份，见 `platform/qr/QrReader`） |
 * | 选一张图（`<input type=file>` → `createImageBitmap` → canvas） | 同一个换算（[pickImage] / [takePickedPixels]） | 同上 |
 *
 * 两档只在「像素从哪来」上不同，**换算是同一段**（`globalThis.__xjtuToolboxArgbFromCanvas`，装一次复用）。
 *
 * ## 为什么是「Kotlin 拉」而不是「JS 推」（**实测的**互操作约束，不是口味）
 *
 * 第一版写的是「JS 每 250ms 抓一帧，回调 `onFrame(pixels, w, h)`」，`kotlin.js` 的 wasm 互操作
 * **直接编不过**：
 *
 * ```
 * Type 'IntArray' cannot be used as parameter type of JS interop function type.
 * Only external, primitive, string, and function types are supported in Kotlin/Wasm JS interop.
 * ```
 *
 * 后来把两种「整块传数组」的写法都试了一遍，**两种都不行**（都是同一条限制）：
 *
 * ```
 * Type 'IntArray' cannot be used as value parameter type of JS interop function.
 * Type 'IntArray' cannot be used as return type of JS interop function.
 * ```
 *
 * 也就是说 `IntArray` 过不了这道门（参数与返回值都不行）。能过门的是 `JsAny` —— 而 JS 那一侧
 * 完全可以**自己造一个对象**：`{ length, valueAt(i) }`（[JsPixelFrame]），Kotlin 拿 `JsAny` 句柄、
 * 按需逐个读进自己那块可复用的 `IntArray`。于是这一桥的形态是：**JS 只把帧放在自己的 canvas 上，
 * Kotlin 按自己的节拍拉**（`WebMatchScanner.scan()` 每 250ms 拉一次）。这与 `AgentSseBridge` 那条
 *「回调推送」不同 —— 那一族的回调参数全是字符串，不受这条限制。
 *
 * ## 三处如实
 *
 * 1. **取景覆盖层是 DOM 画的，不是 Compose**：Android 那一端是 `ScannerOverlay`（Compose：`QrScannerView`
 *    + 两颗 MIUIX 按钮 + `BackHandler`）。这一端要的是「真画面」——只能用一个真 `<video>` 元素，
 *    而 Compose 的画布是另一棵树（`:web` 的既有先例是 `WebGameMerge` 那个 `<iframe>`：挂在 `<body>` 上、
 *    由 JS 按矩形摆位）。所以这里同样把覆盖层交给 DOM：一个全屏 `<video>` + 一行提示 + 一颗「取消」
 *    （文案与 Android 那两句**逐字相同**）。观感与 MIUIX 不一致，如实记着。
 * 2. **扫的密度与节拍**：长边缩到 [ANALYSIS_MAX_EDGE]（640，与 `:app` 相机分析那一档同一个数）、
 *    每 [FRAME_INTERVAL_MS] 拉一帧（4 fps）。一帧解码是 O(像素) 的事，每帧都解会把主线程（wasm 只有
 *    这一个线程）占死；`IntArray` 也按最大尺寸**复用一份**，不每帧新分配。
 * 3. **成功那一跳没在 CI 里验过**：本机的测试浏览器**没有摄像头**（karma 那颗 Chromium 也没给假设备），
 *    所以「真流进来、逐帧扫到」只到「按标准 API 写出来 + 失败那几条都有用例」这一步 ——
 *    见 `WebMatchScanTest` 的 KDoc（它逐条列了哪些验了、哪些没验）。
 *
 * ## 三条失败态是**分开**的（都在 [cameraFailureReason] 里，纯函数、有用例）
 *
 * `getUserMedia` 的失败按 `error.name` 分成「没允许 / 没相机 / 别的」，各自给一句人话；
 * 「这一份部署里浏览器根本不给相机」（非安全上下文 / 没有 `mediaDevices`）在**开门之前**就判掉
 * （[cameraUnavailableReason]），不等到 Promise 拒绝。
 */
internal object WebQrCamera {

    /** 分析用的长边上限（与 `:app` 的相机分析同一档：640）。 */
    const val ANALYSIS_MAX_EDGE = 640

    /** 拉帧的节拍（毫秒）—— 4 fps，见 KDoc 第 2 条。 */
    const val FRAME_INTERVAL_MS = 250

    /**
     * 「这一份部署里浏览器根本不给相机」的原因；给得了就返回 null（纯判断，一个请求都不发）。
     *
     * 两条前置都是**跟着地址走**的（D11 的 `--https` 就是为它）：非安全上下文里
     * `navigator.mediaDevices` 直接是 undefined。
     */
    fun cameraUnavailableReason(): String? = when {
        !secureContext() -> "这一份部署不是安全上下文（要 HTTPS 或 localhost），浏览器不给相机。"
        !hasGetUserMedia() -> "这个浏览器没有给相机这一档（navigator.mediaDevices.getUserMedia 不在）。"
        else -> null
    }

    fun secureContext(): Boolean = webIsSecureContext()

    fun hasGetUserMedia(): Boolean = webHasGetUserMedia()

    /**
     * 开一路取景（覆盖层是 DOM，见 KDoc 第 1 条）。结果按 [onCancelled] / [onError] 回来；
     * 画面本身由 [frameWidth] / [frameHeight] / [grabInto] 按节拍拉。
     *
     * @param key 这一路取景的句柄（`@JsFun` 传不出对象句柄，就用一个 key 换手；[closeCamera] 拿它收摊）
     * @param reuseStreamKey 非空 ⇒ 用那个 key 上**已经存在**的一条 `MediaStream`，不叫 `getUserMedia`
     *   （生产永远传 null；这一条是给真浏览器测试用的：测试用 `canvas.captureStream()` 造一条**真**流，
     *   于是除 `getUserMedia` 之外的那一整跳都跑在测试里 —— 见 `WebMatchScanTest`）
     * @param onError `error.name` 与 `error.message`（给 [cameraFailureReason] 变成人话）
     */
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

    /**
     * 把最新一帧的 ARGB（`0xAARRGGBB`、行优先）拷进 [pixels]；长度必须 >= 宽*高。返回拷了几个像素。
     *
     * 为什么是「逐个拿」而不是「整块传」：Kotlin/Wasm 的 `@JsFun` 只收
     * 「external / 基本类型 / 字符串 / 函数」这四类，**`IntArray` 既不能当参数也不能当返回值**
     * （实测两条：`cannot be used as value parameter type` / `cannot be used as return type`）。
     * 于是这一桥的形态是：JS 把一帧包成一个 `{ length, valueAt(i) }` 的对象（[JsPixelFrame]），
     * Kotlin 自己按需读进那块可复用的 `IntArray`。一段 640×480 的帧是 30 万个元素，4 fps 下这点
     * 互操作开销是可接受的（比「每帧新分配一个百万元素的数组 + 一大串 base64」都便宜）。
     */
    fun grabInto(key: String, pixels: IntArray): Int = cameraGrab(key)?.copyInto(pixels) ?: 0

    /** 收摊（幂等）：停掉这条取景自己开的那个流、去掉覆盖层；取消过 / 关过再关一次什么都不做。 */
    fun closeCamera(key: String) {
        cameraClose(key)
    }

    /**
     * 选一张图（`<input type=file>`）。`width` <= 0 表示「没选 / 取消」（什么都不说）；`error` 非空
     * 表示这张图打不开（一句人话）；否则紧跟着调 [takePickedPixels] 把像素取回来。
     */
    fun pickImage(onDone: (width: Int, height: Int, error: String?) -> Unit) {
        installArgbHelper()
        pickImageFile(IMAGE_MAX_EDGE, onDone)
    }

    /** 把刚选那张图的 ARGB 拷进 [pixels]；取一次就清掉。返回拷了几个像素（0 = 没有）。见 [grabInto]。 */
    fun takePickedPixels(pixels: IntArray): Int = pickFrame()?.copyInto(pixels) ?: 0

    /**
     * `getUserMedia` 失败那几种 `error.name` → 一句人话（纯函数，`WebMatchScanTest` 有用例）。
     *
     * 这几种是 `:app` 那份 `ScannerOverlay` 没有的（Android 的相机权限是系统弹窗 + Activity 结果，
     * 拒绝之后还能再要一次）—— 浏览器这边拒绝之后只能让用户自己去地址栏那把权限打开。
     */
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

/**
 * JS 那一侧的一帧像素（见 [WebQrCamera.grabInto]：`IntArray` 过不了这道门，就用这个对象）。
 *
 * `length` 是属性、`valueAt(i)` 是方法 —— 两边都由这个文件里的 JS 建出来，名字对得上就行。
 */
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

/**
 * 开一路取景（JS 那一半）。见 [WebQrCamera.startCamera]。
 *
 * 覆盖层全在这一段里：一个全屏 `<video>`（`playsinline` + `muted`，手机浏览器才肯就地播）+ 一行提示
 * + 一颗「取消」（两句文案与 Android 那两句逐字相同）。`getUserMedia` 的 Promise 拒绝、播放失败、
 * 取消，各自走各自的那个回调。**这里不抓帧**（抓帧由 Kotlin 按节拍调 [cameraFrameWidth] /
 * [cameraGrab] —— 见 [WebQrCamera] 的 KDoc：`IntArray` 过不了互操作那道门）。
 */
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

/**
 * 把最新一帧画到 canvas 上、读成 ARGB，包成 [JsPixelFrame] 交给 Kotlin（这帧还没准备好就返回 null）。
 */
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

/**
 * `<input type=file>` 选一张图 → 长边缩到 [maxEdge] → 那一帧存在一个全局里，等 Kotlin 用
 * [pickFrame] 取走（`IntArray` 过不了互操作那道门，见 [WebQrCamera] 的 KDoc）。
 *
 * 用户取消：`change` 里没有文件、或 `cancel` 事件 ⇒ `onDone(0, 0, null)`（什么都不说）。
 */
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
