package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar

/**
 * 简化版 MIUIX SearchBar — 仅作为搜索框样式使用，不展开搜索结果页。
 * 统一替换原项目内分散的 TextField + leadingIcon 自制搜索栏。
 */
@Composable
fun AppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String = "搜索",
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {}
) {
    SearchBar(
        inputField = {
            InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                expanded = false,
                onExpandedChange = {},
                label = label,
                modifier = Modifier.fillMaxWidth()
            )
        },
        expanded = false,
        onExpandedChange = {},
        insideMargin = DpSize(0.dp, 0.dp),
        modifier = modifier
    ) {}
}
