# 接手文档：DAP 内置 DAC 直通（DSD native）与播放链路

> 最后更新 2026-11-15 00:55。规矩见 `AGENTS.md`（注释不可信 / 禁批处理改源码 / 改完必须编译 + 单测 / 只有明确说「提交」才 commit）。
> 本文件只写「目标、已确证的事实、仍存在的问题、别再踩的坑」，不写流水账。
> **所有"已修好"必须有日志行或采样序列作证**；没有证据的推断一律标为「待验证」。

---

## 0. 总目标（用户原话，不可动摇）

- **适配所有情况的设备**。
- **做到真正的 Hi-Fi 发烧级**。
- **不接受任何降级或替代策略**。
- 注释不可信，**代码是唯一事实来源**；结论从实测拿，不从源码记忆推。
- 实物只有一台 **飞傲 JM21**（Android 13 / SDK 33，`bengal_515`）与一台测试手机 **OnePlus PLQ110**（Android 16 / SDK 36）；
  其它机型的结论必须逐条对照 Resonāda 的反编译（记录在 `D:\resonada\FINDINGS.md`），不许凭型号猜。

具体到 DSD：**DoP 与 Native DSD 都要在 DAP 的内置 DAC 上真正工作**，包括 Android 14/15/16。

---

## 1. 阶段表

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 判定骨架：`OutputPath.dapDirect`、`isBitExact`、`DapProfile`、`DapDsdCandidates` | ✅ 完成 |
| 2 | native 私有 ABI（`libaudioclient.so` 的 C++ `AudioTrack`） | ✅ 全链路通（set/start/write/getPosition 都有实测） |
| 3 | 候选表逐条对照反编译 + 真机实测 | ✅ JM21 上 `0x1A000002 @ 88200 + DIRECT` 建成流并持续排空 |
| 4 | 能力探测 `DapCapability` + `DapCapabilityProbe`（落盘 / 逐条进度 / 单候选硬超时） | ✅ 两台设备都跑通 |
| 5 | 接进 `AnnieAudioSink`（`DapDirectOutput` 第四条后端） | 🟡 代码就位，**真机从未开流成功**（见 §3.1） |
| 6 | 厂商特化：飞傲 `setParameters`、FPGA sysfs | 🟢 飞傲已生效（探测阶段验证过）；FPGA 无设备 |
| 7 | DSD→PCM 的采样率/重采样链路正确性 | ✅ 已修（三个串曲 bug，见 §2.4） |
| 8 | 位置/时间轴（进度条） | 🟡 **已找到机制并接管时钟**，但修复尚未在真机触发过一次（见 §3.2） |

单测 737 全绿。

---

## 2. 已验证事实（有证据）

### 2.1 不需要厂商私有库；符号可运行时解析

`libfiioaudio.so` / `libsmartaudioservice.so` 只作机型标记，从不 `dlopen`。
`dl_iterate_phdr` 找 `libaudioclient.so` → 解析 `PT_DYNAMIC` → 扫 `.dynsym`。
⚠️ const 成员函数前缀是 `_ZNK`，匹配只能写 `_ZN` + `strstr`。

### 2.2 `set()` 常有三份重载（踩过）

同一个库里 `AudioTrack::set` 可能是回调函数指针版 / `wp` 版 / 老式 `j` format 版。
**匹配到第一个就 `break` 是错的** —— 必须在命中之后才 break，否则 `abi` 留 0、整条路被判不支持（JM21 上真踩过）。

### 2.3 JM21 实测：候选表与 HAL 声明一一对应

`/vendor/etc/audio/audio_policy_configuration.xml` 的 `direct_pcm` 端口（DIRECT）只声明了
`AUDIO_FORMAT_DSD_16_BIT @ 176400` 与 `AUDIO_FORMAT_DSD_32_BIT @ 88200`。实测恰好只有这两条建成流，
`flags=0` 的变体全被堵死（idle=41）→ **`flags` 必须 DIRECT**。

### 2.4 DSD→PCM 链路：三个"串曲"bug 已修（都有日志对照）

