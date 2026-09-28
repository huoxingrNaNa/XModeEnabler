package com.operit.xmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * 事件驱动版 X 模式维持服务（v1.1 优化版）。
 *
 * 相对 v1.0 的改动：
 *  1. onStartCommand 幂等化 + 无条件 startForeground，修掉 stop -> start 竞态
 *     导致的 ForegroundServiceDidNotStartInTimeException 崩溃；
 *  2. 前台包名探测改为「设备侧管道」：dumpsys | grep | head，只回传一行结果，
 *     不再每次把几百 KB 的 dumpsys 全量读进 Java 进程（原实现最大开销点）；
 *  3. 单线程 ScheduledExecutorService 取代「每 tick new Thread」；
 *  4. 自适应心跳：有游戏会话 5s，待机 15s；
 *  5. 通知仅在文本变化时刷新；
 *  6. 所有 shell 调用带 8s 超时，防止线程卡死；
 *  7. 新增 openDevOptions()，供 UI 一键跳到游戏助手隐藏开发者选项页。
 */
public class MonitorService extends Service {

    public static final String TAG = "XModeEnabler";

    /** 以下状态供 UI 直接读取 */
    public static volatile boolean RUNNING = false;
    public static volatile String PHASE = "未启动";
    public static volatile int LAST_APPLIED = -1;
    public static volatile int FIRE_COUNT = 0;
    public static volatile int SKIP_COUNT = 0;

    private static final String CH_ID = "xmode_channel";
    private static final int NOTI_ID = 1001;

    private static final long TICK_ACTIVE_MS = 5000L;
    private static final long TICK_IDLE_MS = 15000L;
    private static final long SESSION_WINDOW_MS = 150000L;
    private static final long SAFETY_MS = 90000L;

    private static final String TARGET = "com.oplus.games/business.service.SpecialFeatureService";
    private static final String ACTION = "PERFORMANCE_CHANGE_ACTION";
    private static final String GAMES_PKG = "com.oplus.games";
    private static final String ACTION_DEV = "oplus.intent.action.GAMESPACE_GAME_DEVELOP_OPTIONS";
    private static final String DEV_ACTIVITY =
            "com.oplus.games/business.compact.activity.GameDevelopOptionsActivity";

    private static final Pattern P_APPLIED = Pattern.compile("toAppliedMode = (-?[0-9]+)");

    private static ScheduledExecutorService pool;

