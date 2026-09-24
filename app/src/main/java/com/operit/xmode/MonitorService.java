package com.operit.xmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * 事件驱动版 X 模式维持服务。
 *
 * - 官方 SpecialFeatureService 未 exported，必须借 Shizuku 以 shell 身份投递；
 * - 实测一次投递可维持约 44 秒，之后助手会按“已保存模式”覆盖；
 * - 每 5 秒重投会让助手不停弹 “mode =3” 调试气泡，故改为
 *   监听助手日志，只在其把模式改成非 3 时补投一枪（另加 90 秒安全网）。
 */
public class MonitorService extends Service {

    public static volatile boolean RUNNING = false;

    private static final String TAG = "XModeEnabler";
    private static final String CH_ID = "xmode_channel";
    private static final int NOTI_ID = 1001;

    private static final long LOOP_MS = 5000L;
    private static final long SESSION_WINDOW_MS = 150000L;
    private static final long SAFETY_MS = 90000L;

    private static final String TARGET =
            "com.oplus.games/business.service.SpecialFeatureService";
    private static final String ACTION = "PERFORMANCE_CHANGE_ACTION";
    private static final String GAMES_PKG = "com.oplus.games";

    private static final Pattern P_APPLIED =
            Pattern.compile("toAppliedMode = (-?\\d+)");

    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile int lastApplied = -1;
    private volatile long lastPerfTs = 0L;
    private volatile long lastFireTs = 0L;
    private volatile boolean watcherStarted = false;
    private volatile Process watcherProc = null;

    private int fireCount = 0;
    private int skipCount = 0;
    private String phase = "启动中";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!RUNNING) {
            RUNNING = true;
            fireCount = 0;
            skipCount = 0;
            lastApplied = -1;
            lastPerfTs = 0L;
            lastFireTs = 0L;
            startForeground(NOTI_ID, buildNotification("已启动，等待游戏…"));
            startWatcher();
            handler.post(loop);
            Log.i(TAG, "monitor started (event-driven)");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        handler.removeCallbacksAndMessages(null);
        try { if (watcherProc != null) watcherProc.destroy(); } catch (Throwable ignored) {}
        stopForeground(true);
        Log.i(TAG, "monitor stopped, fired=" + fireCount + " skipped=" + skipCount);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!RUNNING) return;
            new Thread(new Runnable() { @Override public void run() { tick(); } }).start();
            handler.postDelayed(this, LOOP_MS);
        }
    };

    private void tick() {
        try {
            String top = topPackage();
            long now = System.currentTimeMillis();
            boolean self = top == null
                    || top.equals(getPackageName())
                    || top.equals("com.android.systemui")
                    || top.equals("com.android.launcher");
            boolean session = (now - lastPerfTs) < SESSION_WINDOW_MS;
            boolean need = (lastApplied != 3) || (now - lastFireTs) > SAFETY_MS;

            if (self) {
                phase = "自身/系统前台(待机)";
            } else if (!session) {
                phase = "无游戏会话(待机)";
            } else if (need) {
                if (fire()) {
                    lastApplied = 3;
                    lastFireTs = now;
                    fireCount++;
                    phase = "已补投 X模式";
                } else {
                    phase = "投递失败";
                }
            } else {
                skipCount++;
                phase = "X模式生效中(跳过)";
            }
        } catch (Throwable t) {
            Log.w(TAG, "tick error", t);
        }
        handler.post(new Runnable() { @Override public void run() { updateNotification(); } });
    }

    private void startWatcher() {
        if (watcherStarted) return;
        watcherStarted = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (RUNNING) {
                    Process p = null;
                    try {
                        String gp = pidOf(GAMES_PKG);
                        if (gp == null) { safeSleep(2000); continue; }
                        p = shizukuProcess("logcat -v brief --pid=" + gp);
                        watcherProc = p;
                        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while (RUNNING && (line = br.readLine()) != null) {
                            onLogLine(line);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "watcher restarted: " + t);
                    } finally {
                        if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
                        watcherProc = null;
                    }
                    safeSleep(1500);
                }
                watcherStarted = false;
            }
        }).start();
    }

    private void onLogLine(String line) {
        if (line == null) return;
        if (line.contains("PerfModeFeature") || line.contains("GameStateMachine")
                || line.contains("SpecialFeatureService")) {
            lastPerfTs = System.currentTimeMillis();
        }
        Matcher m = P_APPLIED.matcher(line);
        if (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v != lastApplied) Log.i(TAG, "assistant applied mode -> " + v);
                lastApplied = v;
            } catch (Throwable ignored) {}
        }
    }

    public static boolean fire() {
        try {
            if (!Shizuku.pingBinder()) { Log.w(TAG, "shizuku not running"); return false; }
            String cmd = "am startservice --user 0 -n " + TARGET
                    + " -a " + ACTION + " --ei mode 3";
            ExecResult r = exec(cmd);
            boolean ok = r.code == 0
                    && !r.out.contains("Error")
                    && !r.out.contains("Exception")
                    && !r.out.contains("Failure");
            if (!ok) Log.w(TAG, "fire failed rc=" + r.code + " out=" + r.out);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "fire exception", t);
            return false;
        }
    }

    private static String pidOf(String name) {
        try {
            ExecResult r = exec("pidof " + name);
            String s = r.out == null ? "" : r.out.trim();
            if (s.isEmpty()) return null;
            int i = 0;
            while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
            return s.substring(0, i);
        } catch (Throwable t) { return null; }
    }

    private static ExecResult exec(String cmd) {
        Process p = null;
        StringBuilder sb = new StringBuilder();
        try {
            p = shizukuProcess(cmd);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            int code = p.waitFor();
            return new ExecResult(code, sb.toString());
        } catch (Throwable t) {
            return new ExecResult(-1, String.valueOf(t));
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static Process shizukuProcess(String cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new Object[]{
                new String[]{"sh", "-c", cmd}, null, null});
    }

    private static final class ExecResult {
        final int code;
        final String out;
        ExecResult(int c, String o) { code = c; out = o; }
    }

    private static void safeSleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) {}
    }

    /** 取当前前台包名（不用正则，避免转义问题） */
    private static String topPackage() {
        try {
            ExecResult r = exec("dumpsys activity activities 2>/dev/null");
            if (r.out == null) return null;
            String[] lines = r.out.split("\\n");
            for (String line : lines) {
                if (!line.contains("ResumedActivity")) continue;
                int i = line.indexOf("u0 ");
                if (i < 0) continue;
                String rest = line.substring(i + 3).trim();
                int slash = rest.indexOf('/');
                if (slash <= 0) continue;
                String pkg = rest.substring(0, slash).trim();
                if (isPkg(pkg)) return pkg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isPkg(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return s.indexOf('.') > 0;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "X模式维持", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("游戏时自动维持官方 X 模式");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, buildNotification(buildStatus()));
    }

    private String buildStatus() {
        return phase + "  |  模式=" + lastApplied + "  |  补投" + fireCount + "次/跳过" + skipCount + "次";
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("X模式助手运行中")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b.build();
    }
}
