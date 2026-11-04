# MIUIX Reference

Artifacts: `top.yukonga.miuix.kmp:miuix-{ui,preference,icons,squircle,nav}-android`,
resolved from Maven Central. The version is pinned in `gradle/libs.versions.toml`
(`0.9.4`): every build compiles against exactly that stable release, and an
upgrade is a deliberate edit to the version catalog.

Upstream: https://github.com/compose-miuix-ui/miuix

## Modules

```kotlin
implementation(libs.miuix.ui)
implementation(libs.miuix.preference)
implementation(libs.miuix.icons)
implementation(libs.miuix.squircle)
implementation(libs.miuix.nav)
```

0.9.x renamed the old `Super*` era API into the current packages:

- `top.yukonga.miuix.kmp.basic`
- `top.yukonga.miuix.kmp.preference`
- `top.yukonga.miuix.kmp.overlay`
- `top.yukonga.miuix.kmp.theme`
- `top.yukonga.miuix.kmp.utils`

## Screen Pattern

Use `Scaffold` + `TopAppBar` + `MiuixScrollBehavior` for normal pages.

```kotlin
val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

Scaffold(
    topBar = {
        TopAppBar(
            title = "设置",
            largeTitle = "设置",
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, null) } },
            scrollBehavior = scrollBehavior,
            color = MiuixTheme.colorScheme.background,
        )
    },
) { padding ->
    Column(
        Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
            .padding(padding)
    ) {
        // content
    }
}
```

Important: when using `Scaffold`, pass its `padding` to the content root. The
Miuix source notes that vertical scroll should normally be applied to the child
content, with padding/consumed insets handled at the content root.

## Overlay Rules

`OverlayDialog`, `OverlayBottomSheet`, `OverlayDropdownPreference`, and related
overlay components default to `renderInRootScaffold = true`.

In this app, put overlays inside the same `Scaffold { ... }` content scope unless
there is a specific reason not to. Otherwise they may miss the popup host and fail
to appear.

```kotlin
Scaffold(...) { padding ->
    Column(Modifier.padding(padding)) {
        // page content
    }

    OverlayBottomSheet(
        show = showSheet,
        title = "确认",
        onDismissRequest = { showSheet = false },
    ) {
        // sheet content
    }
}
```

If an overlay must be constrained to the current scaffold bounds, pass
`renderInRootScaffold = false`.

## Common Components

### `Button`

```kotlin
Button(
    onClick = { },
    enabled = true,
    modifier = Modifier,
    colors = ButtonDefaults.buttonColors(),
) {
    Text("确定")
}
```

### `TextButton`

```kotlin
TextButton(
    text = "取消",
    onClick = { },
)
```

### `Card`

```kotlin
Card(
    modifier = Modifier.fillMaxWidth(),
    cornerRadius = 16.dp,
    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
) {
    Column(Modifier.padding(16.dp)) { }
}
```

### `TextField`

The String overload is the normal app choice:

```kotlin
TextField(
    value = value,
    onValueChange = { value = it },
    label = "标题",
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
)
```

### `BasicComponent`

Use for simple settings/list rows.

```kotlin
BasicComponent(
    title = "缓存大小",
    summary = "12 MB",
    startAction = { SettingsIcon(MiuixIcons.CloudFill, color) },
)
```

## Preference Components

`miuix-preference` depends on `miuix-ui` and is the preferred API for settings.

```kotlin
SwitchPreference(
    title = "启动时检查更新",
    summary = "打开 App 时自动检查新版本",
    checked = enabled,
    onCheckedChange = { enabled = it },
)

ArrowPreference(
    title = "更新日志",
    summary = "查看历史版本变化",
    onClick = { showChangelog = true },
)

OverlayDropdownPreference(
    title = "更新渠道",
    items = listOf("Gitee 稳定版", "GitHub 稳定版"),
    selectedIndex = selectedIndex,
    summary = "当前：GitHub 稳定版",
    onSelectedIndexChange = { selectedIndex = it },
)
```

