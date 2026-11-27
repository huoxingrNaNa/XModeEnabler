# HANDOFF · XModeEnabler v1.6.11

交接说明：便于下一位维护者 / 验证者快速接手。原理、逆向细节与用法见
[`README.md`](README.md) 与 [`docs/REVERSE_NOTES.md`](docs/REVERSE_NOTES.md)。
版本演进见 README 的「版本演进」小节。

---

## 1. 仓库与交付物

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/huoxingrNaNa/XModeEnabler （Public / `main`） |
| 当前版本 | `v1.6.11`（`versionCode 22` / `versionName 1.6.11`） |
| 关键提交 | `cfbc81f`（v1.2 ~ v1.5 全部改动）、`ff34d8a`（v1.1 文档收尾） |
| tag / Release | `v1.6.11`，Release 附加 `XModeEnabler-v1.6.11-release.apk`（`v1.6.10` / `v1.6.9` / `v1.6.8` / `v1.6.7` / `v1.6.1` / `v1.6` 保留） |
| 旧版本 | `v1.1`（debug 签名）、`v1.5` Release 保留 |

## 2. 构建

环境：**JDK 17** + **Android SDK（compileSdk 34）**

```bash
cp local.properties.example local.properties   # 填 sdk.dir
./gradlew assembleDebug                        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                      # app/build/outputs/apk/release/（需 keystore.properties，缺失则不签名）
```

> **arm64 设备本机编译（proot / Termux）**：Maven 的 `aapt2` 为 x86_64，会 `Illegal instruction`。
> 需在 `gradle.properties` 取消注释并指向系统 aapt2：
> `android.aapt2FromMavenOverride=/path/to/android-sdk/build-tools/34.0.0/aapt2`
> （普通 PC / CI 构建不需要。）

## 3. CI

`.github/workflows/android.yml`：push 到 `main`（及 PR、手动触发）后执行
`assembleDebug` + `assembleRelease`，并上传两个 artifact。
CI 无签名密钥，产出的 release 包为未签名。

## 4. 运行期前置条件

- **Shizuku** 已安装并处于运行状态（所有 `am` / `logcat` 指令都借它的 shell 身份）。
- 游戏助手的「自动化测试」开关**必须开启**（`auto_test_key` 总闸，App 首屏第 3 行可一键跳隐藏页）。
- 「使用情况访问」权限。
- 建议「忽略电池优化」；Android 13+ 还需允许**通知权限**（前台服务通知，v1.3 起通知带操作按钮）。

## 5. 功能清单（v1.6）

底部两个标签页：**首页 / 关于**。

**首页**
- 主状态：未启动 / 监控中 / X 模式生效中（脉冲动效）
- 实时状态：电池温度、CPU 最高簇当前/上限频率、最热热区（≥40℃ 黄、≥45℃ 红）
- 准备工作：Shizuku、使用情况访问、自动化测试开关、忽略电池优化
- 监控：启动/停止常驻监控、手动投递一次
- 游戏白名单：为空表示对所有游戏生效；玩过一次后可从「最近检测到的游戏」加入
- 诊断日志：最近 12 条（内部保留 60 条），含会话起落、前台应用、助手模式变化、投递失败原因；带「记录开/关」开关与「导出」（SAF 另存为，无需存储权限）
- 工作原理：事件驱动 + 90 秒安全网说明

**关于**
- 应用信息（版本 / 作者 / 许可 / 包名 / 适配版本）
- 可点击入口：**源码仓库**（浏览器打开）、**开源许可证**（进入许可证页）
- 使用步骤、风险提示

**许可证页**：本应用 MIT 全文；Shizuku API/Provider = Apache-2.0；AndroidX Annotation = Apache-2.0。

**通知栏**：状态文本（阶段 / 温度 / 模式 / 补投·跳过计数）+ 两个按钮「立即投递」「停止」，点击通知回到 App。

## 6. 关键修复记录（v1.2）

`ShizukuRemoteProcess` 在进程未退出时抛 `IllegalArgumentException("process hasn't exited")`，
而标准 `Process.waitFor(timeout)` 内部只捕 `IllegalThreadStateException` → 异常冒泡 → `rc=-1` →
**所有 shell 操作被误判失败**（包括 X 模式投递与打开隐藏页）。
修复：自写轮询 `exitValue()`，两种异常都视为「仍在运行」，8 秒 deadline；
同时让 stdout 读取与等待并发（否则管道写满互锁）。

## 7. 运行期实现要点

- 每 tick 只发 **一条** shell 命令，同时取回：`ResumedActivity`、电池温度、最高簇频率、最热热区。
- 心跳自适应：游戏会话中 5s，待机 15s。
- 单次投递可维持约 44 秒，因此只监听助手日志，在模式被改回时补投，另有 90 秒安全网。
- 白名单与已识别游戏存 `SharedPreferences`（`xmode_prefs`）；诊断事件存 `files/events.log`。

## 8. APK 核验事实（v1.6.11 产物）

| 项 | 值 |
|---|---|
| package | `com.operit.xmode` |
| version | `versionCode 22` / `versionName 1.6.11` |
| sdk | minSdk 26 / targetSdk 36（上岛需要） |
| 应用名 | X模式助手 |
| 签名 | v2（APK Signature Scheme v2）= true；RSA 2048 |
| 证书 DN | `CN=XModeEnabler, OU=Personal, O=Personal, L=Unknown, ST=Unknown, C=CN` |
| 证书 SHA-256 | `c2f87c95ba6c289f406b1922803e15c1a6ace75e73b78794ebb7eab7c46ba625` |
| APK SHA-256 | `bebdac131053d749085466f83298b0a8b67f4c193881ce0738c4a44d51aa9b34` |
| 体积 | release 59584 B（R8 + 资源收缩后） |

## 9. 真机验证记录

设备：OnePlus PLQ110，ColorOS / Android 16（SDK 36），arm64-v8a，无 Root，Shizuku 通道。

- v1.1：事件驱动生效，一局 69 秒仅投递 1 次（旧版约 14 次），助手侧日志出现
  `chengPerfMode 3` → `toAppliedMode = 3` → `setIsChangeXMode state:true`
- v1.2：修 `exec()` 后「打开隐藏页」「投递」均恢复；正式签名安装、`apksigner verify` 通过
- v1.3 ~ v1.5：实时采样数值与系统读数一致（电池 33.4℃、最高簇 1.2/4.32GHz、热区峰值 54.7℃ 量级）；
  白名单、诊断日志、通知按钮、标签页与许可证页在真机安装启动无崩溃

## 10. 已知限制

- 强依赖 ColorOS 游戏助手内部实现，助手更新即可能失效（类名 / action / SharedPreferences key / 日志格式）。
- X 模式为官方最激进的性能策略，官方要求配散热背夹；第三方制冷不足时可能高温降频。
- 无自动化测试（无 `test/`、`androidTest/`）。
- 无开机自启（按需求排除）；重启手机后需手动点「启动常驻监控」。
- 日志导出依赖系统 DocumentsUI；极简 ROM 若阉割了 SAF 面板会提示打开失败。
- 隐藏页里的「自动化测试」状态读不到（私有存储），App 固定显示「打开开关」，**不伪造已完成状态**。
- 白名单只按前台游戏包名过滤；不改变助手自身的模式判定逻辑。

## 11. 相关文档

- `README.md` —— 原理、用法、构建、签名、限制
- `docs/REVERSE_NOTES.md` —— 调用链、`auto_test_key` 总闸、44 秒实测、气泡来源、踩坑
- `docs/OPTIMIZATIONS.md` —— v1.1 相对 v1.0 的优化清单
