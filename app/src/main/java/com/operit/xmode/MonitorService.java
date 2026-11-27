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
    /** 是否处于游戏会话中（v1.6.2：UI 判定「生效中」不能只看模式号）。 */
    public static volatile boolean IN_SESSION = false;

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
    /** 事件落盘文件（v1.6.1）：让导出与重启后的诊断日志都有内容。 */
    private static java.io.File logFile;
    private static boolean logLoaded = false;

    private static final String CH_ID = "xmode_channel2";
    /** v1.6.2：旧渠道重要性为 LOW，系统判为「不重要」会折叠，按钮不易看到，已废弃。 */
    private static final String CH_ID_OLD = "xmode_channel";
    private static final int NOTI_ID = 1001;
    /** v1.6.9：实时卡片（流体云 / 状态栏胶囊）走独立渠道与独立通知 id。 */
    private static final String CH_LIVE = "xmode_live_v1";
    private static final int LIVE_ID = 1002;

    private static final long TICK_ACTIVE_MS = 5000L;
    private static final long TICK_IDLE_MS = 10000L;
    private static final long SESSION_WINDOW_MS = 150000L;
    /** 助手心跳过期时间：COSA 心跳 15~45 秒一次，这里留足两三个周期（v1.6.3）。 */
    private static final long INGAME_STALE_MS = 120000L;
    /** 未确认生效时的重试间隔（v1.6.5）：前 2 次 8 秒，之后 15 秒，失败多了退到 30 秒。 */
    private static final long RETRY_FAST_MS = 8000L;
    private static final long RETRY_MID_MS = 15000L;
    private static final long RETRY_SLOW_MS = 30000L;
    private static final int RETRY_FAST_TIMES = 2;
    private static final int RETRY_MID_TIMES = 10;
    private static final long SAFETY_MS = 60000L;

    private static final String TARGET = "com.oplus.games/business.service.SpecialFeatureService";
    private static final String ACTION = "PERFORMANCE_CHANGE_ACTION";
    private static final String GAMES_PKG = "com.oplus.games";
    private static final String ACTION_DEV = "oplus.intent.action.GAMESPACE_GAME_DEVELOP_OPTIONS";
    private static final String DEV_ACTIVITY =
            "com.oplus.games/business.compact.activity.GameDevelopOptionsActivity";

    private static final Pattern P_APPLIED = Pattern.compile("toAppliedMode = (-?[0-9]+)");

    /**
     * 机身温度采样（v1.6.2）：只取外壳 / 皮肤 / 电池类热区，排除 cpu-*、gpuss-*、bcl 等
     * 内部传感器 —— 那些值跳动大且明显高于体感（实测可在 51~64℃ 之间乱跳）。
     */
    private static final String CMD_BODY_TEMP = "echo HZ:$(for z in /sys/class/thermal/thermal_zone*; do "
            + "n=$(cat $z/type 2>/dev/null); "
            + "case \"$n\" in shell_back|shell_front|shell_frame|skin*|battery|batt*) "
            + "cat $z/temp 2>/dev/null;; esac; done | sort -n | tail -1)";

    private static ScheduledExecutorService pool;

    private volatile boolean started = false;
    private volatile long lastPerfTs = 0L;
    private volatile long lastFireTs = 0L;
    private volatile Process watcherProc = null;
    /** 当前 logcat 挂着的游戏助手 pid；-1 表示尚未挂载。 */
    private volatile int watcherPid = -1;
    /** watcher 代际号：递增即让旧线程失效，修「停止后快速启动丢监听」。 */
    private volatile int watcherGen = 0;
    /** 跳过计数 / Shizuku 告警的节流时间戳。 */
    private volatile long lastSkipTs = 0L;
    private volatile long lastShizukuLogTs = 0L;
    /** 游戏助手自报的 in-game 状态 + 心跳时间（v1.6.3：会话判定的主信号）。 */
    private volatile boolean inGameFlag = false;
    private volatile long inGameTs = 0L;
    /** 上一秒起连续投递次数（v1.6.4：未确认生效时的重试计数）。 */
    private volatile int fireAttempts = 0;
    /** 收到「助手真的应用了模式」后，下一轮 tick 把当前前台包名记成游戏。 */
    private volatile boolean rememberPending = false;
    /** 上一次因「不在白名单」被跳过而写日志的包名，避免重复写。 */
    private volatile String lastSkipPkg = null;
    /** 上一次已写进诊断日志的阶段文案（v1.6.5）。 */
    private volatile String lastPhaseSeen = "";
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
            if (logFile == null) {
                logFile = new java.io.File(c.getApplicationContext().getFilesDir(), "events.log");
            }
            if (!logLoaded) {
                logLoaded = true;
                loadLogsFromDisk();
            }
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
            appendLogLineLocked(line);
        }
        Log.i(TAG, "evt " + msg);
    }

    /** 清空诊断日志。 */
    public static void clearLog() {
        synchronized (LOG_Q) {
            LOG_Q.clear();
            LOGS = Collections.emptyList();
            rewriteLogFileLocked();
        }
    }

    // ── 事件落盘（v1.6.1）：应用重启 / 服务未运行时导出仍有内容 ──

    private static void appendLogLineLocked(String line) {
        try {
            if (logFile == null) return;
            java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
            try {
                fw.write(line + "\n");
            } finally {
                fw.close();
            }
            if (logFile.length() > 24000L) {
                rewriteLogFileLocked();
            }
        } catch (Throwable t) {
            Log.w(TAG, "log write failed", t);
        }
    }

    private static void rewriteLogFileLocked() {
        try {
            if (logFile == null) return;
            java.io.FileWriter fw = new java.io.FileWriter(logFile, false);
            try {
                for (String l : LOG_Q) fw.write(l + "\n");
            } finally {
                fw.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "log rewrite failed", t);
        }
    }

    private static void loadLogsFromDisk() {
        try {
            if (logFile == null || !logFile.exists()) return;
            ArrayDeque<String> dq = new ArrayDeque<>();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(logFile));
            try {
                String l;
                while ((l = br.readLine()) != null) {
                    if (l.trim().isEmpty()) continue;
                    dq.addLast(l);
                    while (dq.size() > LOG_MAX) dq.removeFirst();
                }
            } finally {
                br.close();
            }
            synchronized (LOG_Q) {
                LOG_Q.clear();
                LOG_Q.addAll(dq);
                LOGS = new ArrayList<>(LOG_Q);
            }
        } catch (Throwable t) {
            Log.w(TAG, "log load failed", t);
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
                .append("常驻服务: ").append(RUNNING ? "运行中" : "未运行").append('\n')
                .append("阶段: ").append(PHASE).append('\n')
                .append("助手模式: ").append(LAST_APPLIED).append('\n')
                .append("补投 / 跳过: ").append(FIRE_COUNT).append(" / ").append(SKIP_COUNT).append('\n')
                .append("电池温度: ").append(BATTERY_TENTHS > 0
                        ? String.format(Locale.US, "%.1f℃", BATTERY_TENTHS / 10f) : "—").append('\n')
                .append("游戏会话: ").append(IN_SESSION ? "有" : "无").append('\n')
                .append("CPU 最高簇: ").append(CPU_CUR_KHZ > 0 && CPU_MAX_KHZ > 0
                        ? String.format(Locale.US, "%.2f GHz / %.2f GHz",
                        CPU_CUR_KHZ / 1000000f, CPU_MAX_KHZ / 1000000f) : "—").append('\n')
                .append("机身温度: ").append(HOT_ZONE_TENTHS > 0
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
            sb.append("(无事件：常驻监控未启动，或刚刚启动尚无事件)\n");
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
            startForeground(NOTI_ID, buildNotification("X模式助手 · 启动中", "正在连接 Shizuku…"));
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
        IN_SESSION = false;
        // 让旧 watcher 线程立刻失效（v1.6.2），避免快速停止/启动时留下无监听的窗口
        watcherGen++;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        try {
            if (watcherProc != null) watcherProc.destroy();
        } catch (Throwable ignored) {
        }
        watcherProc = null;
        watcherPid = -1;
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
        // Shizuku 不可用时不再假装「待机」，明确告知（v1.6.2）
        if (!shizukuReady()) {
            IN_SESSION = false;
            PHASE = "Shizuku未就绪";
            long t0 = System.currentTimeMillis();
            if (t0 - lastShizukuLogTs > 60000L) {
                lastShizukuLogTs = t0;
                logEvent("Shizuku 未就绪（服务未运行或未授权）");
            }
            nextDelay = TICK_IDLE_MS;
            updateNotificationIfChanged();
            return;
        }

        Sample s = sample();
        String top = s.top;
        long now = System.currentTimeMillis();
        boolean self = top == null
                || top.equals(getPackageName())
                // v1.6.7：Operit 宿主本身也视为「自身」，避免被当成白名单外的应用
                || top.startsWith("com.ai.assistance.operit")
                || top.equals("com.android.systemui")
                || top.endsWith("launcher")
                || top.endsWith("launcher3");
        boolean session = (inGameFlag && (now - inGameTs) < INGAME_STALE_MS)
                || (now - lastPerfTs) < SESSION_WINDOW_MS;
        boolean allowed = WHITELIST.isEmpty() || isWhitelisted(top);
        // v1.6.5：只有助手日志确认 mode=3 才算生效；未确认就按间隔重试
        long gap = now - lastFireTs;
        long retryGap = (fireAttempts <= RETRY_FAST_TIMES) ? RETRY_FAST_MS
                : (fireAttempts <= RETRY_MID_TIMES ? RETRY_MID_MS : RETRY_SLOW_MS);
        boolean need = (LAST_APPLIED == 3) ? gap > SAFETY_MS : gap > retryGap;

        // 游戏助手进程变了（被杀 / 升级）→ 让 logcat 重新挂到新 pid（v1.6.2）
        Process wp = watcherProc;
        if (wp != null && (s.gamesPid <= 0 || s.gamesPid != watcherPid)) {
            try {
                wp.destroy();
            } catch (Throwable ignored) {
            }
        }

        IN_SESSION = session;

        // 实时温度 / 频率（采不到就保留上次值）
        if (s.batteryTenths > 0) BATTERY_TENTHS = s.batteryTenths;
        if (s.cpuCurKhz > 0) CPU_CUR_KHZ = s.cpuCurKhz;
        if (s.cpuMaxKhz > 0) CPU_MAX_KHZ = s.cpuMaxKhz;
        if (s.hotTenths > 0) HOT_ZONE_TENTHS = s.hotTenths;

        // 会话起落 → 写诊断日志；候选游戏只在「跃迁那一刻」记录（v1.6.2）
        if (session && !lastSession) {
            fireAttempts = 0;
            logEvent("检测到游戏会话" + (top != null && !self ? "：" + top : ""));
            if (top != null && !self) rememberGame(this, top);
        } else if (!session && lastSession) {
            logEvent("游戏会话结束");
        }
        lastSession = session;
        if (!self && session && top != null && !top.equals(lastForeground)) {
            logEvent("前台应用 " + top);
        }
        if (top != null) lastForeground = top;

        // v1.6.4：只有助手真的应用了模式，才把前台包名记成游戏（避免 Operit/微信混进候选）
        if (rememberPending) {
            rememberPending = false;
            if (top != null && !self) rememberGame(this, top);
        }

        if (self) {
            PHASE = "自身/系统前台(待机)";
        } else if (!session) {
            PHASE = "无游戏会话(待机)";
        } else if (!allowed) {
            PHASE = "不在白名单(跳过：" + top + ")";
            if (top != null && !top.equals(lastSkipPkg)) {
                lastSkipPkg = top;
                logEvent("跳过 " + top + "（不在白名单）");
            }
        } else if (need) {
            if (fire()) {
                // v1.6.4：不乐观置位 —— 助手可能还没进入 in-game 状态而丢弃这次投递
                lastFireTs = now;
                fireAttempts++;
                FIRE_COUNT++;
                PHASE = "已投递，等待助手确认";
                if (fireAttempts <= 3 || fireAttempts % 5 == 0) {
                    logEvent("投递#" + fireAttempts + " 前台 " + top);
                }
            } else {
                PHASE = "投递失败(检查 Shizuku)";
                logEvent("投递失败 " + oneLine(LAST_ERROR));
            }
        } else {
            // 每分钟最多记一次，避免计数飞涨与通知频繁刷新（v1.6.2）
            if (now - lastSkipTs > 60000L) {
                lastSkipTs = now;
                SKIP_COUNT++;
            }
            PHASE = "X模式生效中(跳过)";
        }
        nextDelay = (session && !self) ? TICK_ACTIVE_MS : TICK_IDLE_MS;

        // v1.6.5：把阶段变化写进诊断日志，便于事后定位
        if (!PHASE.equals(lastPhaseSeen)) {
            lastPhaseSeen = PHASE;
            logEvent("阶段 " + PHASE);
        }
        updateNotificationIfChanged();
    }

    /** 一次 shell 往返同时取回：前台包、电池温度、最高簇频率、机身温度、游戏助手 pid。 */
    private Sample sample() {
        Sample s = new Sample();
        try {
            ExecResult r = exec("echo RA:$(dumpsys activity activities 2>/dev/null | grep -m1 ResumedActivity); "
                    + "echo BT:$(dumpsys battery 2>/dev/null | grep -m1 temperature | tr -dc '0-9'); "
                    + "echo CR:$(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq 2>/dev/null); "
                    + "echo MX:$(cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq 2>/dev/null); "
                    + "echo GP:$(pidof com.oplus.games 2>/dev/null); "
                    + CMD_BODY_TEMP);
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
                    } else if (line.startsWith("GP:")) {
                        s.gamesPid = firstInt(line.substring(3), -1);
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

    /** 取字符串里第一个数字（pidof 可能返回多个 pid，用空格分隔）。 */
    private static int firstInt(String s, int def) {
        if (s == null) return def;
        String t = s.trim();
        int i = 0;
        while (i < t.length() && !Character.isWhitespace(t.charAt(i))) i++;
        return parseInt(i == 0 ? t : t.substring(0, i), def);
    }

    private static final class Sample {
        String top;
        int batteryTenths = -1;
        int cpuCurKhz = -1;
        int cpuMaxKhz = -1;
        int hotTenths = -1;
        /** 游戏助手进程 pid；<=0 表示未运行。 */
        int gamesPid = -1;
    }

    private void startWatcher() {
        final int gen = ++watcherGen;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (RUNNING && gen == watcherGen) {
                    Process p = null;
                    try {
                        int gp = pidOfInt(GAMES_PKG);
                        if (gp <= 0) {
                            safeSleep(2000);
                            continue;
                        }
                        watcherPid = gp;
                        p = shizukuProcess("logcat -v brief -T 1 --pid=" + gp);
                        watcherProc = p;
                        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while (RUNNING && gen == watcherGen && (line = br.readLine()) != null) {
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
                        watcherPid = -1;
                    }
                    safeSleep(1500);
                }
                Log.i(TAG, "watcher exit gen=" + gen);
            }
        }, "xmode-logcat-watcher-" + gen);
        t.setDaemon(true);
        t.start();
    }

    private void onLogLine(String line) {
        if (line == null) return;
        // v1.6.3：助手的 in-game 状态是最可靠的会话信号（含周期心跳）
        if (line.contains("isInGame")) {
            updateInGameFlag(line);
        }
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
                if (v == 3) {
                    // 助手确认生效 → 清零重试计数
                    fireAttempts = 0;
                }
                if (v > 0) {
                    // 助手真的应用了模式 → 下一轮把前台包名记成游戏
                    rememberPending = true;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Shizuku 服务在线且已授权（v1.6.2：tick 用它区分「真待机」与「没权限」）。 */
    public static boolean shizukuReady() {
        try {
            if (!Shizuku.pingBinder()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解析助手日志里的 isInGame 状态（两种写法：`isInGame = true` / `isInGame=false`）。 */
    private void updateInGameFlag(String line) {
        int i = line.indexOf("isInGame");
        if (i < 0) return;
        int p = i + "isInGame".length();
        while (p < line.length() && (line.charAt(p) == ' ' || line.charAt(p) == '=')) p++;
        boolean val;
        if (line.startsWith("true", p)) {
            val = true;
        } else if (line.startsWith("false", p)) {
            val = false;
        } else {
            return;
        }
        // v1.6.4：助手 false 抖得很厉害（实测十几秒翻一次），不据此清会话；
        // 只把 true 当「正在游戏」的正向信号，过期交给 INGAME_STALE_MS。
        if (!val) return;
        inGameFlag = true;
        long now = System.currentTimeMillis();
        inGameTs = now;
        lastPerfTs = now;
    }

    /** 投递一枪 mode=3（需 Shizuku shell 身份） */
    public static boolean fire() {
        try {
            if (!Shizuku.pingBinder()) {
                LAST_ERROR = "Shizuku 未运行";
                Log.w(TAG, "shizuku not running");
                return false;
            }
            int perm;
            try {
                perm = Shizuku.checkSelfPermission();
            } catch (Throwable t) {
                LAST_ERROR = "权限检查异常: " + t;
                Log.w(TAG, "fire: checkSelfPermission threw", t);
                return false;
            }
            if (perm != PackageManager.PERMISSION_GRANTED) {
                LAST_ERROR = "Shizuku 未授权 perm=" + perm;
                Log.w(TAG, "shizuku permission not granted");
                return false;
            }
            ExecResult r = exec("am startservice --user 0 -n " + TARGET + " -a " + ACTION
                    + " --ei mode 3 2>/dev/null");
            boolean ok = r.code == 0
                    && !r.out.contains("Error")
                    && !r.out.contains("Exception")
                    && !r.out.contains("Failure");
            if (ok) {
                LAST_ERROR = "";
            } else {
                LAST_ERROR = "rc=" + r.code + " " + oneLine(r.out);
                Log.w(TAG, "fire failed rc=" + r.code + " out=" + r.out);
            }
            return ok;
        } catch (Throwable t) {
            LAST_ERROR = "异常: " + t;
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

    private static int pidOfInt(String name) {
        try {
            ExecResult r = exec("pidof " + name);
            return firstInt(r.out, -1);
        } catch (Throwable t) {
            return -1;
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
        // 形如：ActivityRecord{1a2b3c4 u0 com.pkg/.Act t123}
        // 用户标识可能是 u0 / u10 / u999（分身、多用户），不能只找 "u0 "。
        int n = line.length();
        for (int i = 0; i < n; i++) {
            if (line.charAt(i) != 'u') continue;
            int j = i + 1;
            while (j < n && Character.isDigit(line.charAt(j))) j++;
            if (j == i + 1 || j >= n || line.charAt(j) != ' ') continue;
            String rest = line.substring(j + 1).trim();
            int slash = rest.indexOf('/');
            if (slash <= 0) continue;
            String pkg = rest.substring(0, slash).trim();
            if (isPkg(pkg)) return pkg;
        }
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
            if (nm == null) return;
            // v1.6.9：独立的「实时卡片」渠道，IMPORTANCE_HIGH —— ColorOS 流体云 /
            // Android 16 实时通知要求渠道级别够高，DEFAULT 的旧渠道不会升格。
            // 渠道属性创建后不可改，所以用新 id；设为无声无振动，避免每次刷新响一下。
            if (nm.getNotificationChannel(CH_LIVE) == null) {
                NotificationChannel live = new NotificationChannel(CH_LIVE, "X模式实时卡片",
                        NotificationManager.IMPORTANCE_HIGH);
                live.setDescription("游戏时在状态栏胶囊 / 流体云显示 X 模式状态");
                live.setShowBadge(false);
                live.setSound(null, null);
                live.enableVibration(false);
                live.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(live);
            }
            // v1.6.9：把系统侧的「流体云 / 实时活动」总开关状态记进日志，方便判断卡在哪
            if (Build.VERSION.SDK_INT >= 36) {
                try {
                    Log.i(TAG, "evt 上岛可用=" + nm.canPostPromotedNotifications());
                } catch (Throwable ignored) {
                }
            }
            // 删掉旧的「不重要」渠道（v1.6.2）
            try {
                if (nm.getNotificationChannel(CH_ID_OLD) != null) {
                    nm.deleteNotificationChannel(CH_ID_OLD);
                }
            } catch (Throwable ignored) {
            }
            if (nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "X模式维持",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("游戏时自动维持官方 X 模式");
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void updateNotificationIfChanged() {
        // v1.6.6：标题也随状态变化，所以一并纳入去重键
        String title = notiTitle();
        String text = buildStatus();
        String key = title + "\n" + text;
        if (!key.equals(lastNoti)) {
            lastNoti = key;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNotification(title, text));
        }
        // v1.6.9：实时卡片自带去重，不能受前台通知的去重短路影响
        updateLiveCard(title, text);
    }

    /** v1.6.6：通知标题随状态变化，作为「X 模式是否真的在生效」的可信指示器。 */
    private String notiTitle() {
        if (PHASE.startsWith("已停止")) return "X模式助手 · 已停止";
        if (PHASE.startsWith("Shizuku")) return "X模式助手 · Shizuku 未连接";
        if (PHASE.startsWith("不在白名单")) return "不在白名单 · 已跳过";
        if (!IN_SESSION) return "X模式助手 · 监控中(待机)";
        if (LAST_APPLIED == 3) return "X模式生效中";
        if (PHASE.startsWith("已投递")) return "已投递，等待助手生效…";
        if (PHASE.startsWith("投递失败")) return "投递失败 · 检查 Shizuku";
        return "X模式助手 · 监控中";
    }

    /** 应用名缓存，避免每次刷新通知都查 PackageManager。 */
    private volatile String labelPkg = "";
    private volatile String labelTxt = "";

    private String appLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        if (pkg.equals(labelPkg)) return labelTxt;
        String out = pkg;
        try {
            PackageManager pm = getPackageManager();
            out = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) {
        }
        labelPkg = pkg;
        labelTxt = out;
        return out;
    }

    private String buildStatus() {
        StringBuilder sb = new StringBuilder();
        // v1.6.7：正文优先显示前台应用的中文名，读起来比包名直观
        String fg = appLabel(lastForeground);
        if (fg != null && !fg.isEmpty()) {
            sb.append("前台 ").append(fg);
            if (PHASE.startsWith("不在白名单")) sb.append("（白名单外）");
        } else {
            sb.append(PHASE);
        }
        if (BATTERY_TENTHS > 0) {
            sb.append(" · 机身 ").append(String.format(Locale.US, "%.1f℃", BATTERY_TENTHS / 10f));
        }
        sb.append(" · 助手模式 ").append(LAST_APPLIED);
        sb.append('\n').append("补投 ").append(FIRE_COUNT)
                .append(" 次 · 跳过 ").append(SKIP_COUNT).append(" 次");
        return sb.toString();
    }

    private int piFlags() {
        return Build.VERSION.SDK_INT >= 23
                ? (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String title, String text) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
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

        Notification n = b.build();
        return n;
    }

    /** v1.6.9：实时卡片去重键。 */
    private String lastLive = "";

    /**
     * v1.6.9：状态栏实时卡片 / ColorOS 流体云。
     * 与前台服务通知分开：前台通知（id 1001）保证不被杀，
     * 这一路（id 1002，渠道 IMPORTANCE_HIGH）负责升格成胶囊 / 实时动态。
     * 照抄 AuroraViz 里已验证可行的写法。
     */
    private void updateLiveCard(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        boolean want = liveCardOn(this) && !PHASE.startsWith("已停止");
        if (!want) {
            if (!lastLive.isEmpty()) {
                lastLive = "";
                try {
                    nm.cancel(LIVE_ID);
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        String key = title + "\n" + text;
        if (key.equals(lastLive)) return;

        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH_LIVE)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    // 官方要求：不得 setColorized(true)，否则直接失去升格资格
                    .setColor(0xFFFF6A1A)
                    .setStyle(new Notification.BigTextStyle().bigText(text));
            if (Build.VERSION.SDK_INT >= 36) {
                String st = liveShortText();
                if (st != null && !st.isEmpty()) {
                    b.setShortCriticalText(st);
                }
            }
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(this, 20, open, piFlags()));

            Notification n = b.build();
            n.flags |= Notification.FLAG_ONGOING_EVENT;
            // 主动请求升格为实时动态；系统不认则退化为普通通知，不会出错
            n.extras.putBoolean("android.requestPromotedOngoing", true);
            nm.notify(LIVE_ID, n);
            lastLive = key;
            Log.i(TAG, "evt 实时卡片 " + liveShortText());
        } catch (Throwable t) {
            Log.w(TAG, "live card failed", t);
        }
    }

    /** v1.6.9：胶囊 / 实时卡片上的短文本（建议 ≤7 字符）。 */
    private String liveShortText() {
        if (!IN_SESSION && LAST_APPLIED != 3) {
            return "待机";
        }
        if (LAST_APPLIED == 3) {
            return BATTERY_TENTHS > 0
                    ? String.format(Locale.US, "X%.0f℃", BATTERY_TENTHS / 10f)
                    : "X生效";
        }
        return "投递中";
    }

    /** v1.6.9：状态栏实时卡片开关（默认开，可在 App 内关闭）。 */
    private static final String KEY_LIVE_CARD = "live_card";

    public static boolean liveCardOn(Context c) {
        try {
            return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_LIVE_CARD, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setLiveCard(Context c, boolean on) {
        try {
            c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_LIVE_CARD, on).apply();
        } catch (Throwable ignored) {
        }
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
