# Windows 交付面（`.msi` / `.exe` · 凭据的 DPAPI · 更新路线）

这一份写的是**桌面端在 Windows 上怎么交付**：包装成什么、谁来打、凭据在 Windows 上受什么保护、
更新怎么走，以及**哪些事这一轮没能在真 Windows 上验过**（最后一节，最该读的就是它）。

上游决策在 `docs/desktop-port-plan.md`：D3（Linux 与 Windows 同一份 `:desktop`）、§5.1（凭据存储：
Windows 给 DPAPI，Linux 用「文件 + `0600`」）、§7（交付形态与待定项）、§7.1（Linux 三样）。

## 1. 交付形态

| 平台 | 形态 | 任务 | 谁能打 |
|---|---|---|---|
| Windows | `.msi`（企业分发 / 组策略常用） | `:desktop:packageMsi` | **只能在 Windows 上**（本仓库：CI 的 `windows-latest`） |
| Windows | `.exe`（普通用户双击就走） | `:desktop:packageExe` | 同上 |
| Windows | app-image（免安装目录） | `:desktop:createDistributable` | 任意平台都能打（它是 jlink 出来的目录，与目标格式无关） |
| Linux | `.deb` / app-image / `tar.gz` | `:desktop:packageDeb` / `createDistributable` / `packageTarGz` | Linux（§7.1 的形态没变；图标与 `Maintainer` 那两处见 `docs/linux-delivery.md`） |

**为什么 `.msi` / `.exe` 不能在 Linux 上交叉构建**：jpackage 出 Windows 安装器时要调 Windows 自己的
WiX（`candle.exe` / `light.exe`）。Compose Desktop 会**自己下载**一份 WiX 3.11 到 gradle 用户目录
（属性 `compose.desktop.application.downloadWix`，默认开；也可以用 `WIX_PATH` 指一份现成的），
但这份 WiX 只能在 Windows 上跑。

⚠️ **本机（Linux）跑 `:desktop:packageMsi` 的结局是 `SKIPPED`，不是失败**（实测：`BUILD SUCCESSFUL`，
那一行是 `> Task :desktop:packageMsi SKIPPED`）—— Compose Desktop 会先看这个 target 与当前宿主是否
兼容（`TargetFormat.isCompatibleWithCurrentOS`），不兼容就连任务体都不跑。所以**本机上这条命令「成功」
什么都不代表**（什么都没出包）；反过来说，CI 里 `if-no-files-found: error` 那条设置就是防这种「静默跳过
却看起来绿了」的（见 §3）。`desktop/build.gradle.kts` 里的 `TargetFormat.Msi` / `TargetFormat.Exe`
在这里只是**声明**（任务会注册），真出包的路在 `.github/workflows/desktop-windows.yml`。

## 2. 打包配置（`desktop/build.gradle.kts`）

| 配置项 | 值 | 为什么 |
|---|---|---|
| `targetFormats` | `Deb` · `AppImage` · **`Msi`** · **`Exe`** | 前两个是 §7.1 定下的 Linux 三样的基础；后两个是本轮的 Windows 两样 |
| `packageName` | `xjtu-toolbox` | 安装目录名、开始菜单项、`tar.gz` 里的目录名都用它（一处定义） |
| `packageVersion` | `5.1.1`（`desktopPackageVersion`） | jpackage 只接受 `x.y.z`；它同时是 `DesktopBuildConfig.VERSION_NAME` 与 `tar.gz` 文件名的一部分 |
| `description` / `vendor` / `copyright` | 工具箱名 / `XJTU ToolBox` / contributors | 装进安装器与控制面板的「发布者/版权」栏 |
| `windows.perUserInstall` | `true` | 装到 `%LOCALAPPDATA%`、**不弹 UAC**。这一端是「每个人装自己一份、装完各登各的」——凭据用 DPAPI 的**当前用户**作用域（§5.2），与安装范围对齐 |
| `windows.upgradeUuid` | `528a294e-23ee-4307-a37a-1a71bb591fa0` | MSI 的 UpgradeCode：**升级与卸载靠它认人**。发过一次版就**绝对不能改**（改了就是另一个产品，老版本不会被升级/卸载，而是并存两个） |
| `windows.menuGroup` | `Education` | 开始菜单里的文件夹名（与 Linux 那栏同一个词） |
| `windows.iconFile` | `desktop/icons/windows.ico` | 多尺寸（256/128/64/48/32/24/16，共 7 张），来自 Figma 设计的 App 图标（与 Android 自适应图标、`icons/linux.png` 同一份导出）；不设它的话开始菜单/控制面板里会是默认的 Java 图标 |
| 其余 | 默认值 | `console = false`（不开控制台窗）、`shortcut = true` / `menu = true`、`dirChooser = true`、`msiPackageVersion` / `exePackageVersion` 取 `packageVersion` |

