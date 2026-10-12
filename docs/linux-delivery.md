# Linux 交付面（`.deb` · app-image · `tar.gz`）

这一份写的是**桌面端在 Linux 上怎么交付**：包装成什么、谁来打、包里的元数据长什么样、怎么验的，
以及**哪些事这一轮没能真验**（最后一节，最该读的就是它）。它与 `docs/windows-delivery.md` 对称 ——
那边是 `.msi`/`.exe`（只能在 Windows 上打），这边是 Linux 那三样（只能在 Linux 上打）。

上游决策在 `docs/desktop-port-plan.md`：D3（Linux 与 Windows **同一份** `:desktop`）、§7.1（Linux 交付
形态**已定**：`.deb` + app-image + `tar.gz`）、§5.1（凭据存储：Linux 用「文件 + `0600`」）。

## 1. 交付形态

| 平台 | 形态 | 任务 | 谁能打 | 本轮实测产物 | 大小 |
|---|---|---|---|---|---|
| Linux | `.deb`（Debian / Ubuntu，apt 管依赖与卸载） | `:desktop:packageDeb` | Linux（本机就能打） | `desktop/build/compose/binaries/main/deb/xjtu-toolbox_5.1.1_amd64.deb` | 111,170,448 B（106.0 MiB） |
| Linux | app-image（免安装目录，自带运行时） | `:desktop:packageAppImage` / `:desktop:createDistributable` | 任意平台 | `desktop/build/compose/binaries/main/app/xjtu-toolbox/` | 204,922,515 B（195.4 MiB，207 个文件） |
| Linux | `tar.gz`（免安装，无 dpkg / 不能用 FUSE 的机器） | `:desktop:packageTarGz` | 任意平台 | `desktop/build/compose/binaries/main/tar/xjtu-toolbox-5.1.1-linux-x64.tar.gz` | 123,750,278 B（118.0 MiB） |
| Windows | `.msi` / `.exe` | `:desktop:packageMsi` / `:desktop:packageExe` | **只能在 Windows 上**（CI 的 `windows-latest`） | 见 `docs/windows-delivery.md` | — |

⚠️ **一个口径说明（§7.1 的原文，别误会）**：Compose Desktop 的 `TargetFormat.AppImage` 是 **jpackage 的
`app-image`**（一个自带运行时的目录），**不是** AppImage.org 那种单文件 `.AppImage`。实测证据：
`TargetFormat.AppImage` 的 id 是 `app-image`、`getOutputDirName()` 返回 `app`、`getFileExt()` 对
`AppImage` 直接抛（它没有单文件扩展名）⇒ 它的产物就是上面那张表里的 app-image 那一份，
**与 `createDistributable` 同一个落点**（本机两次实测：Windows 那一轮的 `createDistributable` 与本轮的
`packageAppImage` 都是 `…/main/app/xjtu-toolbox`）。

推论有两件事值得记下：

1. 这条路上**不需要** `appimagetool`（也就不需要 FUSE / `libfuse2`）—— 本机根本没有 `appimagetool`，
   `packageAppImage` 照样成功；
2. **真正的单文件 `.AppImage` 仍然没有**。要它得加一条与 `packageTarGz` 同形的任务（把 app-image 喂给
   `appimagetool`），属 §7.1 里那条「真·单文件 AppImage 待定」。

## 2. 打包配置（`desktop/build.gradle.kts` 的 Linux 那栏）

**本轮没有改 `desktop/build.gradle.kts`** —— 理由是逐项核过之后 Linux 侧的元数据已经与 Windows 一致，
唯一缺口（图标）需要**新增资源文件**，不在这条任务的文件范围里（见 §7 第 3 条）。现状：