| bug | 证据 | 修法 |
|---|---|---|
| 系统通路的 `PcmAudioSink` 没接 `rawPassthrough` | `dap=skip: 格式对不上 format(rate=352800 enc=21 …)` | 系统通路分支也传 `rawPassthrough` |
| DSD 目标率来自**异步** `publish` | `planRate=176400` 但 `cfglast=352800/21/2` | `_applyOutputPolicy` 改用同步值 `dsdFullChainRate` |
| 重采样器配置比曲目晚一拍 | 切回 44.1k 仍 `cfglast=176400/4/2` | `_setSource` 里先 `_primeResamplerFor(url)`，非 DSD 显式关闭 |

修好后两台机器一致：`deliv=44100 cfglast=44100/2/2` / `deliv=176400 cfglast=176400/4/2`。

### 2.5 五个参数错误（每个都真踩过）

| # | 错误 | 症状 | 正确 |
|---|---|---|---|
| 1 | `AttributionSourceState` 只给 128 字节 | 堆越界，日志报 `__stack_chk_fail` | 给 **512** |
| 2 | `attrs`/`sp`/`wp` 放栈上 | 金丝雀被写坏 → `abort` | 三个都 `calloc` 到堆 |
| 3 | `audio_format_t` 用 Java 编号（21） | 非法 format | native：`PCM_16=1`、`PCM_24_PACKED=6` |
| 4 | `channel_mask` 用 Java 编号（0xC） | `BAD_VALUE` | native：`STEREO=3` |
| 5 | `streamType=3` | OPPO 定制 `set()` 直接 `BAD_VALUE` | 传 **-1** |

构造函数返回 `void`，只能靠 **vptr 非空** 判成败；**不要**用 `mStatus(+0x220)` 当否决条件（Android 13 上是垃圾值）。

### 2.6 Media3 侧两条必须记住的字节码事实

- **`DecoderAudioRenderer` 传给 `AudioSink.configure` 的 `bufferSize` 恒为 0**（`iconst_0`）。
  所以"把 bufferSize 乘几倍再传进去"这类改动**完全无效** —— 曾经在 `AnnieAudioSink` 里写过
  `if (rate > 48k) bufferSize * 4`，从未生效过一次。
- **轨缓冲由 `DefaultAudioTrackBufferSizeProvider` 决定**：
  `clamp(getMinBufferSize × 4, 250ms, 750ms)`，与采样率无关。实测这台机器环深 430–520 ms。

### 2.7 JM21 上的位置缺陷：量化完整

同一首歌（48k / 24-bit，`cfglast=48000/21/2`、`native=48000`，与手机逐项相同）：
**`cfg` 每首歌只 +1**，即全程没有第二次 `configure`；`wmax=25 ms`（单次写最长 25 ms，**没阻塞**）；
`bytes/墙钟 = 288 768 B/s = 1.0025 倍实时`（供数、写、帧宽全正常）。

坏状态下 20 秒的采样：

```
+0s   pos=253   posRaw=253 276    deliv=682 664    queue=429 388
+2s   pos=1911  posRaw=1 599 548  deliv=2 730 656  queue=1 131 108
STALL pos=1911  posRaw=1 382 163  deliv=2 986 655  queue=1 604 492   ← 播放头倒退 217 ms
RESUME pos=2072 posRaw=2 072 743  deliv=4 437 316  queue=2 364 573
+20s  pos=18401 posRaw=18 401 193 deliv=20 735 919 queue=2 334 726
```

- 墙钟 20 011 ms，`posRaw` 只涨 18 155 ms → **丢 1 845 ms，之后斜率精确 1.000**；
- `queue = deliv − posRaw` 从 0.44 s 涨到 **2.34 s 后不动**，而环只有 0.5 s ⇒ 多出来的 1.84 s 就是被少算的量；
- 同一首歌**重启设备后**只丢 12 ms。⇒ **这是设备侧的会话级状态，不是每首歌必现的缺陷。**
- 期间 `state=3`、`loading=false` → **不是重缓冲**；`uiPos` 跟随 `pos` → **不是界面**。

