@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Web 这一端档案的**传输那一半**（S6；`docs/storage-layout-plan.md` 的 S6 行）。
 *
 * 浏览器没有文件系统（`localStorage` 已由 store 投影覆盖，`files` 那一层在这一端根本不存在），
 * 所以这一端的「落地通道」只有两条浏览器 API：
 *
 *  - **导出**：档案字节映射 → zip → `Blob` + `URL.createObjectURL` + `<a download>`（点击即下载）；
 *  - **导入**：`<input type=file>` 选一份 zip → 在**内存里**解成「档案内相对路径 → 字节」
 *    （§6.1 web 那一行说的「内存里解」）。
 *
 * zip 的读写**都在 JS 那一侧**（`@JsFun` 里的那两段）：`DecompressionStream('deflate-raw')` 是浏览器
 * 自带的 inflate（Android/桌面的 zip 是 deflate 压的，必须解得开），而写出去的那一份用 **stored**
 * （不压缩）并且时间戳固定 ⇒ 同一份档案两次导出字节可复现。Kotlin 这一侧只做两件纯事：把
 * `Map<String, String>` 与 JSON 互转（走 `:core` 的 [AppJson]，不新造一套转义）。
 *
 * ⚠️ **本批跑不了运行时证明**：`:web` 现在没有测试源集（D14 = S8），`:core` 也刻意没声明 wasm 测试
 * 环境（`core/build.gradle.kts` 的注释：nodejs 下 Compose 的 `skiko.mjs` 会 `ERR_MODULE_NOT_FOUND`）。
 * 所以这两段 JS 只吃 `:web:compileKotlinWasmJs` 的**编译门禁**，运行时证明要等 S8 或真浏览器里手点
 * —— 这一点如实记在这里，不假装它被验过。
 */
internal object WebArchiveIo {

    /** 与 `:app` 的 `AppArchiveUx.ARCHIVE_MIME` 同一个口径：zip 是档案的运输形态（§2.0）。 */
    const val ARCHIVE_MIME = "application/zip"

    /** 下载的文件名（与 `:app` 的 `exportLauncher.launch("xjtu-toolbox-archive.zip")` 逐字相同）。 */
    const val DEFAULT_FILE_NAME = "xjtu-toolbox-archive.zip"

    /**
     * 档案字节映射 → 浏览器下载（同步：把映射交给 JS 那一侧组装 zip 并点一次下载锚点）。
     *
     * 同步是有意为之：浏览器要求「下载」由用户手势发起，所以这条调用必须落在设置页那一行的
     * `onClick` 里（不能先 `suspend` 再下载）。
     */
    fun download(entries: Map<String, String>, fileName: String = DEFAULT_FILE_NAME) {
        downloadZip(fileName, entriesToJson(entries))
    }

    /**
     * `<input type=file>` 选一份 zip → 内存里解 → [PickedArchive]。
     *
     * 异步：浏览器的文件读取与 `DecompressionStream` 都是 Promise ⇒ 结果从 [onPicked] 出来
     * （设置页那一行把结果写进自己那行文本里）。用户没选文件 / 选了一份不是 zip 的东西 / 解不开，
     * 都给 [PickedArchive.Failed] 带一句如实的原因 —— 不抛、不静默。
     */
    fun pick(onPicked: (PickedArchive) -> Unit) {
        pickArchiveFile(ARCHIVE_MIME) { fileName, payload ->
            onPicked(decodePicked(fileName, payload))
        }
    }

    /** 档案字节映射 → 交回给 JS 的那份 JSON（`{"<档案内相对路径>": "<字节>"}`）。 */
    internal fun entriesToJson(entries: Map<String, String>): String =
        JsonObject(entries.entries.associate { it.key to JsonPrimitive(it.value) }).toString()

    /** JS 交回来的 JSON 信封 → [PickedArchive]（`{"entries": {...}}` 或 `{"error": "..."}`）。 */
    internal fun decodePicked(fileName: String, payload: String): PickedArchive {
        val obj = runCatching { AppJson.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return PickedArchive.Failed(fileName, "选回来的内容不是 JSON")
        (obj["error"] as? JsonPrimitive)?.content?.let { return PickedArchive.Failed(fileName, it) }
        val entries = (obj["entries"] as? JsonObject)
            ?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }
            ?: return PickedArchive.Failed(fileName, "解出来的档案里没有条目")
        return PickedArchive.Entries(fileName, entries)
    }
}

/** 选一份档案的结果：解出条目，或一句如实的原因。 */
internal sealed interface PickedArchive {

    /** 解出来的档案：`entries` 就是「档案内相对路径 → 字节」（含 `manifest.json`）。 */
    data class Entries(val fileName: String, val entries: Map<String, String>) : PickedArchive

    /** 没选文件 / 不是 zip / 解不开 / 压缩方式不认识。 */
    data class Failed(val fileName: String, val reason: String) : PickedArchive
}

/**
 * 组装 zip 并下载（浏览器 API 全在这一段里）。
 *
 * 为什么用 **stored**（不压缩）而不是 `CompressionStream`：档案里全是 JSON 文本、体积本来就小，
 * 换来的是「解出来的字节与写进去的逐字相同」这条最好核的性质；时间戳固定成 1980-01-01
 * （DOS 时间的下界）⇒ 同一份档案两次导出的 zip 字节相同（A3 的口径在 `:core` 的 `export` 上，
 * 这里不引入第二处不稳定源）。
 */
