package com.operit.xmode;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/**
 * 控制面板（v1.1）：
 *  - 仅在前台时 1.5s 刷新一次状态（v1.0 会一直刷新到 onDestroy，浪费电量）；
 *  - 状态区直接读 MonitorService 的静态字段，能实时看到阶段/模式/补投统计；
 *  - 新增「打开游戏助手开发者选项」与「忽略电池优化」两个入口。
 */
public class MainActivity extends Activity {

    private static final int REQ_SHIZUKU = 1001;

    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1500);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    refresh();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("X模式助手  v1.1\n（第三方散热器也能开官方 X 模式）");
        title.setTextSize(17);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        root.addView(btn("① 授权 Shizuku", new View.OnClickListener() {
            @Override public void onClick(View v) { reqShizuku(); }
        }));
        root.addView(btn("② 授权「使用情况访问」", new View.OnClickListener() {
            @Override public void onClick(View v) { openUsageAccess(); }
        }));
        root.addView(btn("③ 打开游戏助手「自动化测试」开关", new View.OnClickListener() {
            @Override public void onClick(View v) { openDevOptions(); }
        }));
        root.addView(btn("④ 启动常驻监控", new View.OnClickListener() {
            @Override public void onClick(View v) { startMonitor(); }
        }));
        root.addView(btn("⑤ 停止监控", new View.OnClickListener() {
            @Override public void onClick(View v) { stopMonitor(); }
        }));
        root.addView(btn("手动投递一次 X模式（测试）", new View.OnClickListener() {
            @Override public void onClick(View v) { fireOnce(); }
        }));
        root.addView(btn("忽略电池优化（防后台被杀）", new View.OnClickListener() {
            @Override public void onClick(View v) { requestIgnoreBattery(); }
        }));

        TextView tip = new TextView(this);
        tip.setTextSize(12);
        tip.setPadding(0, dp(14), 0, 0);
        tip.setText("用法：\n"
                + "1. 依次点 ①②③（③ 里把「自动化测试」打开，其余开关别动）\n"
                + "2. 点 ④ 启动常驻监控，然后正常玩游戏\n\n"
                + "本应用是事件驱动的：监听游戏助手日志，只在它把模式改成非 X 模式时才补投一枪"
                + "（另加 90 秒安全网），无游戏时 15 秒一次待机心跳。");
        root.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        Shizuku.addRequestPermissionResultListener(permListener);

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1002);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        Shizuku.removeRequestPermissionResultListener(permListener);
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        sb.append("Shizuku: ");
        try {
            if (Shizuku.pingBinder()) {
                sb.append(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                        ? "已授权" : "未授权");
            } else {
                sb.append("未运行");
            }
        } catch (Throwable t) {
            sb.append("异常:").append(t.getMessage());
        }
        sb.append("\n使用情况访问: ").append(hasUsageAccess() ? "已授权" : "未授权");
        sb.append("\n电池优化: ").append(isIgnoringBattery() ? "已忽略" : "未忽略");
        sb.append("\n监控服务: ").append(MonitorService.RUNNING ? "运行中" : "未运行");
        if (MonitorService.RUNNING) {
            sb.append("\n阶段: ").append(MonitorService.PHASE);
            sb.append("\n助手当前模式: ").append(MonitorService.LAST_APPLIED)
              .append("   补投 ").append(MonitorService.FIRE_COUNT)
              .append(" 次 / 跳过 ").append(MonitorService.SKIP_COUNT).append(" 次");
        }
        status.setText(sb.toString());
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow("android:get_usage_stats", Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
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

    private void reqShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku 未运行，请先启动 Shizuku");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("已授权");
                refresh();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            toast("请求失败: " + t);
        }
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            toast("请找到「X模式助手」并允许使用情况访问");
        } catch (Throwable t) {
            toast("打开设置失败: " + t);
        }
    }

    private void openDevOptions() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = MonitorService.openDevOptions();
                ui.post(new Runnable() {
                    @Override public void run() {
                        toast(ok ? "已打开隐藏页，把第一项「自动化测试」打开"
                                 : "失败：请先授权 Shizuku");
                    }
                });
            }
        }).start();
    }

    private void startMonitor() {
        Intent i = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        toast("已启动");
    }

    private void stopMonitor() {
        stopService(new Intent(this, MonitorService.class));
        toast("已停止");
    }

    private void fireOnce() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = MonitorService.fire();
                ui.post(new Runnable() {
                    @Override public void run() {
                        toast(ok ? "已投递 mode=3（游戏在前台才生效）" : "失败：请检查 Shizuku 授权");
                    }
                });
            }
        }).start();
    }

    private void requestIgnoreBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("无法打开电池优化设置");
            }
        }
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
