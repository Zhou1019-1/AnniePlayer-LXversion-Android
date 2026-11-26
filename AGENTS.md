# 安妮播放器 Android 仓库 · AI 协作规范

本文件适用于所有参与本仓库的人类贡献者与 AI agent。开始任何修改前，必须先阅读本文件及 `CONTRIBUTING.md`。

## 一、项目边界

- **章鱼只负责 Windows 版开发**，不负责 Android 或其他桌面/移动平台的功能开发。
- 本仓库的 Android 应用由电狗（`@chenhaochen66`）作为平台负责人主导设计、实现、测试和维护；章鱼作为仓库 owner 进行治理、轻审与发布审批，不承诺实现 Android 需求。
- 当前仓库尚无 Android 应用源码或真实工程。不得伪造工程、构建结果、测试记录、Run、Artifact、SHA256 或发布状态。

## 二、分支与 PR 硬规则

1. 所有人和 AI 的所有改动都必须从独立分支发起 PR；功能开发使用 `feature/<主题>`，治理任务可使用 `chore/<主题>`。
2. **禁止直接推送 `main`**，包括文档、配置、格式化及“微小修复”。AI 不得以任务紧急、已获口头认可等理由绕过 PR。
3. 未经 owner 明确批准，任何人和 AI 均不得创建或推送 tag、创建或发布 Release，也不得擅自合并 PR 或修改仓库设置。
4. PR 必须如实列出改动、验证命令与结果、未验证事项和已知限制；不得把“未运行”写成“通过”。
5. 代码、文档、workflow 的变更都要经过审阅。AI 生成内容由提交者负责核对，不能以 AI 输出代替测试或审查。
6. **Android 平台负责人轻审制（2026-11-26 起）**：电狗主导日常代码结构、依赖选择、构建方式、测试安排和溯源文档形式，并对 AI 产出负责。owner 保留 1 次 approval 并负责合并，但只审 PR 摘要、范围、验证声明与签名秘密、workflow、发布等风险边界；不默认逐行审查、逐项重算全部二进制哈希或按个人技术偏好反复打回。电狗仍不得自行合并，owner 可事后抽查。

## 三、联系与 agent 汇报

- **QQ 是主要联系渠道**，具体联系方式以章鱼公开的信息为准。
- 需要章鱼协调评审、权限、Runner、签名或发布时，贡献者 agent 必须发送以下标准模板；没有的项目填“无”，不得编造：

```text
仓库：<owner/repo 与 URL>
PR：<PR URL 或编号；未创建则说明>
分支 commit：<分支名 + 完整 commit SHA>
改动：<按文件概述实际改动>
测试：<逐项列出命令、结果；未运行须注明>
Run 或 Artifact：<GitHub Actions Run / Artifact URL；无则填无>
SHA256：<交付产物的 SHA256；无产物则填无>
需要协助：<需要章鱼处理的具体事项；无则填无>
已知限制：<风险、未覆盖场景或平台限制；无则填无>
```

## 四、秘密、证书与权限

- 密码、令牌、私钥、签名证书、keystore、Play 服务凭据及其他凭据**不得通过 QQ 传输**，也不得进入 Git、Issue、PR、日志、截图或 Artifact。
- 所需秘密和证书由 owner 在 **GitHub Secrets / Environments** 中配置并授权。贡献者只说明条目名称、用途和最小权限，不索取明文。
- 现有 Android 签名密钥由平台开发者在首次正式发布前一次性交付 owner：keystore 本体装进加密压缩包并走私密邮件，解压口令走另一条渠道。owner 写入 `release` Environment Secret 后删除本地明文，并保留离线备份；此后不得再通过仓库、PR、Issue、Actions 日志或群聊传输 keystore 本体、base64 或口令。
- PR 及来自 fork 的无签名构建不得读取发布秘密。发现泄露时立即停止相关工作流，并通过安全渠道通知 owner 轮换凭据。
- **Qobuz 自用客户端例外**：当前 Android 软件仅供自用、无上架计划，Qobuz 请求所需 `app_id` / `app_secret` 可作为接口常量记录在指定配置文件中，不按 Android 签名密钥处理，也不单独作为 PR 阻断项。用户 Token、Cookie、账号数据、GitHub Token 和其他服务凭据不在例外内。

## 五、文件与构建产物

