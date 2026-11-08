@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Web 侧的文本文件通道：保存 = 浏览器下载（Blob + 一个隐藏的 `<a download>`），
 * 读取 = `<input type=file>` + `file.text()`。
 *
 * DOM 那半边用 `@JsFun` 直接写：和 `WebArchiveIo` 里下载 zip / 选 zip 是同一套做法
 * （Kotlin/Wasm 的 DOM 绑定拼 Blob、拿文件内容这几步都不如几行 JS 直白）。
 */
@Composable
actual fun rememberSaveTextFile(): (fileName: String, text: String) -> Unit = remember {
    { fileName, text ->
        downloadText(fileName, text)
        // 浏览器不告诉我们「存到哪了」，只能报一句「交给下载了」
        showBriefMessage("已交给浏览器下载：$fileName")
    }
}

@Composable
actual fun rememberPickTextFile(onLoaded: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val loaded = rememberUpdatedState(onLoaded)
    val failed = rememberUpdatedState(onError)
    return remember {
        {
            pickTextFile(
                { text -> loaded.value(text) },
                { },
                { reason -> failed.value(reason.ifEmpty { "读不出这个文件" }) },
            )
        }
    }
}

/** 一段文本 → 浏览器下载（UTF-8）。 */
@JsFun(
    """
(fileName, text) => {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
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
external fun downloadText(fileName: String, text: String)

/** 选一个文本文件并把内容交回来；取消走 [onCancel]，读失败走 [onError]。 */
@JsFun(
    """
(onText, onCancel, onError) => {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = '.txt,.json,.md,text/plain';
  input.style.display = 'none';
  document.body.appendChild(input);
  const cleanup = () => { try { input.remove(); } catch (e) { } };
  let done = false;
  input.onchange = () => {
    if (done) { return; }
    done = true;
    const file = input.files && input.files[0];
    if (!file) { cleanup(); onCancel(); return; }
    file.text().then((t) => { cleanup(); onText(t); }, (e) => {
      cleanup();
      onError('读不出这个文件：' + String((e && e.message) || e));
    });
  };
  // 现代浏览器的「取消选择」事件（老浏览器不发，那就什么也不发生，不打扰用户）
  input.addEventListener('cancel', () => {
    if (done) { return; }
    done = true;
    cleanup();
    onCancel();
  });
  input.click();
}
"""
)
external fun pickTextFile(
    onText: (String) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
)
