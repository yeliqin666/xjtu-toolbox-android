package com.xjtu.toolbox.auth

import com.xjtu.toolbox.error.SessionExpiredFailure
import java.io.IOException

/** 「站点登录态已失效」的共享基类。 */
open class SessionExpiredException(
    val siteName: String = "",
    message: String = if (siteName.isEmpty()) "登录态已失效" else "${siteName}登录态已失效",
) : IOException(message), SessionExpiredFailure
