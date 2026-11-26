<div align="center">

# 安妮播放器 · Android（AnniePlayer for Android）

**Android 上的 HiFi 音乐播放器：本地曲库 × 七个在线平台 × 独占输出与 DSD**
Kotlin + Jetpack Compose · Media3 · 自编 ffmpeg · 自研原生输出通路与本地解码

[![license](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![platform](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3ddc84)](#下载安装)
[![abi](https://img.shields.io/badge/ABI-arm64--v8a-orange)](#下载安装)
[![version](https://img.shields.io/badge/version-1.0.0-lightgrey)](#版本与发布)

**[兄弟项目：Windows / macOS 桌面版](https://github.com/Zhou1019-1/AnniePlayer-LXversion) · [功能总览](#功能总览) · [常见问题](#常见问题)**

交流 Q 群 **1128065156**（安妮播放器专属群）· **1023637098**（真无损 HiFi 音乐发烧友群）

</div>

> 本仓库同时托管**两个可以独立安装的 Android 应用**：主播放器 `AnniePlayer`，以及为某些区域独占串流服务提供本机代理的 `WrapperLite`。两者工程互相独立、APK 分别产出；只放本地曲库的话装第一个就够。
>
> 除「致谢」中的第三方库与外部生态外，本仓库代码全部由本仓作者自行编写。

## 两个应用

| | 应用 | 作用 | 是否必需 |
|---|---|---|---|
| 🎵 | **安妮播放器（AnniePlayer）** | 主程序：本地曲库、七个在线平台、播放与输出、歌词、下载、遥控 PC 端 | 必需 |
| 🧩 | **WrapperLite** | 可选件：在手机上跑一台极小的虚拟机，把某种区域独占串流服务的接口代理到 `127.0.0.1:12341` | 只在你需要那个服务时才装 |

## 核心卖点

- 🎧 **三条原生输出通路**：系统通路（Media3 → AudioTrack）、AAudio 独占、自研 USB Audio 直通；按设备能力自动降档，界面里显示**真建轨实测**的结果而不是猜
- 💿 **DSD 全链路**：`DSF` / `DFF` 解析与标签读写、DSD→PCM 抽取（r8brain 重采样，保留源率）、DSD 直通与 DoP 打包通道就位，实际可用性由设备与 ROM 决定
- 🔓 **本地解码不依赖系统**：八种容器自研流式解码（含 APE、DSD 这类系统不支持的格式），边解边播、可拖动进度
- 🏷️ **字节级标签引擎**：七种容器的标签读写全部自己实现（不动一个音频字节），写完可用 PC 端 `ffprobe` 反向核对
- 🌐 **七个在线平台**：四个来自洛雪音乐生态（插件 JS + QuickJS 执行），三个是需自有订阅的串流服务（区域独占高解析 / 商店型无损 / 日系）
- 🎛️ **播放策略可验证**：输出矩阵、降档链、独占时钟、字节预算装载控制都是纯函数并带单测，改一行就能看出会影响哪条链路
- 🎨 **沉浸式界面**：封面取色驱动的极光背景、**专辑 / 歌手 / 歌单 / 本地详情页的动态封面 Hero**、页面色调过渡、逐字歌词滚动
- 📺 **遥控 PC 端**：局域网自动发现 + 配对码，手机当遥控器点播桌面端曲库
- 🧰 **工具链**：日志诊断、数字链路自检、缓存分类清理、下载队列、批量下载、歌单解析、元数据在线匹配

## 七个在线平台

| 平台 | 接入方式 | 能做什么 |
|---|---|---|
| 洛雪生态 A / B / C / D（四个） | 导入洛雪插件（JS → QuickJS 执行） | 搜索、详情、专辑、歌手、歌单、榜单、播放、下载、歌词 |
| 区域独占高解析串流 | 账号登录 + 本机代理服务（WrapperLite） | 搜索、详情、专辑、歌手、歌单、播放（HLS 分片，本地解密）、下载、逐字歌词 |
| 商店型无损串流 | 用户自填 Token | 搜索、发现页、标签页、专辑/歌手详情、播放、下载、歌词翻译 |
| 日系流媒体 | 官方票据接口 + 账号管理 | 搜索、专辑/歌手、播放、下载 |

> 四个洛雪生态平台 + 三个订阅型串流服务 = **七个在线平台**。
>
> 全部在线能力都要求**你自己的账号 / 订阅 / 插件**。本仓库不分发任何凭据，也不内置解析服务；洛雪生态的播放与下载统一由插件解析，内置直链接口已整条删除，不做跨源兜底。

## 功能总览

### 主页与发现

- 四个 tab：**主页 / 新发现 / 推荐 / 排行榜**
- 主页货架：多平台推荐货架按平台聚合，卡片可横向滑、可"查看全部"进独立页
- 新发现：按时间线组织的发行内容，可分房间浏览
- 推荐：按标签/风格分组的发现流，支持"查看全部"与标签详情
- 排行榜：多平台榜单入口 → 榜单详情页（可直接播放/批量下载整榜）

### 搜索

- 全局搜索（歌曲 / 歌手 / 专辑 / 歌单），**多源切换**（⇄ 面板选平台）
- 结果多选态：批量加入歌单、批量下载、全选/反选
- 搜索态与主页共享一套浮层与返回优先级（不是独立 Activity）

### 播放页

- 全屏播放页：封面 → 背景取色（极光/渐变）、动态封面、页面色调过渡
- 歌词：逐行 / **逐字**、翻译双轨、字号可调、歌词设置面板、居中滚动
- 设备面板：实时显示当前输出通路、采样率、位深、独占状态
- 播放控制：进度拖拽（带目标位置预览）、播放模式、下一首加入、队列管理
- 媒体会话：通知栏 / 蓝牙 / 耳机按键 / 锁屏控制，前台服务保活

### 详情页

- 覆盖：在线专辑 / 歌手 / 歌单、榜单房间、新发现房间、本地专辑 / 本地歌手 / 自建歌单
- **Hero 动态封面**：有动态封面就用动态封面，随滚动渐隐（`MotionHeroScaffold` + `heroCoverFade`）
- 页内动作：整张/整单播放、批量下载、多选、加入歌单、跳歌手/专辑
- 页内 tab（如歌手的「全部歌曲」）作为页内形态回退，不额外压栈
- 全屏音质面板交给宿主渲染，不会被迷你播放条压住

### 本地曲库

- 五个入口：**全部歌曲 / 歌手 / 专辑 / 文件夹 / 最近播放**，带字母索引与快速滑动
- 扫描规则四项可调：排除文件夹（可恢复）、跳过短音频、跳过隐藏文件夹、尊重 `.nomedia`；另有目录索引缓存
- 标签编辑：标题、艺术家、专辑、专辑艺术家、年份、音轨号、碟号、风格、作曲、注释/版本、版权、封面、歌词
- 元数据在线匹配：多平台候选 → diff 预览 → 落盘（含封面下载、歌词抓取）
- 歌词导入：逐字 / 逐行两种形态，支持"选完就走"的批量合并表单
- 删除与恢复、按文件夹浏览、按编码/时长/大小/路径排序
- 本地解码与标签全部自研，支持格式见下表

### 收藏与歌单

- 收藏库分区：**我喜欢 / 艺人 / 专辑 / 播放列表 / 最近添加**，并区分"流媒体 / 本地"两个来源域
- 自建歌单：新建、改名、换封面（取色）、排序、批量导入歌曲
- 歌单导入：粘贴链接或 ID → 解析成自建歌单（歌单解析页）
- 平台歌单收藏：收藏后落成本地清单，不依赖平台在线状态

### 下载

- 下载任务队列：下载中 / 已暂停 / 失败 / 已取消 四态，支持暂停、取消、重试
- 音质选择：单曲下载与批量下载各一套面板，档位按平台能力列出
- 保存目录走 SAF，可随时更换或清除
- 落盘自动写入全套标签 + 封面 + 歌词；可选写入逐字歌词（增强 LRC）、保存 `.lrc` 文件、写入翻译歌词

### 输出与音质

- **输出能力（实测）**：真建轨探测能拿到多高的采样率、哪种编码能建 offload 轨、USB 描述符读到了什么
- USB 独占 / AAudio 独占 / 蓝牙链路三块独立显示状态与失败原因，失败档位会被记住并降档
- **DSD 输出**独立页面：模式可用性判断、上限推导、按实测能力给出候选
- **调音**：三段式引擎（EQ / 参数均衡 / 音效档），三个引擎的数值各自保留，切换即恢复
- 响度归一化（读 ReplayGain 标签调整增益）
- 强制按源采样率输出（不做任何预转换，代价写清在设置页）

### 工具箱

- **遥控 PC 端安妮播放器**：局域网自动发现、4 位配对码、手动地址、正在播放控制、曲库点播
- **日志诊断**：按时间倒序展示运行日志，可刷新/清空（不按级别过滤，避免漏关键行）
- **数字链路自检**：native 库不可用时明确报"跳过"而不是"通过"
- 缓存分类清理（按类别列出体积，勾选后清理）、电池优化白名单、存储权限管理

### 其他

- 播放现场持久化：重启恢复队列与进度
- 菜单抽屉：区域独占串流服务、商店型凭据、日系账号、落雪插件管理、文件扫描、下载任务、清除缓存、更多设置、PC 版入口、联系我们、检查更新
- 检查更新：每次启动检查 + 手动检查，只比 `versionCode`

## 本地格式支持

| 容器 | 播放（自研流式解码） | 标签读写 | 备注 |
|---|---|---|---|
| FLAC | ✅ | ✅ | 含 PICTURE 块大端处理 |
| MP3 | ✅ | ✅ | ID3v2 / APEv2 双写 |
| M4A / ALAC | ✅ | ✅ | MP4 box 级写入 |
| Ogg Vorbis / Opus | ✅ | ✅ | 整页 CRC 重算 |
| APE | ✅ | ✅ | APEv2 |
| WAV (RIFF) | ✅ | ✅ | ID3 chunk |
| DSF / DFF | ✅ | ✅ | DSDIFF 补丁在自编 ffmpeg 内 |
| AAC（裸流 / ADTS） | ✅ | — | |

## 音频架构

```
Media3 ExoPlayer
   ├─ 系统通路   AudioTrack（按源率建轨；是否被系统重采样由设备决定）
   ├─ AAudio 独占 EXCLUSIVE（回读实际采样率，失败自动降档并记住）
   └─ USB Audio 直通（自研 UAC：按端点能力协商，逐字节校验）
DSP 链：r8brain 重采样 → 声道矩阵 → 音量（独占通路下由应用侧施加）
DSD：原生解析 → PCM 抽取（保留源率）→ 或 DoP 打包成 PCM 帧
```

输出侧统一抽象：`OutputPort` / `AnnieAudioSink` / `SinkResolver` / `OutputMatrix` / `OutputPlan`，
把"想要什么"与"设备能给什么"分开算，最后一律经矩阵裁决，再由 sink 执行。

## 更新机制

- **每次启动都检查**；发现新版本且屏幕上没有其他浮层时自动弹窗
- 菜单 → 关于 → **检查更新** 可随时手动检查
- 点「跳过此版本」后同一构建号不再自动弹，出现更高构建号会重新提示
- 比较**只看 `versionCode`**（纯数字单调递增），`versionName` 仅展示
- 用 ETag 条件请求压住请求量（304 不计入限额），失败静默且下次启动重试

发布侧需在 Release 正文写一行构建号：

```markdown
versionCode: 2

## 更新内容
- 修复 xxx
- 新增 xxx
```

缺这一行时客户端不猜，明确提示"最新发布未标注 versionCode"。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.4 · Jetpack Compose（BOM 2026.06.01）· Material 3 · 自绘图标 |
| 编译 | AGP 9.1 · compileSdk 36 · minSdk 24 · NDK 27.1.12297006 · 仅 arm64-v8a |
| 播放 | Media3 1.4.1（ExoPlayer / HLS / MediaSession / OkHttp datasource） |
| 原生层 | C++ / CMake：Oboe / AAudio / UAC 直通 / r8brain / 自编 ffmpeg 8.1.3 / TagLib |
| 解密 | Rust（Temari：区域独占串流分片解密，Gradle 自动交叉编译，产物入库） |
| 脚本 | QuickJS（洛雪插件执行环境） |
| 网络 / 序列化 | OkHttp 4.12 · kotlinx.serialization |
| 图片 | Coil 2.7 |

## 工程结构

```
android-native/                     主程序（AnniePlayer）
├── app/src/main/java/.../kt/
│   ├── core/audio/                 输出通路、独占、DSD、DSP、DAP 直通、自编 ffmpeg 解复用
│   ├── core/routing/               页面栈与返回优先级（纯函数 + 单测）
│   ├── core/state/                 UiState / PlayerState / 设置与各面板状态
│   ├── core/util/                  歌词解密、多歌手拆分、封面取色、下载队列
│   ├── data/sources/               七个平台适配 + 主页/歌词/榜单/更新等用途包
│   ├── data/local/                 扫描、曲库索引、标签编解码、八种容器流式解码
│   ├── data/favorites/             收藏与自建歌单持久化
│   ├── features/                   主页、搜索、曲库、播放、收藏、下载、工具、设置
│   └── widgets/                    抽屉、弹窗、面板、图标、动效
├── app/src/main/cpp/               CMake 原生层
├── app/src/test/                   JVM 单测（解析 / 编解码 / 矩阵 / 路由）
├── native/ffmpeg/                  自编 ffmpeg（裁剪到音频，含 DSDIFF 补丁与 SAF 垫片）
├── native/temari/                  Rust 解密库与构建脚本
└── wrapper-lite-app/               第二个应用：WrapperLite（独立 Gradle 工程）
```

## 下载安装

**主程序**

- 系统要求：**Android 7.0（API 24）及以上**，仅 **arm64-v8a**
- 从 [Releases](https://github.com/Zhou1019-1/AnniePlayer-LXversion-Android/releases) 下载 APK 安装
- 覆盖安装不丢曲库、收藏、歌单与设置；卸载会清空应用私有数据
- 正式包由维护者签名，**不同签名不能互相覆盖安装**（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），换签名＝必须卸载重装

**WrapperLite（可选）**

- 独立 APK；首启把 guest 资产解包到私有目录（约 107 MB：系统镜像 62 MB + 依赖库 43 MB + firmware 1.6 MB）
- 需要后台运行 + 电池优化白名单，否则系统会冻结虚拟机
- 常驻通知栏服务通知，登录（含两步验证）后提供本机服务

## 开发与构建

**环境**：JDK 17 · Android SDK 36 · NDK 27.1.12297006 ·（重编 ffmpeg 需 WSL + Linux NDK）

```powershell
# workdir: android-native
.\gradlew.bat :app:compileDebugKotlin --console=plain      # 改完必跑
.\gradlew.bat :app:testDebugUnitTest --console=plain       # 改完必跑
.\gradlew.bat :app:assembleDebug --console=plain
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- **Temari（Rust）**：`buildTemari` 挂在 `preBuild`，源码未变自动跳过，产物 `libtemari.so` 入库
- **ffmpeg**：`native/ffmpeg/build-android.sh`（WSL + Linux NDK，约 10 分钟）产出七个 `libav*/libsw*.so`，产物同样入库，克隆后直接出包
- **release 签名**：必须在 `android-native/keystore.properties` 提供 `storeFile` / `storePassword` / `keyAlias` / `keyPassword`。缺这个文件时 `assembleRelease` **直接失败并给出提示**，不会回退到 debug 签名；本地调试请用 `assembleDebug`
- **二进制溯源**：所有入库的 `.so` / `.jar` / 运行时资产逐个记录来源、版本与 SHA256，见 [`android-native/BINARY-PROVENANCE.md`](android-native/BINARY-PROVENANCE.md) 与 [`wrapper-lite-app/BINARY-PROVENANCE.md`](android-native/wrapper-lite-app/BINARY-PROVENANCE.md)
- **WrapperLite**：独立工程，`cd wrapper-lite-app && .\gradlew.bat assembleDebug`
- 内存吃紧的机器请单任务跑（`--max-workers=1`），并行会把提交内存打满

## 版本与发布

- `versionCode` 每次发版 **+1**，`versionName` 自由填写（仅展示）
- 流程：分支 → PR → 审核合并 → 打 tag → 签名的 Releases 产物
- 只通过 GitHub Releases 分发，不做渠道包

## 常见问题

**为什么是两个 App？** 主程序不做区域限制的绕过，那部分能力由独立的 WrapperLite 承担。只听本地音乐完全不需要第二个。

**独占输出一定生效吗？** 不一定。能否拿到独占流、能建多高采样率由 HAL 与 ROM 决定；应用实测后把结果写在「输出能力」页，失败档位记住并降档，不假装成功。

**为什么每次启动都检查更新？** 之前是 24 小时节流，结果"装了旧版却不知道有新版本"。现在每次启动都查，用 ETag 兜住请求量。

**装了旧包能直接升级吗？** 只有同签名可以覆盖安装，换签名必须卸载重装。

**歌词/封面乱码或缺失？** 先跑「重新扫描」重建索引；仍有问题请带文件名与来源反馈。

## 反馈与协助

- 需求与 Bug：Q 群 **1128065156**
- 代码问题：直接开 Issue 或 PR，附「改了什么 / 怎么验证 / 已知限制」
- 提交前跑完编译与单测，PR 里写真实结果（没跑就写没跑）

## 致谢

| 项目 | 用途 |
|---|---|
| **洛雪音乐（LX Music）** | 在线音源生态与插件格式，四个平台的解析能力来自它 |
| **[安妮播放器桌面版](https://github.com/Zhou1019-1/AnniePlayer-LXversion)**（章鱼科技 / 无敌章鱼哥） | 兄弟项目：界面语言、交互规范与功能基线 |
| **[WorldObservationLog/wrapper](https://github.com/WorldObservationLog/wrapper)** | 本机代理服务（WrapperLite）的协议与实现来源 |
| **[WorldObservationLog/Temari](https://github.com/WorldObservationLog/Temari)** | 区域独占串流的分片解密运行时（Rust），本仓以其为解密库 |
| ffmpeg / ffmpeg-kit | 解复用、部分解码、标签与封面抽取 |
| r8brain | 高质量采样率转换 |
| Oboe / AAudio | 低延迟原生输出 |
| TagLib · jflac | 标签与无损解码辅助 |
| QuickJS（quickjs-kt） | 洛雪插件执行环境 |
| Media3 / Compose / OkHttp / Coil / kotlinx | 基础框架 |

## 许可证与免责

- 代码以 **GPL-3.0** 发布，见 [LICENSE](LICENSE)
- 本软件仅供学习交流使用，**完全免费**，不涉及任何商业用途；所有在线资源请在下载后 24 小时内删除
- 在线能力依赖你自己的账号、订阅与插件；请遵守所使用服务的条款，本项目不分发、不破解任何付费内容
- 使用本软件产生的任何后果由使用者自行承担