### 2.8 位置偏移是常量，不是噪声

`DefaultAudioSink.getCurrentPositionUs()` 报的值带一个**精确的 1e12 µs 偏移**
（`raw − 1e12` 与播放器位置毫秒级相等，两次进程、四组采样都一样）。
任何把 `raw` 与"我方交付量"直接比较的写法都会被它吃掉 —— 见 §4 第 ④ 条。

---

## 3. 仍存在的问题

### 3.1 内置 DAC 那条路**从未真正开流成功**

`files/audio-status.txt` 至今**没有一行** `eff=dap-direct`；所有 `dap=` 都是 `skip:`：
`skip: dsdMode=pcm` / `skip: 无实测可用组合` / `skip: 格式对不上 …`。
含义：每次都在**前置判断**就返回，`NativeDapSink.open()` 一次都没被调用。
早期"被 HAL 拒绝（-22/-38）"的推断全部作废 —— 那些码是**探测阶段**的现象。
推进条件：让 `dap=` 出现 `rc=…`。

### 3.2 位置缺陷：机制已找到、时钟已接管，**但修复没有过一次真机触发**

现行实现（`SystemOutput`）：

```
d     = 播放头 − 交付量            // = 偏移 − 环深 − 少算量；偏移在相减里消掉
基准  = max(d)                     // 进程级，只在"交付 > 800 ms"（环已填满）之后采样
lag   = 基准 − d                   // 门限 300 ms … 5 s，低于当抖动、高于当起点不一致
位置  = max(播放头 + lag, 上一次返回值)
```

- 不依赖机型、不依赖 `PROPERTY_OUTPUT_SAMPLE_RATE`、不做比值自标定；
- 设备正常时 `lag` 恒 0，行为与改动前逐字相同（手机永远走这条路）；
- `交付量` 由 `AnnieAudioSink.handleBuffer` 按 `buffer.position` 的**增量**累加
  （不能用 `remaining()`：写不进时 Media3 会拿同一块反复重试）。

**未验证**：重启设备后此缺陷不再复现，所以 `crr` 至今一次非零都没有。
这套逻辑目前只有 8 个单测（`SystemOutputPositionTest`）保证，**没有真机证据**。

### 3.3 设备的会话级状态由什么带入，未定位

重启清零、跑久出现。两个候选，用零代码 A/B 可分：

| 候选 | 机制 | 验法 |
|---|---|---|
| A. HAL/DSP 自身累积 | 飞傲 HAL 的时钟/缓冲记账随开关流次数漂移 | 重启后只播普通 PCM、**不进 DSD 页、不开探测**，连续放半小时 |
| B. 我方探测带坏 | `AudioDirectProbe.capabilities` 每轮开真轨试 176.4k/192k；`DapCapabilityProbe` 还会下发 `setParameters(format=436207618, sampling_rate=…)` | 正常播放 → 手动进一次「输出能力（实测）」页 → 再播放，看是否立刻出现 |

若结论是 B，修法是"播放中不许开试探轨，探测完必须把 HAL 复位到**当前播放规格**"（不是固定 48000）。

---

## 4. 已试过并失败的修法（别再重复）

| # | 做法 | 门控/判据 | 结果 |
|---|---|---|---|
| 1 | 软时钟（写入帧数 + 墙钟）替换 Media3 位置 | `PROPERTY_OUTPUT_SAMPLE_RATE != 交付率` | ❌ 该属性是**静态**的，44.1k 普通曲目也命中 → 把手机弄慢 |
| 2 | 自标定（`Δ位置/Δ墙钟` 后按比值放大） | 实测比值 | ❌ 实测 `ratio≈1.0`，修正是纯噪声放大 |
| 3 | 停滞补偿（连续不动则补真实时间） | `dRaw==0` 连续 ≥400 ms | ❌ `stall` 恒为 0（那时是**起播那一刻**写的状态行，本来就读不到） |
| 4 | 位置 = `max(播放头, 交付量 − 环深)` | 无门控 | ❌ **完全没生效**：播放头带 1e12 µs 偏移，永远赢掉 `max` |
| 5 | 提高高率轨缓冲（`bufferSize * 4`） | `rate > 48k` | ❌ 死代码：`DecoderAudioRenderer` 传进来的 `bufferSize` 恒为 0（§2.6） |

