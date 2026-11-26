# XModeEnabler · X模式助手

> **⚠️ 使用前必读：请搭配散热背夹（散热器）使用**
> 本应用只负责「让第三方散热器也能触发官方 X 模式」，**它本身不提供任何制冷能力**。
> 不接外置散热就开 X 模式，等于把手机推到最激进的功耗策略里跑，结果通常是**烫手机 + 高温降频**。

- 设备验证：**OnePlus PLQ110**，ColorOS / Android 16（SDK 36），arm64-v8a
- 目标应用：`com.oplus.games`（游戏助手 v10.24.20）
- **无需 Root**：全程通过 **Shizuku** 以 `shell` 身份投递官方指令
- 交付形态：独立 App（前台常驻服务），当前版本 **v1.5**

---

## ⚠️ 为什么必须配散热器

| | 说明 |
|---|---|
| X 模式是什么 | 游戏助手里**最激进**的性能策略：放开 CPU/GPU 频率与温控阈值。官方只在识别到原装散热背夹时才提供 |
| 不散热会怎样 | 机身快速升温 → 触发热降频 → 帧率不升反降；长期高温加速电池老化；机身边框发烫 |
| 推荐配置 | 半导体散热背夹（10 W 以上、强档），贴在机身中部发热区；游戏全程保持工作 |
| 怎么判断够不够 | 看 App 首页「**实时状态**」：电池温度 ≥40℃ 变黄、≥45℃ 变红；最热热区同理。**持续红色 = 散热不足，建议退回均衡模式** |

> 没有散热器也能装上，但**不建议开启 X 模式**。如需验证功能，短时间测试后立刻退出游戏。

---

## 它解决什么问题

ColorOS 把 X 模式锁在「原装散热背夹」的私有信号上，第三方散热器再强也触发不了。
本应用**不修改游戏助手安装包、不 Hook、不注入**，而是借用助手自带的「自动化测试」调试通道，
以 Shell 身份向它投递一条性能模式指令：**`mode = 3` 即 X 模式**。

---

## 原理

```
SpecialFeatureService.onStartCommand
  └─ if (!RecordAutoTestResultsUtil.g()) return;      // ★ 总闸：auto_test_key 必须为 true
     └─ handleAction
        └─ isInGame 守卫                              // 只有游戏在前台才受理
           └─ getIntExtra("mode", -1) == 3
              └─ PerfModeFeature.y1(...) → x1(3) → setIsChangeXMode(true)   // ★ 开启 X 模式
```

投递命令（需 shell 权限）：

```bash
am startservice --user 0 \
  -n com.oplus.games/business.service.SpecialFeatureService \
  -a PERFORMANCE_CHANGE_ACTION --ei mode 3
```

`SpecialFeatureService` 是 `exported=false` 的特权服务，普通 App 无法调用，
所以本应用借 **Shizuku** 拿到 shell 身份后执行上面的 `am` 命令。

### 为什么不是「每 5 秒投一次」

实测（见 `docs/REVERSE_NOTES.md`）：**单次投递可维持约 44 秒**，之后助手会按已保存模式覆盖回普通模式。
但每次投递都会让助手弹一个 `mode = 3` 的调试悬浮气泡 —— 盲投就近乎常驻气泡，体验很差。

所以采用**事件驱动**：

1. 用 Shizuku 起 `logcat --pid=<games_pid>` 监听游戏助手日志；
2. 解析 `toAppliedMode = N` 实时跟踪助手当前模式；
3. **只在助手把模式改回非 3 时才补投一枪**，另加 **90 秒安全网**兜底。

实测一局 69 秒：投递 **1 次**（旧版盲投约 14 次），气泡从「几乎常驻」降到「进游戏闪一下」。

---

## 使用方法

### 前置条件

| 项 | 说明 |
|---|---|
| 散热器 | **强烈建议**：半导体散热背夹（见上文）|
| Shizuku | 已安装并处于运行状态（https://shizuku.rikka.app/） |
| 游戏助手「自动化测试」 | **必须开启**，这是投递的总闸 |
| 使用情况访问权限 | 部分机型判定前台游戏时需要 |
| 通知权限 | Android 13+ 必须允许（前台服务通知 + 快捷按钮） |