Use `OverlaySpinnerPreference` when the item model needs `DropdownItem` metadata
instead of plain strings.

## Theme Mapping

Prefer Miuix theme tokens over Material defaults:

| Need | Token |
| --- | --- |
| Page background | `MiuixTheme.colorScheme.background` |
| Card surface | `MiuixTheme.colorScheme.surface` |
| Muted card | `MiuixTheme.colorScheme.surfaceVariant` |
| Primary action | `MiuixTheme.colorScheme.primary` |
| Secondary chip/card | `MiuixTheme.colorScheme.secondaryContainer` |
| Body text | `MiuixTheme.colorScheme.onSurface` |
| Muted summary | `MiuixTheme.colorScheme.onSurfaceVariantSummary` |
| Error | `MiuixTheme.colorScheme.error` |

Text styles used most in this app:

| Use | Style |
| --- | --- |
| Page/card title | `title2`, `title3`, `subtitle` |
| Body | `body1`, `body2` |
| Caption/meta | `footnote1`, `footnote2` |
| Large numeric value | `headline1` |

## Practical Rules For This App

- Use `TopAppBar(largeTitle = ...)` for first-level and complex second-level pages.
- Pair large titles with `MiuixScrollBehavior` and `nestedScroll`; otherwise the
  title will not collapse.
- Prefer `OverlayBottomSheet` for action sheets and rename/edit forms.
- Prefer `OverlayDialog` for short confirmations.
- Do not put overlay components before/outside the page `Scaffold`.
- Avoid raw Material components unless Miuix has no equivalent.
- Keep settings rows on `SwitchPreference`, `ArrowPreference`, and
  `OverlayDropdownPreference` for consistent spacing and animation.
- Use `overScrollVertical()` on main scroll containers for Miuix feel.
- 选色一律用本仓库的 `com.xjtu.toolbox.ui.components.AppColorPicker`，不要直接用
  `ColorPalette` / `ColorPicker`：0.9.4 里这两个组件把透明度滑条写死在内部，
  **没有关掉的参数**（见下节）。

## Color Components: 透明度滑条去不掉

0.9.4 的两个取色组件都内置 alpha，且签名里没有开关：

| 组件 | alpha 滑条在哪 | 能否关掉 |
|---|---|---|
| `ColorPalette` | 网格下面无条件跟一条 `HsvAlphaSlider`（`basic/ColorPalette.kt`，约 163–175 行） | 不能 |
| `ColorPicker` | 通道滑条里最后一条是 `HsvAlphaSlider`（`basic/ColorPicker.kt`，约 233–244 行；OkHSV/OkLab/OkLch 各变体同样） | 不能 |

本项目的颜色只用于课程块、待办图标、主题色与首页卡片，落盘一律走
`CourseColors.toHex`（只留 `#RRGGBB`）。让用户拖出一条半透明色就会出现
「面板显示的 Hex 与眼睛看到的色块对不上」——issue #311 就是这个现象。

`AppColorPicker` 的做法：只借公开的 `HsvHueSlider`（它不带 alpha），
「饱和度 × 明度」二维方块自己用两层渐变画（横铺白→纯色相色，竖压透明→黑），
产出的 alpha 恒为 1。若要再改颜色相关的界面，沿用这个组件。

## 0.9.2 Notes

Important upstream changes in the 0.9.x line:

- Module split: `miuix` became `miuix-ui` and `miuix-preference`.
- `Super*` components were replaced/renamed into the current `Overlay*`,
  `BasicComponent`, and preference APIs.
- `TopAppBar` API was reworked; icon slot padding is handled internally and
  parameter names differ from older snippets.
- New optional modules exist upstream, including `miuix-blur`, `miuix-squircle`,
  and `miuix-navigation3-ui`. This app currently does not depend on them.