`packageVersion` 同时被**两个地方**读：jpackage 与 `DesktopBuildConfig.VERSION_NAME`
（生成的构建常量，设置页的版本号那一行用它）。所以版本号只维护一处（`desktopPackageVersion`）。

### 2.1 Windows ↔ Linux 元数据逐项对照表

（与 `docs/linux-delivery.md` §6.1 同一张表 —— 放在两边是让「从哪一份进来都看得到」。）

| 配置项 | Windows（`.msi` / `.exe`） | Linux（`.deb` / app-image / `tar.gz`） | 一致？ |
|---|---|---|---|
| `packageName` | `xjtu-toolbox` | 同一个（`nativeDistributions` 里**同一行**） | ✅ 不可能分叉 |
| `packageVersion` | `5.1.1`（`desktopPackageVersion`） | 同一个（同一行） | ✅ |
| `description` | `XJTU ToolBox (desktop preview)` | 同一个（**2026-10-19 起改成 ASCII**：Windows 上 jpackage 固定 `-cultures:en-us` ⇒ MSI 代码页 1252，中文会让 WiX 的 `light.exe` 报 `LGHT0311`／退出码 311） | ✅ |
| `vendor` | `XJTU ToolBox`（安装器里的「发布者」栏） | 同一个（也是 `.deb` 的 `Maintainer` 名字那半边） | ✅ |
| `copyright` | `Copyright © XJTU ToolBox contributors` | 同一个 | ✅ |
| `menuGroup` | `Education`（`windows.menuGroup`） | `Education`（`linux.menuGroup`） | ✅ 同一个词 |
| `appCategory` | — Windows 没有这一栏，等价物是 `menuGroup`（值也取了 `Education`） | `Education` ⇒ `.desktop` 的 `Categories=` 与 `.deb` 的 `Section:` | 平台差异 |
| `iconFile` | `desktop/icons/windows.ico`（7 张，但**图的内容**是 Android 模板占位图，§7 第 10 条） | `desktop/icons/linux.png`（256×256，**应用自己的**自适应矢量转的） | ⚠️ **不一致（Linux 那半边这一轮修了，Windows 没动）** |
| `debMaintainer` | — Windows 的等价物是安装器里的「发布者」，取 `vendor` | `114236693+Triangle-GitHub@users.noreply.github.com`（只给地址；jpackage 拼出来的是 `XJTU ToolBox <…>`，拆包实测见 `linux-delivery.md` §3.4） | 平台差异 |
| `msiPackageVersion` / `exePackageVersion` | 默认取 `packageVersion` | `debPackageVersion` / `rpmPackageVersion` 默认取 `packageVersion` | ✅ 同一条口径 |
| `installationPath` | `perUserInstall = true` ⇒ `%LOCALAPPDATA%`（不弹 UAC） | 默认 `/opt`（系统级，`sudo dpkg -i`） | 平台差异（各自与凭据存储的作用域对齐） |
| `upgradeUuid` | `528a294e-23ee-4307-a37a-1a71bb591fa0`（MSI 的 UpgradeCode，发版后不能改） | —（`.deb` 靠**包名**认产品，没有 UUID） | 平台差异 |
| `shortcut` / `menu` | `shortcut = true` / `menu = true` ⇒ 桌面快捷方式 + 开始菜单 | `linux.shortcut = true` ⇒ postinst 里 `xdg-desktop-menu install` | 平台差异 |
| `console` | `console = false`（不开控制台窗） | — | 平台差异 |
| `copyright` 落到哪 | 安装器与控制面板的「版权」栏 | 包里的 `share/doc/copyright`（`License: Unknown` 是 jpackage 按 `licenseType` 默认值填的） | 平台差异（同一个值） |
| `licenseFile` / EULA | **没设** | **没设**（所以 Linux 那份 `copyright` 里是 `License: Unknown`） | ✅ 两边都缺（同一个 TODO：本节 §9 第 2 条 / `linux-delivery.md` §7 第 12 条） |
| 数据目录 | `~/.local/share/xjtu-toolbox`（XDG 口径，见 §9 第 6 条） | 同一个 | ✅ |
| 出包 CI | `.github/workflows/desktop-windows.yml`（windows-latest） | `.github/workflows/desktop-linux.yml`（ubuntu-latest） | 两条独立；Windows 只看平台专属面 |

