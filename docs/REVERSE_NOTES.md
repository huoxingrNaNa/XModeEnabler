# 逆向分析与实测记录

> 设备：OnePlus PLQ110，ColorOS / Android 16（SDK 36），arm64-v8a，无 Root（仅 Shizuku / shell 身份 uid=2000）
> 目标：`com.oplus.games`（游戏助手）v10.24.20（versionCode 100240020）
> APK 路径：`/product/priv-app/OplusGames/OplusGames.apk`

---

## 1. 调用链还原

对 `classes2.dex` 反汇编后，定位到投递入口与处理逻辑：

```
SpecialFeatureService.onStartCommand(Intent intent, ...)
    │
    ├─ if (!RecordAutoTestResultsUtil.g()) return;      // ★ 总闸门禁
    │
    └─ handleAction(intent)
          ├─ if (!isInGame()) return;                   // 仅游戏在前台时受理
          ├─ int mode = intent.getIntExtra("mode", -1);
          └─ e(mode)
                └─ PerfModeFeature.y1(mode, ...)  →  x1(mode)
                        └─ if (mode == 3) setIsChangeXMode(true);   // ★ 开启 X 模式
```

**结论：`mode = 3` 即 X 模式。**

### 关键日志判据

| 日志 | 含义 |
|---|---|
| `chengPerfMode 3` | 指令已被受理 |
| `updateAppliedMode toAppliedMode = N` | 当前生效的性能模式 |
| `setIsChangeXMode  state:true` | X 模式已开启 |

### 投递命令

```bash
am startservice --user 0 \
  -n com.oplus.games/business.service.SpecialFeatureService \
  -a PERFORMANCE_CHANGE_ACTION --ei mode 3
```

> `SpecialFeatureService` 为 `exported=false`，故必须借 shell 身份（Shizuku）执行。

---

## 2. 总闸门禁：`auto_test_key`

`RecordAutoTestResultsUtil.g()` 返回静态字段 `b`，其值来源：

```
q.d() → SharedPreferencesProxy.getBoolean(
            "auto_test_key", false,
            "com.oplus.games_environment_switch")
```

- setter 为 `q.g0(boolean)`，日志串 `setAutoTest()  isOpen===`
- 该 key 存于应用私有 SharedPreferences，**无 Root 不可直接写**，只能通过 UI 打开。

### 隐藏开发者选项入口

```
Component: com.oplus.games/business.compact.activity.GameDevelopOptionsActivity
Action:    oplus.intent.action.GAMESPACE_GAME_DEVELOP_OPTIONS
Extra:     --es gameDevelopOptions "GameDevelopOptionsActivity"   ← 缺失则页面立即自毁
```

页面**第一项即「自动化测试」**（对应 `auto_test_key`）。

> ⚠️ 实测教训：该页面的开关是**同一次写入**的，关闭「游戏自动化」时可能连带把第一项总闸一起关掉，
> 导致"关了就用不了"。**只应操作第一项，其余保持不动。**

---

## 3. 保持时长实测（决定性数据）

停止 App 心跳，只在进入游戏后投递**一枪**，随后静默观察日志：

```
15:23:22.706  chengPerfMode 3
15:23:22.706  updateAppliedMode toAppliedMode = 3
15:23:22.706  setIsChangeXMode  state:true           ← X 模式生效
   （静默 44 秒，无干预）
15:24:06.302  updateAppliedMode toAppliedMode = 0    ← 助手自行覆盖
15:24:07.913  updateAppliedMode toAppliedMode = 2
```

**结论：单次投递可维持约 44 秒。** 因此"每 5 秒盲投"属于 8.8 倍过度投递。

---

## 4. 调试气泡来源

每次投递都会弹出 `mode = 3` 提示。经排查：

- **不是标准 Toast**：`appops set com.oplus.games TOAST_WINDOW deny` 设置成功但气泡仍在。
- 实际是游戏助手的**自定义悬浮窗**（`dumpsys window windows` 可见其持有以下窗口）：

```
New Notification Barrage Window2
GameFloatBarView
PanelContainerHandler
GameFloatMoveBall
oppo-game-sdk-buoy      ← ty=APPLICATION_OVERLAY NOT_FOCUSABLE NOT_TOUCHABLE
```

`APPLICATION_OVERLAY` 类型窗口不受 `TOAST_WINDOW` appop 管控，**无法用 appop 静音**。

因此改为从**源头减少投递次数** —— 即事件驱动方案。

---

## 5. 事件驱动效果实测

改造后，一局三角洲对局（约 69 秒）日志：

```
15:29:45  GameStateMachine: 进入游戏 com.tencent.tmgp.dfm
15:29:47  chengPerfMode 3 / toAppliedMode = 3 / setIsChangeXMode true   ← 仅此 1 次
   （整局 69 秒内助手未再回退）
15:30:56  GameStateMachine: 退出游戏
```

App 通知栏统计：`补投 1 次 / 跳过 15 次`

| | 旧版（每 5 秒盲投） | 事件驱动版 |
|---|---|---|
| 69 秒内投递次数 | ~14 | **1** |
| `mode = 3` 气泡出现次数 | ~14（近乎常驻）| **1** |
| X 模式稳定性 | 稳定 | 稳定 |

---

## 6. 踩坑记录（供复现者参考）

1. **`am` 输出重定向到 `/sdcard` 会失败**
   `am ... > /sdcard/x.log 2>&1` 报 `rc=2 / Failure calling service activity: Failed transaction`；
   重定向到 `/dev/null` 或管道则正常。脚本化投递时务必注意。

2. **`Shizuku.newProcess` 在 13.1.5 中是 private**
   需反射调用：
   ```java
   Method m = Shizuku.class.getDeclaredMethod("newProcess",
           String[].class, String[].class, String.class);
   m.setAccessible(true);
   m.invoke(null, new Object[]{ new String[]{"sh","-c",cmd}, null, null });
   ```

3. **游戏助手是常驻系统应用**
   `am force-stop com.oplus.games` 后约 2 秒内自动重生，故"盲投"不会拉起死进程，开销极小。

4. **arm64 上 AAPT2 报 `Illegal instruction`**
   Maven 下载的 aapt2 为 x86_64 二进制，需用 `android.aapt2FromMavenOverride`
   指向系统 build-tools 中的原生 aapt2。

5. **`pkill -f` 会误杀自身 shell 会话**（exit 143），清理进程请用精确 PID。

---

## 7. 当前实现的判定逻辑（`MonitorService.tick()`）

```
每 5 秒 tick 一次：

  self      = 前台是自身 / SystemUI / launcher      → 待机
  session   = 助手日志 150 秒内有动静（有游戏会话）  → 否则待机
  need      = lastApplied != 3  ||  距上次投递 > 90s

  self            → 待机
  !session        → 待机
  need            → 投递 mode=3（fireCount++）
  else            → 跳过（skipCount++）
```

其中 `lastApplied` 由日志 `toAppliedMode = (-?\d+)` 实时解析得到。
