package com.xjtu.toolbox.platform

/**
 * Web（Kotlin/Wasm）端：空实现 —— **如实降级，不做假气泡**。
 *
 * 长按复制教室名这件事本身在 Web 上是成功的（剪贴板那一半三端共用同一个实现），
 * 缺的只是"复制好了"这一句系统提示：浏览器里没有系统级 Toast，`document.execCommand('copy')`
 * 之类的老路也不提供提示。所以这里什么都不做，而不是拿一个自绘浮层冒充 Android 的 Toast
 * （那会变成"两端看起来一样、行为不一样"的假象）。要接的话只改这一个文件
 * （`Notification` / 自绘气泡 / `console.log`），调用点不动。
 */
actual fun showBriefMessage(message: String) {
}
