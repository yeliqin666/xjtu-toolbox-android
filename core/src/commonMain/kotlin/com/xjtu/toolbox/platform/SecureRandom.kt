package com.xjtu.toolbox.platform

/**
 * 密码学安全的随机整数 `[0, bound)`。
 *
 * 唯一的用处是联机对局的一次性口令（`game/net/OneTimeToken.generate`）：房主把它塞进二维码，
 * 第一个用它 hello 成功的设备消费掉它，防止第三台设备拿同一份码顶替真正的对手。
 * 口令可被预测就等于这层防护不存在，所以 Android/JVM 用 `java.security.SecureRandom`。
 *
 * Web 端退化成 `kotlin.random.Random`：浏览器里**没有** BLE，Web 端从来不可能是房主
 * （`OnlineController` 是 Android 专属，Web 的联机入口整个不出现），这条路径实际不会被走到。
 * 之所以还是做成切口而不是让 commonMain 直接用 `Random`，是为了让 Android 保持
 * 「原来的 SecureRandom」——行为零变化。
 */
expect fun secureRandomInt(bound: Int): Int
