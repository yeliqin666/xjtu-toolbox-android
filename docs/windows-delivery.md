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
| Linux | `.deb` / app-image / `tar.gz` | `:desktop:packageDeb` / `createDistributable` / `packageTarGz` | Linux（§7.1 的现状，未改） |

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
| `windows.iconFile` | `desktop/icons/windows.ico` | 多尺寸（256/128/64/48/32/24/16），从 Android 的 `mipmap-xxxhdpi/ic_launcher.webp` 转出来的；不设它的话开始菜单/控制面板里会是默认的 Java 图标 |
| 其余 | 默认值 | `console = false`（不开控制台窗）、`shortcut = true` / `menu = true`、`dirChooser = true`、`msiPackageVersion` / `exePackageVersion` 取 `packageVersion` |

`packageVersion` 同时被**两个地方**读：jpackage 与 `DesktopBuildConfig.VERSION_NAME`
（生成的构建常量，设置页的版本号那一行用它）。所以版本号只维护一处（`desktopPackageVersion`）。

## 3. CI：`.github/workflows/desktop-windows.yml`

| 步骤 | 内容 |
|---|---|
| 触发 | `pull_request`（路径过滤：`desktop/**` `data/**` `core/**` `gradle/**` `gradlew*` `settings.gradle.kts` 与本文件）+ `workflow_dispatch`；`concurrency` 去重 |
| 环境 | `windows-latest` · `actions/setup-java@v4`（temurin 21，与 `jvmToolchain(21)` / Kotlin 2.4.20 对齐）· `gradle/actions/setup-gradle@v4` |
| 哨兵 | 一条检查 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 的步骤：我们只编 `:desktop`，但 Gradle 会配置整个构建（含 `:app` 的 AGP），缺 SDK 时**响亮地**失败在这一步 |
| 编译 | `.\gradlew.bat :desktop:compileKotlin` |
| 出包 | `.\gradlew.bat :desktop:packageMsi :desktop:packageExe`（WiX 由 Compose Desktop 自动下载） |
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
| UI 入口 | **没接**：`:core` 的设置页那一栏由 `DesktopSettingsHost.updateSection` 提供，桌面端现在给的是 `null`（`DesktopSettings.kt` 的 KDoc 里如实写着）。接线时就是「一个按钮 + 一句文案」 |
| 发布通道 | **没有**：CI 只出 artifact（§3）。要不要发进 Release 由发版流程那一轮定 |

## 7. 未验证项清单（**这一节是这份文档的重点**）

| # | 未验证的事 | 为什么没验 | 怎么才能验 |
|---|---|---|---|
| 1 | **DPAPI 的真实行为**（往返、篡改失败、换用户解不开） | 本机是 Linux；`DpapiSecretProtector` 的调用路只有 Windows 上有 | CI 的 `desktop-windows.yml` → `:data:jvmTest --tests "com.xjtu.toolbox.platform.*"`（`SecretProtectorJvmTest` 里那两条用例在 Windows 上不再跳过）—— **这条 CI 还没跑过** |
| 2 | **`:desktop:packageMsi` / `packageExe` 真的出得来包** | jpackage 不接受交叉构建；本机上那两个任务直接被 `SKIPPED`（见 §1），只能验到「任务名在 + Linux 那几样（`createDistributable`）真跑通」 | 同一条 CI（`packageMsi` + `packageExe` + artifact 上传）—— **还没跑过** |
| 3 | **`desktop/icons/windows.ico` 被 jpackage/WiX 接受** | 同上（Windows 才能读它） | 同一条 CI；若报图标问题，退回「不设 `iconFile`」也能出包（只是图标是默认的 Java 那个） |
| 4 | **WiX 自动下载在 CI 上通** | 本机不是 Windows，`configureWix` 在非 Windows 上直接返回 | 同一条 CI；万一被拦，就加一步 `choco install wixtoolset` 或 `WIX_PATH` 指现成的 |
| 5 | **`.msi` 的 per-user 安装 + 开始菜单 + 升级 UUID 的真实观感** | 需要一台真 Windows 装一遍 | 手动：装 → 看安装目录/开始菜单 → 装第二版看是否**升级**而不是并存 |
| 6 | **未签名产物的 SmartScreen 行为** | 没有证书，也没做签名（§4） | 手动（同一台 Windows）：下载 `.msi` 双击看提示文案 |
| 7 | **整套 `:desktop:test` / `:data:jvmTest` 在 Windows 上是否全绿** | **已知会红一条**：`:desktop` 的 `DesktopAuthLibraryJvmTest` 断言凭据文件是 `0600`（POSIX 权限），Windows 没有这个概念 ⇒ 那条用例在 Windows 上必然失败 | 要么把那条用例改成按平台判（POSIX 不支持就跳过这段断言），要么在 Windows 上只跑指定类（现在 workflow 就是这么做的）。**这一轮没改别人的用例** |
| 8 | **Windows 上会话 cookie / 站点快照 / 设置仍是明文文件** | 见 §5.5 第 3 条：这一轮只保护凭据那一份 | 下一个决定：把 `secureKeyValueStore` 的调用点逐个换成带保护器的版本（信封格式已就绪） |
| 9 | **更新骨架的 UI 接线与发布通道** | 见 §6：调用点与 Release 发布都还没做 | 设置页那一轮（`updateSection`）+ 发版流程那一轮 |

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
| `.github/workflows/desktop-windows.yml` | YAML 解析通过（`yaml.safe_load`，10 个 step）；**没有在 GitHub 上真跑过**（§7 第 1/2/3/4 条） |

## 9. 这一轮**没做**（范围之外，别误会）

1. **Windows 的 serve 模式**（`:server` 那一端）与 Windows 上的服务化/自启；
2. **Windows 的通知 / 托盘 / 文件关联**（`FileAssociation`、`appResourcesRootDir` 都没用上）；
3. **ARM64 的 Windows**（jpackage 的 `--win-*` 只有 x64；Compose Desktop 的 `packageMsi` 是宿主架构）；
4. **Linux 侧的图标与打包改动**（`.deb` 仍然用默认 Java 图标 —— 那是 Linux 交付那一轮的事）；
5. **把保护器扩到 cookie / 站点快照 / 设置**（见 §5.5 第 3 条、§7 第 8 条）；
6. **Windows 上的数据目录惯例**：数据根仍是 `~/.local/share/xjtu-toolbox`（`SecureStore.jvm.kt` 的
   XDG 口径，在那台机器上就是 `C:\Users\<你>\.local\share\…`）。看着别扭但能用，
   而改成 `%LOCALAPPDATA%` 会挪动凭据/会话文件的落点（等于让老用户重新登一次）—— 留到
   「Windows 上真装一次」那一轮一并定（现在没人装过 Windows 预览版，改起来其实零成本）。