| 配置项 | 值 | 为什么 |
|---|---|---|
| `targetFormats` | `Deb` · `AppImage` · `Msi` · `Exe` | 前两个是 §7.1 定的 Linux 两样（第三样 `tar.gz` 由 `packageTarGz` 从 app-image 再打）；后两个是 `docs/windows-delivery.md` 定的 Windows 两样，在 Linux 上只是**声明**（任务会注册，但执行时 `SKIPPED`，见 `windows-delivery.md` §1） |
| `packageName` | `xjtu-toolbox` | 一处定义，三处用：安装目录（`/opt/xjtu-toolbox`）、`.deb` 的包名与 `bin/` 里的可执行名、`tar.gz` 里的目录名 |
| `packageVersion` | `5.1.1`（`desktopPackageVersion`） | jpackage 只接受 `x.y.z`；同时是 `DesktopBuildConfig.VERSION_NAME` 与 `tar.gz` 文件名的一部分。**与 Windows 同源**（同一行） |
| `description` | 西安交通大学工具箱（桌面预览） | 进 `.deb` 的 `Description` 与 `.desktop` 的 `Comment=`（实测见 §3.3） |
| `vendor` | `XJTU ToolBox` | 进 `.deb` 的 `Maintainer` 前半段（`XJTU ToolBox <Unknown>`，见 §7 第 5 条）；Windows 侧进「发布者」栏 |
| `copyright` | `Copyright © XJTU ToolBox contributors` | 进 `/opt/xjtu-toolbox/share/doc/copyright`（实测见 §3.3）。与 Windows 侧同一个值 |
| `linux.menuGroup` | `Education` | 与 `windows.menuGroup` **同一个词**（Windows 那栏就是刻意对齐 Linux 这一栏）。⚠️ 但它在 `.deb` 这条路上**没落地**：jpackage 生成的 `.desktop` 里没有它（见 §7 第 4 条） |
| `linux.appCategory` | `Education` | 进 `.desktop` 的 `Categories=` 与 `.deb` 的 `Section:`（实测两处都是 `Education`） |
| `linux.iconFile` | **未设** | ⇒ 用的是 Compose Desktop 的默认图标（不是我们的 launcher 图标）。这是 Linux 与 Windows **唯一**的元数据不一致，原因与补法见 §7 第 3 条 |
| 其余 | 默认值 | `linux.shortcut = true`（⇒ postinst 里 `xdg-desktop-menu install`）、`debMaintainer` 默认（`Unknown`）、`installationPath` 默认（`/opt`）、`debPackageVersion` 取 `packageVersion` |

Windows 那一栏的 `windows.upgradeUuid`（MSI 的 UpgradeCode）**没有 Linux 对应物**，也**不需要**：
`.deb` 认「同一个产品」靠的是**包名**（`xjtu-toolbox`），升级靠版本号比较 —— 这两样都是 jpackage 从
`packageName` / `packageVersion` 推出来的，不需要另存一个「发过版就不能改」的 UUID。

## 3. 本机实测（2026-10-12 · Ubuntu 26.04.1 LTS · JDK 21 · x86_64 · 7 GB 内存）

### 3.1 命令与结果

```bash
# 第一次：失败（原因见下）
./gradlew :desktop:packageDeb :desktop:packageAppImage --max-workers=1
# → BUILD FAILED in 10m 45s
# → Execution failed for task ':data:jvmJar'
#     Could not add file '…/data/build/classes/kotlin/jvm/main/com/xjtu/toolbox/judge/JudgeApi$submitQuestionnaire$1.class'
#     to ZIP '…/data/build/libs/data-jvm.jar'

# 第二次：成功
./gradlew :desktop:packageDeb :desktop:packageAppImage --max-workers=1
# → BUILD SUCCESSFUL in 6m 39s
# → 22 actionable tasks: 8 executed, 14 up-to-date
# → > Task :desktop:packageDeb
#   > Task :desktop:packageAppImage

# 第三次（顺带把 §7.1 的第三样也真跑一遍）
./gradlew :desktop:packageTarGz --max-workers=1
# → BUILD SUCCESSFUL in 6m 5s
```

**第一次为什么失败（如实记录）**：那一次不是配置错了，是**同一个工作区里有另一个 run 在并行构建**
（`ps` 上同时有 `./gradlew :app:compileDebugKotlin --max-workers=1`，以及 `:core:compileKotlinJvm` /
`:data:compileKotlinJvm` 正在跑）。两边共用同一份 `data/build/`，`jvmJar` 打包时那些 class 文件正被
另一次编译重写 ⇒ 「文件列表里有、打进去时没了」。日志留在 `/tmp/linux-deb-appimage.log`；
第二次（`/tmp/linux-deb-appimage2.log`）就通过了。**这条命令本身没有问题**，但这条记录也说明：
两套构建**不要**在同一个 checkout 上并行跑。

