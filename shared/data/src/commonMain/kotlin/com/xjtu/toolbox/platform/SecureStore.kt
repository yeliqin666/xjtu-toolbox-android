package com.xjtu.toolbox.platform

/** `:data` 的密文键值存储缝：会话内核落 cookie、站点快照、一网通办令牌的那一份。 */
expect fun secureKeyValueStore(name: String, legacyFallbackName: String? = null): KeyValueStore

/** 整份删掉一个密文存储（删账号时用）。 */
expect fun wipeSecureStore(name: String)

/** 单调时钟（毫秒）。 */
expect fun elapsedRealtimeMs(): Long

/** 设备指纹的稳定字段（`XJTULogin.generateFpVisitorId` 的兜底路径用）。 */
expect fun stableDeviceFingerprintFields(): List<String>
