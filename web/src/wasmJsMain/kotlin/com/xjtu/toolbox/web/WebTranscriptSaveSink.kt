@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.web

import com.xjtu.toolbox.dzpz.TranscriptSaveSink
import com.xjtu.toolbox.platform.base64UrlEncode
import com.xjtu.toolbox.platform.showBriefMessage

/**
 * 「把这份成绩单 PDF 存到本端该存的地方」在浏览器这一端的实现：**交回给浏览器自己下**
 * （`Blob` + 一个隐藏的下载锚点）。
 *
 * 三处与 Android / 桌面的差别（都是这一端本来的样子，不是缺口）：
 *
 * 1. **目标位置是浏览器的下载目录**，本端不知道它在哪 ⇒ [locationLabel] 是空串（屏上那一行
 *    「将保存到 X」于是不画 —— 与 `:web` 其它落盘动作同一条：`WebArchiveActions` 也是交给浏览器）；
 * 2. **文件名由浏览器照 `download` 属性存**（同名时浏览器自己加序号）；
 * 3. 失败由这一端自己报一句（`showBriefMessage`）：屏只管点一下。
 */
internal object WebTranscriptSaveSink : TranscriptSaveSink {

    override val actionLabel: String = "下载到本机"

    override val hint: String = "浏览器会把它存到你自己的下载目录（文件名就是上面那个）。"

    /** 空串 = 不画「将保存到 X」那一行（见类 KDoc 第 1 条）。 */
    override val locationLabel: String = ""

    override fun save(filename: String, bytes: ByteArray) {
        val error = saveBase64File(filename.ifBlank { "transcript.pdf" }, PDF_MIME, base64UrlEncode(bytes))
        if (error.isNotEmpty()) showBriefMessage("保存成绩单失败：$error")
    }

    private const val PDF_MIME = "application/pdf"
}

/**
 * 把一段 URL-safe Base64 的字节存成文件（浏览器下载）。回空串 = 成功，否则是给用户看的一句话。
 *
 * 与 `WebArchiveIo.downloadZip` 同一条路子（那一份在页面里拼 zip，这一份只是把现成的字节兜出去）。
 */
@JsFun(
    """
(name, mime, b64) => {
  try {
    const binary = atob(b64.replace(/-/g, '+').replace(/_/g, '/'));
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) { bytes[i] = binary.charCodeAt(i); }
    const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    return '';
  } catch (e) {
    return String((e && e.message) || e);
  }
}
""",
)
external fun saveBase64File(name: String, mime: String, base64Url: String): String