### 打开游戏助手的隐藏「开发者选项」

App 首屏第 3 行「**自动化测试开关**」可一键打开；手工命令如下：

```bash
am start -n com.oplus.games/business.compact.activity.GameDevelopOptionsActivity \
  -a oplus.intent.action.GAMESPACE_GAME_DEVELOP_OPTIONS \
  -c android.intent.category.DEFAULT \
  --es gameDevelopOptions "GameDevelopOptionsActivity"
```

> ⚠️ extra `gameDevelopOptions` 必须携带，否则页面会立即自毁。
> 页面里**只打开第一项「自动化测试」**，其余开关不要动（改动会连带影响总闸）。

### App 内操作

1. 点 **① Shizuku 服务** → 允许授权
2. 点 **② 使用情况访问** → 在系统列表里允许
3. 点 **③ 自动化测试开关** → 隐藏页里把第一项打开，然后返回
4. 点 **④ 忽略电池优化**（可选、推荐），并允许**通知权限**
5. 点 **启动常驻监控**，然后正常进游戏

### 进游戏后看什么

通知栏（Android 13+ 可展开显示两个按钮「立即投递」「停止」）：

```
X模式生效中(跳过)  |  33.4℃  |  模式=3  |  补投1次/跳过15次
```

- `模式=3` → X 模式正在生效
- `补投N次/跳过M次` → 事件驱动的收益（跳过越多说明盲投被省掉了）
- 温度：**接散热器时参考用**，持续偏高就该降档或加强散热

### 首页各区块

| 区块 | 作用 |
|---|---|
| 实时状态 | 电池温度 / CPU 最高簇当前与上限频率 / 最热热区；≥40℃ 黄、≥45℃ 红 |
| 准备工作 | Shizuku、使用情况访问、自动化测试开关、忽略电池优化 四项前置 |
| 监控 | 启动/停止常驻监控；「手动投递一次 X 模式」用于快速验证 |
| 游戏白名单 | 为空 = 对所有游戏生效；玩过一次后可从「最近检测到的游戏」加入 |
| 诊断日志 | 最近 12 条（内部保留 60 条）：会话起落、前台应用、助手模式变化、投递失败原因 |
| 工作原理 | 事件驱动 + 90 秒安全网的简述 |

「关于」页另有：应用信息、**源码仓库入口**、**许可证页**（MIT 全文 + Shizuku Apache-2.0）。

---

## 构建

环境要求：**JDK 17** + **Android SDK（compileSdk 34）**

```bash
git clone https://github.com/huoxingrNaNa/XModeEnabler.git
cd XModeEnabler
cp local.properties.example local.properties   # 填 sdk.dir
./gradlew assembleDebug                       # app/build/outputs/apk/debug/app-debug.apk
```

安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`

> **arm64 设备本机编译的坑**：Maven 下载的 `aapt2` 是 x86_64 二进制，arm64 上直接 `Illegal instruction`。
> 在 `gradle.properties` 加一行指向系统 aapt2 即可（普通 PC / CI 不需要）：
> ```properties
> android.aapt2FromMavenOverride=/path/to/android-sdk/build-tools/34.0.0/aapt2
> ```

### 正式签名（可选）

仓库不含任何密钥。要出可长期升级的正式包：

```bash
keytool -genkeypair -v -keystore my-release.jks -alias mykey \
  -keyalg RSA -keysize 2048 -validity 10950
