package com.xjtu.toolbox.platform

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

/**
 * `:data` 的**凭据保护器缝**：把一份明文交给宿主的原生能力保护，再由本机原样解开。
 *
 * ## 为什么要有这条缝
 *
 * 设计文档 §5.1 把「凭据存储」列在**必须新写的宿主实现**里，并写明两端口径不同：
 * **Windows 上要给 DPAPI / Credential Manager**，**Linux 上「文件 + `0600` 权限」就够**
 *（与 campus-api 现有做法同口径）。同一份 `JvmCredentialStore` 要同时满足这两句 ⇒
 * 变化的那一处必须是可替换的：这就是本文件。
 *
 * ## 它和 [secureKeyValueStore] 的分工
 *
 * [secureKeyValueStore] 管**落在哪儿**（数据目录、文件名、`0600` 权限），本缝管**落成什么字节**
 *（Windows 上不再落明文）。两者叠在一起才是「凭据存储」：[JvmCredentialStore] 里
 * `ProtectedKeyValueStore(secureKeyValueStore(name), protector)` 这一行就是它们的合成点。
 *
 * ## 契约（[ProtectedKeyValueStore] 靠这三条工作）
 *
 * 1. [id] 是**写进密文信封的标识**（见 `ProtectedKeyValueStore` 的 KDoc）：读一份凭据时先看信封
 *    上写的是谁保护的 —— 与当前 [id] 不同就**明确失败**，不去猜、更不把密文当明文交出去；
 * 2. [protect] / [unprotect] **必须真的改变字节**，且解不开时**抛 [SecretProtectionException]**；
 * 3. 它们不该吞异常。任何失败（原生库不在、调用被拒、密文被改过）都必须变成
 *    [SecretProtectionException] 一路冒到调用方 —— **绝不静默降级成明文**：
 *    凭据是分发的代价（设计文档 §8 第 3 条），「悄悄不加密了」比「登录报错」坏得多。
 *
 * ## 各端现在是谁（[secretProtectorForPlatform]）
 *
 * | 平台 | 保护器 | 落在盘上的是什么 |
 * |---|---|---|
 * | Windows | [DpapiSecretProtector]（DPAPI · 当前用户） | `xjtu-sealed:1:dpapi:<base64>`：只有本机本用户能解开 |
 * | Linux / macOS | **没有**（`null`） | 明文 `java.util.Properties`（权限 `0600`）—— 今天的行为，一个字没变 |
 *
 * `null` 是**如实的一档**：Linux 上确实没有原生保护（那条路要 Secret Service，属设计文档 §5.4），
 * 所以这一端不假装自己有。它的代价写在 `docs/windows-delivery.md` 的「残余风险」里。
 */
interface SecretProtector {

    /** 信封标识。同一个 [id] 的两份保护器被认为可以互相解开（换用户/换机器就不行了，见类 KDoc）。 */
    val id: String

    /** 明文 → 密文。**必须改变字节**；失败就抛 [SecretProtectionException]。 */
    fun protect(plain: ByteArray): ByteArray

    /** 密文 → 明文。解不开（换用户 / 换机器 / 密文被改过）就抛 [SecretProtectionException]。 */
    fun unprotect(sealed: ByteArray): ByteArray
}

/**
 * 凭据保护/解保护失败。
 *
 * 为什么要有自己的类型（而不是让 JNA 的 `Win32Exception`、`IllegalArgumentException` 直接冒出来）：
 * 调用方（登录流程 / 设置页）要区分「这份凭据本机解不开」与「网络挂了」这两种失败，
 * 而保护器的实现细节（JNA、原生库、base64）不该漏到那一层。所有实现都必须把内部异常
 * 包成它再抛（见 [SecretProtector] 契约第 2、3 条）。
 */
class SecretProtectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * **Windows 的原生保护**：DPAPI（`CryptProtectData` / `CryptUnprotectData`），**当前用户**作用域。
 *
 * ## 为什么是 DPAPI，为什么经 JNA
 *
 * `CryptProtectData` 是 Windows 上存「本机本用户才能解开的秘密」的标准做法：密钥由系统按登录
 * 用户派生并托管，调用方不持有任何密钥 ⇒ 凭据文件被拷到别的机器、别的用户、或备份里，
 * 都解不开（而 Linux 那条「文件 + `0600`」只能防同机别的用户读，防不了拷走）。
 *
 * 作用域刻意用**当前用户**（不传 `CRYPTPROTECT_LOCAL_MACHINE`）：桌面端是「每个人装自己一份、
 * 装完各登各的」（`perUserInstall`，见 `desktop/build.gradle.kts`），凭据没有理由对同机所有用户
 * 可见。这一条与「换用户就解不开」是同一件事的两面，也是单测里钉住的那条语义。
 *
 * JDK 没有 DPAPI 的绑定（`SunMSCAPI` 是 CryptoAPI 的密钥库/签名，不含 DPAPI），所以这里用
 * **JNA**（`:data` 的 `jvmMain` 依赖 `jna-platform`，见 `data/build.gradle.kts`）：
 * `Crypt32Util` 是对 `Crypt32.CryptProtectData` 的直接封装（含 `DATA_BLOB` 的分配与释放）。
 * 选它而不是自己写 `java.lang.foreign`：FFM API 在 JDK 21 还是 preview（要 `--enable-preview`），
 * 而这一端的工具链就是 21。
 *
 * ## 熵（entropy）是固定的，**不能改**
 *
 * [ENTROPY] 参与密钥派生：改一个字节，之前写下的凭据就全解不开了。它不增加「同机同用户
 * 别的进程能不能解」的强度（那由 DPAPI 决定），只是把密文绑到本应用这一份用途上。
 *
 * ## 不吞异常
 *
 * 三个可能的失败点 —— 原生库没装/加载不了（`UnsatisfiedLinkError`）、Windows 拒绝调用
 *（`Win32Exception`）、密文被改过（同一个 `Win32Exception`）—— 一律包成
 * [SecretProtectionException] 抛出（见 [SecretProtector] 契约第 3 条）。**没有**「失败就退回明文
 * 落盘」这条路：那是把「凭据是分发的代价」这句话反过来写。
 *
 * ⚠️ **本机（Linux）跑不到这条路**：`protect` / `unprotect` 的真实行为只有 Windows 上才能验。
 * 单测里那两条 DPAPI 用例只在 `os.name` 是 Windows 时执行（CI 的 `windows-latest` 会真跑），
 * 本地是跳过 —— 未验证项清单见 `docs/windows-delivery.md` §7。
 */
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

    /**
     * `CRYPTPROTECT_UI_FORBIDDEN`：任何情况下都不弹系统对话框（这个进程没有窗口循环在等它，
     * 弹出来就是卡死）。调用失败时 JNA 抛的 `Win32Exception` 带上 GetLastError 的错误码。
     */
    private inline fun native(call: String, block: () -> ByteArray): ByteArray = try {
        block()
    } catch (t: Throwable) {
        throw SecretProtectionException(
            "DPAPI 的 $call 失败（本次不落盘，凭据不会以明文写入）：$t",
            t,
        )
    }
}

/**
 * 本机的凭据保护器：Windows 给 [DpapiSecretProtector]，其余平台**没有**（`null`）。
 *
 * `osName` 是参数（默认取系统属性）而不是直接读系统属性：平台分派是这一轮唯一能在 Linux 上
 * 真验的判据，所以它必须是可注入的纯函数（`SecretProtectorJvmTest` 就是这么钉的）。
 *
 * 判据用 `startsWith("Windows")`：`os.name` 在 Windows 上是 `Windows 10` / `Windows 11` /
 * `Windows Server 2022` 这一族，而 `:desktop` 里的 `openWithSystemProgram` 也在按 `contains("win")`
 * 做同一件事 —— 这里取更严的那个（小写 `win` 也可能出现在别的名字里）。
 */
fun secretProtectorForPlatform(osName: String = System.getProperty("os.name").orEmpty()): SecretProtector? =
    if (osName.startsWith("Windows", ignoreCase = true)) DpapiSecretProtector else null