产物（`ls -la` 实测）：

```
desktop/build/compose/binaries/main/deb/xjtu-toolbox_5.1.1_amd64.deb   111,170,448 B   md5 a19cf1eef9565ee1e081f286389a31c1
desktop/build/compose/binaries/main/app/xjtu-toolbox/                  204,922,515 B   （du -sb，207 个文件）
desktop/build/compose/binaries/main/tar/xjtu-toolbox-5.1.1-linux-x64.tar.gz  123,750,278 B
```

app-image 的内部形状就是 jpackage 的 `bin/` + `lib/` 两棵：

```
xjtu-toolbox/bin/xjtu-toolbox          ELF 64-bit LSB pie executable, x86-64（21,944 B 的 launcher）
xjtu-toolbox/lib/libapplauncher.so
xjtu-toolbox/lib/xjtu-toolbox.png       PNG 1024×1024（图标，见 §3.3）
xjtu-toolbox/lib/xjtu-toolbox-xjtu-toolbox.desktop
xjtu-toolbox/lib/app/*.jar              应用与依赖（Compose / miuix / kotlinx …）
xjtu-toolbox/lib/runtime/**             jlink 出来的 JDK 运行时
```

### 3.2 包里的元数据（`dpkg-deb -I` 的原样输出）

```
 Package: xjtu-toolbox
 Version: 5.1.1
 Section: Education
 Maintainer: XJTU ToolBox <Unknown>
 Priority: optional
 Architecture: amd64
 Provides: xjtu-toolbox
 Description: 西安交通大学工具箱（桌面预览）
 Depends: libasound2t64, libatomic1, libbrotli1, libbz2-1.0, libc6, libexpat1, libfontconfig1,
          libfreetype6, libgcc-s1, libgif7, libgl1, libglib2.0-0t64, libglvnd0, libglx0,
          libgraphite2-3, libharfbuzz0b, libjpeg-turbo8, liblcms2-2, libpcre2-8-0, libpng16-16t64,
          libstdc++6, libx11-6, libxau6, libxcb1, libxdmcp6, libxext6, libxi6, libxrender1,
          xdg-utils, zlib1g
 Installed-Size: 200119
```

三点值得记下：

1. **`Section:` / `Categories=` 都来自 `linux.appCategory`**，`Description` 来自 `description`，
   `Maintainer` 的前半段来自 `vendor` ⇒ 配置项确实一处生效多处；
2. **`Depends` 是 jpackage 按「宿主机的库名」生成的**（`libasound2t64` / `libpng16-16t64` 这种 `t64`
   后缀是 Ubuntu 24.04 之后的 ABI 改名）。本机是 Ubuntu 26.04 ⇒ 这份 `Depends` 只对得上「本机这一族」
   的发行版，装到别的版本上可能解析失败（见 §7 第 11 条）；
3. **`Installed-Size: 200119`（KB ≈ 195.4 MiB）** 与 `du -sb` 量出来的 app-image 一致。

`dpkg-deb -c` 里 235 个条目，`lib/app/` 与 `lib/runtime/` 之外只有这些：

```
./opt/xjtu-toolbox/bin/xjtu-toolbox
./opt/xjtu-toolbox/lib/libapplauncher.so
./opt/xjtu-toolbox/lib/xjtu-toolbox-xjtu-toolbox.desktop
./opt/xjtu-toolbox/lib/xjtu-toolbox.png
./opt/xjtu-toolbox/share/doc/copyright
```

⇒ 包**自带** `/opt` 下的一整份（含运行时），不往系统目录里撒东西；`/usr/share/applications/` 里
**没有**文件 —— 菜单项的登记是安装脚本干的（§3.3）。

### 3.3 桌面项与图标（从 `.deb` 里真解出来读的）

`dpkg-deb -x` 解到 `/tmp/debroot` 之后：

`opt/xjtu-toolbox/lib/xjtu-toolbox-xjtu-toolbox.desktop`（原文）：

```
[Desktop Entry]
Name=xjtu-toolbox
Comment=西安交通大学工具箱（桌面预览）
Exec=/opt/xjtu-toolbox/bin/xjtu-toolbox
Icon=/opt/xjtu-toolbox/lib/xjtu-toolbox.png
Terminal=false
Type=Application
Categories=Education
MimeType=
```

