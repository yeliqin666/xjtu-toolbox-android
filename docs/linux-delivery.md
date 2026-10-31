# Linux 交付面（`.deb` · app-image · `tar.gz`）

这一份写的是**桌面端在 Linux 上怎么交付**：包装成什么、谁来打、包里的元数据长什么样、怎么验的，
以及**哪些事这一轮没能真验**（最后一节，最该读的就是它）。它与 `docs/windows-delivery.md` 对称 ——
那边是 `.msi`/`.exe`（只能在 Windows 上打），这边是 Linux 那三样（只能在 Linux 上打）。

上游决策在 `docs/desktop-port-plan.md`：D3（Linux 与 Windows **同一份** `:desktop`）、§7.1（Linux 交付
形态**已定**：`.deb` + app-image + `tar.gz`）、§5.1（凭据存储：Linux 用「文件 + `0600`」）。

## 1. 交付形态

| 平台 | 形态 | 任务 | 谁能打 | 本轮实测产物 | 大小 |
|---|---|---|---|---|---|
| Linux | `.deb`（Debian / Ubuntu，apt 管依赖与卸载） | `:desktop:packageDeb` | Linux（本机就能打） | `desktop/build/compose/binaries/main/deb/xjtu-toolbox_5.1.1_amd64.deb` | 111,669,760 B（106.5 MiB，收尾轮重跑 §3.4） |
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

上一轮逐项核过之后，Linux 侧的元数据已经与 Windows 一致，只差两样；这一轮把它俩补上了 ——
**改动只在 `linux { }` 这一块**里（`desktop/build.gradle.kts`）：

| 这一轮补的 | 改前 | 改后 |
|---|---|---|
| 桌面项图标 | 没设 ⇒ 装出来是 Compose Desktop 的默认图标（§3.3 有 md5 证据） | `linux.iconFile = icons/linux.png`（256×256，从仓库里的自适应矢量转的；来源与生成命令见 §2.1，包里的实测见 §3.3） |
| `.deb` 的 `Maintainer` | 没设 ⇒ jpackage 填 `<vendor> <Unknown>`（`apt`/lintian 会盯这一栏） | `XJTU ToolBox <114236693+Triangle-GitHub@users.noreply.github.com>`（用的哪个身份、为什么见 §2.2） |

现状（整栏，含这一轮没动的部分）：

| 配置项 | 值 | 为什么 |
|---|---|---|
| `targetFormats` | `Deb` · `AppImage` · `Msi` · `Exe` | 前两个是 §7.1 定的 Linux 两样（第三样 `tar.gz` 由 `packageTarGz` 从 app-image 再打）；后两个是 `docs/windows-delivery.md` 定的 Windows 两样，在 Linux 上只是**声明**（任务会注册，但执行时 `SKIPPED`，见 `windows-delivery.md` §1） |
| `packageName` | `xjtu-toolbox` | 一处定义，三处用：安装目录（`/opt/xjtu-toolbox`）、`.deb` 的包名与 `bin/` 里的可执行名、`tar.gz` 里的目录名 |
| `packageVersion` | `5.1.1`（`desktopPackageVersion`） | jpackage 只接受 `x.y.z`；同时是 `DesktopBuildConfig.VERSION_NAME` 与 `tar.gz` 文件名的一部分。**与 Windows 同源**（同一行） |
| `description` | `XJTU ToolBox (desktop preview)` | 进 `.deb` 的 `Description` 与 `.desktop` 的 `Comment=`（实测见 §3.3）。**2026-10-19 起是 ASCII**：Windows 上 jpackage 固定 `-cultures:en-us` ⇒ MSI 代码页 1252，中文会触发 WiX 的 `LGHT0311`（`light.exe` 退出码 311、不打印错误）；DSL 没有分平台的 description ⇒ 三端同值，Linux 这边也跟着变英文 |
| `vendor` | `XJTU ToolBox` | 进 `.deb` 的 `Maintainer` 前半段（与 `linux.debMaintainer` 拼成那一行：`XJTU ToolBox <…@…>`，实测见 §3.4）；Windows 侧进「发布者」栏 |
| `copyright` | `Copyright © XJTU ToolBox contributors` | 进 `/opt/xjtu-toolbox/share/doc/copyright`（实测见 §3.3）。与 Windows 侧同一个值 |
| `linux.menuGroup` | `Education` | 与 `windows.menuGroup` **同一个词**（Windows 那栏就是刻意对齐 Linux 这一栏）。⚠️ 但它在 `.deb` 这条路上**没落地**：jpackage 生成的 `.desktop` 里没有它（见 §7 第 4 条） |
| `linux.appCategory` | `Education` | 进 `.desktop` 的 `Categories=` 与 `.deb` 的 `Section:`（实测两处都是 `Education`） |
| `linux.iconFile` | `desktop/icons/linux.png`（256×256 PNG） | 桌面项的图标：`.desktop` 的 `Icon=` 指的就是包里拷进去的这一张（实测见 §3.3）。**不再**是 Compose Desktop 的默认图标。来源与生成命令见 §2.1 |
| `linux.debMaintainer` | `114236693+Triangle-GitHub@users.noreply.github.com`（只给地址） | `.deb` 控制文件里的 `Maintainer:`。⚠️ **实测**：jpackage 只收**邮箱**，那一行是它自己拼的 `<vendor> <这一串>`（本机真出包 + `dpkg-deb -I` 核过）；不设的话它填 `<vendor> <Unknown>`。见 §2.2 |
| 其余 | 默认值 | `linux.shortcut = true`（⇒ postinst 里 `xdg-desktop-menu install`）、`installationPath` 默认（`/opt`）、`debPackageVersion` 取 `packageVersion` |

Windows 那一栏的 `windows.upgradeUuid`（MSI 的 UpgradeCode）**没有 Linux 对应物**，也**不需要**：
`.deb` 认「同一个产品」靠的是**包名**（`xjtu-toolbox`），升级靠版本号比较 —— 这两样都是 jpackage 从
`packageName` / `packageVersion` 推出来的，不需要另存一个「发过版就不能改」的 UUID。

### 2.1 桌面项图标的来源与生成（仓库内资源的转换，没有从网络下载任何东西）