**共同错误**：不去先量清楚"到底是哪一层在动"，就改一个报出来的数。
现行做法（§3.2）之所以不重复这些坑，是因为判据全部来自同一行采样里的**两个独立量**（播放头 vs 我方字节数），
且偏移/环深都在相减里消掉。

两次"诊断本身错"的教训：
- 采样线程不能碰 Media3 的 `currentPosition`/`duration`（主线程限定），否则整列写成 `-1`；
- 判停滞不能按单次采样步长（Media3 每十来毫秒拉一次），必须按**连续段**；
- 固定时刻采样抓不到间歇故障，必须**事件驱动**（位置不动 ≥300 ms 就记）。

---

## 5. 诊断栏（`files/audio-status.txt`）

| 字段 | 含义 | 用法 |
|---|---|---|
| `srcRate` / `decoded` | 源率 / 解码网格率 | DSD64 → 2 822 400 / 352 800 |
| `planRate` / `deliv` | 计划交付率 | DSD 固定档位应等于 `deliv` |
| **`cfglast`** | **真正交给 `AudioTrack` 的 `率/编码/声道`** | 与 `deliv` 不一致即链路错 |
| `inv` | 不变式护栏 | 永远应为 `-` |
| `cfg` | 进程内 `configure` 累计次数 | 一首歌内持续增长 = 反复重建 `AudioTrack` |
| `native` | `PROPERTY_OUTPUT_SAMPLE_RATE` | **静态属性，只作参考，不可当判据** |
| `dap` | 上次内置 DAC 开流的一句话结论 | `skip:` 前缀 = 没走到开流 |
| **`crr`** | 位置被纠正回来多少（ms） | 正常恒 `0`；出现大值是设备进了坏状态 |
| `raw` / `dsdnote` | 实际传输方式 / 降级原因 | DSD 应为 `native`（内置 DAC）或 `pcm`（回退） |
| `want` / `eff` | 通路意图 / 实测通路 | `eff=dap-direct` 才是内置 DAC 真的在响 |

---

## 6. 下一步

1. **§3.3 的 A/B**：重启后先只播 PCM 跑久，再进一次「输出能力（实测）」页复测。
   结论是 B 就去修探测，是 A 就接受"接住它"并把这条写进设备兼容表。
2. **§3.1**：按序排查 `dap=skip:` 的三种前缀，直到出现 `rc=`；`rc=0` 之后才谈"出声"。
3. **§3.2 的真机验证**：等下一次坏状态出现，看 `crr` 是否跳到约 1800，以及 `pos` 是否不再落后。
   若 `crr` 全程为 0 而进度条仍落后，说明判据选错了层，回来重读 §2.7 的采样再动。

---

## 7. 红线

1. Java 编号与 native 编号是两套数，`format` 与 `channelMask` 都必须转换。
2. `streamType` 用 `-1`。
3. `TRANSFER_SYNC` 下先 `play()` 再 `write()`。
4. 崩溃保护：`pthread_once` 装一次 + 转发原 handler；信号处理器里只赋值；所有入口都保护。
5. `AttributionSourceState` 缓冲 512；`attrs`/`sp`/`wp` 堆分配。
6. 收尾**不许同步 close**：`pause()` 与析构在 PLQ110 上都不返回；走异步 teardown。
7. 探测每候选独立线程 + 硬超时；卡住的候选不能拖死整次探测。
8. **`PROPERTY_OUTPUT_SAMPLE_RATE` 是静态属性，任何时候都不许拿它做判据**（已错两次）。
9. 改位置之前必须同时拿到**播放头**与**我方交付量**两个独立读数；只有一个就读不出谁在撒谎。
10. **任何拿 `DefaultAudioSink` 回报做算术的地方都要先确认偏移**（本机 1e12 µs，§2.8）。
11. 临时诊断用完必须删干净；固定时刻采样抓不到间歇故障，要事件驱动。
12. 只有用户明确说「提交」才 commit；从不 push。

