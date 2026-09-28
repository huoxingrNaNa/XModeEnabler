# v1.1 优化记录（相对 v1.0）

优化目标：不改变“投递 mode=3 维持 X 模式”的核心原理，只降低功耗、修掉崩溃隐患、提升可维护性。

## 1. 崩溃 / 稳定性
- **onStartCommand 幂等化**：v1.0 用 `if (!RUNNING)` 包裹初始化，而 `RUNNING` 是静态字段，`stopService()` 紧接 `startService()` 时
  新一次 `onStartCommand` 可能略过 `startForeground()`，触发 `ForegroundServiceDidNotStartInTimeException`。
  现在：**无条件、入口第一件事就 `startForeground()`**，初始化部分用独立的 `started` 标志控制。
- `stopForeground(true)` → `stopForeground(STOP_FOREGROUND_REMOVE)`（前者已废弃）。
- 所有 shell 调用带 **8 秒超时**；`Process.waitFor(timeout)` 之后才读流，避免管道缓冲区写满导致的互锁。

## 2. 功耗（最大头）
- **前台包名探测管道化**：v1.0 每 5 秒执行一次 `dumpsys activity activities`，并把
  全量输出（实测几百 KB）通过 Shizuku Binder 拷回 App 进程再逐行 split 解析——数据搬运量极大。
  现在改为在设备侧完成过滤：`dumpsys activity activities | grep ResumedActivity | head -n 1`，
  **只回传一行（< 100 B）**，解析成本与传输成本降了两个数量级；若管道失败才回退到全量解析。
- **自适应心跳**：有游戏会话时 5s，无会话/自身前台时 15s。
- **单线程 ScheduledExecutorService** 取代“每 tick new Thread”（v1.0 每 5 秒新建线程）。
- 通知仅在文本变化时 `notify()`，不再每 tick 刷。
- UI 的 1.5s 刷新只在 `onResume`～`onPause` 之间运行（v1.0 会一直 self-post 到 `onDestroy`）。

## 3. 功能与体验
- 新增「③ 打开游戏助手「自动化测试」开关」：一键执行隐藏页 Intent，免去手敲 am 命令。
- 新增「忽略电池优化」入口，降低常驻服务被系统清理的概率。
- 状态区实时显示：Shizuku / 使用情况访问 / 电池优化 / 服务状态 / 阶段 / 助手当前模式 / 补投与跳过计数。
- 文案与真实行为对齐（v1.0 界面仍写着“每 5 秒自动维持”，实际已是事件驱动）。
- 新增自适应矢量图标（自适应图标，无 PNG 依赖）。

## 4. 体积
- release 开启 **R8 代码混淆 + shrinkResources 资源收缩**，并配套 `proguard-rules.pro` 保留：
  Shizuku（ContentProvider/Binder + `newProcess` 反射）、四大组件、行号信息。
- debug 包因新增图标资源略增（ ≈ 49.7 KB → 54.2 KB）；release 包体积见构建产物。

## 5. 工程化
- `versionCode 2 / versionName 1.1`。
- 新增 GitHub Actions：push 后自动 `assembleDebug` + `assembleRelease` 并上传产物。

## 遗留 / 待验证
- `pidof`、`logcat --pid`、`am startservice` 均依赖 Shizuku 的 shell 身份，行为与 v1.0 一致。
- 若 ColorOS 后续修改 `toAppliedMode` 日志格式，`P_APPLIED` 正则需同步调整。
- 本轮优化未改动投递命令本身，因此“单次投递约维持 44 秒”的特性不变，安全网仍为 90 秒。
