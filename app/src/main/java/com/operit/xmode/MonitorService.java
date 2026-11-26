package com.operit.xmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    /** 最近一次操作失败的具体原因（供 UI 显示 / 排查用）。 */
    public static volatile String LAST_ERROR = "";

    // ── v1.3 新增：白名单 / 已识别游戏 / 诊断日志 / 实时温度频率 ──

    /** 白名单（包名）。为空表示对所有游戏生效。 */
    public static volatile List<String> WHITELIST = Collections.emptyList();
    /** 曾经在前台被识别为「游戏会话」的包名，最近的在前，最多 12 个。 */
    public static volatile List<String> KNOWN_GAMES = Collections.emptyList();
    /** 诊断日志（时间 + 文本，最新的在末尾，最多 60 条）。 */
    public static volatile List<String> LOGS = Collections.emptyList();

    /** 电池温度，单位 0.1℃；-1 表示未采到。 */
    public static volatile int BATTERY_TENTHS = -1;
    /** 最高频率簇的当前频率 / 上限，单位 kHz；-1 表示未采到。 */
    public static volatile int CPU_CUR_KHZ = -1;
    public static volatile int CPU_MAX_KHZ = -1;
    /** 最热热区温度，单位 0.1℃；-1 表示未采到。 */
    public static volatile int HOT_ZONE_TENTHS = -1;

    private static final String PREF = "xmode_prefs";
    private static final String K_WL = "whitelist";
    private static final String K_GAMES = "known_games";
    private static final String K_LOG = "log_enabled";
    private static final int LOG_MAX = 60;

    /** 诊断日志开关（v1.6）：关闭后不再向缓冲区追加事件（仍写系统 logcat）。 */
    public static volatile boolean LOG_ENABLED = true;

    private static final ArrayDeque<String> LOG_Q = new ArrayDeque<>();
    private static SharedPreferences prefs;

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
    /** v1.3：用于区分状态跃迁，写诊断日志 */
    private volatile boolean lastSession = false;
    private volatile String lastForeground = null;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        initPrefs(this);
    }

    // ================================================================ 配置 / 日志

    /** 载入本地配置（白名单、已识别游戏）。Activity 也会调用一次，保证未启动服务时也能显示。 */
    public static synchronized void initPrefs(Context c) {
        if (c == null) return;
        try {
            if (prefs == null) prefs = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
            List<String> wl = new ArrayList<>(prefs.getStringSet(K_WL, new LinkedHashSet<String>()));
            Collections.sort(wl);
            WHITELIST = wl;
            KNOWN_GAMES = new ArrayList<>(prefs.getStringSet(K_GAMES, new LinkedHashSet<String>()));
            LOG_ENABLED = prefs.getBoolean(K_LOG, true);
        } catch (Throwable t) {
            Log.w(TAG, "initPrefs failed", t);
        }
    }

    /** 追加一条诊断日志（同时写 logcat，便于外部排查）。 */
    public static void logEvent(String msg) {
        if (msg == null) return;
        if (!LOG_ENABLED) {
            Log.i(TAG, "evt(off) " + msg);
            return;
        }
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg;
        synchronized (LOG_Q) {
            LOG_Q.addLast(line);
            while (LOG_Q.size() > LOG_MAX) LOG_Q.removeFirst();
            LOGS = new ArrayList<>(LOG_Q);
        }
        Log.i(TAG, "evt " + msg);
    }

    /** 清空诊断日志。 */
    public static void clearLog() {
        synchronized (LOG_Q) {
            LOG_Q.clear();
            LOGS = Collections.emptyList();
        }
    }

    /** 开 / 关诊断日志（持久化）。关闭只影响缓冲区，系统 logcat 仍会记录。 */
    public static synchronized void setLogEnabled(Context c, boolean on) {
        LOG_ENABLED = on;
        SharedPreferences p = ensurePrefs(c);
        if (p != null) p.edit().putBoolean(K_LOG, on).apply();
        if (on) {
            logEvent("诊断日志已开启");
        } else {
            Log.i(TAG, "diagnostics buffer disabled");
        }
    }

    /** 导出用文本：当前状态摘要 + 全部缓冲区事件。 */
    public static String exportText() {
        StringBuilder sb = new StringBuilder();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        sb.append("X模式助手 · 诊断日志\n")
                .append("导出时间: ").append(now).append('\n')
                .append("记录开关: ").append(LOG_ENABLED ? "开" : "关").append('\n')
                .append("阶段: ").append(PHASE).append('\n')
                .append("助手模式: ").append(LAST_APPLIED).append('\n')
                .append("补投 / 跳过: ").append(FIRE_COUNT).append(" / ").append(SKIP_COUNT).append('\n')
                .append("电池温度: ").append(BATTERY_TENTHS > 0
                        ? String.format(Locale.US, "%.1f℃", BATTERY_TENTHS / 10f) : "—").append('\n')
                .append("CPU 最高簇: ").append(CPU_CUR_KHZ > 0
                        ? String.format(Locale.US, "%.2f GHz / %.2f GHz",
                        CPU_CUR_KHZ / 1000000f, CPU_MAX_KHZ / 1000000f) : "—").append('\n')
                .append("最热热区: ").append(HOT_ZONE_TENTHS > 0
                        ? String.format(Locale.US, "%.1f℃", HOT_ZONE_TENTHS / 10f) : "—").append('\n')
                .append("白名单: ").append(WHITELIST.isEmpty() ? "(空，对所有游戏生效)" : WHITELIST.toString())
                .append('\n')
                .append("最近错误: ").append(LAST_ERROR.isEmpty() ? "无" : LAST_ERROR).append('\n')
                .append("---------------- 事件 ----------------\n");
        List<String> copy;
        synchronized (LOG_Q) {
            copy = new ArrayList<>(LOG_Q);
        }
        if (copy.isEmpty()) {
            sb.append("(无事件)\n");
        } else {
            for (String line : copy) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static boolean isWhitelisted(String pkg) {
        return pkg != null && WHITELIST.contains(pkg);
    }

    /** 加入白名单（持久化）。 */
    public static synchronized void whitelistAdd(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        LinkedHashSet<String> set = new LinkedHashSet<>(WHITELIST);
        if (!set.add(pkg)) return;
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        WHITELIST = list;
        persistSet(c, K_WL, set);
        logEvent("白名单 + " + pkg);
    }

    /** 从白名单移除（持久化）。 */
    public static synchronized void whitelistRemove(Context c, String pkg) {
        if (pkg == null) return;
        LinkedHashSet<String> set = new LinkedHashSet<>(WHITELIST);
        if (!set.remove(pkg)) return;
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        WHITELIST = list;
        persistSet(c, K_WL, set);
        logEvent("白名单 - " + pkg);
    }

    /** 记住一个被识别为游戏的包名（供 UI 里「加入白名单」用）。 */
    private static synchronized void rememberGame(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        List<String> cur = new ArrayList<>(KNOWN_GAMES);
        if (cur.contains(pkg)) return;
        cur.add(0, pkg);
        while (cur.size() > 12) cur.remove(cur.size() - 1);
        KNOWN_GAMES = cur;
        persistSet(c, K_GAMES, new LinkedHashSet<>(cur));
    }

    /** 仅确保 prefs 可用，不覆盖内存里的白名单（避免写回旧值）。 */
    private static synchronized SharedPreferences ensurePrefs(Context c) {
        if (prefs == null && c != null) {
            try {
                prefs = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
            } catch (Throwable t) {
                Log.w(TAG, "ensurePrefs failed", t);
            }
        }
        return prefs;
    }

    private static void persistSet(Context c, String key, LinkedHashSet<String> values) {
        try {
            SharedPreferences p = ensurePrefs(c);
            if (p != null) p.edit().putStringSet(key, values).apply();
        } catch (Throwable t) {
            Log.w(TAG, "persist failed " + key, t);
        }
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
            logEvent("监控已启动");
            Log.i(TAG, "monitor started (event-driven v1.3)");
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
        logEvent("监控已停止");
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
        Sample s = sample();
        String top = s.top;
        long now = System.currentTimeMillis();
        boolean self = top == null
                || top.equals(getPackageName())
                || top.equals("com.android.systemui")
                || top.endsWith("launcher")
                || top.endsWith("launcher3");
        boolean session = (now - lastPerfTs) < SESSION_WINDOW_MS;
        boolean allowed = WHITELIST.isEmpty() || isWhitelisted(top);
        boolean need = (LAST_APPLIED != 3) || (now - lastFireTs) > SAFETY_MS;

        // 实时温度 / 频率（采不到就保留上次值）
        if (s.batteryTenths > 0) BATTERY_TENTHS = s.batteryTenths;
        if (s.cpuCurKhz > 0) CPU_CUR_KHZ = s.cpuCurKhz;
        if (s.cpuMaxKhz > 0) CPU_MAX_KHZ = s.cpuMaxKhz;
        if (s.hotTenths > 0) HOT_ZONE_TENTHS = s.hotTenths;

        // 会话起落 / 新前台应用 → 写诊断日志
        if (session && !lastSession) logEvent("检测到游戏会话");
        if (!session && lastSession) logEvent("游戏会话结束");
        lastSession = session;
        if (!self && session && top != null && !top.equals(lastForeground)) {
            logEvent("前台应用 " + top);
            rememberGame(this, top);
        }
        if (top != null) lastForeground = top;

        if (self) {
            PHASE = "自身/系统前台(待机)";
        } else if (!session) {
            PHASE = "无游戏会话(待机)";
        } else if (!allowed) {
            PHASE = "不在白名单(跳过)";
        } else if (need) {
            if (fire()) {
                LAST_APPLIED = 3;
                lastFireTs = now;
                FIRE_COUNT++;
                PHASE = "已补投 X模式";
            } else {
                PHASE = "投递失败(检查 Shizuku)";
                logEvent("投递失败 " + oneLine(LAST_ERROR));
            }
        } else {
            SKIP_COUNT++;
            PHASE = "X模式生效中(跳过)";
        }
        nextDelay = (session && !self) ? TICK_ACTIVE_MS : TICK_IDLE_MS;
        updateNotificationIfChanged();
    }

    /** 一次 shell 往返同时取回：前台包、电池温度、最高簇频率、最热热区。 */
    private Sample sample() {
        Sample s = new Sample();
        try {
            ExecResult r = exec("echo RA:$(dumpsys activity activities 2>/dev/null | grep -m1 ResumedActivity); "
                    + "echo BT:$(dumpsys battery 2>/dev/null | grep -m1 temperature | tr -dc '0-9'); "
                    + "echo CR:$(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq 2>/dev/null); "
                    + "echo MX:$(cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq 2>/dev/null); "
                    + "echo HZ:$(cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -1)");
            if (r.out != null) {
                int from = 0;
                while (true) {
                    int nl = r.out.indexOf('\n', from);
                    String line = (nl < 0) ? r.out.substring(from) : r.out.substring(from, nl);
                    if (line.startsWith("RA:")) {
                        s.top = parseResumed(line.substring(3));
                    } else if (line.startsWith("BT:")) {
                        s.batteryTenths = parseInt(line.substring(3), -1);
                    } else if (line.startsWith("CR:")) {
                        s.cpuCurKhz = parseInt(line.substring(3), -1);
                    } else if (line.startsWith("MX:")) {
                        s.cpuMaxKhz = parseInt(line.substring(3), -1);
                    } else if (line.startsWith("HZ:")) {
                        int milli = parseInt(line.substring(3), -1);
                        if (milli > 0) s.hotTenths = milli / 100;
                    }
                    if (nl < 0) break;
                    from = nl + 1;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "sample failed", t);
        }
        if (s.top == null) s.top = topPackageSlow();
        return s;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        String t = s.trim();
        if (t.isEmpty()) return def;
        try {
            return Integer.parseInt(t);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static final class Sample {
        String top;
        int batteryTenths = -1;
        int cpuCurKhz = -1;
        int cpuMaxKhz = -1;
        int hotTenths = -1;
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
                if (v != LAST_APPLIED) {
                    Log.i(TAG, "assistant applied mode -> " + v);
                    logEvent("助手模式 " + LAST_APPLIED + " → " + v);
                }
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
            if (!Shizuku.pingBinder()) {
                LAST_ERROR = "Shizuku 未运行";
                Log.w(TAG, "openDevOptions: shizuku not running");
                return false;
            }
            int perm;
            try {
                perm = Shizuku.checkSelfPermission();
            } catch (Throwable t) {
                LAST_ERROR = "权限检查异常: " + t;
                Log.w(TAG, "openDevOptions: checkSelfPermission threw", t);
                return false;
            }
            if (perm != PackageManager.PERMISSION_GRANTED) {
                LAST_ERROR = "Shizuku 未授权 perm=" + perm;
                Log.w(TAG, "openDevOptions: permission denied perm=" + perm);
                return false;
            }
            ExecResult r = exec("am start -n " + DEV_ACTIVITY + " -a " + ACTION_DEV
                    + " -c android.intent.category.DEFAULT"
                    + " --es gameDevelopOptions GameDevelopOptionsActivity");
            boolean ok = r.code == 0
                    && !r.out.contains("Error")
                    && !r.out.contains("Exception");
            if (!ok) {
                LAST_ERROR = "rc=" + r.code + " " + oneLine(r.out);
                Log.w(TAG, "openDevOptions failed rc=" + r.code + " out=" + r.out);
            } else {
                LAST_ERROR = "";
                Log.i(TAG, "openDevOptions ok: " + oneLine(r.out));
            }
            return ok;
        } catch (Throwable t) {
            LAST_ERROR = "异常: " + t;
            Log.w(TAG, "openDevOptions exception", t);
            return false;
        }
    }

    /** 把多行输出压成一行短文本，便于塞进 Toast / 日志。 */
    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
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
        final StringBuilder sb = new StringBuilder();
        try {
            p = shizukuProcess(cmd);
            final Process fp = p;

            // 必须与 waitFor 并发读 stdout：否则子进程写满管道缓冲区会阻塞，
            // 而我们又在等它退出 —— 互相等待直到超时。
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader br = null;
                    try {
                        br = new BufferedReader(new InputStreamReader(fp.getInputStream()));
                        String line;
                        while ((line = br.readLine()) != null) {
                            synchronized (sb) {
                                sb.append(line).append('\n');
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (br != null) try { br.close(); } catch (Throwable ignored) {}
                    }
                }
            }, "exec-reader");
            reader.setDaemon(true);
            reader.start();

            // 不能用标准 waitFor(timeout)：它内部只捕获 IllegalThreadStateException，
            // 而 Shizuku 的 ShizukuRemoteProcess 在「尚未退出」时抛的是
            // IllegalArgumentException("process hasn't exited")，会直接冒泡导致误判失败。
            // 这里自己轮询 exitValue()，两种异常都视为「仍在运行」。
            long deadline = System.currentTimeMillis() + 8000L;
            while (true) {
                try {
                    int code = p.exitValue();
                    try { reader.join(300); } catch (InterruptedException ignored) {}
                    String out;
                    synchronized (sb) {
                        out = sb.toString();
                    }
                    return new ExecResult(code, out);
                } catch (IllegalThreadStateException stillRunning) {
                    // 标准实现：进程仍在运行
                } catch (IllegalArgumentException stillRunning) {
                    // Shizuku 实现：process hasn't exited
                }
                if (System.currentTimeMillis() >= deadline) {
                    p.destroy();
                    return new ExecResult(-1, "timeout(8s)");
                }
                try { Thread.sleep(50L); } catch (InterruptedException ignored) {}
            }
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
        String temp = BATTERY_TENTHS > 0
                ? String.format(Locale.US, "%.1f℃  |  ", BATTERY_TENTHS / 10f)
                : "";
        return PHASE + "  |  " + temp + "模式=" + LAST_APPLIED
                + "  |  补投" + FIRE_COUNT + "次/跳过" + SKIP_COUNT + "次";
    }

    private int piFlags() {
        return Build.VERSION.SDK_INT >= 23
                ? (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                : PendingIntent.FLAG_UPDATE_CURRENT;
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

        // 点击通知主体 → 打开 App
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(this, 10, open, piFlags()));
        } catch (Throwable ignored) {
        }

        // 通知栏快捷操作：立即投递 / 停止
        try {
            Icon icon = Icon.createWithResource(this, android.R.drawable.stat_notify_sync);
            Intent fire = new Intent(this, NotifyActionReceiver.class)
                    .setAction(NotifyActionReceiver.ACTION_FIRE);
            b.addAction(new Notification.Action.Builder(icon, "立即投递",
                    PendingIntent.getBroadcast(this, 11, fire, piFlags())).build());
            Intent stop = new Intent(this, NotifyActionReceiver.class)
                    .setAction(NotifyActionReceiver.ACTION_STOP);
            b.addAction(new Notification.Action.Builder(icon, "停止",
                    PendingIntent.getBroadcast(this, 12, stop, piFlags())).build());
        } catch (Throwable t) {
            Log.w(TAG, "add notification actions failed", t);
        }
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
