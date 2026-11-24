# 桌面端打包

Linux 与 Windows 共用同一个 `:desktop` 模块（Compose Desktop + jpackage）。jpackage 不能交叉构建，安装包只能在目标系统上打。

| 形态 | 任务 | 打包机 |
|---|---|---|
| Windows `.msi` / `.exe` | `:desktop:packageMsi` / `:desktop:packageExe` | Windows |
| Linux `.deb` | `:desktop:packageDeb` | Linux |
| app-image（免安装目录） | `:desktop:createDistributable` / `:desktop:packageAppImage` | 当前系统 |
| `tar.gz` | `:desktop:packageTarGz`（打包 app-image） | Linux |

产物在 `desktop/build/compose/binaries/main/`。

## 配置要点

- 应用名：Windows 为「岱宗盒子」，Linux 为 `xjtu-dzhz`（deb 包名、`/opt/xjtu-dzhz`、启动器）；发布资产统一用 `xjtu-dzhz-<版本>` 前缀。数据目录是 `~/.local/share/xjtu-dzhz`（凭据、设置、serve 令牌与证书），缓存在 `~/.cache/xjtu-dzhz`（或 `$XDG_CACHE_HOME/xjtu-dzhz`）。
- `desktopPackageVersion` 必须与 `:app` 的 `versionName` 逐字相同，且只能是 `x.y.z`（`DesktopPackageVersionJvmTest` 校验）。
- 图标在 `desktop/icons/`（`linux.png`、`windows.ico`），由应用图标导出；`windows.ico` 必须含 256×256 一张。
- MSI 摘要信息不走本地化代码页，`description` 只能写 ASCII；Windows 的中文产品名和快捷方式靠 CI 以 zh_CN locale 跑 jpackage（`desktop-windows.yml` 里的 `JAVA_TOOL_OPTIONS`），MSI 才用 936 代码页；本机打包同样要带上这个环境变量。

## CI

`.github/workflows/desktop-linux.yml` 与 `desktop-windows.yml`：PR 改到打包配置、图标或版本目录时出包验证；正式版发布时由 `build.yml` 以 `workflow_call` 调用，把安装包补进同一个 Release。Windows 那条还会跑 `:shared:data` 的平台测试，真 DPAPI 用例只在那里执行。

## 凭据存储

`JvmCredentialStore` 在 Windows 上套 DPAPI（当前用户作用域，经 JNA 调用），落盘为 `xjtu-sealed:1:<保护器>:<base64>` 信封；拷到别的机器或用户都解不开。熵值 `xjtu-toolbox/credentials/v1` 发版后不可更改。Linux / macOS 没有原生保护，用 `0600` 文件。

- 解不开时抛 `SecretProtectionException`，不把信封原文当密码，也不当作「从未保存」。
- 保护失败时直接写失败，盘上绝不出现明文。
- 旧的明文文件仍可读回。
- 目前只有凭据受保护；cookie、站点快照、设置在 Windows 上仍是明文文件。

## 更新

`DesktopUpdate` 从 GitHub 发布列表里找带 `.msi` / `.exe` 资产的版本并打开发布页，不做自动下载安装（`canInstallAutomatically = false`）。
