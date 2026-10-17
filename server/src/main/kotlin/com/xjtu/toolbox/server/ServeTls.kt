package com.xjtu.toolbox.server

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** 证书层的明确报错（`message` 直接给用户看）。 */
internal class ServeTlsException(message: String) : Exception(message)

/** HTTPS 形态的另一半：把「证书库文件」变成能用的 [SSLContext]。 */
internal object ServeTls {

    /** 证书库密码的环境变量名（不设它就用库旁边那个 `.password` 文件）。 */
    const val ENV_PASSWORD = "XJTU_TOOLBOX_TLS_PASSWORD"

    /** keytool 子进程里传密码用的环境变量名（`-storepass:env` 读它，从而不进 `ps`）。 */
    private const val ENV_KEYTOOL_PASSWORD = "XJTU_TOOLBOX_KEYTOOL_PASSWORD"

    /** 生成的证书库参数。 */
    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE = 2048
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val VALIDITY_DAYS = 3650
    private const val SUBJECT = "CN=localhost"

    /** 密码长度（Base64URL 编码前的字节数）。 */
    private const val PASSWORD_BYTES = 24

    private val random = SecureRandom()

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** SAN 里**永远**有的两条：浏览器用 `https://localhost:…` 与 `https://127.0.0.1:…` 都能对上名字。 */
    private val BASE_SAN = listOf("dns:localhost", "ip:127.0.0.1")

    /** 通配地址进 SAN 没有意义（`ip:0.0.0.0` 谁也不去连）。 */
    private val WILDCARD_HOSTS = setOf("0.0.0.0", "::", "[::]", "*")

    /** 把 [TlsSettings] 变成一份可用的材料：该生成的生成、该报错的报错。 */
    fun prepare(settings: TlsSettings, host: String): TlsMaterial {
        var generated = false
        if (!settings.keyStore.isFile && settings.generateIfMissing) {
            generateSelfSigned(settings.keyStore, settings.alias, host)
            generated = true
        }
        if (!settings.keyStore.isFile) {
            throw ServeTlsException(
                "证书库不存在：${settings.keyStore.path}（--https 下没有证书就开不了服务；" +
                    "要么去掉 --cert 让 serve 在 ${ServeConfig.defaultTlsKeyStore().path} 生成一张自签，" +
                    "要么自己用 keytool 生成一份）",
            )
        }
        val password = resolvePassword(settings.keyStore)
        val keyStore = loadKeyStore(settings.keyStore, password)
        if (!keyStore.containsAlias(settings.alias)) {
            val aliases = keyStore.aliases().toList().sorted().joinToString("、")
            throw ServeTlsException(
                "证书库 ${settings.keyStore.path} 里没有别名「${settings.alias}」（有的是：$aliases）" +
                    "—— 别名不对可以用 --cert-alias 指过去",
            )
        }
        return TlsMaterial(
            context = sslContext(keyStore, password),
            keyStore = settings.keyStore,
            alias = settings.alias,
            generated = generated,
        )
    }

    /** 证书库 + 别名 + 是否这次刚生成。 */
    data class TlsMaterial(
        val context: SSLContext,
        val keyStore: File,
        val alias: String,
        val generated: Boolean,
    )

    /** 自签库的密码文件：与库**同名**、后缀 `.password`（`0600`）。 */
    fun passwordFile(keyStore: File): File = File(keyStore.absoluteFile.parentFile, "${keyStore.name}.password")

    /** 库密码：环境变量优先，其次库旁边的 `.password` 文件。 */
    fun resolvePassword(keyStore: File): CharArray {
        System.getenv(ENV_PASSWORD)?.takeIf { it.isNotEmpty() }?.let { return it.toCharArray() }
        val file = passwordFile(keyStore)
        val raw = if (file.isFile) runCatching { file.readText().trim() }.getOrDefault("") else ""
        if (raw.isNotEmpty()) return raw.toCharArray()
        throw ServeTlsException(
            "不知道证书库 ${keyStore.path} 的密码：设环境变量 $ENV_PASSWORD，或把密码写在 ${file.path} 里" +
                "（serve 自己生成的那张自签证书会把密码写在那个文件里）",
        )
    }