> **2026-10-30 更新**：App 图标已换成 Figma 设计的插画版，`drawable/ic_launcher_background.xml`
> 与 `ic_launcher_foreground.xml` 两片矢量已删除，`mipmap-*/ic_launcher*.webp` 换成新图标，
> `desktop/icons/linux.png` 与 `windows.ico` 也从同一份 Figma 导出重新生成。
> 下面这段正文和 §3.x 里的 md5 是旧雪山图标的记录，只作历史参考。

图标是**应用自己的图标**：仓库里那份自适应图标的两片矢量 ——
`app/src/main/res/drawable/ic_launcher_background.xml`（`#87CEEB` 满幅）与 `ic_launcher_foreground.xml`
（远山 `#7BAED4` / 主峰 `#2E6B9E` / 雪顶 `#FFFFFF` / 地面线 `#1B4F72`，viewport 都是 108×108），
也就是 `mipmap-anydpi/ic_launcher.xml` 引的那两片（提交 `2145ad11` 的说明写着「App 图标恢复 v2.0
那套自适应图标」；`mipmap-*dpi/ic_launcher.webp` 那几份位图是给 API 26 以下的兜底）。

⚠️ **别拿 `mipmap-*/ic_launcher.webp` 当源**：本机把它解出来看过（§7 第 3 条），那是 Android Studio
模板的**绿机器人占位图**，不是应用的图标 —— Windows 那份 `icons/windows.ico` 恰好就是拿它转的
（见 `docs/windows-delivery.md` §7 第 10 条）。**（2026-10-30 起这一条已不成立：`mipmap-*` 里的
位图已全部换成新图标，Windows 的 `.ico` 也一并重做了。）**

本机没有 `rsvg-convert`（ImageMagick 的 SVG 委派就断在这里，`convert x.svg` 直接报 delegate failed），
所以走的是**逐字转写 + 多边形直画**：那 5 个形状全是轴对齐的填充多边形（1 个矩形 + 4 个三角形/矩形），
把坐标乘 10 在 1080×1080 上画出来再缩到 256×256，等价于 4.2 倍超采样：

```bash
magick -size 1080x1080 xc:'#87CEEB' \
  -fill '#7BAED4' -draw 'polygon 220,780 400,520 580,780' \
  -fill '#2E6B9E' -draw 'polygon 380,780 620,360 860,780' \
  -fill '#FFFFFF' -draw 'polygon 560,440 620,360 680,440' \
  -fill '#1B4F72' -draw 'polygon 180,780 900,780 900,820 180,820' \
  -resize 256x256 -strip PNG24:desktop/icons/linux.png
```

转写对不对，靠**取样核对**（本机实测，`magick … -format '%[pixel:p{x,y}]'`，坐标按 ×256/108 折算）：

| 取样点（108 viewport） | 期望 | 实测 |
|---|---|---|
| (5,5) 背景 | `#87CEEB` | `srgb(135,206,235)` ✓ |
| (40,60) 远山 | `#7BAED4` | `srgb(123,174,212)` ✓ |
| (62,50) 主峰 | `#2E6B9E` | `srgb(46,107,158)` ✓ |
| (62,40) 雪顶 | `#FFFFFF` | `srgb(255,255,255)` ✓ |
| (30,80) 地面线 | `#1B4F72` | `srgb(27,79,114)` ✓ |
| (20,40) 主峰之外 | 背景色 | `srgb(135,206,235)` ✓ |

产物：`desktop/icons/linux.png` —— 256×256、8-bit sRGB、3,163 B、md5 `37a6902e346ea027dd3f2948d346aeb0`。

⚠️ 只出了一张 256×256（与 `windows.ico` 里最大的那张同尺寸）。矢量在手，要 512/1024 把上面那条
`-resize` 改一下重跑就行 —— 没做的原因是没量过桌面环境在 HiDPI 上会不会把它放大到发虚（§7 末尾）。

### 2.2 `.deb` 的 `Maintainer` 用的是哪个身份

jpackage 的参数是 `--linux-deb-maintainer`，它**只收邮箱地址** —— 控制文件里那一行是它自己拼的
`Maintainer: <vendor> <地址>`。这一点是**实测**出来的，不是照文档猜的：第一次把完整的
`XJTU ToolBox <…@…>` 填进去，出包后 `dpkg-deb -I` 读到的是
`Maintainer: XJTU ToolBox <XJTU ToolBox <…@…>>` —— 双层尖括号，一看就知道它又替我包了一层
（JDK 21 的 `jdk.jpackage.internal.LinuxDebBundler` 里 `MAINTAINER` 的默认值那条路写着
`VENDOR + " <" + EMAIL + ">"`，`EMAIL` 缺省就是 `Unknown`）。改成只给地址之后控制文件那行才正常
（§3.2 有最终输出）。

用的身份是仓库里能核到的那个：`git log` 里**桌面交付那两批**（`b624008` / `603ece98`，两笔的作者都是它）
以及最近 200 次提交里 **133** 次的作者 —— `Triangle-GitHub <114236693+Triangle-GitHub@users.noreply.github.com>`，也就是
`git config user.email`。选它的理由只有三条：它是这套交付物的作者、它是 GitHub 的 **noreply** 地址
（不往包里发个人邮箱）、换人要改的就是一行。

仓库里**没有** `AUTHORS` / `MAINTAINERS` / `CONTRIBUTORS` 这类文件（`ls -a` 过；只有 `LICENSE` 与
`THIRD_PARTY_NOTICES.md`），README / `docs/` / `settings.gradle.kts` / workflow 里也没有项目级联系邮箱。
`git log --format='%an <%ae>' | sort -u` 里剩下的是各位贡献者的**个人地址**（`liurunqin666@163.com`
等，仓库所有者本人的那个也在其中）—— 往包元数据里公开写谁，那是人的决定，这一轮不替谁做。

## 3. 本机实测（2026-10-12 · Ubuntu 26.04.1 LTS · JDK 21 · x86_64 · 7 GB 内存）

### 3.0 更早那一批（补桌面项图标 + 补 `Maintainer`）的实测

```bash
# 真跑（那一批）
TMPDIR=/mnt/nas/data/tmp-xjtu-pkg ./gradlew --no-daemon :desktop:compileKotlin :desktop:packageDeb \
  --max-workers=1 -x :core:compileKotlinJvm -x :core:jvmJar -x :data:compileKotlinJvm -x :data:jvmJar
# → BUILD SUCCESSFUL in 7m 9s
# → 9 actionable tasks: 1 executed, 8 up-to-date
# → The distribution is written to …/deb/xjtu-toolbox_5.1.1_amd64.deb
```