标「平台差异」的都是**必须**不一样的（安装范围、认产品的方式、签名链）；真正不一致的只有 `iconFile`
那一格 —— 不是配置写错了（两边的 `iconFile` 都指对了文件名），差的是 `windows.ico` 里那张**图本身**。

## 3. CI：`.github/workflows/desktop-windows.yml`

| 步骤 | 内容 |
|---|---|
| 卡死护栏 | job 级 `timeout-minutes: 60`（与 `desktop-linux.yml` 同一个数字；⚠️ 没在 CI 上量过） |
| 触发 | `pull_request`（路径过滤：`desktop/**` `data/**` `shared/data/**` `build-logic/**` `gradle/**` `gradlew` `gradlew.bat` `settings.gradle.kts` 与本文件）+ `workflow_dispatch`；`concurrency` 去重。`core/**`、`shared/**`（除 data）、`feature/**` 不触发 —— 它们编出的 JVM 字节码与平台无关，由 `core-gate.yml` + `desktop-linux.yml` 覆盖 |
| 环境 | `windows-latest` · `actions/setup-java@v4`（temurin 21，与 `jvmToolchain(21)` / Kotlin 2.4.20 对齐）· `gradle/actions/setup-gradle@v4` |
| 哨兵 | 一条检查 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 的步骤：我们只编 `:desktop`，但 Gradle 会配置整个构建（含 `:app` 的 AGP），缺 SDK 时**响亮地**失败在这一步 |
| 编译 | `.\gradlew.bat :desktop:compileKotlin` |
| 出包 | `.\gradlew.bat :desktop:packageMsi :desktop:packageExe`（WiX 由 Compose Desktop 自动下载） |
| 图标自证 | 一条 `pwsh` 步骤：核 `desktop/icons/windows.ico` 是合法 ICO、且带 256×256 那张（MSI 的控制面板图标用它）。放在**跑 WiX 之前**（输入不对就不必等十几分钟出包）；本机用 `pwsh` 把这段脚本跑过正例 + 三个负例，见 §8 |
| 留档 | `actions/upload-artifact@v4` → `desktop/build/compose/binaries/main/{msi,exe}/*.{msi,exe}`，`if-no-files-found: error`（没产物就是失败，不当成跑过了） |
| 验证 | `:data:jvmTest --tests "com.xjtu.toolbox.platform.*"`（**含真 DPAPI 往返/篡改用例**：本机 Linux 上是跳过的，这里是它们唯一的验证现场）与 `:desktop:test --tests "…DesktopUpdateJvmTest"` |

两条刻意的安排：

1. **验证放在出包之后**：万一有平台相关的用例红了，`.msi` 仍然留得下来给人查（不至于「因为一条用例红，产物都没了」）；
2. **不做发布**：这一条只出 artifact，**不上传 Release**。要不要把 `.msi` 发进 Release、发到哪个 tag，
   与 Android 的发版流程（`build.yml` 的 `v*` tag + Gitee 同步）一起定 —— 那是下一步的事。

## 4. 代码签名（**没做**，也不是这一轮的范围）

现状：产物**未签名**。后果很具体 —— Windows 的 SmartScreen 会对下载来的未签名安装器报警
（「Windows 已保护你的电脑」），企业环境里也可能被策略拦下。设计文档 §7 已经把
「Windows 代码签名」列在待定项里。

要接的时候需要这些（都还没配，列出来是为了别漏）：

| 需要什么 | 占位 | 干什么用 |
|---|---|---|
| 代码签名证书（`.pfx` + 口令） | `secrets.WINDOWS_CERT_PFX_B64` · `secrets.WINDOWS_CERT_PASSWORD` | base64 解回 `.pfx` 再签 |
| 时间戳服务 | 常量（如 `http://timestamp.digicert.com`） | 证书过期后签名仍然有效 |
| 签名动作 | 出包**之后**、上传 artifact **之前** | `signtool sign /f cert.pfx /p … /tr … /td sha256 /fd sha256 xjtu-toolbox-5.1.1.msi` |

⚠️ Compose Desktop 的 Windows 那栏**没有**签名设置（macOS 有 `signing` / `notarization`，Windows 没有）
⇒ 签名只能在 workflow 里加一步 `signtool`，签的是 jpackage 的**产物**，不是 app-image 里的 exe。

## 5. 凭据存储：Windows 上的原生保护

### 5.1 这条缝长什么样