`DEBIAN/postinst` 里登记菜单项的那一段（`configure)` 分支）：

```sh
xdg-desktop-menu install /opt/xjtu-toolbox/lib/xjtu-toolbox-xjtu-toolbox.desktop
```

`share/doc/copyright`（**版权栏是我们配的，License 那行是 jpackage 填的默认值**）：

```
Files: *
Copyright: Copyright © XJTU ToolBox contributors
License: Unknown
```

图标：`lib/xjtu-toolbox.png` 的 md5 是 `1d3172a5e4c65c835f017ab4ab2cd6ef` —— 与
`compose-gradle-plugin-1.12.1.jar` 里的 `default-compose-desktop-icon-linux.png` **同一个 md5**
⇒ `linux.iconFile` 没设，装出来在应用菜单里是 **Compose Desktop 的默认图标**，不是我们的 launcher 图标。

## 4. CI：`.github/workflows/desktop-linux.yml`

| 步骤 | 内容 |
|---|---|
| 触发 | `pull_request`（路径过滤：`desktop/**` `data/**` `core/**` `gradle/**` `gradlew` `gradlew.bat` `settings.gradle.kts` 与本文件）+ `workflow_dispatch`；`concurrency` 去重 |
| 环境 | `ubuntu-latest` · `actions/setup-java@v4`（temurin 21，与 `jvmToolchain(21)` / Kotlin 2.4.20 对齐）· `gradle/actions/setup-gradle@v4` |
| 哨兵 | 一条检查 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 的步骤：我们只编 `:desktop`，但 Gradle 会配置整个构建（含 `:app` 的 AGP），缺 SDK 时**响亮地**失败在这一步 |
| 编译 | `./gradlew :desktop:compileKotlin` |
| 出包 | `./gradlew :desktop:packageDeb :desktop:packageAppImage`（**不需要**额外装工具：`.deb` 只用到镜像自带的 `dpkg-deb`；app-image 不需要 `appimagetool`/FUSE） |
| 留档前的自证 | 列出 `.deb`、`dpkg-deb -I` 打出控制信息、`du -sh` 量 app-image —— 这几行进不了 artifact，但它是「包里的元数据对不对」最快的证据 |
| 留档 | `actions/upload-artifact@v4` → `desktop/build/compose/binaries/main/deb/*.deb` 与 `…/app/`，`if-no-files-found: error` |
| 验证 | `:desktop:test`（Linux 上整套都该绿；`:core` / `:data` / `:testkit` 那几道在 `core-gate.yml` 里，同一个 `ubuntu-latest`，不重复） |

三条刻意的安排：

1. **`if-no-files-found: error` 是防「静默跳过」的护栏**。Compose Desktop 对**与宿主不兼容**的 target
   不报错、而是把任务 **`SKIPPED`**（实测：Linux 上 `:desktop:packageMsi` 就是 `SKIPPED`，
   见 `docs/windows-delivery.md` §1）⇒「命令绿了但其实什么都没出包」是可能的。人工核对过的
   `packageDeb`/`packageAppImage` 在 Linux 上**是真跑的**（§3 有产物），但护栏不该只靠这一次人工核对。
2. **验证放在出包之后**：万一有用例红了，`.deb` 与 app-image 仍然留得下来给人查；
3. **不做发布**：这一条只出 artifact，**不上传 Release**（与 Windows 那条同一条口径）。

⚠️ **这条 workflow 还没有在 GitHub 上真跑过**（与 `desktop-windows.yml` 一样：本机只能验 YAML 解析
——`yaml.safe_load` 通过、10 个 step——真跑要等它触发）。这是 §7 第 13 条。

**与 `desktop-windows.yml` 的关系**：两条独立、互不引用、路径过滤重叠（`desktop/**` 一起动时两条都跑）。
`desktop-windows.yml` 本轮**一个字没动**（`git diff` 可核）。

## 5. 代码签名与分发（**没做**，也不是这一轮的范围）

现状：产物**未签名**，也**没有 apt 仓库**。`.deb` 的信任模型与 Windows 那边不同，分开说清：

