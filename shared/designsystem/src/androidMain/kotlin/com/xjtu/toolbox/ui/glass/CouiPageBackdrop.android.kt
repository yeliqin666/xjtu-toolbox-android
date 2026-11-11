package com.xjtu.toolbox.ui.glass

import androidx.compose.ui.Modifier
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.couiBackdrop

/**
 * Android 的 [CouiPageBackdrop]：把 `CouiBackdrop` 包成 commonMain 能拿的接口。
 *
 * 公开而不是 internal，是因为 Android 端的首页 actual 也要把它解回 raw `CouiBackdrop`
 * （`CouiTopAppBarSurface` / 渐变模糊只认 raw 类型）。
 */
class AndroidCouiPageBackdrop(internal val backdrop: CouiBackdrop) : CouiPageBackdrop {
    override fun recordInto(modifier: Modifier): Modifier = modifier.couiBackdrop(backdrop)
}
