# 二进制产物与依赖溯源（WrapperLite）

> 生成时间：2026-11-26 · 基线 commit：`9763fcf` · 覆盖本工程 git 跟踪的全部二进制，共 134 个文件。
> 满足协作规则 §5.2.1：每个文件给出 **来源 / 版本 / SHA256 / 入库 commit**。本仓为 Android 仓，运行时资产随源码入库已获 owner 批准。

## 零、上游项目

本应用是 **wrapper** 项目在 Android 上的 QEMU 封装：

| 项 | 值 |
|---|---|
| 项目 | [WorldObservationLog/wrapper](https://github.com/WorldObservationLog/wrapper)（`apple-music-wrapper`） |
| 使用分支 | 默认分支 **`lite`**（另有 `main` / `arm64`） |
| 许可 | **MIT**（上游 `LICENSE`） |
| 相关上游路径 | `qemu/build.sh`（生成内核 + initramfs）、`qemu/mkdata.sh`（生成 64 MiB ext4 数据盘）、`qemu/init`（guest 启动脚本）、`rootfs/system/`（AOSP 库副本）、`wrapper-lite-qemu.cpp`（宿主侧启动器） |
| 上游发布产物 | `wrapper.x86_64.latest`（`Wrapper.x86_64.latest.zip` 46.6 MB）、`wrapper.arm64.latest`（49 MB）、`wrapper-qemu`（`wrapper.qcow2` 332.9 MB） |

本仓与上游的差异：上游在 **x86_64 主机**上用宿主发行版的内核与 busybox 现场生成 guest 资产；本应用把这套 guest 资产固定下来，在手机里用 **Termux 的 `qemu-system-x86-64-headless`** 运行，并配套使用 Termux 的 aarch64 运行库与 QEMU 固件。

依赖来源分布（一句话）：**guest 资产 = 上游 `qemu/build.sh` + `mkdata.sh`；QEMU 可执行文件 + 84 个运行库 + QEMU 固件 = Termux 包。**

## 一、QEMU 可执行文件

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `wrapper-lite-app/app/src/main/jniLibs/arm64-v8a/libqemu_x86_64.so` | 25496.9 KB | `f6448c2cad77fc89b0a587fdd45b7acee9b210809b8f90e3a6ae2ae494defedb` | `3ad15fd` |

- **来源**：**Termux 包 `qemu-system-x86-64-headless`**（Termux 官方仓库 termux-packages 构建的 `qemu-system-x86_64`），拷出后改名 `libqemu_x86_64.so` 放入 `jniLibs`，靠 `useLegacyPackaging` 解压到 `nativeLibraryDir` 取得 exec 权限
- **版本**：库内 `QEMU emulator version 11.0.3` / `Copyright (c) 2003-2026 Fabrice Bellard`；手机当前安装的包为 `1:11.1.2`（库是更早一次安装的拷贝，与 OpenSSL / curl 的版本漂移同理）
- **证据**：二进制内含 `/data/data/com.termux/files/usr/lib`、`.../usr/tmp` 以及 **`/home/builder/.termux-build/qemu-system-x86-64-headless/src/…`**（Termux 打包 CI 的构建目录）；ELF64 · `e_machine = 0xb7 (AARCH64)` · 仅链接 bionic 的 `libc.so` / `libdl.so` · 内含 `slirp`
- **记录更正**：先前一度标为「作者交叉编译（自编）」，依上述证据改为 **Termux 包**，不再有「来源不明」或「构建脚本待补」

## 二、guest 运行时资产

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `wrapper-lite-app/app/src/main/assets/guest/data.empty.dat` | 64.2 KB | `3253b145c8fa55dd65267e6d3ec8df8e95c1f47db9aa0cb7589ba1f68336137e` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/guest/initramfs.dat` | 48859 KB | `488a16361652d2cd179c2333f95967a8cddefcb6a3c5e879747d02ee7a65b1ad` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/guest/vmlinuz` | 14778.4 KB | `ebb569805ab12818a57c79a578df576faca23b749dc379538087af430519986a` | `3ad15fd` |

**vmlinuz（guest 内核）**

- **来源**：由上游 `qemu/build.sh` 从**构建主机的 `/boot/vmlinuz-*`** 拷出（脚本会打印 `kernel: ... (版本号)`），本仓这份取自一台 Ubuntu Azure 主机，故为 `6.8.0-1064-azure`；证据是同一 initramfs 内 `e1000.ko` / `qemu_fw_cfg.ko` 的 `vermagic=6.8.0-1064-azure SMP mod_unload modversions`
- **版本**：**6.8.0-1064-azure**（Ubuntu 24.04 系 azure 内核，`linux-image-6.8.0-1064-azure`）
- 未打自定义补丁；重编命令：上游 `qemu/build.sh`（可用 `KERNEL_SRC=/path/to/vmlinuz` 指定内核）

**initramfs.dat（guest 根文件系统）**

- **形态**：gzip 压缩的 cpio（magic `1f 8b 08`），解压后 118.8 MB / 137 个条目
- **内容**：`init`、`bin/busybox`（`busybox-static 1:1.30.1-7ubuntu3.1`，证据 `usr/share/doc/busybox-static/changelog.Debian.gz` 首行，Ubuntu jammy-security）、内核模块、`system/lib64/` 下的 AOSP 库副本（bionic 兼容层）、`usr/share/zoneinfo`；用户态依赖库见第三节（取自 Termux）
- **生成方式**：上游 `qemu/build.sh` —— `apt-get download busybox-static` 解包 + `rootfs/` 打包 + `install qemu/init` + 拷入 `qemu_fw_cfg` / `virtio*` / `e1000` / `ext4` 等内核模块 + `find . | cpio -o -H newc | gzip`（默认 gzip，也支持 lz4 / zstd）
- **版本**：Ubuntu 22.04（jammy）的 busybox-static `1:1.30.1-7ubuntu3.1` + `6.8.0-1064-azure` 内核模块 + 上游 `rootfs/system/`（AOSP bionic 库，见上游 `rootfs/system/lib64` 98 个文件）+ Termux aarch64 运行库（第三节）

**data.empty.dat（guest 可写数据盘模板）**

- **形态**：gzip 单文件（magic `1f 8b 08`，内部文件名 `data.empty…`），首次启动由 `AssetsInstaller` 解压/复制为 guest 的 `data.img`
- **内容**：解压后即 `assets-src/data.empty.img` —— ext4 文件系统，label `wrapperdata`，64 MiB，block 4096，16384 blocks，compat features `0x0000046b`
- **生成方式**：上游 `qemu/mkdata.sh` —— `truncate -s 64M data.img` + `mke2fs -q -t ext4 -d rootfs/data -F data.img`，本仓在生成时加了 `-L wrapperdata` 卷标；随后 gzip 成 `data.empty.dat`

## 三、guest 依赖库（84 个，取自 Termux aarch64 包）

这 84 个 `.so` 是 **Termux**（Android 上的 Linux 环境）安装的 aarch64 二进制，直接随包入库，未重编译。判据（全部为二进制内字符串实测）：

- 每个库都含 `/data/data/com.termux/files/usr/lib` 前缀
- 目录内含 Termux 自有的 Android 兼容层：`libandroid-support.so`、`libandroid-shmem.so`、`libandroid-execinfo.so`、`libandroid-sysv-semaphore.so`
- 链接目标是 bionic（`libc.so` / `liblog.so`），不是 glibc（无 `libc.so.6` / `GLIBC_2.x`）
- 因此 guest 侧是「QEMU + bionic 兼容运行环境」，而不是完整的 Linux 发行版用户态；`system/lib64/` 里的 AOSP 库副本服务于这一层

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `wrapper-lite-app/app/src/main/assets/lib/libandroid-execinfo.so` | 33.9 KB | `778fa9eaf0a6f53282af6f24d2f93f56653c008b0f6c0041b6844dbda05aae3d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libandroid-shmem.so` | 14.1 KB | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libandroid-support.so` | 20.2 KB | `739cf829511d71dafd6c67fdbb70f3f0c6048642ea2e1967790ee961fde14430` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libandroid-sysv-semaphore.so` | 4.6 KB | `4b200c15306fe96b50de576395bbeb8ff7b374134dcca03cdcff73a081f392e8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libargp.so` | 28.3 KB | `6f35528f7c3ef0990a52b5e70e809c189d6693321f382a57c16fb4d5422881cc` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libasound.so` | 1070.1 KB | `d74e0abffcb5599132440aa8365746e669d5826736f76f50f1afad671b17b6d8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libbz2.so.1.0` | 49.1 KB | `667055cac259f6a9404a24a6e927f410e2f41141709d27d5cce0071cbf525407` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libbz2.so.1.0.8` | 49.1 KB | `667055cac259f6a9404a24a6e927f410e2f41141709d27d5cce0071cbf525407` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libc++_shared.so` | 1342.1 KB | `e09c2f45cf4cf8ae574f94b6c2650d99ead0d332d5396f6613f062a2d2d73540` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libcrypto.so.3` | 5083.5 KB | `4122f5c95088f11a43c14e49e257cc1ce700960e855840cf9b9f39a8acbcd7e8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libcurl.so` | 927.6 KB | `79c192dc5c9692770871efbbceedfaa96823f0447a67bc2970f448ace1661c80` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libdb-18.1.so` | 943.6 KB | `6fefdbc2900cc51f49e1404e15abb1352c13547cd720616a0dd25fcc1bfed811` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libdbus-1.so` | 299.4 KB | `3f880cdf13bde13f8c71b266fb9acfb83ea4747226f365e69d9fcca22b074e57` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libdw-0.196.so` | 604.8 KB | `f09f780c03f12426545fa06beee7bbbc6c94f6e4e4f22593cb94e982d7d0b7a8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libdw.so.1` | 604.8 KB | `f09f780c03f12426545fa06beee7bbbc6c94f6e4e4f22593cb94e982d7d0b7a8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libelf-0.196.so` | 87.8 KB | `179abc51cf0ae8c9828af6c4d05ea6c879631a91096616db2db03cc29b059c92` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libelf.so.1` | 87.8 KB | `179abc51cf0ae8c9828af6c4d05ea6c879631a91096616db2db03cc29b059c92` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libfdt.so` | 38 KB | `72f0c4e5a56b9a835afae177a2effac4dc715fd021d648bc416eedc4ae77f6b9` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libffi.so` | 84 KB | `fcb356f322c54d7b406e61db0dd96424f6a0ee2d9daaa7318cb9a6ef3bddbdc0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libFLAC.so` | 205.3 KB | `8754519258cfa87889a08856dddd8dc45fb899f172ca96b425b7ed7edaf24ff0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgio-2.0.so.0` | 1803 KB | `263f41bf4c587946273eac70f20c54bc61632bdb85d4405125ed53a1f3d5645d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgio-2.0.so.0.8800.3` | 1803 KB | `263f41bf4c587946273eac70f20c54bc61632bdb85d4405125ed53a1f3d5645d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libglib-2.0.so.0` | 1234.9 KB | `9f45567de2eb2a53ec1395cf168d6d8f755baff6f2388757412a17a5c1f06778` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libglib-2.0.so.0.8800.3` | 1234.9 KB | `9f45567de2eb2a53ec1395cf168d6d8f755baff6f2388757412a17a5c1f06778` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgmodule-2.0.so.0` | 12.4 KB | `c905f8dc93c6121d467ffd5f90290dcd91520ab9d9463470dfb05e9bb4064969` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgmodule-2.0.so.0.8800.3` | 12.4 KB | `c905f8dc93c6121d467ffd5f90290dcd91520ab9d9463470dfb05e9bb4064969` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgmp.so` | 392.8 KB | `d3a7002e8fb504301bc12278260e36eae34c81d15cf8afe72cf4a769832b6f92` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgnutls.so` | 1974.1 KB | `849cb75d031366c125165477078598c097284a32cfc434b8f03d2d7d54569e0d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgobject-2.0.so.0` | 343.9 KB | `72ae38349dcd380c92fdf0112dd9c5341102a47895a9dce98095c10b1829834f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgobject-2.0.so.0.8800.3` | 343.9 KB | `72ae38349dcd380c92fdf0112dd9c5341102a47895a9dce98095c10b1829834f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgstallocators-1.0.so` | 19.6 KB | `ec6e42a38dad28272db943146b6cb3f05650aad2effd6454d58dfbc141b55525` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgstapp-1.0.so` | 80.8 KB | `6dec169e5f9688f75b95b8beb35680b11e7dabd043cf977aa6b387f274d7779f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgstbase-1.0.so` | 424.5 KB | `bd35c7dac8f4240293d8b4c2995465a74129267cd2502ba4e33618dd2e6aa092` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgstreamer-1.0.so` | 1288.2 KB | `456cead9bd0f37c9fa5258d32d411fc7f830ede4975b7dc7514d10259de8fed8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgstvideo-1.0.so` | 687.2 KB | `a6f07d4624255a7e6806b1abb30a4eab59ee3403fce59ff2c319ce3b3cca20b5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libhogweed.so.6` | 283.2 KB | `7f1d77929c042bc5cc74b712d5264a548e5f39233ca5ee3fa06fa3f5144cd1c1` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libhogweed.so.6.11` | 283.2 KB | `7f1d77929c042bc5cc74b712d5264a548e5f39233ca5ee3fa06fa3f5144cd1c1` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libiconv.so` | 1057.1 KB | `53349c7a84ad06da53c3976754c742d6a79f3297562f0fbe61b7ee620f783667` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libidn2.so` | 189.5 KB | `f5efe369f31db41c3bde8629eaa7788b69f8250b512b743cdc88e648679215fd` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libjack.so.0` | 276.1 KB | `ea69ad448391d91e6f8a4451c026db71f90877ee729bab1c345fb9c6ebae0f20` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libjack.so.0.1.0` | 276.1 KB | `ea69ad448391d91e6f8a4451c026db71f90877ee729bab1c345fb9c6ebae0f20` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libjpeg.so.8` | 625.8 KB | `db198012344af0b1ceaae30c696bb2f262765922bfd3a3c6239c0dfa8a82e5f5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libjpeg.so.8.3.2` | 625.8 KB | `db198012344af0b1ceaae30c696bb2f262765922bfd3a3c6239c0dfa8a82e5f5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/liblz4.so` | 152.3 KB | `5a64bf6ff51c260ef5e705206f54ed60c69fac57c440c277221efad179bad176` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/liblzma.so.5` | 155.8 KB | `daa0cf43130e6aa1aa8239c4f0ec4d2fc34ccebc69e6f518b67556b6ab0d588b` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/liblzma.so.5.8.3` | 155.8 KB | `daa0cf43130e6aa1aa8239c4f0ec4d2fc34ccebc69e6f518b67556b6ab0d588b` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/liblzo2.so` | 103.2 KB | `6d7b994df6a156ed868dd297fd5104464192b90bd2e3c4123e8b38715eba0588` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libmp3lame.so` | 239.1 KB | `1b38fc64b5ed67b84c7b89b3b034a969b460a657a0d496eddccde8511faa56b1` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libncursesw.so.6` | 375.5 KB | `795f855f5a988d9e89116847b2c9aa03720cedbc02026259ca735be25398c4c5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libncursesw.so.6.5` | 375.5 KB | `795f855f5a988d9e89116847b2c9aa03720cedbc02026259ca735be25398c4c5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libnettle.so.8` | 303.8 KB | `bf214e85b9dbd5e22f7f9f78e1d911857f99dee7796590c9515956d170bc2e4d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libnettle.so.8.11` | 303.8 KB | `bf214e85b9dbd5e22f7f9f78e1d911857f99dee7796590c9515956d170bc2e4d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libnghttp2.so` | 156.8 KB | `54cb84a8d8a48829b5c9051d95feae7598a82f524b14dc6b7e3f4ecab0c5c7a5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libnghttp3.so` | 151.9 KB | `e8db2a6c16b1ef4ba0138f05d61a9e591a380728298697f511c522020051b020` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libngtcp2_crypto_ossl.so` | 43.2 KB | `844ce91538696526c08128b680bba2b94db8b7ecdb0cf69ffce1512d2676ea01` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libngtcp2.so` | 333.9 KB | `c21394371208f6d76ea645f9f319b6cf9e5e27c575364278594f9bb2c3ac86be` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libogg.so` | 27.1 KB | `bf430f65013e219a867ff927249022ee900edbd6642fa9c4f36104b7c681beaa` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libOpenSLES.so` | 10.8 KB | `84c51caefa4b743d5f1276946b6ed232e753f713f328041e27ebc13a1b70da7e` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libopus.so` | 313.7 KB | `6afdade25e445ddbd7ed1ad077acd8445ded67f4b295585ec0e9f501ce7305c7` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/liborc-0.4.so` | 919.2 KB | `0ba9570812b080ee1f0994e5919154e8efdcfd09561902b71d19db6d84d10a3f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libp11-kit.so` | 1387.2 KB | `ce5c6acefda27c3cc744ed21cb8666176c985b18e5a3eba1976a8631127af1ca` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libpcre2-8.so` | 477.9 KB | `a6bbeb410e269e50aee795b4fd1b11cbddb8b54959d1cfcd927b55b04dc19f5c` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libpixman-1.so` | 553.4 KB | `2659cf1698d60f653203fe4427370f5ef1985fddaebfabb7b8858a7d8c72aac0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libpng16.so` | 173.8 KB | `dc66d69283c60bd0ce07712b425f0f2aeb0de46a2905410b1a3fc7a5ca399481` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libpulse.so` | 294.9 KB | `9f49294ffcc37a4b2c7b50df1e5430d2a2c399f7135c37bfa6b38fb6f97e4780` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libpulsecommon-17.0.so` | 472.4 KB | `9e754a693de7a27657677e392fe616a730ac26f70c3be1964f0cadd77a8c4726` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libsamplerate.so` | 1450.8 KB | `d141090c9cfd5067d90ccadeb3ff63daec26f5439c03358f3e49d671fac734dc` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libsasl2.so` | 96.5 KB | `fb3faf124e957368e7da56e0840443ede6a9820be61aeb22002f83dfba57da97` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libslirp.so` | 132.7 KB | `b66137d89a294b0aa4db4a7faf9da9b62c35c6ca0a92047b4e29d2a8f8c0909e` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libsndfile.so` | 411.5 KB | `e88d4facec39bd632a91e2861652cbac41b7cf8e241ec6d02b545d6d323dc6a0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libspice-server.so` | 925.2 KB | `438c418c09db0129ba507acdbf8b91fb60091faa6e094fda56c2eda5d26d1905` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libssh.so` | 589.2 KB | `da7b54dbe3937783b27ce30de0671e6dd0e17e72649fe5f112ddde503af7ecb5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libssh2.so` | 242.4 KB | `82e42fa48c77efd4b4296d3b09e44e68f192b220714da81ef60b1edc36211bea` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libssl.so.3` | 854.8 KB | `9bf626fadf689237cf641bf1108057f0cc87c587c17fbdeaa16bd4a12807d7ce` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libtasn1.so` | 61.9 KB | `0a779648ef7ab7c8c7d17f7a1d0fc1200dd2057bc5e913469392c4c542f83239` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libunistring.so` | 2001.2 KB | `a6b1bc3791d22869e4fa4d0acd0a5a64c49910fafe47d561003f04b1ffc84e54` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libusb-1.0.so` | 109.3 KB | `79ce43fc136183523c6d21da418e9dcd5aa57440efb0f1db34be09879bd27698` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libusbredirparser.so` | 25.1 KB | `185c0eafd667d80fa61c72a10f61a0fb1ea1939e50afec47803cb2b6f086328b` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libvorbis.so` | 146.8 KB | `8d0b6676b7ae54287021095cd2210f8241741f3cfee0702c8ecd7eb8a9474f45` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libvorbisenc.so` | 631.9 KB | `5aff8bb63cd4690364629e3b6024a8eb449277259406781cac3b726160bc0e36` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libz.so.1` | 71.2 KB | `c47748ed4b6ca6a2ecd664bab04795f3493956ab1f5cddc4ba0efa737ef3b711` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libz.so.1.3.2` | 71.2 KB | `c47748ed4b6ca6a2ecd664bab04795f3493956ab1f5cddc4ba0efa737ef3b711` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libzstd.so.1` | 801.6 KB | `1677d5e828c1839f111f0261697c08f99a5e4e80434659ab88e5ff859a1374f7` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libzstd.so.1.5.7` | 801.6 KB | `1677d5e828c1839f111f0261697c08f99a5e4e80434659ab88e5ff859a1374f7` | `3ad15fd` |

二进制内实测的组件版本：

| 组件 | 版本 | 证据 |
|---|---|---|
| OpenSSL（`libcrypto.so.3`） | 3.6.3 | 二进制字符串 `OpenSSL 3.6.3` |
| GnuTLS（`libgnutls.so`） | 3.8.13 | 二进制字符串 `GnuTLS 3.8.13` |
| libcurl（`libcurl.so`） | 8.21.0 | 二进制字符串 `libcurl/8.21.0` |
| GStreamer（`libgstreamer-1.0.so`） | 1.28.7 | 二进制版本串 |
| alsa-lib（`libasound.so`） | 1.2.16 | 二进制版本串 |
| GLib / GIO | 2.88 | 文件名 `libglib-2.0.so.0.8800.3` |
| liborc（`liborc-0.4.so`） | 0.4.44 | 二进制版本串 |
| libsndfile | 1.2.2 | 二进制版本串 |
| libsamplerate | 0.2.2 | 二进制版本串 |
| Berkeley DB（`libdb-18.1.so`） | 18.1.40 | 二进制版本串 |

- **来源**：Termux 包（aarch64，`pkg install` 安装）。每个 `.so` 对应的 **包名与完整版本见第八节附录**（由手机 Termux 的 dpkg 数据库导出：`status` + `info/*.list`，84/84 全部命中）

## 四、QEMU 固件（44 个，取自 Termux `qemu-common` 包）

来源：Termux 包 **`qemu-common`**（当前手机版本 `1:11.1.2`）的 `/data/data/com.termux/files/usr/share/qemu/`，该目录共 70 项，本仓取用其中 44 项（`bios*.bin`、`efi-*.rom`、`keymaps/*`）。

已做逐字节校验（本仓文件 vs 手机 Termux 内同名文件，SHA256 一致）：

| 文件 | SHA256（前 16 位） | 与 Termux 一致 |
|---|---|---|
| `bios.bin` | `3dfd946d0c03ab0e…` | ✅ |
| `bios-256k.bin` | `ae6f6aa973aaccc1…` | ✅ |
| `efi-e1000.rom` | `f37222fcdf0481bf…` | ✅ |

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `wrapper-lite-app/app/src/main/assets/qemu-fw/bios-256k.bin` | 256 KB | `ae6f6aa973aaccc143f57aa960fb035fd9de4daee4ad0cd713322f8c259e7650` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/bios-microvm.bin` | 128 KB | `d226177d125aaf1b91e507738f4e9a0ade9474169b211751f78be08008cb394c` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/bios.bin` | 128 KB | `3dfd946d0c03ab0e022f84f10c3eb5f1dd507761f73e7d8067511ba35a10f776` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/efi-e1000.rom` | 155.5 KB | `f37222fcdf0481bfbaf7a16d9aaf740aeb41d3d24c187801eb6e9a628cec0fc8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/ar` | 27.9 KB | `059ab719df4296edb800d399501e3823e1e6f470cc06a3a4564f4bb2dbd5c1fc` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/bepo` | 27.2 KB | `01ccdb7e2d0b20f517e03d39cc70802ad83dfc1ed2e5d50ff9b4a4f77e37a9f8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/cz` | 28.5 KB | `3664b3f13e6cfff0e6d05f12000aa6610eff04f570707261ec35ca830ded6c36` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/da` | 28.5 KB | `95ef0c168f0b25e936a7ff944da820a43af8028b515cc6709724f457e105c187` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/de` | 28.5 KB | `cda7dc9656f007ca3e88e5bac50f20c7f61f934bcec2e87c6d61859e632ddb98` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/de-ch` | 28.5 KB | `3260cb64fdcdf48e2b53c5d0573dc2a9410d7d3aae227ed42dda945f9ab74e06` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/en-gb` | 28.5 KB | `e14229b7f9165bf226bf4de968fd5f6a504014a2179de08c2b2184cefe68174e` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/en-us` | 26.5 KB | `52cf4c523e686078699ed58761c7e1443080305237a5f67a3716832570c5c8e5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/es` | 28.5 KB | `d9b1da8193232882705bd4a312c0923b6d91268f1e1a8eed2afbd7fc37681c06` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/et` | 26.7 KB | `95af1e25132185e3cb271d491086ccf09cb667033afb718f1a11e974e80a838f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fi` | 27.9 KB | `4c5cd5359c18635187d7af9570088a7bbd21f96d961aedbc4c9fb1e5df099281` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fo` | 28.5 KB | `3750b8090caea3cf6c827b97f5ecc2d1172c33d960141c50f96e61c53f682ccf` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fr` | 28.5 KB | `60d9baa3565b7727b4178704100bffb02cbd5b527a9dcc9f9ca76d76767b166e` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fr-be` | 28.5 KB | `522b5e758375e3d796fba096a46077766bd44548c8c61a4abe946e3116c1ad97` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fr-ca` | 26.9 KB | `9ee3fc13beec6e74f40fb6d150d7fe3b0afe4557fdf0769601e662a122a88cc0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fr-ch` | 28.5 KB | `7b2ad2929f3b7b4f5d60176bd7e1852f91efbcfe2700e8df9bc9b891609a7170` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/hr` | 28.6 KB | `e8391ed09015ce8459a98e3470daddd3a6d95c4c7e93b750098f5a321fd57482` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/hu` | 28.5 KB | `763a938ed2fdd224b9cb8ad606d02aabc93a0e3fb7a5e5e9caafa0ec8c05d422` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/is` | 28.6 KB | `6eb4e5bd93ca2f7a2059a31fd014444108eb684ecd97902da2492b0a0f6e3cdc` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/it` | 28.6 KB | `12d090cdcb6af0e95a0c0330b8b7f0ac0446ab8cf7140b4f8c6c797c08e9c219` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/ja` | 26.5 KB | `a4561aefcc2fd2f4ba01604001c9c0dc67469514ea0a665063b72e79d5d9c687` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/lt` | 28.5 KB | `0bfc98e913451c0b9082d0317deffa03c6f0c53a1422cb4f4c1ef64dc3939d2f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/lv` | 27.9 KB | `be4f1e28325ae2ea927bd51e31863a327e64c0a938725fd02c2a7260660bdf77` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/mk` | 27 KB | `71068ffe163ce6684b35f423e897e00c97debe0c3df555c43d26b46a79ca68e7` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/nl` | 28.6 KB | `70cb1b41f184d61520867e639e6b69cddea4b0e2458c5502c7c40512301876c7` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/no` | 28.9 KB | `95b3fe5258264b827ba01acc7a774bd9edbaec52c54e582a0737c33087a92514` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/pl` | 28.6 KB | `5f3da802f81f385c58179272e48c4f8680dfdfe55383c504a6d84b3b7a8aadfb` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/pt` | 28.5 KB | `29161e1b8b61b761c3f3bff237ca73ce4ee3316a99090890b32469824b5eed8a` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/pt-br` | 28.5 KB | `fbb9491c0bfc4253cced9d8a20965aca98d0249c4626fe6e7d31358b24e34e8d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/ru` | 27.1 KB | `afa015a736cd935fc38a0df62fd6ab2f3c64b44c48b3e6d95969cbbfcdc43e91` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/sl` | 4.5 KB | `bea8fe8ac0ca281038ac0e1788c39f090507ff0646bd4f57cc18f6873b030147` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/sv` | 3.3 KB | `97731fbe91f254d81c005ffc232180a8dd7baeb6d699f0f0fc15e341672f96b3` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/th` | 27.2 KB | `6dd9562a2b10c81f658ad96075fd87832777e87357620e89c7d070e4097fbbb9` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/tr` | 28.5 KB | `f59f8cc154f0dc8b45166b524b9c1e649ba027e39381aac286663ad2227ddce8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/kvmvapic.bin` | 9 KB | `cdf057a71b07e3b52b19cbe210bdefa59250d01a9810b960f7fe1f98eed95a27` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/linuxboot_dma.bin` | 1.5 KB | `9c49e255340c78fc12e54ed043462bca02fb7fca29b7cfab62ff88a5344b6950` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/multiboot_dma.bin` | 1 KB | `46def003b1c413cc4a5880d858ca54456ad6978d5fdb6e1b7d086b2905a66df0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/pvh.bin` | 1.5 KB | `918ff03a118615c2f4611a5df2d9496aabe099b5cfaaf69e544ebf973305d59d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/vgabios-stdvga.bin` | 38.5 KB | `e8fc9e55790dbe3cb31f019a3deb57206ba6c54f5e581adb2ab2677a9d391472` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/vgabios.bin` | 38 KB | `a7ea86bb06a58ff969fd0942e73b8eae00cb58e4c90bccbd900f6a3a01f54fbb` | `3ad15fd` |

- **来源**：QEMU 11.0.3 构建产物中的 `-L` 固件目录（`bios.bin`、VGA/网卡 ROM、keymaps 等），随 QEMU 版本固定

## 五、其它

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `wrapper-lite-app/assets-src/data.empty.img` | 65536 KB | `26c4b00757c890a32965772ac833e445568ebf37bcce2711b1760b0d2c6ecc18` | `3ad15fd` |
| `wrapper-lite-app/gradle/wrapper/gradle-wrapper.jar` | 52.4 KB | `16caeaf66d57a0d1d2087fef6a97efa62de8da69afa5b908f40db35afc4342da` | `3ad15fd` |

- `assets-src/data.empty.img`：`mkfs.ext4 -L wrapperdata` 生成的 64 MiB 空白 ext4 镜像（`data.empty.dat` 的压缩前原始件）
- `gradle/wrapper/gradle-wrapper.jar`：Gradle 官方 distribution 自带

## 六、全量清单

| 文件 | 大小 | SHA256 | 入库 commit |
|---|---|---|---|
| `wrapper-lite-app/app/src/main/assets/guest/data.empty.dat` | 64.2 KB | `3253b145c8fa55dd65267e6d3ec8df8e95c1f47db9aa0cb7589ba1f68336137e` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/guest/initramfs.dat` | 48859 KB | `488a16361652d2cd179c2333f95967a8cddefcb6a3c5e879747d02ee7a65b1ad` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/guest/vmlinuz` | 14778.4 KB | `ebb569805ab12818a57c79a578df576faca23b749dc379538087af430519986a` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libandroid-execinfo.so` | 33.9 KB | `778fa9eaf0a6f53282af6f24d2f93f56653c008b0f6c0041b6844dbda05aae3d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libandroid-shmem.so` | 14.1 KB | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libandroid-support.so` | 20.2 KB | `739cf829511d71dafd6c67fdbb70f3f0c6048642ea2e1967790ee961fde14430` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libandroid-sysv-semaphore.so` | 4.6 KB | `4b200c15306fe96b50de576395bbeb8ff7b374134dcca03cdcff73a081f392e8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libargp.so` | 28.3 KB | `6f35528f7c3ef0990a52b5e70e809c189d6693321f382a57c16fb4d5422881cc` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libasound.so` | 1070.1 KB | `d74e0abffcb5599132440aa8365746e669d5826736f76f50f1afad671b17b6d8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libbz2.so.1.0` | 49.1 KB | `667055cac259f6a9404a24a6e927f410e2f41141709d27d5cce0071cbf525407` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libbz2.so.1.0.8` | 49.1 KB | `667055cac259f6a9404a24a6e927f410e2f41141709d27d5cce0071cbf525407` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libc++_shared.so` | 1342.1 KB | `e09c2f45cf4cf8ae574f94b6c2650d99ead0d332d5396f6613f062a2d2d73540` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libcrypto.so.3` | 5083.5 KB | `4122f5c95088f11a43c14e49e257cc1ce700960e855840cf9b9f39a8acbcd7e8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libcurl.so` | 927.6 KB | `79c192dc5c9692770871efbbceedfaa96823f0447a67bc2970f448ace1661c80` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libdb-18.1.so` | 943.6 KB | `6fefdbc2900cc51f49e1404e15abb1352c13547cd720616a0dd25fcc1bfed811` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libdbus-1.so` | 299.4 KB | `3f880cdf13bde13f8c71b266fb9acfb83ea4747226f365e69d9fcca22b074e57` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libdw-0.196.so` | 604.8 KB | `f09f780c03f12426545fa06beee7bbbc6c94f6e4e4f22593cb94e982d7d0b7a8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libdw.so.1` | 604.8 KB | `f09f780c03f12426545fa06beee7bbbc6c94f6e4e4f22593cb94e982d7d0b7a8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libelf-0.196.so` | 87.8 KB | `179abc51cf0ae8c9828af6c4d05ea6c879631a91096616db2db03cc29b059c92` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libelf.so.1` | 87.8 KB | `179abc51cf0ae8c9828af6c4d05ea6c879631a91096616db2db03cc29b059c92` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libfdt.so` | 38 KB | `72f0c4e5a56b9a835afae177a2effac4dc715fd021d648bc416eedc4ae77f6b9` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libffi.so` | 84 KB | `fcb356f322c54d7b406e61db0dd96424f6a0ee2d9daaa7318cb9a6ef3bddbdc0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libFLAC.so` | 205.3 KB | `8754519258cfa87889a08856dddd8dc45fb899f172ca96b425b7ed7edaf24ff0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgio-2.0.so.0` | 1803 KB | `263f41bf4c587946273eac70f20c54bc61632bdb85d4405125ed53a1f3d5645d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgio-2.0.so.0.8800.3` | 1803 KB | `263f41bf4c587946273eac70f20c54bc61632bdb85d4405125ed53a1f3d5645d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libglib-2.0.so.0` | 1234.9 KB | `9f45567de2eb2a53ec1395cf168d6d8f755baff6f2388757412a17a5c1f06778` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libglib-2.0.so.0.8800.3` | 1234.9 KB | `9f45567de2eb2a53ec1395cf168d6d8f755baff6f2388757412a17a5c1f06778` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgmodule-2.0.so.0` | 12.4 KB | `c905f8dc93c6121d467ffd5f90290dcd91520ab9d9463470dfb05e9bb4064969` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgmodule-2.0.so.0.8800.3` | 12.4 KB | `c905f8dc93c6121d467ffd5f90290dcd91520ab9d9463470dfb05e9bb4064969` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgmp.so` | 392.8 KB | `d3a7002e8fb504301bc12278260e36eae34c81d15cf8afe72cf4a769832b6f92` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgnutls.so` | 1974.1 KB | `849cb75d031366c125165477078598c097284a32cfc434b8f03d2d7d54569e0d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgobject-2.0.so.0` | 343.9 KB | `72ae38349dcd380c92fdf0112dd9c5341102a47895a9dce98095c10b1829834f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgobject-2.0.so.0.8800.3` | 343.9 KB | `72ae38349dcd380c92fdf0112dd9c5341102a47895a9dce98095c10b1829834f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgstallocators-1.0.so` | 19.6 KB | `ec6e42a38dad28272db943146b6cb3f05650aad2effd6454d58dfbc141b55525` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgstapp-1.0.so` | 80.8 KB | `6dec169e5f9688f75b95b8beb35680b11e7dabd043cf977aa6b387f274d7779f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgstbase-1.0.so` | 424.5 KB | `bd35c7dac8f4240293d8b4c2995465a74129267cd2502ba4e33618dd2e6aa092` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgstreamer-1.0.so` | 1288.2 KB | `456cead9bd0f37c9fa5258d32d411fc7f830ede4975b7dc7514d10259de8fed8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libgstvideo-1.0.so` | 687.2 KB | `a6f07d4624255a7e6806b1abb30a4eab59ee3403fce59ff2c319ce3b3cca20b5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libhogweed.so.6` | 283.2 KB | `7f1d77929c042bc5cc74b712d5264a548e5f39233ca5ee3fa06fa3f5144cd1c1` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libhogweed.so.6.11` | 283.2 KB | `7f1d77929c042bc5cc74b712d5264a548e5f39233ca5ee3fa06fa3f5144cd1c1` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libiconv.so` | 1057.1 KB | `53349c7a84ad06da53c3976754c742d6a79f3297562f0fbe61b7ee620f783667` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libidn2.so` | 189.5 KB | `f5efe369f31db41c3bde8629eaa7788b69f8250b512b743cdc88e648679215fd` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libjack.so.0` | 276.1 KB | `ea69ad448391d91e6f8a4451c026db71f90877ee729bab1c345fb9c6ebae0f20` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libjack.so.0.1.0` | 276.1 KB | `ea69ad448391d91e6f8a4451c026db71f90877ee729bab1c345fb9c6ebae0f20` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libjpeg.so.8` | 625.8 KB | `db198012344af0b1ceaae30c696bb2f262765922bfd3a3c6239c0dfa8a82e5f5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libjpeg.so.8.3.2` | 625.8 KB | `db198012344af0b1ceaae30c696bb2f262765922bfd3a3c6239c0dfa8a82e5f5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/liblz4.so` | 152.3 KB | `5a64bf6ff51c260ef5e705206f54ed60c69fac57c440c277221efad179bad176` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/liblzma.so.5` | 155.8 KB | `daa0cf43130e6aa1aa8239c4f0ec4d2fc34ccebc69e6f518b67556b6ab0d588b` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/liblzma.so.5.8.3` | 155.8 KB | `daa0cf43130e6aa1aa8239c4f0ec4d2fc34ccebc69e6f518b67556b6ab0d588b` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/liblzo2.so` | 103.2 KB | `6d7b994df6a156ed868dd297fd5104464192b90bd2e3c4123e8b38715eba0588` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libmp3lame.so` | 239.1 KB | `1b38fc64b5ed67b84c7b89b3b034a969b460a657a0d496eddccde8511faa56b1` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libncursesw.so.6` | 375.5 KB | `795f855f5a988d9e89116847b2c9aa03720cedbc02026259ca735be25398c4c5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libncursesw.so.6.5` | 375.5 KB | `795f855f5a988d9e89116847b2c9aa03720cedbc02026259ca735be25398c4c5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libnettle.so.8` | 303.8 KB | `bf214e85b9dbd5e22f7f9f78e1d911857f99dee7796590c9515956d170bc2e4d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libnettle.so.8.11` | 303.8 KB | `bf214e85b9dbd5e22f7f9f78e1d911857f99dee7796590c9515956d170bc2e4d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libnghttp2.so` | 156.8 KB | `54cb84a8d8a48829b5c9051d95feae7598a82f524b14dc6b7e3f4ecab0c5c7a5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libnghttp3.so` | 151.9 KB | `e8db2a6c16b1ef4ba0138f05d61a9e591a380728298697f511c522020051b020` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libngtcp2_crypto_ossl.so` | 43.2 KB | `844ce91538696526c08128b680bba2b94db8b7ecdb0cf69ffce1512d2676ea01` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libngtcp2.so` | 333.9 KB | `c21394371208f6d76ea645f9f319b6cf9e5e27c575364278594f9bb2c3ac86be` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libogg.so` | 27.1 KB | `bf430f65013e219a867ff927249022ee900edbd6642fa9c4f36104b7c681beaa` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libOpenSLES.so` | 10.8 KB | `84c51caefa4b743d5f1276946b6ed232e753f713f328041e27ebc13a1b70da7e` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libopus.so` | 313.7 KB | `6afdade25e445ddbd7ed1ad077acd8445ded67f4b295585ec0e9f501ce7305c7` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/liborc-0.4.so` | 919.2 KB | `0ba9570812b080ee1f0994e5919154e8efdcfd09561902b71d19db6d84d10a3f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libp11-kit.so` | 1387.2 KB | `ce5c6acefda27c3cc744ed21cb8666176c985b18e5a3eba1976a8631127af1ca` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libpcre2-8.so` | 477.9 KB | `a6bbeb410e269e50aee795b4fd1b11cbddb8b54959d1cfcd927b55b04dc19f5c` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libpixman-1.so` | 553.4 KB | `2659cf1698d60f653203fe4427370f5ef1985fddaebfabb7b8858a7d8c72aac0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libpng16.so` | 173.8 KB | `dc66d69283c60bd0ce07712b425f0f2aeb0de46a2905410b1a3fc7a5ca399481` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libpulse.so` | 294.9 KB | `9f49294ffcc37a4b2c7b50df1e5430d2a2c399f7135c37bfa6b38fb6f97e4780` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libpulsecommon-17.0.so` | 472.4 KB | `9e754a693de7a27657677e392fe616a730ac26f70c3be1964f0cadd77a8c4726` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libsamplerate.so` | 1450.8 KB | `d141090c9cfd5067d90ccadeb3ff63daec26f5439c03358f3e49d671fac734dc` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libsasl2.so` | 96.5 KB | `fb3faf124e957368e7da56e0840443ede6a9820be61aeb22002f83dfba57da97` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libslirp.so` | 132.7 KB | `b66137d89a294b0aa4db4a7faf9da9b62c35c6ca0a92047b4e29d2a8f8c0909e` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libsndfile.so` | 411.5 KB | `e88d4facec39bd632a91e2861652cbac41b7cf8e241ec6d02b545d6d323dc6a0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libspice-server.so` | 925.2 KB | `438c418c09db0129ba507acdbf8b91fb60091faa6e094fda56c2eda5d26d1905` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libssh.so` | 589.2 KB | `da7b54dbe3937783b27ce30de0671e6dd0e17e72649fe5f112ddde503af7ecb5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libssh2.so` | 242.4 KB | `82e42fa48c77efd4b4296d3b09e44e68f192b220714da81ef60b1edc36211bea` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libssl.so.3` | 854.8 KB | `9bf626fadf689237cf641bf1108057f0cc87c587c17fbdeaa16bd4a12807d7ce` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libtasn1.so` | 61.9 KB | `0a779648ef7ab7c8c7d17f7a1d0fc1200dd2057bc5e913469392c4c542f83239` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libunistring.so` | 2001.2 KB | `a6b1bc3791d22869e4fa4d0acd0a5a64c49910fafe47d561003f04b1ffc84e54` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libusb-1.0.so` | 109.3 KB | `79ce43fc136183523c6d21da418e9dcd5aa57440efb0f1db34be09879bd27698` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libusbredirparser.so` | 25.1 KB | `185c0eafd667d80fa61c72a10f61a0fb1ea1939e50afec47803cb2b6f086328b` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libvorbis.so` | 146.8 KB | `8d0b6676b7ae54287021095cd2210f8241741f3cfee0702c8ecd7eb8a9474f45` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libvorbisenc.so` | 631.9 KB | `5aff8bb63cd4690364629e3b6024a8eb449277259406781cac3b726160bc0e36` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libz.so.1` | 71.2 KB | `c47748ed4b6ca6a2ecd664bab04795f3493956ab1f5cddc4ba0efa737ef3b711` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libz.so.1.3.2` | 71.2 KB | `c47748ed4b6ca6a2ecd664bab04795f3493956ab1f5cddc4ba0efa737ef3b711` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libzstd.so.1` | 801.6 KB | `1677d5e828c1839f111f0261697c08f99a5e4e80434659ab88e5ff859a1374f7` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/lib/libzstd.so.1.5.7` | 801.6 KB | `1677d5e828c1839f111f0261697c08f99a5e4e80434659ab88e5ff859a1374f7` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/bios-256k.bin` | 256 KB | `ae6f6aa973aaccc143f57aa960fb035fd9de4daee4ad0cd713322f8c259e7650` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/bios-microvm.bin` | 128 KB | `d226177d125aaf1b91e507738f4e9a0ade9474169b211751f78be08008cb394c` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/bios.bin` | 128 KB | `3dfd946d0c03ab0e022f84f10c3eb5f1dd507761f73e7d8067511ba35a10f776` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/efi-e1000.rom` | 155.5 KB | `f37222fcdf0481bfbaf7a16d9aaf740aeb41d3d24c187801eb6e9a628cec0fc8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/ar` | 27.9 KB | `059ab719df4296edb800d399501e3823e1e6f470cc06a3a4564f4bb2dbd5c1fc` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/bepo` | 27.2 KB | `01ccdb7e2d0b20f517e03d39cc70802ad83dfc1ed2e5d50ff9b4a4f77e37a9f8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/cz` | 28.5 KB | `3664b3f13e6cfff0e6d05f12000aa6610eff04f570707261ec35ca830ded6c36` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/da` | 28.5 KB | `95ef0c168f0b25e936a7ff944da820a43af8028b515cc6709724f457e105c187` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/de` | 28.5 KB | `cda7dc9656f007ca3e88e5bac50f20c7f61f934bcec2e87c6d61859e632ddb98` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/de-ch` | 28.5 KB | `3260cb64fdcdf48e2b53c5d0573dc2a9410d7d3aae227ed42dda945f9ab74e06` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/en-gb` | 28.5 KB | `e14229b7f9165bf226bf4de968fd5f6a504014a2179de08c2b2184cefe68174e` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/en-us` | 26.5 KB | `52cf4c523e686078699ed58761c7e1443080305237a5f67a3716832570c5c8e5` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/es` | 28.5 KB | `d9b1da8193232882705bd4a312c0923b6d91268f1e1a8eed2afbd7fc37681c06` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/et` | 26.7 KB | `95af1e25132185e3cb271d491086ccf09cb667033afb718f1a11e974e80a838f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fi` | 27.9 KB | `4c5cd5359c18635187d7af9570088a7bbd21f96d961aedbc4c9fb1e5df099281` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fo` | 28.5 KB | `3750b8090caea3cf6c827b97f5ecc2d1172c33d960141c50f96e61c53f682ccf` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fr` | 28.5 KB | `60d9baa3565b7727b4178704100bffb02cbd5b527a9dcc9f9ca76d76767b166e` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fr-be` | 28.5 KB | `522b5e758375e3d796fba096a46077766bd44548c8c61a4abe946e3116c1ad97` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fr-ca` | 26.9 KB | `9ee3fc13beec6e74f40fb6d150d7fe3b0afe4557fdf0769601e662a122a88cc0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/fr-ch` | 28.5 KB | `7b2ad2929f3b7b4f5d60176bd7e1852f91efbcfe2700e8df9bc9b891609a7170` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/hr` | 28.6 KB | `e8391ed09015ce8459a98e3470daddd3a6d95c4c7e93b750098f5a321fd57482` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/hu` | 28.5 KB | `763a938ed2fdd224b9cb8ad606d02aabc93a0e3fb7a5e5e9caafa0ec8c05d422` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/is` | 28.6 KB | `6eb4e5bd93ca2f7a2059a31fd014444108eb684ecd97902da2492b0a0f6e3cdc` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/it` | 28.6 KB | `12d090cdcb6af0e95a0c0330b8b7f0ac0446ab8cf7140b4f8c6c797c08e9c219` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/ja` | 26.5 KB | `a4561aefcc2fd2f4ba01604001c9c0dc67469514ea0a665063b72e79d5d9c687` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/lt` | 28.5 KB | `0bfc98e913451c0b9082d0317deffa03c6f0c53a1422cb4f4c1ef64dc3939d2f` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/lv` | 27.9 KB | `be4f1e28325ae2ea927bd51e31863a327e64c0a938725fd02c2a7260660bdf77` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/mk` | 27 KB | `71068ffe163ce6684b35f423e897e00c97debe0c3df555c43d26b46a79ca68e7` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/nl` | 28.6 KB | `70cb1b41f184d61520867e639e6b69cddea4b0e2458c5502c7c40512301876c7` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/no` | 28.9 KB | `95b3fe5258264b827ba01acc7a774bd9edbaec52c54e582a0737c33087a92514` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/pl` | 28.6 KB | `5f3da802f81f385c58179272e48c4f8680dfdfe55383c504a6d84b3b7a8aadfb` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/pt` | 28.5 KB | `29161e1b8b61b761c3f3bff237ca73ce4ee3316a99090890b32469824b5eed8a` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/pt-br` | 28.5 KB | `fbb9491c0bfc4253cced9d8a20965aca98d0249c4626fe6e7d31358b24e34e8d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/ru` | 27.1 KB | `afa015a736cd935fc38a0df62fd6ab2f3c64b44c48b3e6d95969cbbfcdc43e91` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/sl` | 4.5 KB | `bea8fe8ac0ca281038ac0e1788c39f090507ff0646bd4f57cc18f6873b030147` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/sv` | 3.3 KB | `97731fbe91f254d81c005ffc232180a8dd7baeb6d699f0f0fc15e341672f96b3` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/th` | 27.2 KB | `6dd9562a2b10c81f658ad96075fd87832777e87357620e89c7d070e4097fbbb9` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/keymaps/tr` | 28.5 KB | `f59f8cc154f0dc8b45166b524b9c1e649ba027e39381aac286663ad2227ddce8` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/kvmvapic.bin` | 9 KB | `cdf057a71b07e3b52b19cbe210bdefa59250d01a9810b960f7fe1f98eed95a27` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/linuxboot_dma.bin` | 1.5 KB | `9c49e255340c78fc12e54ed043462bca02fb7fca29b7cfab62ff88a5344b6950` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/multiboot_dma.bin` | 1 KB | `46def003b1c413cc4a5880d858ca54456ad6978d5fdb6e1b7d086b2905a66df0` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/pvh.bin` | 1.5 KB | `918ff03a118615c2f4611a5df2d9496aabe099b5cfaaf69e544ebf973305d59d` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/vgabios-stdvga.bin` | 38.5 KB | `e8fc9e55790dbe3cb31f019a3deb57206ba6c54f5e581adb2ab2677a9d391472` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/assets/qemu-fw/vgabios.bin` | 38 KB | `a7ea86bb06a58ff969fd0942e73b8eae00cb58e4c90bccbd900f6a3a01f54fbb` | `3ad15fd` |
| `wrapper-lite-app/app/src/main/jniLibs/arm64-v8a/libqemu_x86_64.so` | 25496.9 KB | `f6448c2cad77fc89b0a587fdd45b7acee9b210809b8f90e3a6ae2ae494defedb` | `3ad15fd` |
| `wrapper-lite-app/assets-src/data.empty.img` | 65536 KB | `26c4b00757c890a32965772ac833e445568ebf37bcce2711b1760b0d2c6ecc18` | `3ad15fd` |
| `wrapper-lite-app/gradle/wrapper/gradle-wrapper.jar` | 52.4 KB | `16caeaf66d57a0d1d2087fef6a97efa62de8da69afa5b908f40db35afc4342da` | `3ad15fd` |

## 七、复核命令

```powershell
git ls-files android-native/wrapper-lite-app |
  Where-Object { $_ -match '\.(so|dat|img|vmlinuz|jar)$' -or $_ -match 'assets/(guest|lib|qemu-fw)/' } |
  ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLower() + '  ' + $_ }