| 要什么 | 现在 | 说明 |
|---|---|---|
| `.deb` 本身签名 | **没有**（Debian 的 `.deb` 本来就不带签名） | 用户手动 `dpkg -i` 时 dpkg 只做完整性校验，不验来源 |
| apt 仓库 + `Release.gpg` / `InRelease` | **没有**（占位：`secrets.DEB_SIGNING_KEY`、一个静态仓库目录 + `apt-ftparchive`） | 「装的时候系统能验来源」靠的是这层，不是包本身 |
| `Maintainer` 里的真实邮箱 | **没有**（现在是 `XJTU ToolBox <Unknown>`） | 这是一个**决定**（拿哪个邮箱当维护者），不是技术问题（§7 第 5 条） |
| AppImage 的签名（`appimagetool --sign`） | 不适用 | 单文件 `.AppImage` 本身就没做（§1） |

所以现在用户拿到 `.deb` 只有两条路：`sudo dpkg -i` 手动装，或 `tar zxf` 解压后直接跑
`bin/xjtu-toolbox`。**没有自动升级通道** —— 与桌面端的更新骨架（`DesktopUpdate`）口径一致：
它只认 GitHub 的**发布资产**，而 CI 现在只在 artifact 里留档。

## 6. 与 Windows 的差异表

| 维度 | Windows（`docs/windows-delivery.md`） | Linux（本文） |
|---|---|---|
| 出包任务 | `packageMsi` / `packageExe` | `packageDeb` / `packageAppImage`（+ `packageTarGz` / `createDistributable`） |
| 能在哪种宿主上打 | **只能 Windows**（要 WiX `candle.exe`/`light.exe`，Compose 自动下载） | **只能 Linux**（`.deb` 要 `dpkg-deb`）；本机 Linux 就能真跑 |
| 在对方的宿主上会怎样 | Linux 上 `packageMsi` 是 `SKIPPED`（不是失败） | Windows 上 `packageDeb` 同理（未在本轮验，机理相同） |
| 额外工具 | WiX 3.11（自动下载到 gradle 用户目录，可用 `WIX_PATH` 覆盖） | **无**（`dpkg-deb` 镜像自带；app-image 不需要 `appimagetool`/FUSE） |
| 安装形态 | `perUserInstall = true` ⇒ 装到 `%LOCALAPPDATA%`、**不弹 UAC** | 系统级 `/opt/xjtu-toolbox`，`sudo dpkg -i` ⇒ **要 root**；免 root 的路是 app-image / `tar.gz` |
| 菜单项 / 快捷方式 | `menu` + `shortcut` ⇒ 开始菜单（`menuGroup=Education`）+ 桌面快捷方式 | 只有 `.desktop` + postinst 里 `xdg-desktop-menu install`（没有桌面快捷方式；`linux.shortcut` 管的是菜单登记） |
| 图标 | `windows.iconFile = icons/windows.ico`（我们自己转的多尺寸 .ico） | **未设** ⇒ Compose Desktop 的默认图标（§3.3 的 md5 证据） |
| 「同一个产品」靠什么认 | MSI 的 `upgradeUuid`（发版后不能改） | `.deb` 的**包名**（`xjtu-toolbox`），没有 UUID 这种东西 |
| 升级 / 卸载 | MSI 的升级序列（靠 UpgradeCode） | `dpkg` / `apt`（靠包名 + 版本号比较） |
| 包里的依赖声明 | 无（Windows 不带 `Depends` 这类元数据） | `.deb` 的 `Depends: libasound2t64, libgl1, …`（jpackage 按宿主库名生成） |
| 代码签名 | 未做（`signtool` + 证书占位，SmartScreen 会报警） | 未做，且 `.deb` 本身就不带签名（信任在 apt 仓库那一层，§5） |
| 凭据保护 | DPAPI（当前用户作用域，JNA） | **没有原生保护**：文件 + `0600`（§5.1 明确接受了这一档） |
| 数据目录 | `~/.local/share/xjtu-toolbox`（XDG 口径，在 Windows 上就是 `C:\Users\<你>\.local\share\…`） | `~/.local/share/xjtu-toolbox`（同一个口径） |
| CI | `.github/workflows/desktop-windows.yml`（windows-latest） | `.github/workflows/desktop-linux.yml`（ubuntu-latest） |
| 本轮真跑过的 | `createDistributable` ✓；`packageMsi`/`packageExe` 在 Linux 上 `SKIPPED` | `packageDeb` ✓ `packageAppImage` ✓ `packageTarGz` ✓（§3） |

