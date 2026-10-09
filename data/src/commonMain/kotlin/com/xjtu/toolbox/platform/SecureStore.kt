package com.xjtu.toolbox.platform

/**
 * `:data` 的**密文键值存储**缝：会话内核落 cookie、站点快照、一网通办令牌的那一份。
 *
 * ## 为什么不能直接用 `:core` 的 [keyValueStore]
 *
 * `:core` 那一份在 Android 上是**普通** `SharedPreferences`，而 `:app` 现在放 cookie / 快照 /
 * 凭据用的是 `SecurePrefs`（`EncryptedSharedPreferences`，见 `data/androidMain`）。会话数据换到
 * 明文文件上会让「老数据必须无缝」那条红线破掉——不只是文件变了，密文也解不开了。
 *
 * 所以这一份是**同一批文件名、同一批键、同一批值类型**的密文版本：Android 的 actual 就是
 * `SecurePrefs.open(context, name)`，桌面的 actual 是 `0600` 权限的落盘文件（与 campus-api
 * 现有做法同口径）。文件名与键名一个都不能改，改了用户就要重登。
 *
 * 类型刻意复用 `:core` 的 [KeyValueStore] 接口：调用方（会话内核）只用到 getString / putString /
 * remove / contains / clear 这几件，接口一模一样，不必再长一个同形接口出来。
 */
expect fun secureKeyValueStore(name: String): KeyValueStore

/**
 * 整份删掉一个密文存储（删账号时用）。
 *
 * 语义与 `:app` 原来的 `SessionBackend.wipe` 逐条对齐：**文件不存在就什么都不做**，
 * 不为了清它去创建一份新的加密存储（Android 上 `SecurePrefs.open` 会真的建文件）。
 */
expect fun wipeSecureStore(name: String)

/**
 * 单调时钟（毫秒）。只用于**进程内**的会话新鲜度窗口（`SiteSession` 的探活 TTL、
 * `SessionManager` 的网关 TTL），从不落盘、从不跨进程比较，所以各端用自己的单调计时器即可：
 * Android = `SystemClock.elapsedRealtime()`（含深睡），JVM = `System.nanoTime()`。
 *
 * 为什么不用 `System.currentTimeMillis()`：墙上时钟会被用户/时区/NTP 往回拨，一拨就
 * 「刚登录的会话瞬间过期」或「早过期的会话显示还新鲜」。
 */
expect fun elapsedRealtimeMs(): Long

/**
 * 设备指纹的**稳定字段**（`XJTULogin.generateFpVisitorId` 的兜底路径用）。
 *
 * 为什么是 expect：Android 那份读 `Build.MANUFACTURER/BRAND/MODEL/DEVICE`，桌面没有这几个字段。
 * 判据只有一条 —— **同一台设备每次得到同一个值**：以前这里是 `UUID.randomUUID()`，每构造一次
 * `XJTULogin` 就换一个指纹，学校会当成一台新设备，反而多触发一次短信验证。
 *
 * ⚠️ 顺序与内容在 Android 上必须与搬迁前**逐字一致**（`"android"` 开头那五项），
 * 否则兜底路径派生的指纹会变，老用户走到兜底时会重新触发 MFA。
 */
expect fun stableDeviceFingerprintFields(): List<String>
