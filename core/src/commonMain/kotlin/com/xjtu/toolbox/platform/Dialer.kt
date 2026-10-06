package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable

/**
 * 平台能力：**拨号**（把号码交给系统拨号盘）。
 *
 * 这是交接文档 §4 里「分享 / 打开」那一族的邻居：黄页点电话、学籍档案点号码都走它。
 * 语义刻意只到「把号码交给系统」为止 —— 不直接拨出（`ACTION_DIAL` 而非 `ACTION_CALL`），
 * 用户在拨号盘上还有一次确认机会，这与 :app 现有行为一致。
 *
 * 返回一个 `(String) -> Unit`：号码取不出来（空串）时调用方按约定不应把号码画成可点，
 * 所以这里不需要返回值告诉调用方"成功没有"。
 *
 * 各端：Android = `Intent.ACTION_DIAL`；jvm / Web 没有系统拨号盘，退化成空实现
 * （不弹错误、不改变可点样式，只是点了没反应——与这些端本来就没有电话能力一致）。
 */
@Composable
expect fun rememberPhoneDialer(): (String) -> Unit