    private volatile boolean started = false;
    private volatile long lastPerfTs = 0L;
    private volatile long lastFireTs = 0L;
    private volatile boolean watcherStarted = false;
    private volatile Process watcherProc = null;
    private volatile long nextDelay = TICK_ACTIVE_MS;
    private volatile String lastNoti = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须无条件、尽快 startForeground（Android 8+/12+ 均有 5 秒硬限制）
        try {
            startForeground(NOTI_ID, buildNotification("已启动，等待游戏…"));
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
        }
        if (!started) {
            started = true;
            RUNNING = true;
            PHASE = "启动中";
            LAST_APPLIED = -1;
            FIRE_COUNT = 0;
            SKIP_COUNT = 0;
            lastPerfTs = 0L;
            lastFireTs = 0L;
            if (pool == null || pool.isShutdown()) {
                pool = Executors.newSingleThreadScheduledExecutor();
            }
            startWatcher();
            scheduleTick(800L);
            Log.i(TAG, "monitor started (event-driven v1.1)");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        started = false;
        PHASE = "已停止";
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        try {
            if (watcherProc != null) watcherProc.destroy();
        } catch (Throwable ignored) {
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "monitor stopped, fired=" + FIRE_COUNT + " skipped=" + SKIP_COUNT);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleTick(long delay) {
        final ScheduledExecutorService p = pool;
        if (p == null || p.isShutdown()) return;
        p.schedule(new Runnable() {
            @Override
            public void run() {
                if (!RUNNING) return;
                try {
                    tick();
                } catch (Throwable t) {
                    Log.w(TAG, "tick error", t);
                }
                scheduleTick(nextDelay);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        String top = topPackage();
        long now = System.currentTimeMillis();
        boolean self = top == null
                || top.equals(getPackageName())
                || top.equals("com.android.systemui")
                || top.endsWith("launcher")
                || top.endsWith("launcher3");
        boolean session = (now - lastPerfTs) < SESSION_WINDOW_MS;
        boolean need = (LAST_APPLIED != 3) || (now - lastFireTs) > SAFETY_MS;

        if (self) {
            PHASE = "自身/系统前台(待机)";
        } else if (!session) {
            PHASE = "无游戏会话(待机)";
        } else if (need) {
            if (fire()) {
                LAST_APPLIED = 3;
                lastFireTs = now;
                FIRE_COUNT++;
                PHASE = "已补投 X模式";
            } else {
                PHASE = "投递失败(检查 Shizuku)";
            }
        } else {
            SKIP_COUNT++;
            PHASE = "X模式生效中(跳过)";
        }
        nextDelay = (session && !self) ? TICK_ACTIVE_MS : TICK_IDLE_MS;
        updateNotificationIfChanged();
    }

    private void startWatcher() {
        if (watcherStarted) return;
        watcherStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (RUNNING) {
                    Process p = null;
                    try {
                        String gp = pidOf(GAMES_PKG);
                        if (gp == null) {
                            safeSleep(2000);
                            continue;
                        }
                        p = shizukuProcess("logcat -v brief --pid=" + gp);
                        watcherProc = p;
                        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while (RUNNING && (line = br.readLine()) != null) {
                            onLogLine(line);
                        }
                    } catch (Throwable x) {
                        Log.w(TAG, "watcher restarted: " + x);
                    } finally {
                        if (p != null) try {
                            p.destroy();
                        } catch (Throwable ignored) {
                        }
                        watcherProc = null;
                    }
                    safeSleep(1500);
                }
                watcherStarted = false;
            }
        }, "xmode-logcat-watcher");
        t.setDaemon(true);
        t.start();
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
                if (v != LAST_APPLIED) Log.i(TAG, "assistant applied mode -> " + v);
                LAST_APPLIED = v;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 投递一枪 mode=3（需 Shizuku shell 身份） */
    public static boolean fire() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "shizuku not running");
                return false;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "shizuku permission not granted");
                return false;
            }
            ExecResult r = exec("am startservice --user 0 -n " + TARGET + " -a " + ACTION
                    + " --ei mode 3 2>/dev/null");
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

    /** 打开游戏助手的隐藏「开发者选项」页（「自动化测试」总闸在那里） */
    public static boolean openDevOptions() {
        try {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            ExecResult r = exec("am start -n " + DEV_ACTIVITY + " -a " + ACTION_DEV
                    + " -c android.intent.category.DEFAULT"
                    + " --es gameDevelopOptions GameDevelopOptionsActivity");
            return r.code == 0 && !r.out.contains("Error") && !r.out.contains("Exception");
        } catch (Throwable t) {
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
        } catch (Throwable t) {
            return null;
        }
    }

    private static String topPackage() {
        String fast = topPackageFast();
        if (fast != null) return fast;
        return topPackageSlow();
    }

    /** 只把一行结果传回来（管道在设备侧执行） */
    private static String topPackageFast() {
        try {
            ExecResult r = exec("dumpsys activity activities 2>/dev/null | grep ResumedActivity | head -n 1");
            return parseResumed(r.out);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String topPackageSlow() {
        try {
            ExecResult r = exec("dumpsys activity activities 2>/dev/null");
            if (r.out == null || r.out.isEmpty()) return null;
            int from = 0;
            while (true) {
                int nl = r.out.indexOf('\n', from);
                String line = (nl < 0) ? r.out.substring(from) : r.out.substring(from, nl);
                if (line.contains("ResumedActivity")) {
                    String pkg = parseResumed(line);
                    if (pkg != null) return pkg;
                }
                if (nl < 0) break;
                from = nl + 1;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String parseResumed(String line) {
        if (line == null) return null;
        int i = line.indexOf("u0 ");
        if (i < 0) return null;
        String rest = line.substring(i + 3).trim();
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String pkg = rest.substring(0, slash).trim();
        return isPkg(pkg) ? pkg : null;
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

    private static ExecResult exec(String cmd) {
        Process p = null;
        StringBuilder sb = new StringBuilder();
        try {
            p = shizukuProcess(cmd);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroy();
                return new ExecResult(-1, "timeout");
            }
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return new ExecResult(p.exitValue(), sb.toString());
        } catch (Throwable t) {
            return new ExecResult(-1, String.valueOf(t));
        } finally {
            if (p != null) try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Shizuku.newProcess 在 13.1.5 中是 private，只能反射调用 */
    private static Process shizukuProcess(String cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new Object[]{
                new String[]{"sh", "-c", cmd}, null, null});
    }

    private static void safeSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "X模式维持",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("游戏时自动维持官方 X 模式");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void updateNotificationIfChanged() {
        String text = buildStatus();
        if (text.equals(lastNoti)) return;
        lastNoti = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, buildNotification(text));
    }

    private String buildStatus() {
        return PHASE + "  |  模式=" + LAST_APPLIED + "  |  补投" + FIRE_COUNT + "次/跳过" + SKIP_COUNT + "次";
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

    private static final class ExecResult {
        final int code;
        final String out;

        ExecResult(int c, String o) {
            code = c;
            out = o;
        }
    }
}