---

## 8. 环境与命令

- 设备：`PLQ110`（OnePlus / Android 16）`192.168.3.10:5555`；`JM21`（飞傲 / Android 13）**USB 连接**，
  serial 显示为 `?`，**transport_id 每次都变**（先 `adb devices -l`，再用 `adb -t <id>`）。
- `adb`：`D:\ANSSDK\platform-tools\adb.exe`；`JAVA_HOME=D:\ANS\jbr`；NDK `27.1.12297006`，仅 `arm64-v8a`。
- 编译 + 单测：`.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --console=plain`
- 装机：`.\gradlew.bat :app:assembleDebug` → `adb install -r app\build\outputs\apk\debug\app-debug.apk`（**不要 `--no-streaming`**）
- 状态：`adb -t <id> shell "run-as com.annieplayer.annie_music.kt tail -n 5 files/audio-status.txt"`
- 探测：`… cat files/dap-capability.txt`
- 轨/端口：`adb shell dumpsys media.audio_flinger`（看 Output thread 的 `Sample rate` / `Standby` / `FrmRdy` / `Underruns`）
- **JM21 的 logcat 可用**（2026-11-15 实测）：
  - 本应用日志：`adb shell logcat -d | grep '$pid' | grep -iE 'dap|dsd|AudioTrack'`
    （native 的 `LOGI` 走 `annie-dap` 标签，出现在本进程 pid 行里）
  - **更值钱的是厂商侧**：`fiioprocess` / `fiioaudio` / `AudioStream` / `AHAL` 会把
    `set()` 的全部参数、`hwRate`/`dstFormat`/`frameSize`、`adev_open_output_stream` 打出来
    —— 这是判断"设备为什么不取数"的唯一直接证据。
  - ⚠️ 本文档曾写「JM21 的 logcat 不保留 app 日志」（或写的是当时 `-s annie-dap` 过滤为空），
    并据此只靠 `files/` 计数器推断了三轮，**那是错的**。文档结论同样按"注释不可信"对待。
- 两台机器 adb 都不能 root；`kill -3` 被拒（`Operation not permitted`）。
- **两条机型事实（RE 给不了，只能实测）**：
  - 私有 DSD 端口的 `getPosition()` 返回 **-38**（`INVALID_OPERATION`）→ 时钟必须自建。
  - 建出来的 DIRECT 线程 `Processing frame size: 1 bytes`，而 HAL 自报 `frameSize 8`
    → 它的帧/字节换算不可信；我方只按**字节**喂，不依赖它的帧长。

## 9. 参考产物

- `D:\resonada\FINDINGS.md` —— 逐条对照反编译的候选表、判定函数、建轨参数
- `D:\resonada\work\lib\arm64-v8a\libaudiophile_native.so.i64` —— IDA 库
  `sub_229764`(open) / `sub_22B510`(保护+建轨) / `sub_236908`(set) / `sub_227104`(FPGA) /
  `sub_227210`(飞傲) / `sub_225F44`(山灵) / `sub_227CF4`(Cayin) / `sub_228188`(smartaudio)
- `D:\resonada\work\lib\jm21-libaudioclient.so` —— 从 JM21 拉的系统库，用于核对符号
- Media3 1.4.1 的 `DecoderAudioRenderer` / `DefaultAudioTrackBufferSizeProvider` 字节码
  （`javap -p -c`，用来确认 `bufferSize=0` 与环深公式）

---

## 10. AAudio 独占（MMAP）：能开、能对，但在这台机器上出不了可用声音

### 10.1 整份策略里只有一个 MMAP 端口

`/vendor/etc/audio/audio_policy_configuration.xml:91`

