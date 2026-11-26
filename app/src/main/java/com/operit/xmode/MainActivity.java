package com.operit.xmode;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * 宿主 Activity：功能层接线。
 *
 * <p>职责边界：
 * <ul>
 *   <li>界面全部交给 {@link XModeUi}，本文件不构建任何 View、不改任何文案/颜色；</li>
 *   <li>只做两件事：把用户点击转成真实动作（6 个回调），以及把系统/服务状态
 *       打包成 {@link XModeUi.State} 交给 {@link XModeUi#render}。</li>
 * </ul>
 *
 * <p>状态出口只有一条：{@code ui.render(snapshot())}，由 {@code onResume} 起的
 * 1.5s 轮询驱动；授权回调里额外立即渲染一次。
 */
public class MainActivity extends Activity implements XModeUi.Listener {

    private static final int REQ_SHIZUKU = 1001;
    private static final int REQ_NOTIFICATION = 1002;
    /** 导出诊断日志的系统「另存为」请求码。 */
    private static final int REQ_EXPORT = 1003;
    /** 界面重建（旋转屏幕等）时保存的当前页面编号。 */
    private static final String KEY_PAGE = "xmode_page";

    /** 「手动投递」进行中标记，防止连点并发投递。 */
    private volatile boolean fireBusy = false;

    /** 界面轮询间隔：仅在 onResume ~ onPause 之间运行。 */
    private static final long TICK_MS = 1500L;

    private XModeUi ui;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, TICK_MS);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            render();
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 深色窗口配色（纯外观，与 UI 层风格一致）
        getWindow().setStatusBarColor(0xFF070B14);
        getWindow().setNavigationBarColor(0xFF070B14);

        ui = new XModeUi(this, this);
        setContentView(ui.root());
        if (savedInstanceState != null) {
            ui.setPage(savedInstanceState.getInt(KEY_PAGE, 0));
        }
        MonitorService.initPrefs(this);
        render();

        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                        REQ_NOTIFICATION);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (ui != null) outState.putInt(KEY_PAGE, ui.page());
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
    }

    // ================================================================
    // 6 个功能回调
    // ================================================================

    /** 第 1 行 / 右上胶囊：请求或检查 Shizuku 授权。 */
    @Override
    public void onShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku 未运行，请先启动 Shizuku");
                return;
            }
            // 未授权时 checkSelfPermission() 会抛异常
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("已授权");
                render();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            toast("请求失败：" + t);
        }
    }

    /** 第 3 行：打开游戏助手隐藏开发者选项页（让用户手动开「自动化测试」）。 */
    @Override
    public void onAutoTest() {
        // openDevOptions() 是阻塞调用（内部走 Shizuku shell），必须放子线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = MonitorService.openDevOptions();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(ok ? "已打开隐藏页，请把第一项「自动化测试」打开"
                                : "打开失败：" + MonitorService.LAST_ERROR);
                        render();
                    }
                });
            }
        }, "open-dev-options").start();
    }

    /** 第 4 行：请求忽略电池优化。 */
    @Override
    public void onBattery() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("无法打开电池优化设置");
            }
        }
    }

    /** 主按钮：按当前运行状态启动或停止常驻监控。 */
    @Override
    public void onToggleMonitor() {
        try {
            if (MonitorService.RUNNING) {
                stopService(new Intent(this, MonitorService.class));
                toast("已停止");
            } else {
                Intent intent = new Intent(this, MonitorService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
                toast("已启动");
            }
        } catch (Throwable t) {
            toast("操作失败：" + t);
        }
        render();
    }

    /** 「手动投递一次 X 模式」：fire() 阻塞，放子线程。 */
    @Override
    public void onFireOnce() {
        if (fireBusy) {
            toast("上一次投递还没结束，请稍等");
            return;
        }
        fireBusy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = MonitorService.fire();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        fireBusy = false;
                        toast(ok ? "已投递 mode=3（游戏在前台才生效）"
                                : "投递失败：" + MonitorService.LAST_ERROR);
                        render();
                    }
                });
            }
        }, "fire-once").start();
    }

    // ================================================================
    // v1.3 新增回调：白名单 / 诊断日志
    // ================================================================

    @Override
    public void onWhitelistAdd(String pkg) {
        MonitorService.whitelistAdd(this, pkg);
        toast("已加入白名单：" + pkg);
        render();
    }

    @Override
    public void onWhitelistRemove(String pkg) {
        MonitorService.whitelistRemove(this, pkg);
        toast("已移出白名单：" + pkg);
        render();
    }

    @Override
    public void onLogClear() {
        MonitorService.clearLog();
        render();
    }

    /** 关于页「源码仓库」：交给系统浏览器打开。 */
    @Override
    public void onOpenRepo() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(XModeUi.REPO_URL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            toast("没有可用的浏览器：" + t);
        }
    }

    /** 切换诊断日志记录开关（v1.6）。 */
    @Override
    public void onToggleLog() {
        MonitorService.setLogEnabled(this, !MonitorService.LOG_ENABLED);
        toast(MonitorService.LOG_ENABLED ? "诊断日志：已开启" : "诊断日志：已关闭（仍写系统 logcat）");
        render();
    }

    /** 导出诊断日志：走系统「另存为」，让用户自己选位置（无需存储权限）。 */
    @Override
    public void onExportLog() {
        String name = "xmode-log-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Throwable t) {
            toast("无法打开保存面板：" + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            toast("已取消导出");
            return;
        }
        // 不依赖字段中转：返回时重新生成文本，避免 Activity 被重建后写出空文件
        final String text = MonitorService.exportText();
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException("openOutputStream returned null");
            out.write(text.getBytes("UTF-8"));
            out.flush();
            toast("已导出诊断日志（" + text.length() + " 字符）");
        } catch (Throwable t) {
            toast("写入失败：" + t);
        }
    }

    /** 返回键：许可证页 → 关于页 → 首页 → 退出。 */
    @Override
    public void onBackPressed() {
        if (ui != null && ui.handleBack()) {
            return;
        }
        super.onBackPressed();
    }

    // ================================================================
    // 状态快照
    // ================================================================

    private void render() {
        if (ui != null) {
            ui.render(snapshot());
        }
    }

    /** 把系统权限状态 + 服务状态转成 UI 需要的数据结构。 */
    private XModeUi.State snapshot() {
        XModeUi.State s = new XModeUi.State();

        boolean running = false;
        try {
            running = Shizuku.pingBinder();
            if (running) {
                s.shizukuGranted =
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable ignored) {
            // 未授权 / binder 已死，按未就绪处理
        }
        s.shizukuRunning = running;

        s.batteryIgnored = isIgnoringBattery();

        s.monitoring = MonitorService.RUNNING;
        s.inSession = MonitorService.IN_SESSION;
        String phase = MonitorService.PHASE;
        s.phase = phase == null ? "" : phase;
        s.mode = MonitorService.LAST_APPLIED;
        s.fireCount = MonitorService.FIRE_COUNT;
        s.skipCount = MonitorService.SKIP_COUNT;

        // v1.3：版本 / 白名单 / 诊断日志 / 实时温度频率
        try {
            s.versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        s.whitelist = MonitorService.WHITELIST;
        s.knownGames = MonitorService.KNOWN_GAMES;
        s.logs = MonitorService.LOGS;
        s.batteryTenths = MonitorService.BATTERY_TENTHS;
        s.cpuCurKhz = MonitorService.CPU_CUR_KHZ;
        s.cpuMaxKhz = MonitorService.CPU_MAX_KHZ;
        s.hotZoneTenths = MonitorService.HOT_ZONE_TENTHS;
        s.logEnabled = MonitorService.LOG_ENABLED;
        s.gamesVersion = gamesVersionName();
        return s;
    }

    /** 读取游戏助手版本名（用于关于页「适配」行，v1.6.2 改为动态）。 */
    private String gamesVersionName() {
        try {
            return getPackageManager().getPackageInfo("com.oplus.games", 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean isIgnoringBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