    /** 生成一张自签证书库（PKCS12）并把它与密码文件的权限收到 `0600`。 */
    fun generateSelfSigned(keyStore: File, alias: String, host: String) {
        val keytool = keytoolExecutable()
        val password = newPassword()
        keyStore.absoluteFile.parentFile?.mkdirs()
        val command = listOf(
            keytool.path,
            "-genkeypair",
            "-alias", alias,
            "-keyalg", KEY_ALGORITHM,
            "-keysize", "$KEY_SIZE",
            "-sigalg", SIGNATURE_ALGORITHM,
            "-validity", "$VALIDITY_DAYS",
            "-dname", SUBJECT,
            "-ext", "SAN=${sanOf(host)}",
            "-storetype", "PKCS12",
            "-keystore", keyStore.path,
            // 密码走**子进程的环境变量**：写成 -storepass <值> 会在 ps 里露出来
            "-storepass:env", ENV_KEYTOOL_PASSWORD,
        )
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .also { it.environment()[ENV_KEYTOOL_PASSWORD] = String(password) }
                .start()
        } catch (e: IOException) {
            throw ServeTlsException("生成自签证书失败：起不了 keytool（${keytool.path}）：${e.message}")
        }
        // 先把输出读完再 waitFor（子进程写满管道就卡住的那种死锁）
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0 || !keyStore.isFile) {
            throw ServeTlsException("生成自签证书失败（keytool 退出码 $code）：${output.trim()}")
        }
        restrictToOwner(keyStore)
        writePasswordFile(keyStore, password)
    }

    /** 自签证书的 SAN：永远有 `dns:localhost` 与 `ip:127.0.0.1`，另外 [host] 是一个具体地址时把它也加上（`--host 192.168.1.5` 那种对外服务的用法少一条「名字对不上」的抱怨）。 */
    fun sanOf(host: String): String {
        val entries = BASE_SAN.toMutableList()
        when {
            host.isBlank() || host in WILDCARD_HOSTS -> Unit
            IPV4.matches(host) -> entries += "ip:$host"
            host.contains(':') -> Unit
            else -> entries += "dns:$host"
        }
        return entries.distinct().joinToString(",")
    }

    /** 库里的证书（测试用来建「只信这一张」的 trust store；顺带证明库是能读的）。 */
    fun certificate(keyStore: File, alias: String): X509Certificate {
        val password = resolvePassword(keyStore)
        val store = loadKeyStore(keyStore, password)
        return store.getCertificate(alias) as? X509Certificate
            ?: throw ServeTlsException("证书库 ${keyStore.path} 的别名「$alias」不是一张 X.509 证书")
    }

    /** 密码文件（`0600`）。 */
    private fun writePasswordFile(keyStore: File, password: CharArray) {
        val file = passwordFile(keyStore)
        file.writeText(String(password) + "\n")
        restrictToOwner(file)
    }

    /** 只有本用户可读写（非 posix 文件系统上 `setPosixFilePermissions` 会抛 ⇒ 与 `:data` 同款 `runCatching`）。 */
    private fun restrictToOwner(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private fun newPassword(): CharArray = ByteArray(PASSWORD_BYTES)
        .also { random.nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        .toCharArray()

    /** 读一个证书库。 */
    private fun loadKeyStore(keyStore: File, password: CharArray): KeyStore {
        for (type in listOf("PKCS12", "JKS")) {
            val store = runCatching { KeyStore.getInstance(type) }.getOrNull() ?: continue
            val loaded = runCatching { keyStore.inputStream().use { store.load(it, password) } }
            if (loaded.isSuccess) return store
        }
        throw ServeTlsException(
            "读不了证书库 ${keyStore.path}：密码不对（环境变量 $ENV_PASSWORD 或 ${passwordFile(keyStore).path}），" +
                "或者它不是 PKCS12/JKS 格式",
        )
    }

    private fun sslContext(keyStore: KeyStore, password: CharArray): SSLContext {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        return SSLContext.getInstance("TLS").apply {
            init(managers.keyManagers, null, null)
        }
    }

    /** `java.home` 下那个 keytool（打包运行时里也有它：它属于 `java.base`）。 */
    private fun keytoolExecutable(): File {
        val name = if (File.separatorChar == '\\') "keytool.exe" else "keytool"
        val file = File(File(System.getProperty("java.home"), "bin"), name)
        if (!file.canExecute()) {
            throw ServeTlsException(
                "找不到 keytool：${file.path}（自签证书是它生成的；" +
                    "要么换个带 keytool 的 JDK 跑，要么自己生成一份证书库并用 --cert 指过来）",
            )
        }
        return file
    }
}
