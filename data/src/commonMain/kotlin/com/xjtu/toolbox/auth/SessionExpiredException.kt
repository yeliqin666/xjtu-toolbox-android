package com.xjtu.toolbox.auth

import com.xjtu.toolbox.error.SessionExpiredFailure
import java.io.IOException

/**
 * 「站点登录态已失效」的**共享基类**（原来只有 `:app` 的 `AuthExpiredException` 一个类）。
 *
 * ## 为什么要有基类
 *
 * 数据层里到处是「这一枪被重定向到登录页了，别把它当成普通网络错误」这种分支
 *（图书馆那半就有 6 处：`getSeats` / `getPlanImage` / `bookSeat` / `swapSeat` / `executeAction` /
 * `fetchMyBooking`）。那些分支原来写的是 `catch (e: AuthExpiredException)` —— 而
 * `AuthExpiredException` 是 `:app` 的类（同 `XJTULogin` 一起挂着 Android 的依赖），
 * `:data` 认识不了它。
 *
 * 所以把一个**泛用**的基类放这里，`:app` 的 `AuthExpiredException` 原样继承它：
 *  - 类的名字、构造参数、文案、父类型（仍是 `IOException`）**一点没变** ⇒ Android 侧
 *    所有 `is AuthExpiredException` / `catch (e: AuthExpiredException)` 的行为不变；
 *  - `:data` 只按基类判，于是同一段数据层代码在桌面/Android 上都认得出「会话失效」。
 *
 * 实现 `:core` 的 [SessionExpiredFailure] 标记接口：共享的 ViewModel 与屏按它认领
 *「静默重登」这条走向（见 `:core/error/FriendlyError.kt` 与 `auth/AuthExpiry.kt`）。
 */
open class SessionExpiredException(
    val siteName: String = "",
    message: String = if (siteName.isEmpty()) "登录态已失效" else "${siteName}登录态已失效",
) : IOException(message), SessionExpiredFailure
