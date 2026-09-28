# HANDOFF · XModeEnabler v1.1

交接说明：便于下一位维护者 / 验证者快速接手。原理与逆向细节见
[`README.md`](README.md) 与 [`docs/REVERSE_NOTES.md`](docs/REVERSE_NOTES.md)，
本次相对 v1.0 的改动见 [`docs/OPTIMIZATIONS.md`](docs/OPTIMIZATIONS.md)。

---

## 1. 仓库与交付物

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/huoxingrNaNa/XModeEnabler （Public / `main`） |
| 当前版本 | `v1.1`（`versionCode 2` / `versionName 1.1`） |
| 关键提交 | `229bc12`（v1.1 全部改动）、`d3e598d`（初始提交） |
| tag / Release | `v1.1`，Release 附加 `XModeEnabler-v1.1-release.apk` |
| 源码同步副本 | 设备 `/sdcard/XModeEnabler`（与仓库逐字节一致） |

## 2. 构建

环境：**JDK 17** + **Android SDK（compileSdk 34）**

```bash
cp local.properties.example local.properties   # 填 sdk.dir
./gradlew assembleDebug                        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                      # app/build/outputs/apk/release/
```

> **arm64 设备本机编译（proot / Termux）**：Maven 的 `aapt2` 为 x86_64，会 `Illegal instruction`。
> 需在 `gradle.properties` 取消注释并指向系统 aapt2：
> `android.aapt2FromMavenOverride=/path/to/android-sdk/build-tools/34.0.0/aapt2`
> （普通 PC / CI 构建不需要。）

## 3. CI

`.github/workflows/android.yml`：push 到 `main`（及 PR、手动触发）后执行
`assembleDebug` + `assembleRelease`，并上传两个 artifact。

> ⚠️ **历史坑（已修）**：v1.1 首次 CI 失败，根因是
> `android-actions/setup-android@v3` 默认向 `sdkmanager` 请求 **`tools`** 包，
> 而 Google 已从 SDK 仓库移除该包 → `Failed to find package 'tools'`，exit code 1。
> 现改为复用 runner 预装 SDK，并用 `sdkmanager` 按需补齐
> `platform-tools` / `platforms;android-34` / `build-tools;34.0.0`。

## 4. 运行期前置条件

- **Shizuku** 已安装并处于运行状态（所有 `am` / `logcat` 指令都借它的 shell 身份）。
- 游戏助手「自动化测试」开关**必须开启**（`auto_test_key` 总闸，见 README 的隐藏页入口）。
- 「使用情况访问」权限（部分机型判断前台应用时需要）。
- 建议「忽略电池优化」，防止常驻服务被系统清理。

## 5. 交付状态

### ✅ 已完成
- v1.1 代码优化（FGS 竞态修复、dumpsys 管道化、事件驱动、R8/CI 等）
- 提交 `229bc12` 已推送；`v1.1` tag 与 Release 已建
- APK 交叉核验（`aapt2` + `apksigner` + MT 管理器三方一致）

### ⏳ 未完成 / 待办
- [ ] **真机回归验证**：v1.1 重写（`MonitorService` / `MainActivity`）后**尚未在真机跑过**，
      文档中的“补投 1 次/跳过 15 次、单次维持约 44 秒”来自改造阶段实测，需在发布包上复测。
- [ ] **release 包当前为 debug 密钥签名**，仅供测试安装；如需正式分发请配置正式 keystore。
- [ ] **无自动化测试**（无 `test/`、`androidTest/`）。
- [ ] 强依赖 ColorOS 游戏助手内部实现，助手更新即可能失效（类名 / action / SharedPreferences key / 日志格式）。

## 6. APK 核验事实（v1.1 产物）

| 项 | 值 |
|---|---|
| package | `com.operit.xmode` |
| version | `versionCode 2` / `versionName 1.1` |
| sdk | minSdk 26 / targetSdk 34 |
| 应用名 | X模式助手 |
| 签名 | `Verifies`（v2 + v3），`CN=Android Debug` |
| SHA-256 | `c199f5b2c3bce238051c476a44445bc331fd7deb89edc3706a10d31032d86a7f` |
| 体积 | debug 54159 B / release 41663 B |

## 7. 真机验证步骤（待办）

1. 启动 Shizuku，并在其中授权「X模式助手」。
2. 打开 App：确认状态区 `Shizuku / 使用情况访问 / 电池优化` 三项均正常。
3. 点「打开游戏助手自动化测试」，确认隐藏页第一项开关已开（**其余开关不要动**）。
4. 点「启动常驻监控」。
5. 进入任意支持的游戏，观察通知栏：
   `X模式生效中(跳过) | 模式=3 | 补投N次/跳过M次`。
6. 退出游戏后确认服务回到待机、不再投递。

## 8. 相关文档

- `README.md` —— 原理、用法、构建、限制
- `docs/REVERSE_NOTES.md` —— 调用链、`auto_test_key` 总闸、44 秒实测、气泡来源、踩坑
- `docs/OPTIMIZATIONS.md` —— v1.1 相对 v1.0 的优化清单