**那两个 `-x` 是环境逼的，不是配置**：并行那一轮正在搬 Agent 域（`core/src/commonMain/…/agent/`、
`data/src/commonMain/…/agent/`），`:core` 与 `:data` 的 **jvm** 编译当时处在红的状态（实测报错：
`…/agent/AgentChat.kt` 里 `androidx.activity.compose.rememberLauncherForActivityResult` 在 commonMain
里解析不了；`…/agent/AgentSessionStore.kt` 里 `AgentSessionPersistence` 解析不了）。这两个模块**不是这
一轮改的东西**，所以用它们上一轮已经编好的 `core-jvm.jar` / `data-jvm.jar` 出包 —— `:desktop:compileKotlin`
与 `:desktop:packageDeb` 本身都是真跑的（`desktop/src/**` 这一轮一个字没动，`git status` 可核）。

**`TMPDIR` 也是环境逼的（这一条值得记住）**：本机 `/tmp` 是 3.6 G 的 tmpfs、当时只剩 ~0.8 G，于是
`packageDeb` 在最后一步炸掉，而 jpackage 只报退出码、把 dpkg-deb 的 stderr 吞了：

```
java.io.IOException: Command [fakeroot, dpkg-deb, -b, /tmp/jdk.jpackage…/images,
  …/deb/xjtu-toolbox_5.1.1_amd64.deb] exited with 2 code
```

**在同一个 `images` 目录上手跑那条命令才看见真话**：

```
dpkg-deb (subprocess): compressing tar member: zstd write error: Disk quota exceeded
dpkg-deb: error: <compress> from tar -cf subprocess failed with exit status 2
```

⇒ dpkg-deb 的临时成员写在 `$TMPDIR`（缺省 `/tmp`）。A/B 实测过：`TMPDIR` 缺省失败、指向
`/mnt/nas/data/…`（58 G 空闲）就成功（同一份 `images`、同一条命令）。`TMPDIR` 会经
Gradle → jpackage → fakeroot → dpkg-deb 传下去（它进的是**构建进程的环境**）。

⚠️ **但它管不到 jpackage 自己的暂存盘 —— 收尾轮实测把这一条纠正了**：`jpackage` 的临时目录**永远是
`/tmp/jdk.jpackage…`**，与 `TMPDIR` 无关（本机拿一个最小 app A/B 过：`--type app-image` 时给
`TMPDIR=/mnt/nas/data/tmp-xjtu-pkg`，那几个临时目录**仍然**出现在 `/tmp` 下；Compose 的插件也没把
jpackage 的 `--temp` 暴露出来 —— `compose-gradle-plugin-1.12.1.jar` 里 `AbstractJPackageTask` 的那串参数
逐个数过，没有它）。真正的病根见 §3.4：本机 `/tmp` 是挂了 `usrquota` 的 tmpfs，**按用户的配额**先用完。

产物（`ls -la` / `md5sum` 实测）：

```
…/deb/xjtu-toolbox_5.1.1_amd64.deb   111,034,072 B   md5 50d858ac99d10db380678502c6ccb1e3
```

⚠️ 与上一轮那份（111,170,448 B / md5 `a19cf1eef9565ee1e081f286389a31c1`）**不是同一个字节流**：
图标从 185,223 B 的 Compose 默认图换成 3,163 B 的我们这张，控制文件里的 `Maintainer:` 那行也变了。

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

### 3.2 包里的元数据（`dpkg-deb -I` 的原样输出，§3.4 那一轮那份）

```
 Package: xjtu-toolbox
 Version: 5.1.1
 Section: Education
 Maintainer: XJTU ToolBox <114236693+Triangle-GitHub@users.noreply.github.com>
 Priority: optional
 Architecture: amd64
 Provides: xjtu-toolbox
 Description: XJTU ToolBox (desktop preview)
 Depends: libasound2t64, libatomic1, libbrotli1, libbz2-1.0, libc6, libexpat1, libfontconfig1,
          libfreetype6, libgcc-s1, libgif7, libgl1, libglib2.0-0t64, libglvnd0, libglx0,
          libgraphite2-3, libharfbuzz0b, libjpeg-turbo8, liblcms2-2, libpcre2-8-0, libpng16-16t64,
          libstdc++6, libx11-6, libxau6, libxcb1, libxdmcp6, libxext6, libxi6, libxrender1,
          xdg-utils, zlib1g
 Installed-Size: 200658
```

三点值得记下：

1. **`Section:` / `Categories=` 都来自 `linux.appCategory`**，`Description` 来自 `description`，
   `Maintainer` 则是 `linux.debMaintainer` 与 `vendor` 拼出来的（§2.2）⇒ 配置项确实一处生效多处；
2. **`Depends` 是 jpackage 按「宿主机的库名」生成的**（`libasound2t64` / `libpng16-16t64` 这种 `t64`
   后缀是 Ubuntu 24.04 之后的 ABI 改名）。本机是 Ubuntu 26.04 ⇒ 这份 `Depends` 只对得上「本机这一族」
   的发行版，装到别的版本上可能解析失败（见 §7 第 11 条）；
3. **`Installed-Size: 200658`（KB ≈ 196.0 MiB）** 是**未压缩**的安装体积，与 app-image 的 `du -sb`
   （收尾轮量到 204,780,224 B ≈ 195.3 MiB；§1 那行的 204,922,515 B 是更早那批量的 —— app-image
   目录这两个数差在「哪一次重建的」）同量级；它会随 `desktop/src/**` 浮动（22:45 那次 199980、
   更早那次 200119），**与打包配置无关**。

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
Comment=XJTU ToolBox (desktop preview)
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

图标（这一轮**换了**，改前/改后都在 `.deb` 里量过）：