cp keystore.properties.example keystore.properties   # 填入 4 个字段
./gradlew assembleRelease
```

`keystore.properties` 与 `*.jks` 已在 `.gitignore` 中；文件缺失时 `assembleRelease` 仍能构建，只是不签名。
> ⚠️ 密钥丢失后无法再更新同一包名的应用，请自行备份。

---

## 项目结构

```
XModeEnabler/
├── .github/workflows/android.yml       # CI：push 后构建 debug + release
├── app/
│   ├── build.gradle                    # 含 signingConfigs.release（凭据外置）
│   ├── proguard-rules.pro              # R8 保留规则（Shizuku 反射入口等）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/operit/xmode/
│       │   ├── MainActivity.java       # 功能接线：回调 + 状态快照
│       │   ├── MonitorService.java     # 事件驱动核心（前台服务 + shell 采样）
│       │   ├── NotifyActionReceiver.java  # 通知栏快捷按钮
│       │   ├── XModeUi.java            # 界面层（标签页 / 卡片 / 渲染）
│       │   ├── PulseView.java          # 主状态脉冲动画
│       │   └── XIcon.java              # Canvas 线框图标集
│       └── res/                        # 自适应图标 + 配色
├── docs/
│   ├── REVERSE_NOTES.md                # 逆向分析、调用链、实测记录
│   └── OPTIMIZATIONS.md                # v1.1 优化清单
├── keystore.properties.example
├── HANDOFF.md                          # 交接说明
├── README.md
├── LICENSE
└── gradle/ gradlew gradlew.bat build.gradle settings.gradle gradle.properties
```

---

## 版本演进

- **v1.1**：事件驱动重写（前台包名管道探测、自适应心跳 5s/15s、单线程调度、通知去重）；新增「打开自动化测试」与「忽略电池优化」入口；release 开 R8
- **v1.2**：修复 **Shizuku `exec()` 致命兼容 bug**（`ShizukuRemoteProcess` 未退出时抛 `IllegalArgumentException` 而非 `IllegalThreadStateException`，导致所有 shell 操作被误判失败，X 模式投递同样会失效）；启用正式签名（`keystore.properties` + `signingConfigs.release`，缺失自动降级）
- **v1.3**：实时状态采样（电池温度 / CPU 最高簇频率 / 最热热区，一次 shell 往返取齐）、游戏白名单、诊断日志、通知栏快捷按钮与点击通知回 App
- **v1.4**：界面重构为底部标签页（首页 / 关于），关于页独立
- **v1.5**：许可证页（本应用 MIT 全文 + Shizuku Apache-2.0 + AndroidX Apache-2.0，已核验）、「打开源码仓库」入口

v1.1 的性能优化细节见 [docs/OPTIMIZATIONS.md](docs/OPTIMIZATIONS.md)。

---

## 排错 FAQ

**Q：点「自动化测试开关」提示打开失败？**
先确认 Shizuku 在运行且已授权本应用；再看「诊断日志」里最后一条报错（会附 `rc` 与原始输出）。

**Q：监控在跑，但助手模式一直不是 3？**
依次检查：隐藏页「自动化测试」是否真的打开 → 当前前台是否游戏（本应用只看前台包名）→ 白名单是否把它拦了。

**Q：X 模式开了几十秒又掉了？**
正常，单次投递约 44 秒。事件驱动的设计就会在助手改回时补投；诊断日志里能看到 `助手模式 3 → 0` 后紧跟一次投递。

**Q：通知栏没有「立即投递 / 停止」按钮？**
Android 13+ 的通知权限没给。到系统设置里允许「X模式助手」的通知。

**Q：首屏温度/频率都是「—」？**
说明常驻服务没在跑（数据由服务用 shell 采样）。点「启动常驻监控」即可；个别机型热区路径受限也会显示为空。

**Q：打游戏能开 MT 管理器之类的工具吗？**
不建议。与本应用无关，是个别游戏反作弊自身会处理这类工具。

**Q：换会游戏助手 / 系统升级后失效？**
可能。本应用依赖助手的内部类名、action 与日志格式，升级后需重新验证（见 `docs/REVERSE_NOTES.md`）。

---

## 风险与免责

- X 模式是官方最激进的性能策略，**官方形态要求配专用散热背夹**；第三方制冷不足时可能高温降频、耗电加快与电池老化。
- 修改系统应用行为可能影响保修；请自行评估风险后使用。
- 本应用**不改动**游戏助手安装包，仅以 Shizuku 提供的 shell 身份发送官方指令。
- 仅供个人设备学习研究，请遵守当地法律与厂商条款。

---

## License

[MIT](LICENSE) © 2026 huoxingrNaNa

三方组件：Shizuku API/Provider（Apache-2.0）、AndroidX Annotation（Apache-2.0）。
