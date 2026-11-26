# 二进制产物与依赖溯源（主程序）

> 生成时间：2026-11-26 · 基线 commit：`9763fcf` · 覆盖 git 跟踪的全部二进制，共 15 个文件。
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

## 七、复核命令

```powershell
git ls-files android-native/app android-native/gradle |
  Where-Object { $_ -match '\.(so|jar)$' } |
  ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLower() + '  ' + $_ }
```