| | `opt/xjtu-toolbox/lib/xjtu-toolbox.png` 的 md5 |
|---|---|
| **改前**（上一轮那份 `.deb`，`19:21`）| `1d3172a5e4c65c835f017ab4ab2cd6ef` |
| ⌞ 参考：`compose-gradle-plugin-1.12.1.jar` 里的 `default-compose-desktop-icon-linux.png` | `1d3172a5e4c65c835f017ab4ab2cd6ef`（**同一个** ⇒ 那时用的是 Compose 的默认图标） |
| **改后**（这一轮那份 `.deb`，`22:45`）| `37a6902e346ea027dd3f2948d346aeb0` |
| ⌞ 参考：仓库里 `desktop/icons/linux.png` | `37a6902e346ea027dd3f2948d346aeb0`（**同一个** ⇒ 现在是我们自己的图标） |
| **收尾轮重跑**（`03:20` 那份，§3.4） | `37a6902e346ea027dd3f2948d346aeb0`（**同一个** —— 图标与配置都没动，只有应用代码换了） |

`.desktop` 里 `Icon=` 那一行本身**没变**（`Icon=/opt/xjtu-toolbox/lib/xjtu-toolbox.png`，jpackage 一直是
这么写的）—— 变的是它指的那张图。顺手也把「包里那张 == 仓库里那张」写成了 CI 的一步（§4）。

### 3.4 收尾轮（2026-10-13 03:20）：真跑 `:desktop:packageDeb` 并用 `dpkg-deb` 核对

收尾轮按「真跑一次 + 拆包核对」把打包重跑了一遍（跑前 `ps aux` 确认没有别的构建）：

```bash
# 03:16 起，BUILD SUCCESSFUL in 4m 28s；`:desktop:packageDeb` 这一步是真执行的（不是 UP-TO-DATE/SKIPPED）
TMPDIR=/mnt/nas/data/tmp-xjtu-pkg \
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/nas/data/tmp-xjtu-pkg \
  ./gradlew --console=plain --no-daemon --max-workers=1 -Pkotlin.incremental=false :desktop:packageDeb
```

两个环境变量都是被本机的坑逼出来的（**不是配置**，CI 的 runner 上不需要）：

1. **并行构建**：02:47 那次死在 `:desktop:compileKotlin` ——
   `e: …/ToolboxDesktopApp.kt:1185:27 Unresolved reference 'DesktopAgentScreen'`，那是**另一个 run 正在写**的
   `desktop/src/**`（它 02:52 才把那个 private 函数写进去）。这一轮的文件范围不含 `desktop/src/**` ⇒ 等它。
2. **`/tmp` 的按用户配额**（本机特有）：`/tmp` 是挂了 `usrquota` 的 tmpfs
   （`findmnt -no OPTIONS /tmp` ⇒ `rw,nosuid,nodev,nr_inodes=1048576,inode64,usrquota`），
   **配额先于容量用完**：`df -h /tmp` 显示 772 M 空闲，而
   `dd if=/dev/zero of=/tmp/x bs=1M count=200` 在 **49 M** 处就 `dd: IO error: Disk quota exceeded`。
   于是 jpackage 把运行时拷进自己暂存盘时炸掉：
   `java.nio.file.FileSystemException: …/runtime/lib/modules -> /tmp/jdk.jpackage…: Disk quota exceeded`。
   ⚠️ **那个暂存盘不认 `TMPDIR`**（§3.0 末尾那条纠正）：`TMPDIR` 只管到 `dpkg-deb` 那一步，
   jpackage 自己的临时目录永远是 `/tmp/jdk.jpackage…`，要挪它只能改那个 JVM 的 `java.io.tmpdir`
   （`JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<大卷>`；本机最小 app A/B 过，加了它就落在大卷上）。
   `--no-daemon` 是为了这一次构建的 JVM 直接用这份环境。

核对结果（`dpkg-deb -I` 的完整原文见 §3.2，那一段就是这一轮的输出）：

- ① `.desktop` 的 `Icon=` 仍然是 `Icon=/opt/xjtu-toolbox/lib/xjtu-toolbox.png`（jpackage 一直这么写），
  **它指的那张图**与仓库里的源**同一个 md5**（`dpkg-deb --fsys-tarfile … | tar -xO ./opt/xjtu-toolbox/lib/xjtu-toolbox.png`）：

  | | md5 |
  |---|---|
  | 包里 `opt/xjtu-toolbox/lib/xjtu-toolbox.png`（03:20 那份） | `37a6902e346ea027dd3f2948d346aeb0` |
  | 仓库 `desktop/icons/linux.png` | `37a6902e346ea027dd3f2948d346aeb0` |

  ⇒「桌面项图标是我们自己的」在**真包**上核到位（§7 第 3 条）。
- ② `Maintainer:` = `XJTU ToolBox <114236693+Triangle-GitHub@users.noreply.github.com>` ⇒ `debMaintainer`
  那行生效了，jpackage 拼的就是 `<vendor> <地址>`（机制与身份见 §2.2）。**这就是 §7 第 5 条的收官证据。**
- ③ `Depends` 与更早那份**逐字相同**（同一台宿主、同一张库名表）；`Installed-Size` 199980 → **200658**
  （+0.7 MiB）—— **不是**打包配置变了，是这期间 `desktop/src/**` 换过（另一个 run 的 Agent 路由）。
- ④ 产物：`…/deb/xjtu-toolbox_5.1.1_amd64.deb` = **111,669,760 B**、md5 **`0c47a5df6c9d757696050fe2d49d6e05`**
  （22:45 那份是 111,034,072 B —— 差的就是上面那些应用代码，打包配置一个字没动）。

§4 里那条「图标自证」CI 步骤的**脚本原文**，也拿真包跑了两个方向：

```
# 正例（03:20 这份 .deb）：放行（exit 0）
包里 opt/xjtu-toolbox/lib/xjtu-toolbox.png = 37a6902e346ea027dd3f2948d346aeb0
仓库 desktop/icons/linux.png            = 37a6902e346ea027dd3f2948d346aeb0
Icon=/opt/xjtu-toolbox/lib/xjtu-toolbox.png

# 负例（把要比对的那份换成别的文件 ⇒ 等价于「包里那张不是仓库那份」）：如实红（exit 1）
'[' 37a6902e346ea027dd3f2948d346aeb0 = 918b548033b8278882f4ab1dcb180f62 ']'   → 退出码 1
```

⚠️ 负例是**换掉比对对象**触发的：收尾轮本想拿「改前那份 `.deb`」（19:21）再跑一遍，但那份包已经不在盘上
⇒ 它验的是「断言本身会红」，**不是**「旧包必然红」。

⚠️ 这一轮仍然**没有**验证的：这一步在 GitHub 的 runner 上跑（§7 第 13 条）、`.deb` 真装一次（§7 第 1 条）。
## 4. CI：`.github/workflows/desktop-linux.yml`


