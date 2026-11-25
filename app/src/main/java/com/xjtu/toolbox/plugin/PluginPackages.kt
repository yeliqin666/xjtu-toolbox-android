package com.xjtu.toolbox.plugin

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipFile

/** 包里的清单固定叫这个名字，签名是清单**原始字节**的 `SHA256withECDSA`。 */
internal const val PACKAGE_MANIFEST_NAME = "dzhz.plugin.json"
internal const val PACKAGE_SIGNATURE_NAME = "dzhz.plugin.sig"

/** 防御上限：基本够用就好，不追求把每种 zip 炸弹都挡住。 */
internal const val MAX_PACKAGE_ENTRIES = 64
internal const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024

/** 一次检查完的插件包：清单、运行时形态、签名者指纹、声明过的文件。 */
internal class InspectedPackage(
    val manifest: PluginPackageManifest,
    val runtime: PluginManifest,
    /** 公钥 SPKI 的 sha256，TOFU 用它认「同一个作者」。 */
    val signerSha256: String,
    val files: Map<String, PluginFileEntry>,
)

/**
 * 插件 zip 包的读写：验签、核对 [PluginPackageManifest.files]、解压。
 *
 * 信任模型是 TOFU：第一包装进来时记住签名者指纹，之后同名 id 的更新必须用同一把钥匙、
 * versionCode 更大。包里每个会落到磁盘的文件都必须在 files 里声明，声明了就必须对得上
 * sha256 和大小——解压时再核对一次，防住「校验完再替换」。
 */
internal object PluginPackages {

    /** 包内相对路径的安全检查：不许绝对路径、不许 `..`、不许反斜杠。 */
    fun isSafePath(path: String): Boolean {
        if (path.isBlank() || path.length > 200) return false
        if (path.startsWith("/") || path.contains('\\') || path.contains('\u0000')) return false
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** 打开包、验签、核对文件；返回检查结果。任何一步失败都抛 [IllegalArgumentException]。 */
    fun inspect(packageFile: File): InspectedPackage {
        ZipFile(packageFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.isNotEmpty()) { "插件包是空的" }
            require(entries.size <= MAX_PACKAGE_ENTRIES) { "插件包条目过多（上限 $MAX_PACKAGE_ENTRIES）" }
            entries.forEach { entry ->
                val name = entry.name
                val check = if (name.endsWith("/")) name.dropLast(1) else name
                require(isSafePath(check)) { "包内路径不合法：$name" }
            }

            val manifestEntry = zip.getEntry(PACKAGE_MANIFEST_NAME)
                ?: throw IllegalArgumentException("插件包根目录缺少 $PACKAGE_MANIFEST_NAME")
            val signatureEntry = zip.getEntry(PACKAGE_SIGNATURE_NAME)
                ?: throw IllegalArgumentException("插件包根目录缺少 $PACKAGE_SIGNATURE_NAME")
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            val signatureText = zip.getInputStream(signatureEntry).use { it.readBytes().toString(Charsets.UTF_8) }

            val manifest = parsePackageManifest(manifestBytes)
            validatePackageManifest(manifest)

            val signerSha256 = verifySignature(manifestBytes, signatureText, manifest.pubkey)
            verifyFiles(zip, manifest)

            return InspectedPackage(
                manifest = manifest,
                runtime = manifest.toRuntimeManifest(),
                signerSha256 = signerSha256,
                files = manifest.files.associateBy { it.path },
            )
        }
    }

    /** 验签，返回公钥 SPKI 的 sha256（十六进制）。 */
    fun verifySignature(manifestBytes: ByteArray, signatureText: String, pubkeyBase64: String): String {
        val keyBytes = decodeBase64(pubkeyBase64, "pubkey")
        val signatureBytes = decodeBase64(signatureText.filterNot { it.isWhitespace() }, PACKAGE_SIGNATURE_NAME)
        val publicKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        }.getOrElse { throw IllegalArgumentException("pubkey 不是有效的 ECDSA 公钥：${it.message}") }
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey)
            update(manifestBytes)
        }
        require(runCatching { verifier.verify(signatureBytes) }.getOrDefault(false)) {
            "签名校验失败：清单被改过，或者不是这把公钥签的"
        }
        return sha256Hex(keyBytes)
    }

    /** 逐条核对 files：存在、大小对、sha256 对。 */
    fun verifyFiles(zip: ZipFile, manifest: PluginPackageManifest) {
        var total = 0L
        manifest.files.forEach { declared ->
            val entry = zip.getEntry(declared.path)
                ?: throw IllegalArgumentException("插件包缺少 ${declared.path}")
            require(!entry.isDirectory) { "${declared.path} 是目录，不是文件" }
            var size = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    require(size <= MAX_PACKAGE_BYTES) { "插件包解压后超过 32 MB" }
                    digest.update(buffer, 0, read)
                }
            }
            require(size == declared.size) {
                "${declared.path} 大小不符（清单写 ${declared.size}，实际 $size）"
            }
            require(sha256Hex(digest.digest()) == declared.sha256) {
                "${declared.path} 的 sha256 不符，包内容被改过"
            }
            total += size
            require(total <= MAX_PACKAGE_BYTES) { "插件包解压后超过 32 MB" }
        }
    }

    /**
     * 把 [files] 里的文件解到 [dest]，边写边复核 sha256 和大小。
     * 写完置为只读——Android 14+ 不允许从可写文件加载 dex。
     */
    fun extract(zipFile: File, files: Collection<PluginFileEntry>, dest: File) {
        ZipFile(zipFile).use { zip ->
            files.forEach { declared -> copyEntry(zip, declared, File(dest, declared.path)) }
        }
    }

    /** 只解一个声明过的文件（装包前把 APK 解出来检查入口类）。 */
    fun extractOne(zipFile: File, declared: PluginFileEntry, out: File) {
        ZipFile(zipFile).use { zip -> copyEntry(zip, declared, out) }
    }

    private fun copyEntry(zip: ZipFile, declared: PluginFileEntry, out: File) {
        val entry = zip.getEntry(declared.path)
            ?: throw IllegalArgumentException("插件包缺少 ${declared.path}")
        out.parentFile?.mkdirs()
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size <= MAX_PACKAGE_BYTES) { "插件包解压后超过 32 MB" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(size == declared.size && sha256Hex(digest.digest()) == declared.sha256) {
                "${declared.path} 解压后校验失败"
            }
            out.setReadOnly()
        } catch (t: Throwable) {
            runCatching { out.delete() }
            throw t
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decodeBase64(text: String, what: String): ByteArray = try {
        Base64.getDecoder().decode(text.trim())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("$what 不是合法的 Base64")
    }
}
