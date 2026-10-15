package com.xjtu.toolbox.platform

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

/** `:data` 的凭据保护器缝：把一份明文交给宿主的原生能力保护，再由本机原样解开。 */
interface SecretProtector {

    /** 信封标识。同一个 [id] 的两份保护器被认为可以互相解开（换用户/换机器就不行了，见类 KDoc）。 */
    val id: String

    /** 明文 → 密文。**必须改变字节**；失败就抛 [SecretProtectionException]。 */
    fun protect(plain: ByteArray): ByteArray

    /** 密文 → 明文。解不开（换用户 / 换机器 / 密文被改过）就抛 [SecretProtectionException]。 */
    fun unprotect(sealed: ByteArray): ByteArray
}

/** 凭据保护/解保护失败。 */
class SecretProtectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Windows 的原生保护：DPAPI（`CryptProtectData` / `CryptUnprotectData`），当前用户作用域。 */
object DpapiSecretProtector : SecretProtector {

    override val id: String = "dpapi"

    /** `CryptProtectData` 的熵。**一旦发过版就不能改**：改了老凭据全解不开。 */
    private val ENTROPY = "xjtu-toolbox/credentials/v1".encodeToByteArray()

    /** 交给系统的描述串（DPAPI 自己的诊断/凭据列表里能看到的说明；不允许弹 UI，所以只作记录）。 */
    private const val DESCRIPTION = "XJTU ToolBox 凭据"

    override fun protect(plain: ByteArray): ByteArray = native("CryptProtectData") {
        Crypt32Util.cryptProtectData(
            plain,
            ENTROPY,
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
            DESCRIPTION,
            null,
        )
    }

    override fun unprotect(sealed: ByteArray): ByteArray = native("CryptUnprotectData") {
        Crypt32Util.cryptUnprotectData(sealed, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
    }

    /** `CRYPTPROTECT_UI_FORBIDDEN`：任何情况下都不弹系统对话框（这个进程没有窗口循环在等它，弹出来就是卡死）。 */
    private inline fun native(call: String, block: () -> ByteArray): ByteArray = try {
        block()
    } catch (t: Throwable) {
        throw SecretProtectionException(
            "DPAPI 的 $call 失败（本次不落盘，凭据不会以明文写入）：$t",
            t,
        )
    }
}

/** 本机的凭据保护器：Windows 给 [DpapiSecretProtector]，其余平台没有（`null`）。 */
fun secretProtectorForPlatform(osName: String = System.getProperty("os.name").orEmpty()): SecretProtector? =
    if (osName.startsWith("Windows", ignoreCase = true)) DpapiSecretProtector else null