@JsFun(
    """
(fileName, entriesJson) => {
  const entries = JSON.parse(entriesJson);
  const enc = new TextEncoder();
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) { c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1); }
    table[i] = c >>> 0;
  }
  const crc32 = (u8) => {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < u8.length; i++) { c = table[(c ^ u8[i]) & 0xFF] ^ (c >>> 8); }
    return (c ^ 0xFFFFFFFF) >>> 0;
  };
  const parts = [];
  const central = [];
  let offset = 0;
  const keys = Object.keys(entries);
  for (let n = 0; n < keys.length; n++) {
    const name = keys[n];
    const nameBytes = enc.encode(name);
    const data = enc.encode(entries[name]);
    const crc = crc32(data);
    const local = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(6, 0x0800, true);
    lv.setUint16(8, 0, true);
    lv.setUint16(10, 0, true);
    lv.setUint16(12, 0x0021, true);
    lv.setUint32(14, crc, true);
    lv.setUint32(18, data.length, true);
    lv.setUint32(22, data.length, true);
    lv.setUint16(26, nameBytes.length, true);
    lv.setUint16(28, 0, true);
    local.set(nameBytes, 30);
    parts.push(local);
    parts.push(data);
    const cd = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(cd.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint16(12, 0, true);
    cv.setUint16(14, 0x0021, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, data.length, true);
    cv.setUint32(24, data.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint16(30, 0, true);
    cv.setUint16(32, 0, true);
    cv.setUint16(34, 0, true);
    cv.setUint16(36, 0, true);
    cv.setUint32(38, 0, true);
    cv.setUint32(42, offset, true);
    cd.set(nameBytes, 46);
    central.push(cd);
    offset += local.length + data.length;
  }
  let cdSize = 0;
  for (let i = 0; i < central.length; i++) { cdSize += central[i].length; }
  const eocd = new Uint8Array(22);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, central.length, true);
  ev.setUint16(10, central.length, true);
  ev.setUint32(12, cdSize, true);
  ev.setUint32(16, offset, true);
  const blob = new Blob(parts.concat(central, [eocd]), { type: 'application/zip' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}
"""
)
external fun downloadZip(fileName: String, entriesJson: String)

/**
 * 选一份 zip 并在内存里解开（`<input type=file>` + 中央目录解析 + `DecompressionStream`）。
 *
 * 结果从回调的 `(name, payload)` 出来：`payload` = `{"entries": {"<路径>": "<字节>"}}`，
 * 或 `{"error": "..."}`。任何异常都在 JS 里被接住并变成 `error`（不让异常穿过 wasm 边界）。
 */
@JsFun(
    """
(accept, onPicked) => {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = accept;
  input.style.display = 'none';
  document.body.appendChild(input);
  const finish = (name, payload) => {
    try { input.remove(); } catch (e) { }
    onPicked(name, payload);
  };
  input.onchange = () => {
    const file = input.files && input.files[0];
    if (!file) { finish('', JSON.stringify({ error: '没有选择文件' })); return; }
    file.arrayBuffer().then(async (buf) => {
      try {
        const dv = new DataView(buf);
        let eocd = -1;
        const lowest = Math.max(0, buf.byteLength - 65558);
        for (let i = buf.byteLength - 22; i >= lowest; i--) {
          if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
        }
        if (eocd < 0) { throw new Error('这不是 zip（没找到目录结尾）'); }
        const count = dv.getUint16(eocd + 10, true);
        let off = dv.getUint32(eocd + 16, true);
        const dec = new TextDecoder();
        const entries = Object.create(null);
        for (let n = 0; n < count; n++) {
          if (dv.getUint32(off, true) !== 0x02014b50) { throw new Error('zip 目录项损坏'); }
          const method = dv.getUint16(off + 10, true);
          const compSize = dv.getUint32(off + 20, true);
          const nameLen = dv.getUint16(off + 28, true);
          const extraLen = dv.getUint16(off + 30, true);
          const commentLen = dv.getUint16(off + 32, true);
          const localOff = dv.getUint32(off + 42, true);
          const entryName = dec.decode(new Uint8Array(buf, off + 46, nameLen));
          const lNameLen = dv.getUint16(localOff + 26, true);
          const lExtraLen = dv.getUint16(localOff + 28, true);
          const dataAt = localOff + 30 + lNameLen + lExtraLen;
          const raw = new Uint8Array(buf, dataAt, compSize);
          off += 46 + nameLen + extraLen + commentLen;
          if (entryName.charAt(entryName.length - 1) === '/') { continue; }
          if (method === 0) { entries[entryName] = dec.decode(raw); continue; }
          if (method !== 8) { throw new Error('不支持的压缩方式：' + method); }
          const stream = new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
          const plain = new Uint8Array(await new Response(stream).arrayBuffer());
          entries[entryName] = dec.decode(plain);
        }
        finish(file.name, JSON.stringify({ entries: entries }));
      } catch (e) {
        finish(file.name, JSON.stringify({ error: String((e && e.message) || e) }));
      }
    }, (e) => {
      finish(file.name, JSON.stringify({ error: '读文件失败：' + String((e && e.message) || e) }));
    });
  };
  input.click();
}
"""
)
external fun pickArchiveFile(accept: String, onPicked: (String, String) -> Unit)