```
JvmCredentialStore(storeName, protector = secretProtectorForPlatform())
        │                     │
        │                     └─ Windows → DpapiSecretProtector（DPAPI · 当前用户）
        │                        其余    → null（没有原生保护：文件 + 0600，今天的 Linux 口径）
        └─ ProtectedKeyValueStore(secureKeyValueStore(storeName), protector)
                    │
                    └─ xjtu-sealed:1:<保护器 id>:<base64(protect(明文))>   ← Windows 上落盘的就是这个
                       明文原样落盘                                      ← Linux / macOS（一个字没变）
```

代码：`data/src/jvmMain/kotlin/com/xjtu/toolbox/platform/SecretProtector.kt`（缝 + DPAPI + 平台分派）、
`…/ProtectedKeyValueStore.kt`（信封的读写与失败语义）、`…/CredentialStore.jvm.kt`（两者在那里合成）。

### 5.2 Windows 上具体做了什么

- **DPAPI**（`CryptProtectData` / `CryptUnprotectData`），**当前用户**作用域，带固定熵
  （`xjtu-toolbox/credentials/v1`，**发版后不可改**：改了老凭据全解不开）；
- 经 **JNA** 调（`com.sun.jna.platform.win32.Crypt32Util`，依赖只在 `:data` 的 `jvmMain`：
  `jna-platform`；见 `data/build.gradle.kts`）。为什么不用别的：JDK 没有 DPAPI 绑定
  （`SunMSCAPI` 是 CryptoAPI 的密钥库/签名，不含 DPAPI），FFM API 在 JDK 21 还是 preview；
- 效果：凭据文件**被拷到别的机器、别的用户、或备份里都解不开** —— 这是 Linux 那条
  「文件 + `0600`」做不到的（那条只能防同机别的用户读）。

### 5.3 失败语义（三条，都不许静默降级）

1. **解不开就抛** `SecretProtectionException`：换用户 / 换机器 / 数据目录跨平台复制、
   或密文被改过。**不会**返回信封原文（那会把 `xjtu-sealed:1:…` 当密码去登录），也不会
   当成「本来就没存过」（那会让「密码丢了」看起来像「第一次登录」）；
2. **保护失败就不落盘**：原生库不在 / Windows 拒绝调用 ⇒ 写路径直接失败，
   盘上**绝不**出现明文（那是把「凭据是分发的代价」反过来写）；
3. **老明文文件仍读得回来**：这一轮之前写下的凭据（Linux 上的、或 `:desktop` 里手工造的
   老文件）走「原样返回」那一档，不受影响。

### 5.4 测试口径（在 Linux 上能钉住什么）

| 测试 | 钉住的是 |
|---|---|
| `data/src/jvmTest/.../SecretProtectorJvmTest.kt` | 平台分派（`Windows 10/11/Server 2022` → DPAPI；`Linux/macOS/FreeBSD` → 没有）；**真 DPAPI 的往返 / 密文≠明文 / 篡改解不开**（只在 Windows 上真跑） |
| `data/src/jvmTest/.../ProtectedKeyValueStoreJvmTest.kt` | 用**假保护器**钉住信封语义：密文落盘、读回解密、篡改抛、另一把钥匙抛、外来信封抛、老明文通过、原生保护不可用时写失败且不留明文、布尔/整数/集合档同样受保护 |
| `data/src/jvmTest/.../JvmCredentialStoreJvmTest.kt` | 装上了保护器的**真凭据存储**：账号密码不落明文、老文件兼容、换用户抛、`clear` 清干净、RSA 公钥 24 小时窗口不变；**本机若是 Windows，这条会走真 DPAPI 一遍** |

假保护器（`FakeSecretProtectors.kt`）刻意**真的改字节、真的带完整性校验、只有同一把钥匙能解**：
它替的是 DPAPI 的行为形状，不是它的强度。

### 5.5 残余风险（要一直记着）

1. **这一轮没在真 Windows 上跑过 DPAPI** —— 只有 CI 的 `windows-latest` 会真跑（§7 第 1 条）；
2. **JNA 是新依赖**（`:data` 的 jvmMain）：多了两个 jar（`jna` + `jna-platform`，约 3MB）
   进桌面的 app-image 与安装包；JNA 在 Windows 上会把 `jnidispatch.dll` 解到临时目录再加载 ——
   企业环境如果有「禁止从临时目录加载 DLL」之类的策略，会走到 [5.3] 第 2 条那条失败路径（**响亮地失败**，
   不会退回明文）；
3. **只有凭据这一份存储受保护**：cookie / 站点快照 / 设置（行走在 `secureKeyValueStore` 上）
   在 Windows 上仍是「明文文件」（连 `0600` 都设不上 —— `PosixFilePermissions` 在 Windows 上不支持，
   `SecureStore.jvm.kt` 里那步是 `runCatching` 吞掉的）。要不要把它们也套上同一个保护器，
   是**下一个决定**（套上就改了这些文件的格式，得按 `ProtectedKeyValueStore` 的信封版本号那一套来）；
   ⚠️ 会话 cookie 的价值不低于密码，这条别忘；
