# XModeEnabler · 第三方散热器也能开官方 X 模式

> 让 OnePlus / OPPO（ColorOS）自带的「游戏助手」对你**任意第三方散热背夹**开放官方 **X 模式**（原本仅限官方散热背夹触发）。

- 设备验证：**OnePlus PLQ110**，ColorOS / Android 16（SDK 36），arm64-v8a
- 目标应用：`com.oplus.games`（游戏助手 v10.24.20）
- **无需 Root**，全程通过 **Shizuku** 以 `shell` 身份投递指令
- 交付形态：**独立 Android App**（前台服务常驻）

---

## 原理

ColorOS 游戏助手内部有一个「自动化测试」调试通道。开启后，外部可向它的
`SpecialFeatureService` 投递性能模式指令：

```
SpecialFeatureService.onStartCommand
  └─ if (!RecordAutoTestResultsUtil.g()) return;   // ★ 总闸：auto_test_key 必须为 true
     └─ handleAction
        └─ isInGame 守卫                         // 只有游戏在前台才受理
           └─ getIntExtra("mode", -1) == 3
              └─ PerfModeFeature.y1(...) → x1(3) → setIsChangeXMode(true)   // ★ 开启 X 模式
```

即：**`mode = 3` 就等于 X 模式**。

投递命令（需 shell 权限）：

```bash
am startservice --user 0 \
  -n com.oplus.games/business.service.SpecialFeatureService \
  -a PERFORMANCE_CHANGE_ACTION --ei mode 3
```

由于 `SpecialFeatureService` 是 `exported=false` 的特权服务，普通 App 无法直接调用，
本应用借用 **Shizuku** 拿到 shell 身份后执行上面的 `am` 命令。

### 为什么不是"每 5 秒投一次"

实测（见 `docs/REVERSE_NOTES.md`）：**单次投递可维持约 44 秒**，之后助手会按"已保存模式"覆盖回普通模式。
但每次投递都会让助手弹一个 `mode = 3` 的调试悬浮气泡。若每 5 秒盲投，气泡几乎常驻，体验很差。

因此本应用采用**事件驱动**：

1. 用 Shizuku 起 `logcat --pid=<games_pid>` 监听游戏助手日志；
2. 解析 `toAppliedMode = N` 实时跟踪助手当前模式；
3. **只有当助手把模式改成非 3 时才补投一枪**，另加 **90 秒安全网**兜底。

实测一局 69 秒：投递 **1 次**（旧版约 14 次），气泡从"几乎常驻"降到"进游戏闪一下"。

---

## 使用方法

### 前置条件

| 项 | 说明 |
|---|---|
| Shizuku | 已安装并处于运行状态（[Shizuku](https://shizuku.rikka.app/)） |
| 游戏助手「自动化测试」 | **必须开启**（见下）|
| 使用情况访问权限 | 部分机型判断前台应用时需要 |

**如何打开游戏助手的隐藏「开发者选项」：**

```bash
am start -n com.oplus.games/business.compact.activity.GameDevelopOptionsActivity \
  -a oplus.intent.action.GAMESPACE_GAME_DEVELOP_OPTIONS \
  -c android.intent.category.DEFAULT \
  --es gameDevelopOptions "GameDevelopOptionsActivity"
```

> ⚠️ extra `gameDevelopOptions` 必须携带，否则页面会立即自毁。
> 页面里的**第一项「自动化测试」必须打开**（这是上文的 `auto_test_key` 总闸）；
> 其余开关不要随意改动（改动会连带影响该总闸的写入）。

### App 内操作

1. 点 **① 授权 Shizuku** → 允许
2. 点 **② 授权「使用情况访问」** → 在系统列表里允许
3. 点 **③** 打开游戏助手的隐藏页，把第一项「自动化测试」打开（其余开关别动）
4. 点 **④ 启动常驻监控**（可选：点「忽略电池优化」防止被系统清理）
5. 之后正常玩支持的任意游戏即可，通知栏会显示实时状态：

```
X模式生效中(跳过)  |  模式=3  |  补投1次/跳过15次
```

- `模式=3` → X 模式正在生效
- `补投N次/跳过M次` → 事件驱动的实际收益（跳过越多说明盲投被省掉了）

---

## v1.1 优化摘要

- 修掉 `stopService()` → `startService()` 竞态可能导致的 FGS 超时崩溃
- 前台包名探测改为设备侧管道（`dumpsys | grep | head`），每次只回传一行，替换掉原来每 5 秒拉回几百 KB 全量 dumpsys 的做法
- 自适应心跳（游戏中 5s / 待机 15s）、单线程调度器、通知按需刷新、UI 仅前台刷新
- 新增「打开游戏助手自动化测试」与「忽略电池优化」入口；状态区实时显示阶段/模式/补投统计
- release 开启 R8 + 资源收缩（54.2 KB → 31.9 KB）；新增自适应图标与 GitHub Actions CI

详见 [docs/OPTIMIZATIONS.md](docs/OPTIMIZATIONS.md)。

## 构建

环境要求：**JDK 17** + **Android SDK（compileSdk 34）**

```bash
git clone <this-repo>
cd XModeEnabler

# 指定你的 Android SDK 路径
cp local.properties.example local.properties
# 编辑 local.properties，把 sdk.dir 改成你自己的路径
```

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **在 arm64 设备上本地构建的坑**：Maven 下载的 `aapt2` 是 x86_64 二进制，在 arm64 上会直接
> `Illegal instruction`。若你也在 ARM Android 设备上编译（proot/Termux），请在 `gradle.properties`
> 中加一行，指向系统自带的 aapt2：
> ```properties
> android.aapt2FromMavenOverride=/path/to/android-sdk/build-tools/34.0.0/aapt2
> ```
> 普通 PC 构建**不需要**这行。

---

## 项目结构

```
XModeEnabler/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/operit/xmode/
│           ├── MainActivity.java      # 界面 + 授权 + 启动/停止
│           └── MonitorService.java    # 事件驱动核心（前台服务）
├── docs/
│   └── REVERSE_NOTES.md               # 逆向分析与实测记录
├── gradle/wrapper/                    # Gradle Wrapper
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## 已知限制 / 风险

- **游戏助手更新后可能失效**：类名、`action`、`SharedPreferences` key 均为逆向所得，官方改版即可能变化。
- **需要游戏助手保持"自动化测试"开启**；关闭后整条链路失效。
- **X 模式是激进性能策略**，官方要求搭配散热背夹。第三方散热器若制冷能力不足，可能导致高温、降频、电池加速老化。
- **改机行为可能影响保修**；请自行评估。
- 本应用仅向系统服务投递公开 action，不修改游戏助手 APK。

## 免责声明

本项目仅供**在自己合法拥有的设备上**进行学习与技术研究使用。
使用本软件所产生的一切后果（包括但不限于设备损坏、保修失效、账号封禁、违反游戏用户协议等）由使用者自行承担。

## License

[MIT](LICENSE)
