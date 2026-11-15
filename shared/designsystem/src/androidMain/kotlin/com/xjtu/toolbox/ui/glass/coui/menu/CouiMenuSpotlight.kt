package com.xjtu.toolbox.ui.glass.coui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import com.xjtu.toolbox.ui.glass.coui.SpotlightPreset
import com.xjtu.toolbox.ui.glass.coui.rememberCouiSpotlight

/** 光照特化和诊断由宿主提供, 不传播到其他组件。 */
@Composable
internal fun couiMenuSpotlight(
    interactive: Boolean,
    dark: Boolean,
    inputTarget: ((Offset, Boolean) -> LayoutCoordinates?)? = null,
): Pair<Modifier, (DrawScope.() -> Unit)?> =
    rememberCouiSpotlight(
        interactive = interactive,
        inputTarget = inputTarget,
        preset = SpotlightPreset.TranslucentLarge1,
        dark = dark,
        diffuse = LocalCouiMenuStyle.current.spotlightDiffuse,
        onDiagnostic = LocalCouiMenuDiagnostic.current ?: {},
    )