```xml
<mixPort name="mmap_no_irq_out" role="source" flags="AUDIO_OUTPUT_FLAG_DIRECT AUDIO_OUTPUT_FLAG_MMAP_NOIRQ">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
             samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
</mixPort>
```

- 唯一可用的 AAudio 独占组合：**48000 Hz / PCM 16 bit / 立体声**。
- 44.1k / 88.2k / 96k / 192k 没有 MMAP 端口 → AAudio 侧 graceful 失败
  （`AAudioServiceEndpointMMAP: openMmapStream() returned status -38` = ENOSYS），AAudio 自己换
  FLOAT / 32_BIT 重试也全是 `-38`。**44.1k 永远拿不到独占**。
- 这个端口上没有声明 `PCM_FLOAT` / `PCM_24_BIT_PACKED` → 24 位 / float 的独占在本机不存在
  （不是我们没实现）。

### 10.2 请求未声明的组合会把 HAL 打死（不要再犯）

旧探测硬编码 `[44.1, 48, 88.2, 96, 192] × [I24, I16, FLOAT]` 逐个真开流。对没声明的组合
AAudio 照样向 HAL 申请 MMAP buffer，飞傲 HAL 抛异常自杀：

```
Abort message: 'terminating with uncaught exception of type St12out_of_range'
  #06 StreamOutPrimary::Open()
  #07 StreamOutPrimary::CreateMmapBuffer(int, audio_mmap_buffer_info*)
  #08 astream_out_create_mmap_buffer
Cmdline: /vendor/bin/hw/android.hardware.audio.service_64
```

实证两次（`03:54:59.514` / `03:55:01.368`），HAL pid `1052 → 6567 → 6662`，audioserver 同步重启。
HAL 一崩，后续候选全在“重启中的服务”上失败 → 探测矩阵全 `false` → `aaudioExclusiveAt(rate)`
永远 false → 链路永远回落 AudioTrack。**「16/48 的歌也起不来独占」就是这么来的：不是歌的问题，
是探测先把 HAL 打死在前。**

修法（已入库）：`core/audio/MmapProfiles.kt` 读 policy 里 `flags` 含 `MMAP_NOIRQ` 的 `<mixPort>`，
探测**只探它声明的 `(采样率, 格式)`**（`audio_policy_configuration.xml` 与 `…_gaming.xml` 取并集）；
文件读不到时退到 `(primary 主率, I16)` 单候选，失败也是 graceful 的 `-38`。
`AudioDirectProbe.probeAaudioSync` 改成逐格式发布结论，某个候选崩了也不丢已得结论。

验证：`createMmapBuffer() buffer_size = 6144 fr, burst_size = 48 fr` 成功；HAL pid 前后一致；
`out_of_range` 计数 0。

### 10.3 独占成立后的读数（48k/16）

`files/audio-status.txt`：

```
eff=aaudio-exclusive  planPath=aaudio-exclusive  planRate=0  srcRate=48000  planTier=bitPerfect
```

播放期统计（临时插桩，已删除）：`fw`/`fr` 每 500 ms 都涨 ≈24.2k 帧（≈48.4k/s，二者同速）、
`fw − fr` 恒 18,720 帧、`ring` 恒 ≈18,720 帧、`buf` 恒 6048/6144、稳态 `und` / `st` 不动、
`gapMax` 2.6–7.7 ms（期望 2.67 ms）。

### 10.4 A/B：卡顿在 AAudio 这条路，不在数据

同一首 48k/16 FLAC、同样不做重采样，只换出口：

| 出口 | 听感 |
|---|---|
| `aaudio-exclusive`（MMAP 直连） | 一直卡 |
| `audio-track`（deep buffer，48k 源 = 48k） | 顺畅 |

配合 10.3：解码 → PCM → 写入 → 缓冲 整段干净，差异只发生在 `mmap_no_irq_out` 端点 → DAC 之间。
旁证：该 HAL 自报 `getExternalPosition, the reported position is not changed within 5 msec.
Set the external position as not supported`（连位置都不报），`AAudioStream_getXRunCount()` 恒 0
（**不可信**，不能拿来当“没有欠载”的证据）。