| 步骤 | 内容 |
|---|---|
| 卡死护栏 | job 级 `timeout-minutes: 60`（与 `desktop-windows.yml` 同一个数字；本机出包实测 6 分 39 秒，60 分钟是留给冷缓存的余量。⚠️ 没在 CI 上量过） |
| 触发 | `pull_request`（路径过滤：`desktop/**` `data/**` `core/**` `gradle/**` `gradlew` `gradlew.bat` `settings.gradle.kts` 与本文件）+ `workflow_dispatch`；`concurrency` 去重 |
| 环境 | `ubuntu-latest` · `actions/setup-java@v4`（temurin 21，与 `jvmToolchain(21)` / Kotlin 2.4.20 对齐）· `gradle/actions/setup-gradle@v4` |
| 哨兵 | 一条检查 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 的步骤：我们只编 `:desktop`，但 Gradle 会配置整个构建（含 `:app` 的 AGP），缺 SDK 时**响亮地**失败在这一步 |
| 编译 | `./gradlew :desktop:compileKotlin` |
| 出包 | `./gradlew :desktop:packageDeb :desktop:packageAppImage`（**不需要**额外装工具：`.deb` 只用到镜像自带的 `dpkg-deb`；app-image 不需要 `appimagetool`/FUSE） |
| 留档前的自证 | 列出 `.deb`、`dpkg-deb -I` 打出控制信息、`du -sh` 量 app-image —— 这几行进不了 artifact，但它是「包里的元数据对不对」最快的证据 |
| 图标自证 | 一条 shell 步骤：把 `.deb` 里的 `opt/xjtu-toolbox/lib/xjtu-toolbox.png` 与仓库里的 `desktop/icons/linux.png` 比 md5，并打出 `.desktop` 的 `Icon=` 行。不相等就是 `iconFile` 没生效。收尾轮把这一步的**脚本原文**拿真 `.deb` 两个方向各跑了一次（放行 / 退出码 1，见 §3.4） |
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
——`yaml.safe_load` 通过、11 个 step——真跑要等它触发）。这是 §7 第 13 条。新增的两步（图标自证 /
`timeout-minutes`）里，图标自证那一步的**脚本本体**在本机真跑过（拿真 `.deb` 试了正反两向），但
「在 GitHub 的 runner 上跑」仍是未验证。

**与 `desktop-windows.yml` 的关系**：两条独立、互不引用、路径过滤部分重叠（`desktop/**`、`data/**` 一起动时两条都跑；Windows 那条只覆盖平台专属面）。
上一轮 Windows 那份一个字没动；**这一轮两条都动了**，而且是**对称**的：都加了 job 级
`timeout-minutes: 60`，各自多了一步「自己那个图标」的自证（Linux 比包里的 png、Windows 比
`windows.ico` 的 ICO 头与 256×256 那张），而 JDK / 缓存 / task 名 / `if-no-files-found` 四项两份逐字一致
（`git diff` 可核）—— 差异只剩平台必需的：Linux 多了 `chmod +x gradlew`，Windows 把验证拆成两步。

## 5. 代码签名与分发（**没做**，也不是这一轮的范围）

现状：产物**未签名**，也**没有 apt 仓库**。`.deb` 的信任模型与 Windows 那边不同，分开说清：

| 要什么 | 现在 | 说明 |
|---|---|---|
| `.deb` 本身签名 | **没有**（Debian 的 `.deb` 本来就不带签名） | 用户手动 `dpkg -i` 时 dpkg 只做完整性校验，不验来源 |
| apt 仓库 + `Release.gpg` / `InRelease` | **没有**（占位：`secrets.DEB_SIGNING_KEY`、一个静态仓库目录 + `apt-ftparchive`） | 「装的时候系统能验来源」靠的是这层，不是包本身 |
| `Maintainer` 里的地址 | **有了**（`XJTU ToolBox <114236693+Triangle-GitHub@users.noreply.github.com>`） | 这一轮补的，身份与理由见 §2.2。它是 GitHub 的 **noreply** ⇒ 没往包里发个人邮箱 |
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
| 图标 | `windows.iconFile = icons/windows.ico`（多尺寸 .ico，但**图的内容**还是 Android 模板占位图，见 `windows-delivery.md` §7 第 10 条） | `linux.iconFile = icons/linux.png`（256×256，应用自己的自适应矢量转的；§2.1 / §3.3） |
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

### 6.1 Linux ↔ Windows 元数据逐项对照表

逐项列出来是为了让「两端一致」有据可查（而不是靠读一遍 `build.gradle.kts` 相信它）：