4. **Linux / macOS 依然没有原生保护**（设计文档 §5.1 明确接受了这一档）：要真做得上 Secret Service，
   属 §5.4 的宿主机能族。

## 6. 更新路线

现状（骨架在 `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/DesktopUpdate.kt`）：

| 能力 | 现在 |
|---|---|
| 「检查更新」 | **有骨架**：拉 GitHub 的发布列表，复用 `:core` 的 `BulletinRules.compareVersions` 比版本；只认**带 `.msi` / `.exe` 资产**的发布（仓库当前只有 APK 的发布 ⇒ 会如实答「还没有带桌面安装包的发布」，而不是把 APK 的版本号推给用户） |
| 「打开发布页」 | **有**：把发布页交给系统浏览器（`openInBrowser`，与教师检索那一屏同一条路） |
| 「自动下载并安装」 | **没有，也不打算做**：`DesktopUpdate.canInstallAutomatically = false`。`.deb` / `tar.gz` / `.msi` 只能由用户自己装（Windows 上还要过 SmartScreen 与 UAC）；设计文档 §7 把「更新机制（没有商店）」列为待定项 |
| UI 入口 | **没接**：`:core` 的设置页那一栏由 `DesktopSettingsHost.updateSection` 提供，桌面端现在给的是 `null`（`DesktopSettings.kt` 的 KDoc 里如实写着）。接线时就是「一个按钮 + 一句文案」。⚠️ **截至桌面交付打磨的末轮仍未接** —— 接线要改 `desktop/src/**`，不在那几轮任务的文件范围里，所以只记 TODO（§7 第 9 条） |
| 发布通道 | **没有**：CI 只出 artifact（§3）。要不要发进 Release 由发版流程那一轮定 |

## 7. 未验证项清单（**这一节是这份文档的重点**）