二进制默认不直接进入 Git；仅本节白名单内的 Android 原生库与运行时资产可按普通 Git 对象入库。路径与类型必须同时命中，新增单文件不得超过 **90 MiB（94,371,840 字节）**。

### 5.1 允许入库的白名单（owner 于 2026-11-25 批准）

| 路径 | 内容 | 单文件上限 |
|---|---|---|
| `android-native/app/src/main/jniLibs/arm64-v8a/*.so` | 自编 / 抽取的原生库（ffmpeg 七件套、`libffmpegkit*`、`libc++_shared`、`libtaglib`、`libtemari`、`liboboe`） | 90 MiB |
| `android-native/wrapper-lite-app/app/src/main/jniLibs/arm64-v8a/*.so` | QEMU 可执行文件 `libqemu_x86_64.so` | 90 MiB |
| `android-native/wrapper-lite-app/app/src/main/assets/guest/**` | QEMU guest 资产 `initramfs.dat` / `vmlinuz` / `data.empty.dat` | 90 MiB |
| `android-native/wrapper-lite-app/app/src/main/assets/lib/**` | aarch64 依赖库 | 90 MiB |
| `android-native/wrapper-lite-app/app/src/main/assets/qemu-fw/**` | QEMU firmware / keymaps | 90 MiB |
| `android-native/wrapper-lite-app/assets-src/data.empty.img` | 生成 `data.empty.dat` 的空白 ext4 源镜像 | 90 MiB |

白名单仅适用于本仓，不得扩展到其他平台；是否改用 Git LFS 或新增路径只能由 owner 决定。

### 5.2 仍然禁止入库

- `.jks`、`.keystore`、`.p12`、`.p8`、`.mobileprovision` 及其他签名材料。
- APK、AAB、DMG、ZIP 安装包；产物只走 Actions Artifact 或 Draft Release。
- Token、Cookie、账号配置、`local.properties`、含秘密的配置、构建缓存和大型媒体样本。
- 来源不明、无版本与构建来源记录的第三方运行时。

### 5.3 二进制边界检查与轻审原则

1. 新增单文件 > 90 MiB，或路径不在白名单内，仍须调整。
2. 白名单内的 `.so` 应对应 `android-native/native/**` 下的构建脚本，或 PR 中记录的外部来源。
3. 自编产物以「构建脚本 + 补丁集 + 提交 SHA」追溯；抽取的第三方二进制记录原始 AAR / 上游项目、版本与 SHA256。
4. 二进制信息可写在 PR 正文，也可由 PR 链接仓内 AI 生成的溯源清单；至少包含来源、版本与 SHA256，不限定人工排版。
5. 电狗对清单完整性、版本判断和验证结论负责；owner 只做存在性检查与必要抽查，不默认逐项重算全部哈希或重复审计。
6. 白名单文件允许使用普通 Git 对象，不要求也不建议改用 Git LFS。

签名材料与本地配置只保留在开发者本机或 GitHub Secrets / Environments，仓库中不得放入真实凭据。当前仓库没有应用 workflow，不提供构建或发布能力；真实工程落地后必须通过 PR 新增并验证，流程约束见 `RELEASING.md`。保持现有 `LICENSE` 不变，除非另有明确授权与独立 PR。Android 当前自用且无上架计划，不因第三方项目采用 GPL 就默认拒收；仍须保留 LICENSE、NOTICE 和必要版权说明，向他人分发时按对应许可证履行义务。

## 六、Android 工程规则索引

本仓已落地真实 Android 工程：`android-native/`（AnniePlayer）与 `android-native/wrapper-lite-app/`（WrapperLite）。第五节末段「当前仓库没有应用 workflow」一句已被 `RELEASING.md` 第 1 节描述的 PR 无签名构建取代，对应 workflow 为 `.github/workflows/android-ci.yml`：只读源码、不使用任何 Secret、不建 tag、不建 Release，产物标注为无签名并记录 SHA256。

工程内部规则不在本文件，统一见 **`android-native/AGENTS.md`**：禁止写注释、代码分层与依赖方向、缓存边界、Apple 实现硬约束、页面栈与返回手势、Compose 写法红线、构建与本地单测命令。动手改 `android-native/` 之前必须先读那一份（在该目录下工作时它会被自动加载）。

二进制溯源清单：`android-native/BINARY-PROVENANCE.md`（主程序）与 `android-native/wrapper-lite-app/BINARY-PROVENANCE.md`（WrapperLite），逐文件列来源、版本与 SHA256。
