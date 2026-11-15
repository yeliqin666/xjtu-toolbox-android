package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * 文本文件的平台缝（主页设置里「导出 / 导入自定义布局」用）。
 *
 * 三端都有「存一个文件 / 挑一个文件」这件事，但没有一种跨端 API：Android 是 SAF
 * （系统「新建文档」/「打开文档」），桌面是 `JFileChooser`，Web 只能是浏览器下载 + `<input type=file>`。
 * 所以照 `rememberHeroImagePicker` 那一套（同一份 expect + 三端 actual）来做，抽屉只认这两个动作。
 *
 * 文本一律 UTF-8；扩展名与 MIME 由调用方给（`.txt` / `text/plain` 就够，内容是我们自己的行式格式）。
 */

/** 存文本时用的 MIME；三端一致。 */
internal const val TEXT_FILE_MIME: String = "text/plain"

/**
 * 把一段文本存成文件：Android 弹系统「新建文档」、桌面弹保存对话框、Web 交给浏览器下载。
 *
 * 成功 / 失败由各端自己冒一句提示（`showBriefMessage`），调用方不用管落盘细节。
 */
@Composable
expect fun rememberSaveTextFile(): (fileName: String, text: String) -> Unit

/**
 * 让用户挑一个文本文件读进来。
 *
 * 用户取消时**不回调**；读不出来走 [onError]（给一句能直接显示的话）。
 * 返回「去挑」的动作，由调用方挂在某一行上。
 */
@Composable
expect fun rememberPickTextFile(onLoaded: (String) -> Unit, onError: (String) -> Unit): () -> Unit