| # | 未验证的事 | 为什么没验 | 怎么才能验 |
|---|---|---|---|
| 1 | **DPAPI 的真实行为**（往返、篡改失败、换用户解不开） | 本机是 Linux；`DpapiSecretProtector` 的调用路只有 Windows 上有 | CI 的 `desktop-windows.yml` → `:data:jvmTest --tests "com.xjtu.toolbox.platform.*"`（`SecretProtectorJvmTest` 里那两条用例在 Windows 上不再跳过）—— **这条 CI 还没跑过** |
| 2 | **`:desktop:packageMsi` / `packageExe` 真的出得来包** | jpackage 不接受交叉构建；本机上那两个任务直接被 `SKIPPED`（见 §1），只能验到「任务名在 + Linux 那几样（`createDistributable`）真跑通」 | 同一条 CI（`packageMsi` + `packageExe` + artifact 上传）—— **还没跑过** |
| 3 | **`desktop/icons/windows.ico` 被 jpackage/WiX 接受** | ✅ **2026-10-19 在 CI 上实证**：`Desktop Windows Packaging` 真跑通、`.msi` 与 `.exe` 都出来了（artifact `xjtu-toolbox-windows`，`xjtu-toolbox-5.2.0.msi` 118.4 MB）⇒ 这份 ICO 是被 WiX 收下的（本机那套校验脚本仍然管「文件本身合法 + 有 256×256」那一半，见 §3 / §8） | 已闭环 |
| 4 | **WiX 在 CI 上能出包** | ✅ **2026-10-19 闭环，但结论与当初的假设相反**：Compose 自动下载的那份 **WiX 3.11 要 .NET Framework 3.5**，而 `windows-latest` 不带 3.5 ⇒ `light.exe` 起不来、**不打印任何错误**就退出（实测退出码 311）。改成**发现 runner 自带的 WiX 3.14**（`WIX_PATH` 交给 Compose，没有才 `choco install`）后出包成功 | 已闭环；预案当初就写在 `docs/windows-delivery.md` §7 第 4 条（`choco install wixtoolset` / `WIX_PATH`） |
| 5 | **`.msi` 的 per-user 安装 + 开始菜单 + 升级 UUID 的真实观感** | 需要一台真 Windows 装一遍 | 手动：装 → 看安装目录/开始菜单 → 装第二版看是否**升级**而不是并存 |
| 6 | **未签名产物的 SmartScreen 行为** | 没有证书，也没做签名（§4） | 手动（同一台 Windows）：下载 `.msi` 双击看提示文案 |
| 7 | **整套 `:desktop:test` / `:data:jvmTest` 在 Windows 上是否全绿** | **已知会红一条**：`:desktop` 的 `DesktopAuthLibraryJvmTest` 断言凭据文件是 `0600`（POSIX 权限），Windows 没有这个概念 ⇒ 那条用例在 Windows 上必然失败 | 要么把那条用例改成按平台判（POSIX 不支持就跳过这段断言），要么在 Windows 上只跑指定类（现在 workflow 就是这么做的）。**这一轮没改别人的用例** |
| 8 | **Windows 上会话 cookie / 站点快照 / 设置仍是明文文件** | 见 §5.5 第 3 条：这一轮只保护凭据那一份 | 下一个决定：把 `secureKeyValueStore` 的调用点逐个换成带保护器的版本（信封格式已就绪） |
| 9 | **更新骨架的 UI 接线与发布通道** | 见 §6：调用点与 Release 发布都还没做 | 设置页那一轮（`updateSection`）+ 发版流程那一轮 |
| 10 | **已修复（2026-10-30）：`windows.ico` 换成 Figma 设计的 App 图标** | （修复前的记录）本机把 `desktop/icons/windows.ico` 的 7 张图逐张解出来看过（收尾轮复核过，`magick "desktop/icons/windows.ico[$i]" -format '%wx%h %[pixel:p{w/2,h*0.3}]' info:`）：256 与 128 两张是 `srgba(60,218,130,1)`（模板绿 `#3DDC84`），48 那张是 `srgba(64,219,132,1)`，64 / 32 / 24 / 16 四张是同一个绿的百分比写法（16×16 那张漂到 `29.1765%,86.2745%,54.5098%` —— 缩小重采样的抖动）；`mipmap-xxxhdpi/ic_launcher.webp`（192×192）取同一点也是 `srgba(60,218,130,1)`。应用自己的图标是仓库里那份自适应矢量（`ic_launcher_background.xml` `#87CEEB` + `ic_launcher_foreground.xml`，提交 `2145ad11` 写着「App 图标恢复 v2.0 那套自适应图标」）—— `desktop/icons/linux.png` 已经从它转出来了（`linux-delivery.md` §2.1，取样核过 `srgb(135,206,235)` / `srgb(46,107,158)` / `srgb(123,174,212)` / `srgb(27,79,114)`，收尾轮又拿真 `.deb` 核了一遍 md5，见那边 §3.4）。⚠️ 这一批**没有**重新生成 `windows.ico`（只记录问题；重转与真打一次 `.msi` 都要 Windows 那一侧） | **已按此重做（2026-10-30，源改为 Figma 导出，`.ico` 里仍是 256/128/64/48/32/24/16 七张）**：把 `linux-delivery.md` §2.1 那条命令最后一步换成各尺寸重导 —— 源就是同一份 `app/src/main/res/drawable/` 的自适应矢量，输出改成 `desktop/icons/windows.ico`：`magick -size 1080x1080 xc:'#87CEEB' -fill '#7BAED4' -draw 'polygon 220,780 400,520 580,780' -fill '#2E6B9E' -draw 'polygon 380,780 620,360 860,780' -fill '#FFFFFF' -draw 'polygon 560,440 620,360 680,440' -fill '#1B4F72' -draw 'polygon 180,780 900,780 900,820 180,820' -define icon:auto-resize=256,128,64,48,32,24,16 desktop/icons/windows.ico`。现仓库的 `.ico` 里确实有 256/128/64/48/32/24/16 七张，所以换完尺寸不用变。本机验不了 WiX 收不收新文件（jpackage 不能交叉出包）⇒ 归到「Windows 上真打一次」那一轮：换完先让 §3 里那段 ICO 校验过一遍，再真出一次 `.msi` 看开始菜单/控制面板 |

## 8. 门禁与测试数字（本轮实测）
数字都是这一轮在本机（Linux、JDK 21）跑出来的：

