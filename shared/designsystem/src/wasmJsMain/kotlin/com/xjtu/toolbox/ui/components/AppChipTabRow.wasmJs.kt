package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
internal actual fun <K : Any> PlatformChipTabRow(
    options: List<K>,
    selected: K,
    onSelect: (K) -> Unit,
    label: (K) -> String,
    containerColor: Color,
    modifier: Modifier,
    reselectable: Boolean,
) = FilterChipRow(options, selected, onSelect, label, modifier)