**结论：JM21 上 AAudio 独占 = 能开、逐字节保真、但出不了可用声音。**
要保真只剩 USB 独占（UAC 端点）与内置 DAC 私有格式两条路；应用层修不了（HAL 是二进制、本机无 root）。

### 10.4.1 DoP over AAudio（已实现，本机永远不触发）

DoP 帧本身就是 24 bit PCM（DSD64 → 176 400 Hz），所以它能走 AAudio 的**唯一**条件是：
这台机器的 policy 声明了该率/该格式的 MMAP 端口，且探测真的开出过独占流。链路：

```
OutputRequest(dopFrames = transport == dop,
              aaudioDopExclusive = AudioDirectProbe.probedExclusive(dopFrameRate, I24) == true)
  → SinkRequest(dopFrames, aaudioExclusiveAvailable = 上面那个值)
  → resolveSinkChain: raw && dop && aaudio → [oboe]
  → decideDsdTransport(sink = oboe, aaudioDopReady) → dop 才成立，否则 PCM(PathNotBitExact)
```

- `native` 帧永远不走 AAudio（AAudio 只有 PCM 格式，1-bit 只能靠厂商私有 format）。
- 用户要 native 但私有 format 不可用时，`resolveDsdChain` 会退到 **DoP over AAudio**（若探测成立）。
- 增益强制 1.0（`streamRawFrames`），DoP 标记不被乘 —— 与 UAC 那条路同一判据。
- **JM21 上恒不成立**：policy 的 MMAP 端口只有 `48000/PCM_16_BIT`，`probedExclusive(176400, I24)`
  永远是 false，所以 `_aaudioDopExclusive()` 返回 false，链里不会出现 oboe 的 DoP 分支。
- 新增测试：`SinkResolverTest`（DoP→oboe / DoP 无 AAudio→私有 format / 两者都无→空链 /
  native 不看 AAudio）、`DsdTransportPlanTest`（DoP+oboe 成立 / 未探到→PCM / native+oboe→PCM /
  native 不可用退 DoP）。

### 10.5 回滚掉的实验（别再重复）

- 回调粒度 128 → 960 帧（20 ms）：AAudio 没按它回调（`gapMax` 仍 2.6 ms），而首播预填变长，
  表现为“一开始没声音、随后才出声”。已回滚到 128 帧，测试同步回滚。
- `openWire` 把 `framesPerCallback` / `bufferCapacityFrames` 从“仅重试生效”改成“首开也生效”：
  与上一条同源（正是它暴露了 960 生效不了），一并回滚。
- 保留：native `setBufferSizeInFrames(max(请求帧数, 设备 capacity))` —— 不再替设备缩小缓冲
  （设备给 6144 帧 / 128 ms，旧请求只有 4800 帧）。

### 10.6 复现与观察入口

```powershell
$adb = "D:\ANSSDK\platform-tools\adb.exe"
& $adb -t <id> shell run-as com.annieplayer.annie_music.kt cat files/audio-status.txt
& $adb -t <id> shell logcat -d | Select-String -Pattern 'AAudio|AAudioServiceEndpointMMAP|out_of_range'
& $adb -t <id> shell dumpsys media.audio_flinger | Select-String -Pattern 'Output thread|Sample rate|No FastMixer'
& $adb -t <id> shell pidof android.hardware.audio.service_64   # 探测前后对比：pid 变了 = HAL 崩过
```

开关：`更多设置 → AAudio 独占`（prefs `audio-aaudio-exclusive`）、`USB 独占`（`audio-output-path=usb-direct`）。
两个都关时普通 PCM 走 AudioTrack。

### 10.7 仍未验证

- 24 位内容改用 **FLOAT 容器**在 48k 上能不能开 MMAP（policy 没声明 float，探测会跳过）。
  要做必须单独一次受控实验，风险是再 abort 一次 HAL。
- 游戏模式的 `audio_policy_configuration_gaming.xml` 是否声明更多 MMAP 端口 —— 解析已取并集，
  但没有实测切到游戏模式。

