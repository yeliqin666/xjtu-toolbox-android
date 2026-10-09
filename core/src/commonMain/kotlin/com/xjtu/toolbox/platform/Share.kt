package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * 平台能力：**系统分享**（把「标题 + 链接」交给系统分享面板）。
 *
 * 这是交接文档 §4 里「分享 / 打开」那一族，与 [rememberPhoneDialer] 是邻居：都是「把一件事交给
 * 系统去办」。**移植的是 :app 既有实现，不是新造 API** —— 语义、Extra 键、那个「分享到」的
 * chooser 标题、以及「不吞异常」都与 `community/DiscussionModeration.kt` 里的 `shareLink(context, …)`
 * 逐字一致（那正是这条家族规矩的由来：上一轮自造过一套 Haptics/Share，撤回了）。
 *
 * 调用点约定：`val share = rememberShareLink(); … share(title, url)`，正文由实现拼成
 * `"$title\n$url"`（社区帖子的分享文案历来如此，别在调用点各拼一遍）。
 *
 * 各端：Android = `Intent.ACTION_SEND` + `createChooser`；jvm / Web 没有系统分享面板，
 * 退化成空实现（不弹错误、点了没反应 —— 与这些端本来就没有分享能力一致）。
 * Web 将来要用 `navigator.share` 时只改 `Share.wasmJs.kt` 一个文件。
 */
@Composable
expect fun rememberShareLink(): (title: String, url: String) -> Unit