| 门禁 | 结果 |
|---|---|
| `:data:jvmTest` | **222 通过 / 0 失败**（基线 193；本轮新增 21 条凭据保护用例，其余差额来自并行那一轮的课表搬迁） |
| `:desktop:test` | **70 通过 / 0 失败**（基线 63；本轮新增 7 条更新骨架用例） |
| `:desktop:compileKotlin` | ✓ |
| `:core:jvmTest` | **334 通过 / 0 失败**（基线 314；+20 正是并行那一轮从 `:app` 搬进来的考试/倒计时/变更检测用例） |
| `:app:testDebugUnitTest` | **450 通过 / 0 失败**（`--rerun` 真跑过；比 470 基线少 20，也就是上面搬走的那 20 条 —— 不是本轮改动导致的） |
| `:core:compileKotlinWasmJs` | ✓ |
| `:data:compileAndroidMain` | ✓ |
| `:app:compileDebugKotlin` | ✓ |
| `:desktop:tasks --all` | `packageMsi` / `packageExe` / `packageDeb` / `packageAppImage` / `createDistributable` / `packageTarGz` 都在（任务名核实过；workflow 里用的是前两个） |
| `:desktop:createDistributable`（Linux 真跑） | ✓ 196MB，落点 `desktop/build/compose/binaries/main/app/xjtu-toolbox`（声明了 Msi/Exe 也没影响 Linux 这条路） |
| `:desktop:packageMsi`（Linux） | `SKIPPED`（见 §1：不兼容的宿主上它连任务体都不跑） |
| `.github/workflows/desktop-windows.yml` | **2026-10-19 起在 GitHub 上真跑通**（PR #142 的 `Desktop Windows Packaging` = pass，`.msi` 与 `.exe` 都出来了）。此后又加了几步：装 WiX 3.14 / Defender 排除 / 打包 3 次重试 / 失败时上传诊断（测试报告 + `desktop/build/compose/logs/**`） |

⚠️ **桌面交付打磨的末轮（收尾轮）没重跑上表任何一条**：那轮改的只有 `desktop/build.gradle.kts` 的
`linux { }` 那一块、新增 `desktop/icons/linux.png`、两条 workflow 与两份交付文档（`windows { }` 一个字
没动），所以 Windows 这条路的行为不会因为那轮改动而变化。那一轮里**与本文件有关、真跑过**的有两条：

1. 用本机 `pwsh` 把 `desktop-windows.yml` 里那段 ICO 校验脚本的**原文**再跑了一遍：正例（现仓库的 ico：
   7 张，256/128/64/48/32/24/16）放行；三个负例（缺文件 / magic 错 / 没有 256 那张）分别以
   `找不到 …`、`不是 ICO 文件…`、`里没有 256×256 那张…` 报错，退出码 1；
2. 两条 workflow 的 YAML 各解析通过（`yaml.safe_load`，各 **11** 个 step），护栏逐项对过（五项一致，
   见 `linux-delivery.md` §7 第 13 条）。

同一轮里 Linux 那一侧**真跑过后又拆了包**（`:desktop:packageDeb` + `dpkg-deb`：包里图标 md5 与
`desktop/icons/linux.png` 相同、`Maintainer:` 就是 `debMaintainer` 拼出来的那行），细节在
`linux-delivery.md` §3.4 —— 它反过来印证了本文件 §7 第 10 条那句「Linux 那半边已经是我们自己的图，
Windows 这份还是模板占位图」。

## 9. 这一轮**没做**（范围之外，别误会）

1. **Windows 的 serve 模式**（`:server` 那一端）与 Windows 上的服务化/自启；
2. **Windows 的通知 / 托盘 / 文件关联**（`FileAssociation`、`appResourcesRootDir` 都没用上）；
3. **ARM64 的 Windows**（jpackage 的 `--win-*` 只有 x64；Compose Desktop 的 `packageMsi` 是宿主架构）；
4. **Linux 侧其余打包改动** —— Linux 侧的图标这一轮做了（`linux.iconFile = icons/linux.png`，从应用自己的自适应矢量转的），`.deb` 的 `Maintainer` 也从 `<Unknown>` 换成了仓库里能核到的真实身份；两件事的来源、命令与实测都在 `docs/linux-delivery.md` §2.1 / §2.2 / §3.3；
5. **把保护器扩到 cookie / 站点快照 / 设置**（见 §5.5 第 3 条、§7 第 8 条）；
6. **Windows 上的数据目录惯例**：数据根仍是 `~/.local/share/xjtu-toolbox`（`SecureStore.jvm.kt` 的
   XDG 口径，在那台机器上就是 `C:\Users\<你>\.local\share\…`）。看着别扭但能用，
   而改成 `%LOCALAPPDATA%` 会挪动凭据/会话文件的落点（等于让老用户重新登一次）—— 留到
   「Windows 上真装一次」那一轮一并定（现在没人装过 Windows 预览版，改起来其实零成本）。

## 10. 怎么开 HTTPS（serve 模式，D11）