**没有分叉的产品行为**：两端共用同一份 `:desktop` 与同一套 Compose 屏（D3），这一轮 Linux 侧除了
「多了一个 CI job」没有引入任何运行时行为差异 —— 凭据存储仍是 Linux 那条「文件 + `0600`」的口径。

## 7. 未验证项清单（**这一节是这份文档的重点**）

| # | 未验证的事 | 为什么没验 | 怎么才能验 |
|---|---|---|---|
| 1 | **`.deb` 真装一次**（`sudo dpkg -i` / `apt install ./…deb`：依赖解析、菜单项出现、卸载干净） | 本机只有一个 checkout，没在真机器上装过；装它要改这台机器的系统状态 | 一台一次性 Debian/Ubuntu（容器或虚拟机）：装 → 看应用菜单 → 卸 |
| 2 | **app-image / `.deb` 在真桌面环境里启动** | 本机没有显示服务器（`DISPLAY` 为空），也**没有 Xvfb**（`xvfb-run` 没装）⇒ 只能验到「出包成功 + skiko 要的 `libGL.so.1` 在系统里 + 运行时自带」 | 有桌面的机器（X11 与 Wayland 各一次），或 CI 里加 `xvfb-run` 跑一次冷启动 |
| 3 | **桌面项图标是我们自己的** | `linux.iconFile` 未设 ⇒ 实测用的是 Compose Desktop 默认图标（§3.3）；补它要**新增** `desktop/icons/*.png`（从 Android 的 `ic_launcher.webp` 转，与 `windows.ico` 同一来源）并把 `linux.iconFile` 指过去 —— 新增资源不在本轮文件范围内 | 下一个决定：加 `linux { iconFile.set(project.file("icons/linux.png")) }` + 一份 512×512 PNG，再出一遍 `.deb` |
| 4 | **`linux.menuGroup` 在 `.deb` 这条路上有没有用** | 实测：jpackage 生成的 `.desktop` 里**没有**它（进分类的是 `appCategory` → `Categories=`/`Section=`）。它在 RPM 那条路上才有意义 | 真要「应用菜单里放在某个文件夹下」，得自己改 `.desktop` 或用 `xdg-desktop-menu --mode` 之类的后处理 —— 属下一个决定 |
| 5 | **`Maintainer: XJTU ToolBox <Unknown>` 是否可接受** | `linux.debMaintainer` 没设 ⇒ jpackage 填的是 `<vendor> <Unknown>`。`apt`/lintian 会盯着这一栏 | 一个决定（拿哪个邮箱当维护者）+ `linux { debMaintainer = "… <…@…>" }` |
| 6 | **Wayland（XWayland）下的行为** | 见第 2 条（没有显示服务器） | 同第 2 条，Wayland 会话里单独跑一次 |
| 7 | **真·单文件 `.AppImage`** | §1：`TargetFormat.AppImage` 是 jpackage 的 app-image，不是单文件；单文件要 `appimagetool`（且依赖 FUSE） | 需要时加一条与 `packageTarGz` 同形的任务（app-image → `appimagetool`） |
| 8 | **`tar.gz` 解压后真跑** | 只验到「真打出来了 + 118.0 MiB」（它里面就是 app-image 那棵树） | 同第 2 条 |
| 9 | **ARM64 / 非 x86_64** | 本机是 `x86_64`，jpackage 出的是宿主架构（文件名 `_amd64`）⇒ 树莓派/ARM 服务器上没有包 | CI 加 `ubuntu-24.04-arm` 之类的 arm runner（jpackage 支持 aarch64） |
| 10 | **非 Debian 系的发行版** | `TargetFormat.Rpm` **没声明**（Fedora / RHEL 没有包） | 需要时加 `TargetFormat.Rpm`（Compose 支持），或只认「app-image / tar.gz 是通用那条路」 |
| 11 | **`.deb` 的 `Depends` 在别的发行版上对不对得上** | jpackage 按**宿主**的库名生成；本机 Ubuntu 26.04 给出来的是 `libasound2t64` / `libpng16-16t64` 这种新版名字（§3.2） | 在目标发行版（如 Ubuntu 22.04 / Debian 12）的 runner 上重新出一次包，或把 `Depends` 人工调宽 |
| 12 | **`copyright` 文件里的 `License: Unknown`** | 我们只配了版权行，license 那行是 jpackage 的默认值 —— 而我们**没有** EULA/license 文件（`desktop-port-plan.md` §7 把 EULA 列在待定项） | 补一份 license 文件 + `appResourcesRootDir`（Windows 那边的 `windows-delivery.md` 也把 EULA 列为待定） |
| 13 | **两条 CI workflow 真跑** | 仓库还没触发过 `desktop-linux.yml`（本机只验了 YAML 解析：`yaml.safe_load` 通过、10 个 step） | 推一次带 `desktop/**` 改动的 PR，看两条 job 的 artifact 与 `if-no-files-found` |
| 14 | **jpackage 在本机之外的内存/时间** | 本机 7 GB 内存、`--max-workers=1`：出包 6 分 39 秒（一道）。CI 的 runner 内存更宽，但同 checkout 上**不能**并行跑两套构建（§3.1 那次失败） | CI 里看这一步的耗时；出现 `jvmJar` 那种「文件正被重写」的报错时，先确认是不是并行构建 |