```
## 八、附录：84 个依赖库 → Termux 包 / 版本

来源数据：手机 Termux 的 `/data/data/com.termux/files/usr/var/lib/dpkg/status` 与 `info/*.list`（2026-11-26 导出，共 313 个已装包）。

> 注意：表中「包版本」是手机**当前** dpkg 记录；入库的 `.so` 是较早一次安装时的拷贝，两者可能不同步。以**二进制内实测版本**为准，包名 / 包版本用于定位来源。
> 已确认的不同步：`libcrypto.so.3` 库内 `OpenSSL 3.6.3`（当前包 `1:3.6.5`）、`libcurl.so` 库内 `8.21.0`（当前包 `8.23.0`）。

| 库文件 | Termux 包 | 包版本 |
|---|---|---|
| `libandroid-execinfo.so` | `libandroid-execinfo` | `0.1-3` |
| `libandroid-shmem.so` | `libandroid-shmem` | `0.7` |
| `libandroid-support.so` | `libandroid-support` | `29-1` |
| `libandroid-sysv-semaphore.so` | `libandroid-sysv-semaphore` | `0.1-1` |
| `libargp.so` | `argp` | `1.5.0-1` |
| `libasound.so` | `alsa-lib` | `1.2.16.1` |
| `libbz2.so.1.0` | `libbz2` | `1.0.8-8` |
| `libbz2.so.1.0.8` | `libbz2` | `1.0.8-8` |
| `libc++_shared.so` | `libc++` | `30` |
| `libcrypto.so.3` | `openssl` | `1:3.6.5` |
| `libcurl.so` | `libcurl` | `8.23.0` |
| `libdb-18.1.so` | `libdb` | `18.1.40-6` |
| `libdbus-1.so` | `dbus` | `1.16.2-3` |
| `libdw-0.196.so` | `libdw` | `0.196` |
| `libdw.so.1` | `libdw` | `0.196` |
| `libelf-0.196.so` | `libelf` | `0.196` |
| `libelf.so.1` | `libelf` | `0.196` |
| `libfdt.so` | `dtc` | `1.8.1` |
| `libffi.so` | `libffi` | `3.8.0` |
| `libFLAC.so` | `libflac` | `1.5.0-1` |
| `libgio-2.0.so.0` | `glib` | `2.90.1` |
| `libgio-2.0.so.0.8800.3` | `glib` | `2.90.1` |
| `libglib-2.0.so.0` | `glib` | `2.90.1` |
| `libglib-2.0.so.0.8800.3` | `glib` | `2.90.1` |
| `libgmodule-2.0.so.0` | `glib` | `2.90.1` |
| `libgmodule-2.0.so.0.8800.3` | `glib` | `2.90.1` |
| `libgmp.so` | `libgmp` | `6.3.0-2` |
| `libgnutls.so` | `libgnutls` | `3.8.13-1` |
| `libgobject-2.0.so.0` | `glib` | `2.90.1` |
| `libgobject-2.0.so.0.8800.3` | `glib` | `2.90.1` |
| `libgstallocators-1.0.so` | `gst-plugins-base` | `1.28.8` |
| `libgstapp-1.0.so` | `gst-plugins-base` | `1.28.8` |
| `libgstbase-1.0.so` | `gstreamer` | `1.28.8` |
| `libgstreamer-1.0.so` | `gstreamer` | `1.28.8` |
| `libgstvideo-1.0.so` | `gst-plugins-base` | `1.28.8` |
| `libhogweed.so.6` | `libnettle` | `4.0+really3.10.2` |
| `libhogweed.so.6.11` | `libnettle` | `4.0+really3.10.2` |
| `libiconv.so` | `libiconv` | `1.19` |
| `libidn2.so` | `libidn2` | `2.3.8-1` |
| `libjack.so.0` | `jack2` | `1.9.22-2` |
| `libjack.so.0.1.0` | `jack2` | `1.9.22-2` |
| `libjpeg.so.8` | `libjpeg-turbo` | `3.2.0` |
| `libjpeg.so.8.3.2` | `libjpeg-turbo` | `3.2.0` |
| `liblz4.so` | `liblz4` | `1.10.0-1` |
| `liblzma.so.5` | `liblzma` | `5.8.4` |
| `liblzma.so.5.8.3` | `liblzma` | `5.8.4` |
| `liblzo2.so` | `liblzo` | `2.10-5` |
| `libmp3lame.so` | `libmp3lame` | `4.0-1` |
| `libncursesw.so.6` | `ncurses` | `6.6.20260307+really6.5.20250830` |
| `libncursesw.so.6.5` | `ncurses` | `6.6.20260307+really6.5.20250830` |
| `libnettle.so.8` | `libnettle` | `4.0+really3.10.2` |
| `libnettle.so.8.11` | `libnettle` | `4.0+really3.10.2` |
| `libnghttp2.so` | `libnghttp2` | `1.70.0` |
| `libnghttp3.so` | `libnghttp3` | `1.18.0` |
| `libngtcp2_crypto_ossl.so` | `libngtcp2` | `1.25.0` |
| `libngtcp2.so` | `libngtcp2` | `1.25.0` |
| `libogg.so` | `libogg` | `1.3.6-1` |
| `libOpenSLES.so` | `libandroid-stub` | `30-2` |
| `libopus.so` | `libopus` | `1.6.1` |
| `liborc-0.4.so` | `liborc` | `0.4.44` |
| `libp11-kit.so` | `p11-kit` | `0.26.5` |
| `libpcre2-8.so` | `pcre2` | `10.49` |
| `libpixman-1.so` | `libpixman` | `0.46.4-1` |
| `libpng16.so` | `libpng` | `1.6.59` |
| `libpulse.so` | `pulseaudio` | `17.0-4` |
| `libpulsecommon-17.0.so` | `pulseaudio` | `17.0-4` |
| `libsamplerate.so` | `libsamplerate` | `0.2.2-4` |
| `libsasl2.so` | `libsasl` | `2.1.28-3` |
| `libslirp.so` | `libslirp` | `4.9.5` |
| `libsndfile.so` | `libsndfile` | `1.2.2-3` |
| `libspice-server.so` | `libspice-server` | `0.16.0-1` |
| `libssh.so` | `libssh` | `0.12.2` |
| `libssh2.so` | `libssh2` | `1.11.1-2` |
| `libssl.so.3` | `openssl` | `1:3.6.5` |
| `libtasn1.so` | `libtasn1` | `4.21.0` |
| `libunistring.so` | `libunistring` | `1.4.2` |
| `libusb-1.0.so` | `libusb` | `1.0.30` |
| `libusbredirparser.so` | `libusbredir` | `0.15.0` |
| `libvorbis.so` | `libvorbis` | `1.3.7-4` |
| `libvorbisenc.so` | `libvorbis` | `1.3.7-4` |
| `libz.so.1` | `zlib` | `1.3.2` |
| `libz.so.1.3.2` | `zlib` | `1.3.2` |
| `libzstd.so.1` | `zstd` | `1.5.7-1` |
| `libzstd.so.1.5.7` | `zstd` | `1.5.7-1` |
