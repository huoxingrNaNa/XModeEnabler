# 更新日志

本项目的所有重要改动记录在此。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循语义化版本（`MAJOR.MINOR.PATCH`）。

## [1.6] - 2026-11-26

### 新增
- 诊断日志「记录开/关」开关（持久化；关闭后不再写入缓冲区，仍写系统 logcat）
- 诊断日志「导出」：走系统「另存为」保存为 `xmode-log-日期-时间.txt`（状态摘要 + 全部 60 条事件），不需要存储权限
- 手动投递防连点：投递进行中再点会提示「上一次投递还没结束」

### 修复
- **返回键分级**：许可证页 → 关于页 → 首页 → 退出；此前在许可证页按返回键会直接退出 App

## [1.5] - 2026-11-26

### 新增
- 许可证页：本应用 MIT 全文；Shizuku API/Provider（Apache-2.0）、AndroidX Annotation（Apache-2.0）
- 关于页「源码仓库」入口（浏览器打开）

## [1.4] - 2026-11-26

### 变更
- 界面重构为**底部标签页**（首页 / 关于），关于页独立
- 关于页新增页头图标、使用步骤、风险提示与页脚署名

## [1.3] - 2026-11-26

### 新增
- 实时状态：电池温度 / CPU 最高簇当前与上限频率 / 最热热区（一次 shell 往返取齐，≥40℃ 黄、≥45℃ 红）
- 游戏白名单（为空 = 对所有游戏生效；可从「最近检测到的游戏」一键加入）
- 诊断日志（60 条环形缓冲，界面显示最近 12 条）
- 通知栏快捷按钮「立即投递 / 停止」，点击通知回到 App；通知文本带温度

## [1.2] - 2026-11-26

### 修复
- **Shizuku `exec()` 致命兼容 bug**：`ShizukuRemoteProcess` 在进程未退出时抛
  `IllegalArgumentException("process hasn't exited")`，而标准 `waitFor(timeout)` 只捕
  `IllegalThreadStateException`，导致所有 shell 操作被误判失败（含 X 模式投递与打开隐藏页）
- `exec()` 的 stdout 读取改为与等待并发，避免管道写满互锁

### 变更
- 启用正式签名：`keystore.properties` + `signingConfigs.release`，文件缺失时自动降级为不签名

## [1.1] - 2026-09-28

### 变更
- 事件驱动重写：监听助手日志 `toAppliedMode`，只在模式被改回时补投（一局 69 秒仅 1 次投递）
- 前台包名探测改为设备侧管道（`dumpsys | grep | head`），只回传一行
- 自适应心跳（游戏中 5s / 待机 15s）、单线程调度器、通知按需刷新
- 新增「打开游戏助手自动化测试」与「忽略电池优化」入口

### 修复
- `stopService()` → `startService()` 竞态可能导致的前台服务超时崩溃

## [1.0] - 2026-09

### 新增
- 首个可用版本：定时投递 `mode=3` 维持 X 模式（无 Root，走 Shizuku shell）

[1.6]: https://github.com/huoxingrNaNa/XModeEnabler/releases/tag/v1.6
[1.5]: https://github.com/huoxingrNaNa/XModeEnabler/releases/tag/v1.5
[1.1]: https://github.com/huoxingrNaNa/XModeEnabler/releases/tag/v1.1