| 配置项 | Linux（`.deb` / app-image / `tar.gz`） | Windows（`.msi` / `.exe`） | 一致？ |
|---|---|---|---|
| `packageName` | `xjtu-toolbox` | 同一个（`nativeDistributions` 里**同一行**） | ✅ 不可能分叉 |
| `packageVersion` | `5.1.1`（`desktopPackageVersion`） | 同一个（同一行） | ✅ |
| `description` | `XJTU ToolBox (desktop preview)` | 同一个（2026-10-19 起 ASCII，理由同上） | ✅ |
| `vendor` | `XJTU ToolBox` | 同一个 | ✅ |
| `copyright` | `Copyright © XJTU ToolBox contributors` | 同一个 | ✅ |
| `menuGroup` | `Education` | `Education`（`windows.menuGroup`） | ✅ 同一个词 |
| `appCategory` | `Education` ⇒ `.desktop` 的 `Categories=` 与 `.deb` 的 `Section:` | — Windows 没有这一栏，等价物是 `menuGroup`（值也取了 `Education`） | 平台差异 |
| `iconFile` | `desktop/icons/linux.png`（256×256，**应用自己的**自适应矢量转的，§2.1） | `desktop/icons/windows.ico`（7 张，但图的内容仍是 Android 模板占位图 —— `windows-delivery.md` §7 第 10 条） | ⚠️ **不一致（只修了 Linux 那半边）** |
| `debMaintainer` | `114236693+Triangle-GitHub@users.noreply.github.com`（只给地址；拼出来的控制文件那一行是 `XJTU ToolBox <…>`，实测见 §3.4） | — Windows 的等价物是安装器里的「发布者」，取 `vendor` | 平台差异 |
| `debPackageVersion` / `rpmPackageVersion` | 默认取 `packageVersion` | `msiPackageVersion` / `exePackageVersion` 默认取 `packageVersion` | ✅ 同一条口径 |
| `installationPath` | 默认 `/opt`（系统级，`sudo dpkg -i`） | `perUserInstall = true` ⇒ `%LOCALAPPDATA%`（不弹 UAC） | 平台差异（各自与凭据存储的作用域对齐） |
| `upgradeUuid` | —（`.deb` 靠**包名**认产品，没有 UUID） | `528a294e-23ee-4307-a37a-1a71bb591fa0`（MSI 的 UpgradeCode，发版后不能改） | 平台差异 |
| `shortcut` / `menu` | `linux.shortcut = true` ⇒ postinst 里 `xdg-desktop-menu install` | `shortcut = true` / `menu = true` ⇒ 桌面快捷方式 + 开始菜单 | 平台差异 |
| `console` | — | `console = false`（不开控制台窗） | 平台差异 |
| `copyright` 落到哪 | 包里的 `share/doc/copyright`（`Copyright:` 行是我们配的；`License: Unknown` 是 jpackage 按 `licenseType` 默认值填的） | 安装器与控制面板的「版权」栏 | 平台差异（同一个值） |
| `licenseFile` / EULA | **两边都没设**（Linux 那份 `copyright` 里因此是 `License: Unknown`） | 同左 | ✅ 两边都缺（同一个 TODO：§7 第 12 条） |
| 数据目录 | `~/.local/share/xjtu-toolbox` | 同一个 XDG 口径（在 Windows 上就是 `C:\Users\<你>\.local\share\…`） | ✅ |
| 出包 CI | `.github/workflows/desktop-linux.yml`（ubuntu-latest） | `.github/workflows/desktop-windows.yml`（windows-latest） | 两条独立、路径过滤重叠 |

逐项核下来只有一处**真的不一致**：图标那一格。它不是「配置写错了」—— 两边的 `iconFile` 都指对了
文件名，差的是 `windows.ico` 里那张**图本身**（Android 模板的绿机器人）。要不要把 Windows 那份也换成
同一套图标，见 `windows-delivery.md` §7 第 10 条。

## 7. 未验证项清单（**这一节是这份文档的重点**）

