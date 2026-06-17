package com.xjtu.toolbox.auth

/**
 * 校内业务系统的访问方式。
 *
 * - [NORMAL]：通过原域名直连（jwxt.xjtu.edu.cn / jwapp / lms / bkkq / ncard …），
 *   仅在校园网（校内 WiFi 或 Srun 已认证）环境可用。
 * - [WEBVPN]：经由 webvpn.xjtu.edu.cn 加密代理访问，校外环境唯一可达校内业务系统的路径。
 *
 * 两种 mode 的 [SessionBackend] 始终并存于内存，cookies 物理隔离。
 * 网络切换时只更新 active mode 指针，不动任一边的 cookies，下次切回可零成本 SSO 复用。
 */
enum class AccessMode(val key: String) {
    NORMAL("normal"),
    WEBVPN("webvpn"),
}