`docs/desktop-port-plan.md` §16.1 的 **D11**：serve 内置 HTTPS + 鉴权，开箱可启用。**这一端与 Linux 是
同一个 `:server` 模块**（`docs/linux-delivery.md` §10 是同一件事的 Linux 版命令），所以这里的差别只有
路径写法与 `gradlew.bat`。口径在 `docs/api-contract.md` §3 第 6/7 条，实现与判据在 `:server`
（`ServeConfig` / `ServeTls` / `ServeHttps`）与 `:server:test` 的 `ServeHttpsTest`（**8** 例）。

**默认不变**：不给 `--https` 时 serve 与今天**逐字相同**（明文 HTTP + 令牌闸门，默认只监听 `127.0.0.1`）。
HTTPS 是**另一档形态**，它带来的三件事：`--host/--port` 变成 HTTPS 的地址与端口、令牌换来的
cookie 多一个 `Secure`、纯明文那一层只绑回环（外面看不到）。

### 10.1 一条命令：自签 + 起 HTTPS

```powershell
# 在仓库根跑
.\gradlew.bat :server:run --args="--https --host 0.0.0.0 --port 8443"
```

证书不存在就**现生成一张自签**（JDK 的 `bin\keytool.exe`；`java.home` 那个运行时里一定有它，
jpackage 打出来的运行时镜像里也有），然后起在 `0.0.0.0:8443` 上。浏览器打开
`https://<那台机器的地址>:8443/`，第一次会警告证书不可信 —— 点「继续访问」即可
（自己签的证书没人替它背书，这条警告是必然的）。

证书与密码落在**数据根**下面（Windows 上仍是 XDG 口径，见 §9 第 6 条）：

| 文件 | 是什么 | 权限 |
|---|---|---|
| `C:\Users\<你>\.local\share\xjtu-toolbox\tls\serve.p12` | 证书库（PKCS12，含私钥 + 自签证书） | 只给本用户（POSIX 权限在 Windows 上不适用，`0600` 那一档会静默跳过） |
| `C:\Users\<你>\.local\share\xjtu-toolbox\tls\serve.p12.password` | 上面那个库的密码（随机生成） | 同上 |

⚠️ 两个文件都**不要**进仓库 / 截图 / 聊天。想换一张自签：删掉这两个文件再启动（与「换访问令牌 =
删 `serve.properties`」同一套口径）。

### 10.2 用自己那份证书

```powershell
# PKCS12 或 JKS 都认；密码走环境变量（命令行上的密码会留在 ps 与 shell 历史里）
$env:XJTU_TOOLBOX_TLS_PASSWORD = "<你的库密码>"
.\gradlew.bat :server:run --args="--https --host 0.0.0.0 --port 8443 --cert C:\certs\serve.p12 --cert-alias serve"
```

自己生成一份也可以（alias 与 SAN 按你的访问地址写）：

```powershell
keytool -genkeypair -alias serve -keyalg RSA -keysize 2048 -validity 3650 `
  -dname "CN=你的域名或机器名" -ext "SAN=dns:localhost,ip:127.0.0.1,ip:192.168.1.5" `
  -storetype PKCS12 -keystore C:\certs\serve.p12
```

### 10.3 让浏览器不再警告（可选）

把证书库里的**证书**导出来（只有证书、不含私钥），用 `certmgr.msc` 或者
「管理用户证书 → 受信任的根证书颁发机构 → 导入」装进去：

```powershell
$root = "$env:USERPROFILE\.local\share\xjtu-toolbox\tls"
keytool -exportcert -rfc -alias serve -keystore "$root\serve.p12" `
  -storepass (Get-Content "$root\serve.p12.password") > serve.crt
```

### 10.4 参数与失败语义（都会明确报错，**不会**回落成明文）

| 参数 | 作用 |
|---|---|
| `--https` | 开关：切成 HTTPS 形态（`--host/--port` 就是 HTTPS 的地址与端口） |
| `--cert <路径>` | 证书库（PKCS12/JKS）。省略 = `<数据根>\tls\serve.p12`，不存在就生成自签 |
| `--cert-alias <别名>` | 库里的别名，默认 `serve` |

- `--cert` / `--cert-alias` 给了却没给 `--https` ⇒ 参数报错（退出码 2）—— 不静默忽略；
- `--cert` 指的文件不存在 ⇒ 报错，**不**在那儿捂着生成、**不**回落成明文；
- 库读不了（密码没有/不对、不是 PKCS12/JKS、别名不在库里）⇒ 报错并指出下一步（退出码 2）。

⚠️ HTTPS 只解决「传输这一段」。**在 HTTPS 真正用起来之前，写 / 支付端点不要挂在公网口上**
（`docs/desktop-port-plan.md` §16.4 第二条风险）—— 那一条同时写在 `docs/api-contract.md` §3 第 7 条。