| # | 未验证的事 | 为什么没验 | 怎么才能验 |
|---|---|---|---|
| 1 | **`.deb` 真装一次**（`sudo dpkg -i` / `apt install ./…deb`：依赖解析、菜单项出现、卸载干净） | 本机只有一个 checkout，没在真机器上装过；装它要改这台机器的系统状态 | 一台一次性 Debian/Ubuntu（容器或虚拟机）：装 → 看应用菜单 → 卸 |
| 2 | **app-image / `.deb` 在真桌面环境里启动** | 本机没有显示服务器（`DISPLAY` 为空），也**没有 Xvfb**（`xvfb-run` 没装）⇒ 只能验到「出包成功 + skiko 要的 `libGL.so.1` 在系统里 + 运行时自带」 | 有桌面的机器（X11 与 Wayland 各一次），或 CI 里加 `xvfb-run` 跑一次冷启动 |
| 3 | ~~桌面项图标是我们自己的~~ ⇒ **已做**（更早那轮修 + 收尾轮在**真包**上复核） | 更早那轮 `linux.iconFile` 未设 ⇒ 包里是 Compose Desktop 的默认图标（§3.3 的 md5）；后来换成 `desktop/icons/linux.png`（应用自己的自适应矢量，§2.1）。收尾轮真跑一次 `:desktop:packageDeb` 后拆包：包里那张与仓库里那张 md5 都是 `37a6902e346ea027dd3f2948d346aeb0`（§3.4） | 剩下的是「真装一次看应用菜单里的观感」（第 1 条）与 HiDPI 放大（第 16 条） |
| 4 | **`linux.menuGroup` 在 `.deb` 这条路上有没有用** | 实测：jpackage 生成的 `.desktop` 里**没有**它（进分类的是 `appCategory` → `Categories=`/`Section=`）。它在 RPM 那条路上才有意义 | 真要「应用菜单里放在某个文件夹下」，得自己改 `.desktop` 或用 `xdg-desktop-menu --mode` 之类的后处理 —— 属下一个决定 |
| 5 | ~~`Maintainer: XJTU ToolBox <Unknown>` 是否可接受~~ ⇒ **已做**（更早那轮补 + 收尾轮在**真包**上复核） | `linux.debMaintainer` 没设时 jpackage 填的是 `<vendor> <Unknown>`。`apt`/lintian 会盯着这一栏 | 收尾轮拆包读到的是 `Maintainer: XJTU ToolBox <114236693+Triangle-GitHub@users.noreply.github.com>`（§3.4；`debMaintainer` 只给地址，机制与身份见 §2.2）。⚠️ 想换成「项目邮箱」是一行的事 —— **谁当维护者仍是人的决定** |
| 6 | **Wayland（XWayland）下的行为** | 见第 2 条（没有显示服务器） | 同第 2 条，Wayland 会话里单独跑一次 |
| 7 | **真·单文件 `.AppImage`** | §1：`TargetFormat.AppImage` 是 jpackage 的 app-image，不是单文件；单文件要 `appimagetool`（且依赖 FUSE） | 需要时加一条与 `packageTarGz` 同形的任务（app-image → `appimagetool`） |
| 8 | **`tar.gz` 解压后真跑** | 只验到「真打出来了 + 118.0 MiB」（它里面就是 app-image 那棵树） | 同第 2 条 |
| 9 | **ARM64 / 非 x86_64** | 本机是 `x86_64`，jpackage 出的是宿主架构（文件名 `_amd64`）⇒ 树莓派/ARM 服务器上没有包 | CI 加 `ubuntu-24.04-arm` 之类的 arm runner（jpackage 支持 aarch64） |
| 10 | **非 Debian 系的发行版** | `TargetFormat.Rpm` **没声明**（Fedora / RHEL 没有包） | 需要时加 `TargetFormat.Rpm`（Compose 支持），或只认「app-image / tar.gz 是通用那条路」 |
| 11 | **`.deb` 的 `Depends` 在别的发行版上对不对得上** | jpackage 按**宿主**的库名生成；本机 Ubuntu 26.04 给出来的是 `libasound2t64` / `libpng16-16t64` 这种新版名字（§3.2） | 在目标发行版（如 Ubuntu 22.04 / Debian 12）的 runner 上重新出一次包，或把 `Depends` 人工调宽 |
| 12 | **`copyright` 文件里的 `License: Unknown`** | 我们只配了版权行，license 那行是 jpackage 的默认值 —— 而我们**没有** EULA/license 文件（`desktop-port-plan.md` §7 把 EULA 列在待定项） | 补一份 license 文件 + `appResourcesRootDir`（Windows 那边的 `windows-delivery.md` 也把 EULA 列为待定） |
| 13 | **两条 CI workflow 真跑** | 仓库还没触发过 `desktop-linux.yml`。本机验到的：两条 YAML 解析都通过（`yaml.safe_load`，各 **11** 个 step）；收尾轮又把两条的护栏逐项对了一遍 —— `timeout-minutes: 60` · `actions/setup-java@v4`（`"21"` + `temurin`）· `gradle/actions/setup-gradle@v4` · `ANDROID_HOME`/`ANDROID_SDK_ROOT` 哨兵 · `if-no-files-found: error`，五项两份一致；不一样的只有平台必需的（`runs-on`、出包任务名、Linux 那条多一步 `chmod +x gradlew`、验证怎么跑）⇒ **没有需要补的缺口** | 推一次带 `desktop/**` 改动的 PR，看两条 job 的 artifact 与 `if-no-files-found`；两条里各自新增的自证步骤，**脚本本体**本机都跑过（Linux 那条见 §3.4、Windows 那条见 `windows-delivery.md` §8），没跑过的只是「在 GitHub 的 runner 上」这件事 |
| 14 | **jpackage 在本机之外的内存/时间** | 本机 7 GB 内存、`--max-workers=1`：出包 6 分 39 秒（更早那批）；收尾轮那次是 **4 分 28 秒**（配置缓存命中，只有一道 task 真跑，§3.4）。CI 的 runner 内存更宽，但同 checkout 上**不能**并行跑两套构建（§3.1 / §3.4 第 1 条） | CI 里看这一步的耗时；出现 `jvmJar` 那种「文件正被重写」的报错时，先确认是不是并行构建 |
| 15 | **更新骨架的 UI 调用点没接** | `:desktop` 的 `DesktopUpdate` 已经能调用，但 `:core` 设置页那一栏由 `DesktopSettingsHost.updateSection` 提供，桌面端现在给的是 `null`（`DesktopSettings.kt` 的 KDoc 里如实写着）。接线要改 `desktop/src/**` —— **不在这条任务的文件范围里**（这一轮只动 `desktop/build.gradle.kts`、`desktop/icons/**`、两条 workflow 与这两份交付文档），所以只记 TODO，没动代码 | 设置页那一轮：一个按钮 + 一句文案（`windows-delivery.md` §6/§9） |
| 16 | **图标在 HiDPI / 大尺寸下会不会发虚** | 只出了一张 256×256（与 `windows.ico` 里最大的那张同尺寸）；矢量源在仓库里，但本机没有显示服务器，量不了桌面环境把它放大到 512 的样子 | 有桌面的机器上装一次，看应用菜单/任务栏里的观感（与第 1、2 条一起验） |
| 17 | **真正会咬人的三个本机环境坑**（不进交付物，但重跑的人会碰） | (a) **并行构建**：会把别人的 `:core` / `:data` / `:desktop` 编译弄红或弄脏（§3.1 的 `:data:jvmJar` 竞态；§3.4 第 1 条那个 `Unresolved reference 'DesktopAgentScreen'` 就是另一个 run 的半成品）；(b) **`/tmp` 是按用户配额的 tmpfs**（`usrquota`）—— **配额先于容量用完**：`df` 还显 772 M 空闲，写 200 M 的文件在 49 M 处就 `EDQUOT`（§3.4 第 2 条）；(c) **jpackage 的暂存盘不认 `TMPDIR`**，它永远用 `/tmp/jdk.jpackage…` ⇒ 只指 `TMPDIR` 挡不住 (b) | 重跑前 `ps aux` 看一眼有没有别的构建；两个变量都给上（`TMPDIR=<大卷>` 管 `dpkg-deb` 的成员临时文件，`JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<大卷>` 管 jpackage 的暂存盘），并加 `--no-daemon` 让这次构建的 JVM 直接用这份环境 —— 可照抄 §3.4 那条命令。CI 的 runner 不需要（`/tmp` 够大、也没有并行） |

## 8. 门禁与测试数字（本轮实测）

数字都是这一轮在本机（Ubuntu 26.04.1 LTS · JDK 21 · `--max-workers=1`）跑出来的：

| 门禁 | 结果 |
|---|---|
| `:desktop:compileKotlin` | ✓（本轮走了三次：两次出包各一次、单独门禁一次） |
| `:desktop:test` | **76 通过 / 0 失败 / 0 跳过**（17 个测试类，`BUILD SUCCESSFUL in 2m 34s`） |
| `:desktop:packageDeb`（真跑） | ✓ 收尾轮（03:20，§3.4）**再真跑一次**：`xjtu-toolbox_5.1.1_amd64.deb` = 111,669,760 B、md5 `0c47a5df6c9d757696050fe2d49d6e05`（更早那批是 111,170,448 B —— 差的是这期间换过的应用代码） |
| `:desktop:packageAppImage`（真跑） | ✓ app-image = 204,922,515 B（与 `createDistributable` 同一个落点） |
| `:desktop:packageTarGz`（真跑） | ✓ `xjtu-toolbox-5.1.1-linux-x64.tar.gz` = 123,750,278 B |
| `:data:jvmTest` / `:core:jvmTest` / `:app:testDebugUnitTest` | **本轮没跑**：同一工作区里另有 run 正在搬 Agent 域（`app/` `core/` `data/` 都有它在途的改动；§3.1 那次 `:data:jvmJar` 竞态就是它引起的），跑出来的红绿指向的是它的在途代码，不是本轮改动 |
| `.github/workflows/desktop-linux.yml`（与 `desktop-windows.yml`） | 两条 YAML 解析都通过（`yaml.safe_load`，各 **11** 个 step）；收尾轮逐项对过两条的护栏，五项一致（见 §7 第 13 条）；**两条都没有在 GitHub 上真跑过**（§7 第 13 条） |

