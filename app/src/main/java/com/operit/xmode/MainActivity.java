package com.operit.xmode;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int REQ_SHIZUKU = 1001;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
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
        title.setText("X模式助手\n（第三方散热器也能开官方 X 模式）");
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
        root.addView(btn("③ 启动常驻监控", new View.OnClickListener() {
            @Override public void onClick(View v) { startMonitor(); }
        }));
        root.addView(btn("④ 停止监控", new View.OnClickListener() {
            @Override public void onClick(View v) { stopMonitor(); }
        }));
        root.addView(btn("手动开一次 X模式（测试）", new View.OnClickListener() {
            @Override public void onClick(View v) { fireOnce(); }
        }));

        TextView tip = new TextView(this);
        tip.setTextSize(12);
        tip.setPadding(0, dp(14), 0, 0);
        tip.setText("用法：\n1. 依次点 ①②③\n2. 正常玩游戏即可\n\n只要游戏在前台，本应用每 5 秒自动维持 X 模式。\n（需先在游戏助手开发者选项里打开「自动化测试」）");
        root.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        Shizuku.addRequestPermissionResultListener(permListener);

        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1002); }
            catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onResume() { super.onResume(); refresh(); tick(); }

    private void tick() {
        refresh();
        ui.postDelayed(new Runnable() { @Override public void run() { tick(); } }, 1500);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permListener);
        ui.removeCallbacksAndMessages(null);
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        sb.append("Shizuku: ");
        try {
            if (Shizuku.pingBinder()) {
                sb.append(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? "已授权 OK" : "未授权 !");
            } else {
                sb.append("未运行 X");
            }
        } catch (Throwable t) { sb.append("异常:").append(t.getMessage()); }
        sb.append("\n使用情况访问: ").append(hasUsageAccess() ? "已授权 OK" : "未授权 !");
        sb.append("\n监控服务: ").append(MonitorService.RUNNING ? "运行中 OK" : "未运行");
        status.setText(sb.toString());
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow("android:get_usage_stats", Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) { return false; }
    }

    private void reqShizuku() {
        try {
            if (!Shizuku.pingBinder()) { toast("Shizuku 未运行，请先启动 Shizuku"); return; }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { toast("已授权"); refresh(); return; }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) { toast("请求失败: " + t); }
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            toast("请在列表中找到「X模式助手」并允许");
        } catch (Throwable t) { toast("打开设置失败: " + t); }
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
        new Thread(new Runnable() { @Override public void run() {
            final boolean ok = MonitorService.fire();
            ui.post(new Runnable() { @Override public void run() {
                toast(ok ? "已投递 mode=3（游戏中才生效）" : "失败：请检查 Shizuku 授权");
            }});
        }}).start();
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
