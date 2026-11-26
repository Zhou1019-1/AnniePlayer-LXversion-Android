# 二进制产物与依赖溯源（主程序）

> 生成时间：2026-11-26 · 基线 commit：`9763fcf` · 覆盖 git 跟踪的全部 `.so` / `.jar`，共 15 个文件；资源图片与单测夹具另见 [六之二](#六之二资源图片与单测夹具非-so--jar)。
> 满足协作规则 §5.2.1：每个文件给出 **来源 / 版本 / SHA256**，自编产物额外给出 **构建脚本 + 补丁集 + 入库 commit**。
> 复核：见文末命令；不一致说明文件被改动但未同步本文件。

## 一、自编产物（ffmpeg 七件套）

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `app/src/main/jniLibs/arm64-v8a/libavcodec.so` | 1262.5 KB | `944bfaccf3f9fcfab6a98ec311386f4a3d6f41da8c650abd099acbf69300c2c0` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libavdevice.so` | 9.8 KB | `70d5e7ee279c102f234b8d3027a9a26d719e5e53d50a5a55e85ab75eb3329541` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libavfilter.so` | 164.3 KB | `4282cadca19a998f17c6edfe752a28b259078cd79ee7d5375aa2a086d24d42c4` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libavformat.so` | 826.6 KB | `0a0f17d47d0915ce2751951b851f0cbc69068f52bdc974fc782671591f816043` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libavutil.so` | 708.2 KB | `f823ceeb76298507dcbdbef5c7b3fc590f3367696b269b719b92ca17890bd188` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libswresample.so` | 96.1 KB | `eaa2847c5ecd2a9a0970e054c724f8de2b225d0377aca04ae64b1ef1c94dd65f` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libswscale.so` | 1132.1 KB | `cff3655f3fc3f20be726d4515df5981d2612795376f5e1ce7a0e789ed998d1bd` | `c4b43c5` |

- **来源**：ffmpeg 源码官方发行版 `ffmpeg-8.1.3.tar.xz`（<https://ffmpeg.org/releases/>），源码树不入库
- **版本**：8.1.3（`--disable-everything` 后仅启用音频编解码与所需 filter，见 `native/ffmpeg/configure.sh`）
- **构建脚本**：`native/ffmpeg/build-android.sh`（WSL + Linux NDK 27.1.12297006，`llvm-strip` 后入库，约 10 分钟）
- **补丁集**：
  - `native/ffmpeg/patches/dsdiffdec.c` —— 上游 8.1.3 只有 DSF、没有 DSDIFF demuxer
  - `build-android.sh` 内注入的 SAF 垫片（`av_set_saf_open` / `av_set_saf_fd`，加进 `libavutil`）
- **复核**：`bash native/ffmpeg/build-android.sh` 后与上表 SHA256 比对（脚本版本一致时产物应逐字节相同）

## 二、自编产物（Rust 解密运行时）

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `app/src/main/jniLibs/arm64-v8a/libtemari.so` | 658.5 KB | `b6270f31c449eaf818fb3d69e3c0728ffc7ef4fa4a57f97fa7bb1df4901416f7` | `d1144c9` |

- **来源**：本仓 `native/temari/`（crate `temari`，协议与解密运行时源自 <https://github.com/WorldObservationLog/Temari>）
- **版本**：`Cargo.toml` 中 `0.3.1`，工具链由 `native/temari/rust-toolchain.toml` 固定
- **构建脚本**：`native/temari/build-android.ps1`（Windows）/ `build-android.sh`；Gradle 的 `buildTemari` 会在源码变更时自动重编
- **复核**：重编后比对 SHA256

## 三、抽取的第三方二进制

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `app/libs/ffmpeg-kit-audio-8.1.9.jar` | 68.3 KB | `e1058a77e786809070aade9632fdc48b9094226ebab968473cd82fa9fd113076` | `888935a` |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | 1752.7 KB | `f9992c4ba6b7c5a716e3a202fceb1ce029d6a2b0605838ac6b3219f489dd7970` | `888935a` |
| `app/src/main/jniLibs/arm64-v8a/libffmpegkit_abidetect.so` | 29.6 KB | `5ba693b0255eaa5789d4b54d18351b85240657f2fbab390f2614279b58e51a95` | `888935a` |
| `app/src/main/jniLibs/arm64-v8a/libffmpegkit.so` | 456.2 KB | `6dcdbda20f5b7ebbcd83a0cdbc46de4d24365955ebf3d09eca26f34a77dc06aa` | `888935a` |

- **来源**：ffmpeg-kit Android **audio** 变体 **8.1.9**（<https://github.com/arthenica/ffmpeg-kit>）
- `libffmpegkit.so` / `libffmpegkit_abidetect.so` 是其 JNI 层，我们的脚本无法重建；`libc++_shared.so` 必须与它们同一次工具链产出
- `libs/ffmpeg-kit-audio-8.1.9.jar` 为同一版本的 Java 桥接 jar
- **许可**：LGPL，许可证文本随包入库（`app/src/main/res/raw/license_*.txt`）

## 四、第三方预编译库

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `app/src/main/jniLibs/arm64-v8a/liboboe.so` | 326.3 KB | `3f026ef84e9ca978723af18bd2b3bba2518579c1a8783358a0cb0c6d8373ad02` | `888935a` |
| `app/src/main/jniLibs/arm64-v8a/libtaglib.so` | 1005.8 KB | `b71745986f4333f948f1585e63bebace666bacd6f4b56027100356a6cd12468c` | `888935a` |

- `libtaglib.so`：TagLib JNI（Java 包名 `com.lonx.audiotag`），`proguard-rules.pro` 中整体 keep
- `liboboe.so`：Google **Oboe 1.11.0**（源码在 `app/src/main/cpp/thirdparty/oboe`，Apache-2.0；`Version.h` 中 `OBOE_VERSION 1.11.0`）

## 五、构建工具

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `gradle/wrapper/gradle-wrapper.jar` | 52.4 KB | `16caeaf66d57a0d1d2087fef6a97efa62de8da69afa5b908f40db35afc4342da` | `94b3733` |

- `gradle-wrapper.jar`：Gradle 官方 distribution 自带（版本见 `gradle/wrapper/gradle-wrapper.properties`）

## 六、全量清单

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `app/libs/ffmpeg-kit-audio-8.1.9.jar` | 68.3 KB | `e1058a77e786809070aade9632fdc48b9094226ebab968473cd82fa9fd113076` | `888935a` |
| `app/src/main/jniLibs/arm64-v8a/libavcodec.so` | 1262.5 KB | `944bfaccf3f9fcfab6a98ec311386f4a3d6f41da8c650abd099acbf69300c2c0` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libavdevice.so` | 9.8 KB | `70d5e7ee279c102f234b8d3027a9a26d719e5e53d50a5a55e85ab75eb3329541` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libavfilter.so` | 164.3 KB | `4282cadca19a998f17c6edfe752a28b259078cd79ee7d5375aa2a086d24d42c4` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libavformat.so` | 826.6 KB | `0a0f17d47d0915ce2751951b851f0cbc69068f52bdc974fc782671591f816043` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libavutil.so` | 708.2 KB | `f823ceeb76298507dcbdbef5c7b3fc590f3367696b269b719b92ca17890bd188` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | 1752.7 KB | `f9992c4ba6b7c5a716e3a202fceb1ce029d6a2b0605838ac6b3219f489dd7970` | `888935a` |
| `app/src/main/jniLibs/arm64-v8a/libffmpegkit_abidetect.so` | 29.6 KB | `5ba693b0255eaa5789d4b54d18351b85240657f2fbab390f2614279b58e51a95` | `888935a` |
| `app/src/main/jniLibs/arm64-v8a/libffmpegkit.so` | 456.2 KB | `6dcdbda20f5b7ebbcd83a0cdbc46de4d24365955ebf3d09eca26f34a77dc06aa` | `888935a` |
| `app/src/main/jniLibs/arm64-v8a/liboboe.so` | 326.3 KB | `3f026ef84e9ca978723af18bd2b3bba2518579c1a8783358a0cb0c6d8373ad02` | `888935a` |
| `app/src/main/jniLibs/arm64-v8a/libswresample.so` | 96.1 KB | `eaa2847c5ecd2a9a0970e054c724f8de2b225d0377aca04ae64b1ef1c94dd65f` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libswscale.so` | 1132.1 KB | `cff3655f3fc3f20be726d4515df5981d2612795376f5e1ce7a0e789ed998d1bd` | `c4b43c5` |
| `app/src/main/jniLibs/arm64-v8a/libtaglib.so` | 1005.8 KB | `b71745986f4333f948f1585e63bebace666bacd6f4b56027100356a6cd12468c` | `888935a` |
| `app/src/main/jniLibs/arm64-v8a/libtemari.so` | 658.5 KB | `b6270f31c449eaf818fb3d69e3c0728ffc7ef4fa4a57f97fa7bb1df4901416f7` | `d1144c9` |
| `gradle/wrapper/gradle-wrapper.jar` | 52.4 KB | `16caeaf66d57a0d1d2087fef6a97efa62de8da69afa5b908f40db35afc4342da` | `94b3733` |

## 六之二、资源图片与单测夹具（非 `.so` / `.jar`）

这两类文件不在 `AGENTS.md` §5.1 的二进制白名单内（白名单只列了 `.so` 与 guest 资产），
按 §5.1 的判据它们需要 owner 明确追加，或由本清单承担「来源 + 版本 + SHA256」的追溯义务。
本仓选择后者：逐文件列出，并已在 PR 中提出白名单补充申请。

### 应用资源图片（20 个，合计 0.9 MB，单文件最大 306.8 KiB）

均为随应用分发的图标与内置图片（其中 `qq_group_*.png` 是 QQ 群二维码图），由本应用作者自制，
不是第三方素材库文件。入库 commit `94b3733`。

| 文件 | 大小 | SHA256 |
|---|---|---|
| `app/src/main/res/drawable-nodpi/annie.jpg` | 57.5 KiB | `f927672639fce5ce723f70ff8d5ddd4585c4cf2d1a93472284376b6e974f1d9b` |
| `app/src/main/res/drawable-nodpi/group1.jpg` | 306.8 KiB | `d7dbf427cd0c72e7e28ed5ddcbdcb6990b410cc9e73c3c43016a9b9a361d9a1e` |
| `app/src/main/res/drawable-nodpi/group2.jpg` | 212.1 KiB | `71793bb8b3b3d153746d829179cce87895ecf2d9babc402288d8f0c120ec10cd` |
| `app/src/main/res/drawable-nodpi/qq_group_annie.png` | 75.8 KiB | `e8fbcaa19efaa06ee5b3c8322836faaa4e0b12e0fc6a6eba11361f538cfbab7d` |
| `app/src/main/res/drawable-nodpi/qq_group_hifi.png` | 73.4 KiB | `b73bfb4f70a90e6e994a61fbbbbb4bad3ad95fb187131c91f857884bba7556e3` |
| `app/src/main/res/mipmap-hdpi/ic_launcher.png` | 3.4 KiB | `8ff3b352011f263f5584726e47f1cfdf31b9a368c0894cdd033688e34d6dfeac` |
| `app/src/main/res/mipmap-hdpi/ic_launcher_foreground.png` | 6.4 KiB | `82abf4eabbe0060500cd5c6e04c0fabb2cb3a7ec6d2a95e88798fa163351637e` |
| `app/src/main/res/mipmap-hdpi/ic_launcher_round.png` | 6.0 KiB | `e2bc7ea7f6fc98c594a800c016977e46bfe7cbc06195d0e495d2a3072a8c8b4c` |
| `app/src/main/res/mipmap-mdpi/ic_launcher.png` | 2.0 KiB | `9428aac576a3a2e99e479c8e3ee6f43da170b3aae4091fcdd5aae2e2588a6580` |
| `app/src/main/res/mipmap-mdpi/ic_launcher_foreground.png` | 3.7 KiB | `fa5a64abc3873f830e3005b36a521f799d2d069a0163b672ca68ae23e619e589` |
| `app/src/main/res/mipmap-mdpi/ic_launcher_round.png` | 3.6 KiB | `f8e4559bb3d71bc8b7caf5432ff6c0ee83eff157d8c00e57fe353cbae9c303cf` |
| `app/src/main/res/mipmap-xhdpi/ic_launcher.png` | 4.9 KiB | `7048b0d91e860883d12f18b0d3bd89e26561dd1eb55f9d73b9f5b2b8fd71b7f2` |
| `app/src/main/res/mipmap-xhdpi/ic_launcher_foreground.png` | 9.8 KiB | `2f906b30d1421b9649e5553acd7f180eeee33c006ef53640af5bedf925199ca7` |
| `app/src/main/res/mipmap-xhdpi/ic_launcher_round.png` | 9.0 KiB | `b9f34e998e54fca8bdc164d29913c7880eac7802c959a8aaca46caa8ad11c771` |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` | 8.4 KiB | `7a4b690211271acff02825b3bec28b860702b43925db7d7109db3851c9911dc8` |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png` | 17.4 KiB | `ce82700f3bff0c97fbe31111595dd8596580b81861a68fd0094de0a4e622c519` |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png` | 15.2 KiB | `ea71a14154154ab019669fecd8ab2a17412cee425f80aa969cabf4b2a6215ebe` |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | 12.4 KiB | `b4d26381498c7135b05608446d9b26ec80050ae5a0e8c6e4eb145711c0635544` |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png` | 27.1 KiB | `ee1e48273fdab17eacb017fe340cdcf37f1ac4562c21dac0e3b22132f5a4cbb7` |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png` | 22.2 KiB | `5f27e27a1b56c202a50156ec910895a498b761f6903fbad3bb16df65dfdeee3a` |

### 单测夹具（10 个，合计 0.3 MB，单文件最大 86.5 KiB）

入库 commit `888935a`。全部是**本机生成的 1 秒单声道样本**，不含任何第三方录音或唱片内容：
44.1 kHz（`fix.opus` 为 48 kHz），`fix.m4a` / `fix-fast.m4a` / `fix.flac` / `fix.mp3`
带一张 mjpeg 封面用于验证内嵌图读写。用途见 `app/src/test/.../AnnieTag*Test.kt`。

> 已知限制：生成这些夹具的脚本没有入库，只能靠 SHA256 固定现状（见 PR「已知限制」一节）。

| 文件 | 大小 | SHA256 |
|---|---|---|
| `app/src/test/resources/fixtures/fix-fast.m4a` | 10.0 KiB | `7df08aac2dfa0c9519d120cc0e5cc0aa1791c5e837da4228887575ebce673894` |
| `app/src/test/resources/fixtures/fix.aiff` | 86.5 KiB | `195212d2e79d52c1a1f04751c2eaf9c1b7ffa0229c7d878352148da6a5b21cf7` |
| `app/src/test/resources/fixtures/fix.flac` | 23.7 KiB | `7d47a0f025f9e3507dfc9778ebb7198d4e868756d0afce6ea004ae85ec9142b4` |
| `app/src/test/resources/fixtures/fix.m4a` | 10.0 KiB | `2827fcdf6271fc85a49f107f11ad8004bc27599ba36ecf1737833847f65ca80d` |
| `app/src/test/resources/fixtures/fix.mp3` | 8.9 KiB | `2ac415dc0d7d3ddb79f292bc1221834057909b1e1f4bff70d5a7d0c4773889ef` |
| `app/src/test/resources/fixtures/fix.ogg` | 5.3 KiB | `37df4a49306b967eeaf513dfe1c9047c72b4756bf1a8ef03c2b704c29a862fe8` |
| `app/src/test/resources/fixtures/fix.opus` | 9.5 KiB | `08e12b295ba27d9af48c23358b82bb415b8d3b643e7c2a95be24b90a53c10cc1` |
| `app/src/test/resources/fixtures/fix.tta` | 14.6 KiB | `9e643ab50d0c4bbf448a51619efc2c7fa2e357e8a009f5fd69229b4ee4b6aabf` |
| `app/src/test/resources/fixtures/fix.wav` | 86.3 KiB | `bcec3cc0bfa8e349171a057b55f366197aec1d7a28164d3793a3bab3b4d26ea4` |
| `app/src/test/resources/fixtures/fix.wv` | 23.0 KiB | `dbd2b24c513c5b79622a26d4deb60d720133d7abcf658b0fe68b8980b4ab6900` |

另有两个**文本**夹具不入此表：`fixtures/kuwo_hotpage.xml`、`fixtures/kuwo_tuijian.xml`
（酷我榜单响应样本，纯文本，供 `KuwoHotPage` 解析测试）。

## 七、复核命令

```powershell
git ls-files android-native/app android-native/gradle |
  Where-Object { $_ -match '\.(so|jar)$' } |
  ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLower() + '  ' + $_ }
```

图片与夹具（第六之二节）：

```powershell
git ls-files android-native/app/src/main/res android-native/app/src/test/resources |
  Where-Object { $_ -match '\.(png|jpg|m4a|aiff|flac|mp3|ogg|opus|tta|wav|wv)$' } |
  ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLower() + '  ' + $_ }
```