这份表里的数字跳了两批：前几行属于「桌面交付」那批（`packageAppImage` / `packageTarGz` 那两条），
收尾轮只**重跑了 `:desktop:packageDeb`**（§3.4）与它前面的 `:desktop:compileKotlin`，其余几道
**没有**重跑 —— 与上面 `:data:jvmTest` 那一行同一个原因：同一工作区里另有 run 在改 `desktop/src/**`
（收尾轮 02:47 那次 `:desktop:compileKotlin` 就撞在它的半成品上，报了 `Unresolved reference
'DesktopAgentScreen'`；它 02:52 把那个函数补上之后才编得过）。

## 9. 这一轮**没做**（范围之外，别误会）

1. **`.desktop` 的分类/菜单层级定制**（第 4 条）—— Linux 侧的图标这一轮做了（§2.1 / §3.3）；
2. **真·单文件 AppImage**（第 7 条）、**RPM**（第 10 条）、**ARM64**（第 9 条）；
3. **代码签名与 apt 仓库**（§5）—— 只出 artifact，不上传 Release；
4. **`serve` 模式的 Linux 服务化 / 自启 / systemd 单元**；
5. **Linux 的通知 / 托盘 / 文件关联**（`fileAssociation` 没用上）；
6. **凭据的 Secret Service 集成**：Linux 仍是「文件 + `0600`」（§5.1 接受这一档；`windows-delivery.md`
   §5.5 第 4 条把两端一起记着）。⚠️ 桌面端的 `DISPLAY` 之外，这一条是 Linux 交付面**最大的**安全缺口。

## 10. 怎么开 HTTPS（serve 模式，D11）

`docs/desktop-port-plan.md` §16.1 的 **D11**：serve 内置 HTTPS + 鉴权，开箱可启用。这一节只讲**怎么用**；
口径在 `docs/api-contract.md` §3 第 6/7 条，实现与判据在 `:server`（`ServeConfig` / `ServeTls` /
`ServeHttps`）与 `ServeHttpsTest`（**8** 例：`:server:test` 85 → **93**）。

**默认不变**：不给 `--https` 时 serve 与今天**逐字相同**（明文 HTTP + 令牌闸门，默认只监听 `127.0.0.1`）。
HTTPS 是**另一档形态**，它带来的三件事：`--host/--port` 变成 HTTPS 的地址与端口、令牌换来的
cookie 多一个 `Secure`、纯明文那一层只绑回环（外面看不到）。

### 10.1 一条命令：自签 + 起 HTTPS

```bash
# 在仓库根跑（`:server:run` 的工作目录已钉成仓库根，`--dist` 的默认值按它算）
# 证书不存在就**现生成一张自签**（JDK 的 keytool），然后起在 0.0.0.0:8443 上
sh gradlew :server:run --args="--https --host 0.0.0.0 --port 8443"
```

横幅里会多三行：`监听： https://0.0.0.0:8443`、证书库的绝对路径（并说明「本次刚生成的自签证书」
还是「已有的证书库」）、密码在哪儿。浏览器打开 `https://<那台机器的地址>:8443/`，
第一次会警告证书不可信 —— 点「继续访问」即可（自己签的证书没人替它背书，这条警告是必然的）。

证书与密码落在**数据根**下面（与访问令牌同一个根，§6 那张表的口径）：

| 文件 | 是什么 | 权限 |
|---|---|---|
| `~/.local/share/xjtu-toolbox/tls/serve.p12` | 证书库（PKCS12，含私钥 + 自签证书） | `0600` |
| `~/.local/share/xjtu-toolbox/tls/serve.p12.password` | 上面那个库的密码（随机生成） | `0600` |

⚠️ 两个文件都**不要**进仓库 / 截图 / 聊天。想换一张自签：删掉这两个文件再启动（与「换访问令牌 =
删 `serve.properties`」同一套口径）。

### 10.2 用自己那份证书

```bash
# PKCS12 或 JKS 都认；密码走环境变量（命令行上的密码会留在 ps 与 shell 历史里）
XJTU_TOOLBOX_TLS_PASSWORD='<你的库密码>' \
  sh gradlew :server:run --args="--https --host 0.0.0.0 --port 8443 --cert /etc/xjtu-toolbox/serve.p12 --cert-alias serve"
```

自己生成一份也可以（alias 与 SAN 按你的访问地址写）：

```bash
keytool -genkeypair -alias serve -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=你的域名或机器名" -ext "SAN=dns:localhost,ip:127.0.0.1,ip:192.168.1.5" \
  -storetype PKCS12 -keystore /etc/xjtu-toolbox/serve.p12
```

### 10.3 让浏览器不再警告（可选）

把证书库里的**证书**导出来（只有证书、不含私钥），装进系统/浏览器信任库：

```bash
keytool -exportcert -rfc -alias serve \
  -keystore ~/.local/share/xjtu-toolbox/tls/serve.p12 \
  -storepass "$(cat ~/.local/share/xjtu-toolbox/tls/serve.p12.password)" > serve.crt
# Firefox/Chromium 走各自的证书管理页导入；系统级可以：
sudo cp serve.crt /usr/local/share/ca-certificates/xjtu-toolbox-serve.crt && sudo update-ca-certificates
```

### 10.4 参数与失败语义（都会明确报错，**不会**回落成明文）

| 参数 | 作用 |
|---|---|
| `--https` | 开关：切成 HTTPS 形态（`--host/--port` 就是 HTTPS 的地址与端口） |
| `--cert <路径>` | 证书库（PKCS12/JKS）。省略 = `<数据根>/tls/serve.p12`，不存在就生成自签 |
| `--cert-alias <别名>` | 库里的别名，默认 `serve` |

- `--cert` / `--cert-alias` 给了却没给 `--https` ⇒ 参数报错（退出码 2）—— 不静默忽略；
- `--cert` 指的文件不存在 ⇒ 报错，**不**在那儿捂着生成、**不**回落成明文；
- 库读不了（密码没有/不对、不是 PKCS12/JKS、别名不在库里）⇒ 报错并指出下一步（退出码 2）。

⚠️ HTTPS 只解决「传输这一段」。**在 HTTPS 真正用起来之前，写 / 支付端点不要挂在公网口上**
（`docs/desktop-port-plan.md` §16.4 第二条风险）—— 那一条同时写在 `docs/api-contract.md` §3 第 7 条。
