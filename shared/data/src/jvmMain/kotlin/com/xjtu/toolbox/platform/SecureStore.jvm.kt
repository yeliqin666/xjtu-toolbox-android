package com.xjtu.toolbox.platform

import java.io.File
/** 桌面端的安全存储落点。 */
@Volatile
var dataRootOverride: File? = null

/** 本端数据根目录。 */
internal fun dataRoot(): File =
    dataRootOverride ?: File(System.getProperty("user.home") ?: "/tmp", ".local/share/xjtu-dzhz")

/** 数据根目录的对外形态：宿主拿它下面的子目录。 */
fun dataRootDir(): File = dataRoot()

// 桌面没有 Android 旧明文文件迁移步骤，legacyFallbackName 不使用。
actual fun secureKeyValueStore(name: String, legacyFallbackName: String?): KeyValueStore =
    jvmFileKeyValueStore(File(dataRoot(), "$name.properties"))

actual fun wipeSecureStore(name: String) =
    wipeJvmFileKeyValueStore(File(dataRoot(), "$name.properties"))

actual fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000

/** 桌面：用操作系统名 + 架构 + 当前用户拼一个「同机同值」的稳定种子。 */
actual fun stableDeviceFingerprintFields(): List<String> = listOf(
    "desktop",
    System.getProperty("os.name") ?: "",
    System.getProperty("os.version") ?: "",
    System.getProperty("os.arch") ?: "",
    System.getProperty("user.name") ?: "",
)