## 8. 门禁与测试数字（本轮实测）

数字都是这一轮在本机（Ubuntu 26.04.1 LTS · JDK 21 · `--max-workers=1`）跑出来的：

| 门禁 | 结果 |
|---|---|
| `:desktop:compileKotlin` | ✓（本轮走了三次：两次出包各一次、单独门禁一次） |
| `:desktop:test` | **76 通过 / 0 失败 / 0 跳过**（17 个测试类，`BUILD SUCCESSFUL in 2m 34s`） |
| `:desktop:packageDeb`（真跑） | ✓ `xjtu-toolbox_5.1.1_amd64.deb` = 111,170,448 B |
| `:desktop:packageAppImage`（真跑） | ✓ app-image = 204,922,515 B（与 `createDistributable` 同一个落点） |
| `:desktop:packageTarGz`（真跑） | ✓ `xjtu-toolbox-5.1.1-linux-x64.tar.gz` = 123,750,278 B |
| `:data:jvmTest` / `:core:jvmTest` / `:app:testDebugUnitTest` | **本轮没跑**：同一工作区里另有 run 正在搬 Agent 域（`app/` `core/` `data/` 都有它在途的改动；§3.1 那次 `:data:jvmJar` 竞态就是它引起的），跑出来的红绿指向的是它的在途代码，不是本轮改动 |
| `.github/workflows/desktop-linux.yml` | YAML 解析通过（`yaml.safe_load`，10 个 step）；**没有在 GitHub 上真跑过**（§7 第 13 条） |

本轮的改动只有两个文件：新增 `.github/workflows/desktop-linux.yml` 与本文档 —— **没有一行 Kotlin、
没有一行构建配置**（`desktop/build.gradle.kts` 也没动，§2），`desktop-windows.yml` 与
`docs/windows-delivery.md` 更是没碰。所以上面那三道「没跑」的门禁不可能被本轮改动碰坏。

## 9. 这一轮**没做**（范围之外，别误会）

1. **Linux 侧的图标**（第 3 条）与 **`.desktop` 的分类/菜单层级定制**（第 4 条）；
2. **真·单文件 AppImage**（第 7 条）、**RPM**（第 10 条）、**ARM64**（第 9 条）；
3. **代码签名与 apt 仓库**（§5）—— 只出 artifact，不上传 Release；
4. **`serve` 模式的 Linux 服务化 / 自启 / systemd 单元**；
5. **Linux 的通知 / 托盘 / 文件关联**（`fileAssociation` 没用上）；
6. **凭据的 Secret Service 集成**：Linux 仍是「文件 + `0600`」（§5.1 接受这一档；`windows-delivery.md`
   §5.5 第 4 条把两端一起记着）。⚠️ 桌面端的 `DISPLAY` 之外，这一条是 Linux 交付面**最大的**安全缺口。
