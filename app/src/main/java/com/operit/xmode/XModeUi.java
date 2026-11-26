package com.operit.xmode;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * X模式助手 UI 层（v2）。
 *
 * <p><b>本类只做两件事：构建视图、按 {@link State} 渲染。</b>
 * 不含任何业务逻辑：不访问 Shizuku、不启停服务、不读系统权限、不发网络请求。
 * 所有用户操作都通过 {@link Listener} 回抛给宿主，由功能实现方决定怎么做。
 *
 * <p>接入方式（三步）：
 * <pre>
 * XModeUi ui = new XModeUi(context, listener);   // 1. 传入回调
 * setContentView(ui.root());                     // 2. 挂上视图
 * ui.render(state);                              // 3. 数据到了就渲染（可反复调用）
 * </pre>
 */
public class XModeUi {

    /** 作者署名（关于卡片与源码链接都引用它）。 */
    public static final String AUTHOR = "huoxingrNaNa";

    /** 源码仓库地址（关于页「打开仓库」和 MainActivity 共用）。 */
    public static final String REPO_URL = "https://github.com/" + AUTHOR + "/XModeEnabler";

    /** 本应用许可：MIT 全文（关于页→许可证页展示）。 */
    private static final String MIT_TEXT =
            "Copyright (c) 2026 " + AUTHOR + "\n\n"
                    + "Permission is hereby granted, free of charge, to any person obtaining a copy "
                    + "of this software and associated documentation files (the \"Software\"), to deal "
                    + "in the Software without restriction, including without limitation the rights "
                    + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies "
                    + "of the Software, and to permit persons to whom the Software is furnished to do so, "
                    + "subject to the following conditions:\n\n"
                    + "The above copyright notice and this permission notice shall be included in all "
                    + "copies or substantial portions of the Software.\n\n"
                    + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, "
                    + "INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A "
                    + "PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT "
                    + "HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF "
                    + "CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE "
                    + "OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.";

    /** 交互回调：UI 只报告"用户点了什么"，不关心后续如何处理。 */
    public interface Listener {
        /** 点「Shizuku 服务」行（或右侧胶囊）。 */
        void onShizuku();

        /** 点「自动化测试开关」行（或右侧胶囊）。 */
        void onAutoTest();

        /** 点「忽略电池优化」行（或右侧胶囊）。 */
        void onBattery();

        /** 点主按钮：当前未运行时应启动，运行中时应停止。 */
        void onToggleMonitor();

        /** 点「手动投递一次 X 模式」。 */
        void onFireOnce();

        // ── v1.3 新增 ──

        /** 把某个包名加入游戏白名单。 */
        void onWhitelistAdd(String pkg);

        /** 把某个包名移出游戏白名单。 */
        void onWhitelistRemove(String pkg);

        /** 清空诊断日志。 */
        void onLogClear();

        /** 在浏览器打开源码仓库。 */
        void onOpenRepo();

        /** 切换诊断日志记录开关。 */
        void onToggleLog();

        /** 把诊断日志导出为文本文件。 */
        void onExportLog();
    }

    /** 渲染所需的数据，全部由功能实现方填充。 */
    public static final class State {
        /** Shizuku 服务是否在运行。 */
        public boolean shizukuRunning;
        /** Shizuku 是否已对本应用授权。 */
        public boolean shizukuGranted;
        /** 是否已忽略电池优化。 */
        public boolean batteryIgnored;
        /** 常驻监控是否在运行。 */
        public boolean monitoring;
        /** 当前是否处于游戏会话中（v1.6.2：判定「生效中」还需它）。 */
        public boolean inSession;
        /** 游戏助手版本名（关于页「适配」行用）；空串表示未知。 */
        public String gamesVersion = "";
        /** 阶段文案，例如「X模式生效中(跳过)」「自身/系统前台(待机)」。 */
        public String phase = "";
        /** 游戏助手当前模式，-1 表示未知。 */
        public int mode = -1;
        /** 累计补投次数。 */
        public int fireCount;
        /** 累计跳过次数。 */
        public int skipCount;

        // ── v1.3 新增 ──

        /** 版本名，例如 "1.3"；空串表示未知。 */
        public String versionName = "";
        /** 白名单（包名）。空表示对所有游戏生效。 */
        public java.util.List<String> whitelist = java.util.Collections.emptyList();
        /** 最近被识别为游戏的包名（供「加入白名单」）。 */
        public java.util.List<String> knownGames = java.util.Collections.emptyList();
        /** 诊断日志（最新在末尾）。 */
        public java.util.List<String> logs = java.util.Collections.emptyList();
        /** 电池温度，单位 0.1℃；<=0 表示未知。 */
        public int batteryTenths = -1;
        /** CPU 最高簇当前频率 / 上限，单位 kHz；<=0 表示未知。 */
        public int cpuCurKhz = -1;
        public int cpuMaxKhz = -1;
        /** 最热热区温度，单位 0.1℃；<=0 表示未知。 */
        public int hotZoneTenths = -1;
        /** 诊断日志记录开关（v1.6）。 */
        public boolean logEnabled = true;
    }

    private static final int MP = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int CARD = 0xFF141D30;
    private static final int CARD_ALT = 0xFF101827;
    private static final int STROKE = 0x14FFFFFF;
    private static final int TEXT_MAIN = 0xFFEEF3FF;
    private static final int TEXT_SUB = 0xFF8494B4;
    private static final int ACCENT = 0xFF38E1C6;
    private static final int BLUE = 0xFF4E8DFF;
    private static final int VIOLET = 0xFFB48CFF;
    private static final int RED = 0xFFFF6B6B;
    private static final int AMBER = 0xFFFFB020;
    private static final int GREEN = 0xFF3ED598;
    private static final int IDLE = 0xFF5A6890;
    private static final int DEEP = 0xFF06121A;

    private final Context context;
    private final Listener listener;
    private final float density;

    private final Row rowShizuku = new Row();
    private final Row rowAutoTest = new Row();
    private final Row rowBattery = new Row();

    private FrameLayout root;
    private PulseView pulse;
    private TextView heroTitle;
    private TextView heroPhase;
    private TextView statFire;
    private TextView statSkip;
    private TextView statMode;
    private TextView shizukuPill;
    private LinearLayout mainButton;
    private TextView mainLabel;
    private XIcon mainIcon;

    // v1.3 新增视图引用
    private TextView subTitle;
    private TextView statBattery;
    private TextView statCpu;
    private TextView statHot;
    private LinearLayout wlBox;
    private TextView logView;
    private TextView logToggle;
    private TextView aboutVersion;
    private TextView aboutGamesVersion;
    private String lastWlSig = "";
    private String lastLogSig = "";

    // v1.3 底部标签页
    private ScrollView homeScroll;
    private ScrollView aboutScroll;
    private ScrollView licenseScroll;
    private XIcon tabHomeIcon;
    private XIcon tabAboutIcon;
    private TextView tabHomeLabel;
    private TextView tabAboutLabel;
    private int currentPage = 0;

    private static final class Row {
        View dot;
        TextView action;
    }

    public XModeUi(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    /** 界面根视图，交给宿主 setContentView。 */
    public View root() {
        if (root == null) {
            root = build();
        }
        return root;
    }

    /** 用最新状态刷新界面，可在任意线程的 UI 回调里反复调用。 */
    public void render(State state) {
        if (state == null) {
            state = new State();
        }
        root();

        final boolean effective = state.monitoring && state.mode == 3 && state.inSession;

        // 顶部 Shizuku 胶囊
        int pillColor = state.shizukuGranted ? GREEN : state.shizukuRunning ? AMBER : RED;
        shizukuPill.setText(state.shizukuRunning ? "Shizuku" : "无 Shizuku");
        shizukuPill.setTextColor(pillColor);
        shizukuPill.setBackground(pillBg(alpha(pillColor, 0x1F)));

        // 主状态
        if (!state.monitoring) {
            heroTitle.setText("未启动");
            heroTitle.setTextColor(TEXT_MAIN);
            heroPhase.setText("点击下方按钮启动常驻监控");
            pulse.setAccent(IDLE);
        } else if (effective) {
            heroTitle.setText("X 模式生效中");
            heroTitle.setTextColor(ACCENT);
            heroPhase.setText(state.phase);
            pulse.setAccent(ACCENT);
        } else {
            heroTitle.setText("监控中");
            heroTitle.setTextColor(BLUE);
            heroPhase.setText(state.phase);
            pulse.setAccent(BLUE);
        }
        pulse.setActive(effective);

        statFire.setText(state.monitoring ? String.valueOf(state.fireCount) : "—");
        statSkip.setText(state.monitoring ? String.valueOf(state.skipCount) : "—");
        statMode.setText(state.monitoring && state.mode >= 0 ? String.valueOf(state.mode) : "—");

        // 前置条件
        setRow(rowShizuku,
                state.shizukuGranted ? GREEN : state.shizukuRunning ? AMBER : RED,
                state.shizukuGranted ? "已就绪" : "去授权",
                state.shizukuGranted);

        // 该开关存在游戏助手私有存储里，本应用无从得知，固定标注为需手动确认
        setRow(rowAutoTest, VIOLET, "打开开关", false);

        setRow(rowBattery,
                state.batteryIgnored ? GREEN : AMBER,
                state.batteryIgnored ? "已就绪" : "去设置",
                state.batteryIgnored);

        // ── v1.3：版本号 / 实时状态 / 白名单 / 诊断日志 ──
        String ver = (state.versionName == null || state.versionName.isEmpty())
                ? "1.6.5" : state.versionName;
        if (subTitle != null) {
            subTitle.setText("v" + ver + " · ColorOS 游戏助手");
        }
        if (aboutVersion != null) {
            aboutVersion.setText("v" + ver);
        }
        if (aboutGamesVersion != null) {
            String gv = state.gamesVersion == null ? "" : state.gamesVersion.trim();
            aboutGamesVersion.setText("游戏助手 " + (gv.isEmpty() ? "未知" : gv));
        }

        if (statBattery != null) {
            if (state.monitoring && state.batteryTenths > 0) {
                statBattery.setText(String.format(Locale.US, "%.1f℃", state.batteryTenths / 10f));
                statBattery.setTextColor(tempColor(state.batteryTenths));
            } else {
                statBattery.setText("—");
                statBattery.setTextColor(TEXT_MAIN);
            }
        }
        if (statCpu != null) {
            if (state.monitoring && state.cpuCurKhz > 0 && state.cpuMaxKhz > 0) {
                statCpu.setText(String.format(Locale.US, "%.1f/%.1fG",
                        state.cpuCurKhz / 1000000f, state.cpuMaxKhz / 1000000f));
            } else {
                statCpu.setText("—");
            }
        }
        if (statHot != null) {
            if (state.monitoring && state.hotZoneTenths > 0) {
                statHot.setText(String.format(Locale.US, "%.1f℃", state.hotZoneTenths / 10f));
                statHot.setTextColor(tempColor(state.hotZoneTenths));
            } else {
                statHot.setText("—");
                statHot.setTextColor(TEXT_MAIN);
            }
        }

        refreshWhitelist(state);
        refreshLogs(state);

        if (logToggle != null) {
            boolean on = state.logEnabled;
            logToggle.setText(on ? "记录开" : "记录关");
            logToggle.setTextColor(on ? ACCENT : IDLE);
            logToggle.setBackground(pillBg(alpha(on ? ACCENT : IDLE, on ? 0x1E : 0x18)));
        }

        styleMainButton(state.monitoring);
    }

    // ================================================================ v1.3 动态区块

    /** 温度越大越红（0.1℃ 输入）。 */
    private int tempColor(int tenths) {
        if (tenths >= 450) return RED;
        if (tenths >= 400) return AMBER;
        return TEXT_MAIN;
    }

    /** 重建白名单区块（内容变化时才重建，避免每 1.5s 抖动）。 */
    private void refreshWhitelist(State state) {
        if (wlBox == null) return;
        List<String> wl = state.whitelist == null ? new ArrayList<String>() : state.whitelist;
        List<String> known = state.knownGames == null ? new ArrayList<String>() : state.knownGames;

        StringBuilder sig = new StringBuilder();
        for (String p : wl) sig.append(p).append('|');
        sig.append('#');
        for (String p : known) sig.append(p).append('|');
        String signature = sig.toString();
        if (signature.equals(lastWlSig)) return;
        lastWlSig = signature;

        wlBox.removeAllViews();

        if (wl.isEmpty()) {
            wlBox.addView(hint("白名单为空 → 对所有游戏生效"));
        } else {
            for (String pkg : wl) {
                wlBox.addView(wlRow(pkg, "移除", RED, false));
            }
        }

        List<String> candidates = new ArrayList<String>();
        for (String pkg : known) {
            if (!wl.contains(pkg)) candidates.add(pkg);
        }
        if (!candidates.isEmpty()) {
            wlBox.addView(dividerLine());
            wlBox.addView(hint("最近检测到的游戏（可加入白名单）"));
            for (String pkg : candidates) {
                wlBox.addView(wlRow(pkg, "加入", ACCENT, true));
            }
        } else if (wl.isEmpty()) {
            wlBox.addView(hint("玩过一次游戏后，这里会出现可选的应用"));
        }

        if (!wl.isEmpty()) {
            wlBox.addView(hint("注意：白名单非空时，只对以上游戏维持 X 模式，其他游戏会被跳过"));
        }
    }

    /** 诊断日志：只显示最近 12 条。 */
    private void refreshLogs(State state) {
        if (logView == null) return;
        List<String> logs = state.logs == null ? new ArrayList<String>() : state.logs;
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, logs.size() - 12);
        for (int i = from; i < logs.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(logs.get(i));
        }
        String text = sb.length() == 0 ? "暂无事件" : sb.toString();
        if (!state.logEnabled) {
            text = "记录已关闭（不再写入缓冲区，仍写系统 logcat）\n" + text;
        }
        if (text.equals(lastLogSig)) return;
        lastLogSig = text;
        logView.setText(text);
    }

    private TextView hint(String value) {
        TextView view = text(value, 10.5f, TEXT_SUB, false);
        view.setPadding(dp(6), dp(6), dp(6), dp(2));
        return view;
    }

    private View dividerLine() {
        View v = new View(context);
        v.setBackgroundColor(0x10FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, dp(1));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(2);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    // ================================================================ 视图构建

    private FrameLayout build() {
        FrameLayout shell = new FrameLayout(context);
        shell.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF070B14, 0xFF0E1726, 0xFF070B14}));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // 内容区：首页 / 关于 两页叠放，靠可见性切换
        FrameLayout content = new FrameLayout(context);
        column.addView(content, new LinearLayout.LayoutParams(MP, 0, 1f));

        homeScroll = newScroll();
        homeScroll.addView(buildHome(), new FrameLayout.LayoutParams(MP, WC));
        content.addView(homeScroll, new FrameLayout.LayoutParams(MP, MP));

        aboutScroll = newScroll();
        aboutScroll.addView(buildAboutPage(), new FrameLayout.LayoutParams(MP, WC));
        aboutScroll.setVisibility(View.GONE);
        content.addView(aboutScroll, new FrameLayout.LayoutParams(MP, MP));

        licenseScroll = newScroll();
        licenseScroll.addView(buildLicensePage(), new FrameLayout.LayoutParams(MP, WC));
        licenseScroll.setVisibility(View.GONE);
        content.addView(licenseScroll, new FrameLayout.LayoutParams(MP, MP));

        column.addView(buildBottomBar(), new LinearLayout.LayoutParams(MP, WC));
        shell.addView(column, new FrameLayout.LayoutParams(MP, MP));

        selectPage(0);
        return shell;
    }

    private ScrollView newScroll() {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        return scroll;
    }

    /** 首页内容。 */
    private LinearLayout buildHome() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(20), dp(20), dp(28));

        column.addView(buildHeader());
        column.addView(buildHero(), top(20));
        column.addView(sectionLabel("实时状态"), top(24));
        column.addView(buildThermal(), top(10));
        column.addView(sectionLabel("准备工作"), top(24));
        column.addView(buildChecklist(), top(10));
        column.addView(sectionLabel("监控"), top(24));
        column.addView(buildActions(), top(10));
        column.addView(sectionLabel("游戏白名单"), top(24));
        column.addView(buildWhitelist(), top(10));
        column.addView(sectionLabel("诊断日志"), top(24));
        column.addView(buildLogCard(), top(10));
        column.addView(buildInfo(), top(20));

        return column;
    }

    /** 关于页内容（独立页面）。 */
    private LinearLayout buildAboutPage() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(22), dp(20), dp(28));

        // 页头：图标 + 名称
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout logo = new FrameLayout(context);
        GradientDrawable logoBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT, BLUE});
        logoBg.setCornerRadius(dp(14));
        logo.setBackground(logoBg);
        logo.addView(new XIcon(context, XIcon.ZAP, DEEP),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        head.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(0, WC, 1f);
        titlesLp.leftMargin = dp(14);
        head.addView(titles, titlesLp);
        titles.addView(text("X模式助手", 19, TEXT_MAIN, true));
        TextView sub = text("关于本应用", 11.5f, TEXT_SUB, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(WC, WC);
        subLp.topMargin = dp(3);
        titles.addView(sub, subLp);
        column.addView(head);

        column.addView(sectionLabel("应用信息"), top(26));
        column.addView(buildAbout(), top(10));

        // 可点击入口：打开仓库 / 查看许可证
        LinearLayout links = new LinearLayout(context);
        links.setOrientation(LinearLayout.VERTICAL);
        links.setPadding(dp(4), dp(4), dp(4), dp(4));
        links.setBackground(cardBg(22, CARD_ALT, STROKE));
        links.addView(navRow("源码仓库", "github.com/" + AUTHOR + "/XModeEnabler",
                new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onOpenRepo();
                        }
                    }
                }));
        links.addView(hDivider());
        links.addView(navRow("开源许可证", "MIT · Apache-2.0",
                new Runnable() {
                    @Override
                    public void run() {
                        selectPage(2);
                    }
                }));
        column.addView(links, top(10));

        column.addView(sectionLabel("使用步骤"), top(20));
        column.addView(infoCard("三步搞定", "① 授权 Shizuku（一次即可，重启手机后无需重授）\n"
                + "② 进游戏助手的隐藏页，只打开第一项「自动化测试」\n"
                + "③ 忽略电池优化，再回首页点「启动常驻监控」\n\n"
                + "之后正常进游戏即可：助手切到 X 模式后本应用会自动维持。"), top(10));

        column.addView(sectionLabel("风险提示"), top(20));
        column.addView(infoCard("请自行评估", "X 模式是官方最激进的性能策略，官方形态要求配专用散热背夹。"
                + "第三方散热器制冷不足时，可能出现高温降频、耗电加快与电池损耗。\n\n"
                + "修改系统应用行为可能影响保修；游戏助手升级后类名 / action 若变化，需重新适配。\n\n"
                + "本应用不改动游戏助手安装包，仅以 Shizuku 提供的 shell 身份发送官方指令。"), top(10));

        TextView foot = text("MIT License  ·  仅供个人设备使用", 10.5f, TEXT_SUB, false);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(MP, WC);
        footLp.topMargin = dp(20);
        column.addView(foot, footLp);

        return column;
    }

    /** 底部标签栏：首页 / 关于。 */
    private View buildBottomBar() {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);

        View divider = new View(context);
        divider.setBackgroundColor(0x1AFFFFFF);
        wrap.addView(divider, new LinearLayout.LayoutParams(MP, dp(1)));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF0B1120);
        bar.setPadding(dp(24), dp(5), dp(24), dp(9));

        bar.addView(buildTab(XIcon.ZAP, "首页", 0),
                new LinearLayout.LayoutParams(0, WC, 1f));
        bar.addView(buildTab(XIcon.INFO, "关于", 1),
                new LinearLayout.LayoutParams(0, WC, 1f));

        wrap.addView(bar, new LinearLayout.LayoutParams(MP, WC));
        return wrap;
    }

    private View buildTab(int iconType, String label, final int page) {
        LinearLayout tab = new LinearLayout(context);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(0, dp(7), 0, dp(7));
        tab.setBackground(rippleBg(16, 0x00000000, 0));

        XIcon icon = new XIcon(context, iconType, IDLE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(21), dp(21));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        tab.addView(icon, iconLp);

        TextView name = text(label, 10.5f, IDLE, true);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(MP, WC);
        nameLp.topMargin = dp(4);
        tab.addView(name, nameLp);

        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectPage(page);
            }
        });

        if (page == 0) {
            tabHomeIcon = icon;
            tabHomeLabel = name;
        } else {
            tabAboutIcon = icon;
            tabAboutLabel = name;
        }
        return tab;
    }

    /** 系统返回键：许可证页 → 关于页 → 首页；返回 false 表示交给系统处理（退出）。 */
    public boolean handleBack() {
        if (currentPage == 2) {
            selectPage(1);
            return true;
        }
        if (currentPage == 1) {
            selectPage(0);
            return true;
        }
        return false;
    }

    /** 当前页面编号（0 首页 / 1 关于 / 2 许可证），供宿主在界面重建后恢复。 */
    public int page() {
        return currentPage;
    }

    /** 恢复页面（界面重建后调用，例如旋转屏幕）。 */
    public void setPage(int page) {
        if (page < 0 || page > 2) return;
        selectPage(page);
    }

    /** 切换页面（0 首页 / 1 关于 / 2 许可证）。 */
    private void selectPage(int page) {
        currentPage = page;
        if (homeScroll != null) {
            homeScroll.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        }
        if (aboutScroll != null) {
            aboutScroll.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        }
        if (licenseScroll != null) {
            licenseScroll.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        }
        if (page == 0) {
            if (homeScroll != null) homeScroll.scrollTo(0, 0);
        }
        boolean homeActive = page == 0;
        if (tabHomeIcon != null) {
            tabHomeIcon.setIconColor(homeActive ? ACCENT : IDLE);
            tabHomeLabel.setTextColor(homeActive ? ACCENT : IDLE);
        }
        if (tabAboutIcon != null) {
            tabAboutIcon.setIconColor(homeActive ? IDLE : ACCENT);
            tabAboutLabel.setTextColor(homeActive ? IDLE : ACCENT);
        }
    }

    /** 通用说明卡片：标题 + 正文。 */
    private View infoCard(String title, String body) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(cardBg(22, CARD_ALT, STROKE));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        XIcon info = new XIcon(context, XIcon.INFO, TEXT_SUB);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(dp(13), dp(13));
        infoLp.rightMargin = dp(7);
        head.addView(info, infoLp);
        head.addView(text(title, 12, TEXT_MAIN, true));
        card.addView(head);

        TextView bodyView = text(body, 11.5f, TEXT_SUB, false);
        bodyView.setLineSpacing(dp(4), 1.18f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(MP, WC);
        bodyLp.topMargin = dp(9);
        card.addView(bodyView, bodyLp);

        return card;
    }

    /** 可点击的导航行：左侧标题、右侧摘要 + 箭头。 */
    private View navRow(String key, String value, final Runnable action) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(10), dp(12), dp(10), dp(12));
        line.setBackground(rippleBg(16, 0x00000000, 0));

        line.addView(text(key, 12.5f, TEXT_MAIN, true));
        line.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView val = text(value, 10.5f, TEXT_SUB, false);
        val.setSingleLine(true);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        val.setMaxWidth(dp(190));
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(WC, WC);
        valLp.leftMargin = dp(10);
        valLp.rightMargin = dp(6);
        line.addView(val, valLp);
        line.addView(text("›", 16, ACCENT, true));

        line.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return line;
    }

    /** 许可证页（从关于页进入，底部仍停在「关于」tab）。 */
    private LinearLayout buildLicensePage() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(18), dp(20), dp(28));

        TextView back = text("‹  返回关于", 12.5f, ACCENT, true);
        back.setPadding(0, dp(8), dp(8), dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectPage(1);
            }
        });
        column.addView(back);

        column.addView(text("开源许可证", 22, TEXT_MAIN, true), top(10));
        column.addView(text("本应用与第三方组件", 11.5f, TEXT_SUB, false), top(6));

        column.addView(sectionLabel("本应用"), top(22));
        column.addView(infoCard("MIT License · " + AUTHOR, MIT_TEXT), top(10));

        column.addView(sectionLabel("第三方组件"), top(20));
        column.addView(infoCard("Shizuku API / Provider（dev.rikka.shizuku）",
                "Apache License 2.0\n\n"
                        + "本应用通过 Shizuku 提供的 shell 身份发送系统指令，"
                        + "仅以二进制依赖形式随应用分发，未修改其源码。\n\n"
                        + "许可全文：apache.org/licenses/LICENSE-2.0"), top(10));
        column.addView(infoCard("AndroidX Annotation（androidx.annotation）",
                "Apache License 2.0\n\n仅作为编译期注解引入，运行时不参与逻辑。"), top(10));

        TextView foot = text("以上许可均允许自由使用、修改与再分发，"
                + "但需保留版权与许可声明。", 10.5f, TEXT_SUB, false);
        foot.setGravity(Gravity.CENTER);
        foot.setLineSpacing(dp(3), 1.15f);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(MP, WC);
        footLp.topMargin = dp(18);
        column.addView(foot, footLp);

        return column;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout logo = new FrameLayout(context);
        GradientDrawable logoBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT, BLUE});
        logoBg.setCornerRadius(dp(11));
        logo.setBackground(logoBg);
        logo.addView(new XIcon(context, XIcon.ZAP, DEEP),
                new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        header.addView(logo, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(0, WC, 1f);
        titlesLp.leftMargin = dp(12);
        header.addView(titles, titlesLp);

        titles.addView(text("X模式助手", 17, TEXT_MAIN, true));
        subTitle = text("v1.6.1 · ColorOS 游戏助手", 11, TEXT_SUB, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(WC, WC);
        subLp.topMargin = dp(3);
        titles.addView(subTitle, subLp);

        shizukuPill = text("Shizuku", 10.5f, IDLE, true);
        shizukuPill.setGravity(Gravity.CENTER);
        shizukuPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        shizukuPill.setBackground(pillBg(alpha(IDLE, 0x1F)));
        header.addView(shizukuPill);

        return header;
    }

    private View buildHero() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(cardBg(26, CARD, STROKE));

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView modePill = text("MODE 3", 10, ACCENT, true);
        modePill.setLetterSpacing(0.08f);
        modePill.setGravity(Gravity.CENTER);
        modePill.setPadding(dp(9), dp(4), dp(9), dp(4));
        modePill.setBackground(pillBg(alpha(ACCENT, 0x1F)));
        topRow.addView(modePill);

        topRow.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        topRow.addView(text("事件驱动 · 无 Root", 10.5f, TEXT_SUB, false));
        card.addView(topRow);

        FrameLayout pulseWrap = new FrameLayout(context);
        pulse = new PulseView(context);
        pulseWrap.addView(pulse, new FrameLayout.LayoutParams(MP, MP));
        LinearLayout.LayoutParams pulseLp = new LinearLayout.LayoutParams(dp(132), dp(132));
        pulseLp.gravity = Gravity.CENTER_HORIZONTAL;
        pulseLp.topMargin = dp(10);
        card.addView(pulseWrap, pulseLp);

        heroTitle = text("未启动", 20, TEXT_MAIN, true);
        heroTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(MP, WC);
        titleLp.topMargin = dp(10);
        card.addView(heroTitle, titleLp);

        heroPhase = text("点击下方按钮启动常驻监控", 11.5f, TEXT_SUB, false);
        heroPhase.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams phaseLp = new LinearLayout.LayoutParams(MP, WC);
        phaseLp.topMargin = dp(4);
        card.addView(heroPhase, phaseLp);

        View line = new View(context);
        line.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(MP, dp(1));
        lineLp.topMargin = dp(16);
        card.addView(line, lineLp);

        LinearLayout stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setPadding(0, dp(14), 0, 0);

        statFire = addStat(stats, "补投次数");
        stats.addView(vDivider());
        statSkip = addStat(stats, "跳过次数");
        stats.addView(vDivider());
        statMode = addStat(stats, "助手模式");
        card.addView(stats);

        return card;
    }

    private TextView addStat(LinearLayout parent, String label) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);

        TextView value = text("—", 18, TEXT_MAIN, true);
        value.setGravity(Gravity.CENTER);
        cell.addView(value);

        TextView name = text(label, 10.5f, TEXT_SUB, false);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(WC, WC);
        nameLp.topMargin = dp(3);
        cell.addView(name, nameLp);

        parent.addView(cell, new LinearLayout.LayoutParams(0, WC, 1f));
        return value;
    }

    private View vDivider() {
        View v = new View(context);
        v.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(26));
        lp.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        return v;
    }

    private View buildChecklist() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBg(24, CARD, STROKE));

        addRow(card, rowShizuku, XIcon.SHIELD, ACCENT,
                "Shizuku 服务", "指令的执行身份", new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onShizuku();
                        }
                    }
                });
        card.addView(hDivider());
        addRow(card, rowAutoTest, XIcon.SLIDERS, VIOLET,
                "自动化测试开关", "游戏助手隐藏页总闸", new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onAutoTest();
                        }
                    }
                });
        card.addView(hDivider());
        addRow(card, rowBattery, XIcon.BATTERY, AMBER,
                "忽略电池优化", "防止后台被清理", new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onBattery();
                        }
                    }
                });

        return card;
    }

    private void addRow(LinearLayout parent, Row row, int iconType, int tint,
                        String title, String hint, final Runnable action) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(10), dp(11), dp(10), dp(11));
        line.setBackground(rippleBg(18, 0x00000000, 0));

        row.dot = new View(context);
        row.dot.setBackground(dotBg(IDLE));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(10);
        line.addView(row.dot, dotLp);

        FrameLayout tile = new FrameLayout(context);
        GradientDrawable tileBg = new GradientDrawable();
        tileBg.setCornerRadius(dp(12));
        tileBg.setColor(alpha(tint, 0x26));
        tile.setBackground(tileBg);
        tile.addView(new XIcon(context, iconType, tint),
                new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        line.addView(tile, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, WC, 1f);
        textsLp.leftMargin = dp(12);
        textsLp.rightMargin = dp(8);
        line.addView(texts, textsLp);

        texts.addView(text(title, 13.5f, TEXT_MAIN, true));

        TextView hintView = text(hint, 11, TEXT_SUB, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(WC, WC);
        hintLp.topMargin = dp(3);
        texts.addView(hintView, hintLp);

        row.action = text("去授权", 11.5f, ACCENT, true);
        row.action.setGravity(Gravity.CENTER);
        line.addView(row.action);

        View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        };
        line.setOnClickListener(click);
        row.action.setOnClickListener(click);

        parent.addView(line);
    }

    private View hDivider() {
        View v = new View(context);
        v.setBackgroundColor(0x10FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, dp(1));
        lp.leftMargin = dp(63);
        lp.rightMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private View buildActions() {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);

        mainButton = new LinearLayout(context);
        mainButton.setOrientation(LinearLayout.HORIZONTAL);
        mainButton.setGravity(Gravity.CENTER);
        mainButton.setMinimumHeight(dp(56));
        mainButton.setPadding(dp(20), 0, dp(20), 0);

        mainIcon = new XIcon(context, XIcon.PLAY, DEEP);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        iconLp.rightMargin = dp(8);
        mainButton.addView(mainIcon, iconLp);

        mainLabel = text("启动常驻监控", 15, DEEP, true);
        mainButton.addView(mainLabel);

        mainButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onToggleMonitor();
                }
            }
        });
        wrap.addView(mainButton, new LinearLayout.LayoutParams(MP, WC));

        LinearLayout manual = new LinearLayout(context);
        manual.setOrientation(LinearLayout.HORIZONTAL);
        manual.setGravity(Gravity.CENTER);
        manual.setMinimumHeight(dp(50));
        manual.setBackground(rippleBg(26, 0x00FFFFFF, 0x1FFFFFFF));
        manual.setPadding(dp(18), 0, dp(18), 0);

        XIcon zap = new XIcon(context, XIcon.ZAP, TEXT_MAIN);
        LinearLayout.LayoutParams zapLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        zapLp.rightMargin = dp(8);
        manual.addView(zap, zapLp);
        manual.addView(text("手动投递一次 X 模式", 13.5f, TEXT_MAIN, true));

        manual.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onFireOnce();
                }
            }
        });
        wrap.addView(manual, top(10));

        return wrap;
    }

    /** 实时状态：电池温度 / CPU 频率 / 热区峰值（数据来自服务侧 shell 采样）。 */
    private View buildThermal() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(cardBg(26, CARD, STROKE));

        LinearLayout stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        statBattery = addStat(stats, "电池温度");
        stats.addView(vDivider());
        statCpu = addStat(stats, "CPU 最高簇");
        stats.addView(vDivider());
        statHot = addStat(stats, "机身温度");
        card.addView(stats);

        return card;
    }

    /** 游戏白名单卡片（内容在 render 时重建）。 */
    private View buildWhitelist() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(cardBg(22, CARD_ALT, STROKE));

        wlBox = new LinearLayout(context);
        wlBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(wlBox);

        return card;
    }

    /** 诊断日志卡片。 */
    private View buildLogCard() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(cardBg(22, CARD_ALT, STROKE));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        XIcon icon = new XIcon(context, XIcon.INFO, TEXT_SUB);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(13), dp(13));
        iconLp.rightMargin = dp(7);
        head.addView(icon, iconLp);
        head.addView(text("最近事件", 12, TEXT_MAIN, true));
        head.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));

        // 记录开关（v1.6）
        logToggle = text("记录开", 10.5f, ACCENT, true);
        logToggle.setPadding(dp(8), dp(4), dp(8), dp(4));
        logToggle.setBackground(pillBg(alpha(ACCENT, 0x1E)));
        logToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onToggleLog();
                }
            }
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(WC, WC);
        toggleLp.rightMargin = dp(6);
        head.addView(logToggle, toggleLp);

        // 导出（v1.6）
        TextView export = text("导出", 10.5f, TEXT_SUB, true);
        export.setPadding(dp(8), dp(4), dp(8), dp(4));
        export.setBackground(pillBg(0x14FFFFFF));
        export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onExportLog();
                }
            }
        });
        LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(WC, WC);
        exportLp.rightMargin = dp(6);
        head.addView(export, exportLp);

        TextView clear = text("清空", 11.5f, TEXT_SUB, true);
        clear.setPadding(dp(10), dp(5), dp(10), dp(5));
        clear.setBackground(pillBg(0x14FFFFFF));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onLogClear();
                }
            }
        });
        head.addView(clear);
        card.addView(head);

        logView = text("暂无事件", 10.5f, TEXT_SUB, false);
        logView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        logView.setLineSpacing(dp(2), 1.12f);
        logView.setMaxLines(18);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(MP, WC);
        logLp.topMargin = dp(9);
        card.addView(logView, logLp);

        return card;
    }

    /** 关于卡片：作者 / 版本 / 许可 / 源码。 */
    private View buildAbout() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(cardBg(22, CARD_ALT, STROKE));

        aboutVersion = addKv(card, "版本", "v1.6.5");
        addKv(card, "作者", AUTHOR);
        addKv(card, "许可", "MIT License");
        addKv(card, "包名", "com.operit.xmode");
        aboutGamesVersion = addKv(card, "适配", "游戏助手 —");
        return card;
    }

    /** 一行「标签 + 值」，返回值对应的 TextView。 */
    private TextView addKv(LinearLayout parent, String key, String value) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(4), 0, dp(4));

        line.addView(text(key, 11.5f, TEXT_SUB, false));
        line.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView val = text(value, 11.5f, TEXT_MAIN, false);
        val.setGravity(Gravity.END);
        val.setMaxLines(1);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(val);

        parent.addView(line);
        return val;
    }

    /** 白名单里的一行（右侧「移除」/「加入」）。 */
    private View wlRow(final String pkg, String actionText, final int color, final boolean add) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(6), dp(7), dp(6), dp(7));
        line.setBackground(rippleBg(14, 0x00000000, 0));

        TextView name = text(pkg, 11.5f, TEXT_MAIN, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(0, WC, 1f));

        TextView btn = text(actionText, 11, color, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(9), dp(4), dp(9), dp(4));
        btn.setBackground(pillBg(alpha(color, 0x1E)));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(WC, WC);
        btnLp.leftMargin = dp(8);
        line.addView(btn, btnLp);

        View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener == null) return;
                if (add) {
                    listener.onWhitelistAdd(pkg);
                } else {
                    listener.onWhitelistRemove(pkg);
                }
            }
        };
        line.setOnClickListener(click);
        btn.setOnClickListener(click);
        return line;
    }

    private View buildInfo() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(cardBg(22, CARD_ALT, STROKE));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        XIcon info = new XIcon(context, XIcon.INFO, TEXT_SUB);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(dp(13), dp(13));
        infoLp.rightMargin = dp(7);
        head.addView(info, infoLp);
        head.addView(text("工作原理", 12, TEXT_MAIN, true));
        card.addView(head);

        TextView body = text("借助 Shizuku 以 shell 身份向游戏助手的 SpecialFeatureService 投递 mode=3。"
                + "仅当游戏在前台时受理；单次投递约维持 44 秒，因此改为监听助手日志，"
                + "只在模式被改回时才补投一枪，另加 90 秒安全网兜底。"
                + "\n\n隐藏页里只打开第一项「自动化测试」，其余开关请勿改动。", 11.5f, TEXT_SUB, false);
        body.setLineSpacing(dp(4), 1.18f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(MP, WC);
        bodyLp.topMargin = dp(9);
        card.addView(body, bodyLp);

        return card;
    }

    private TextView sectionLabel(String value) {
        TextView label = text(value, 12, TEXT_SUB, true);
        label.setLetterSpacing(0.06f);
        return label;
    }

    // ================================================================ 渲染细节

    private void setRow(Row row, int color, String actionText, boolean ready) {
        row.dot.setBackground(dotBg(color));
        row.action.setText(actionText);
        row.action.setTextColor(ready ? GREEN : color);
        row.action.setBackground(ready
                ? pillBg(alpha(GREEN, 0x1A))
                : rippleBg(14, alpha(color, 0x24), 0));
        row.action.setPadding(dp(10), dp(5), dp(10), dp(5));
    }

    private void styleMainButton(boolean monitoring) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(28));
        mask.setColor(0xFFFFFFFF);

        if (monitoring) {
            GradientDrawable content = new GradientDrawable();
            content.setCornerRadius(dp(28));
            content.setColor(0xFF2A1520);
            content.setStroke(dp(1), 0x66FF6B6B);
            mainButton.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x33FF6B6B), content, mask));
            mainLabel.setText("停止常驻监控");
            mainLabel.setTextColor(RED);
            mainIcon.setIconColor(RED);
            mainIcon.setIconType(XIcon.STOP);
        } else {
            GradientDrawable content = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{ACCENT, BLUE});
            content.setCornerRadius(dp(28));
            mainButton.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x55FFFFFF), content, mask));
            mainLabel.setText("启动常驻监控");
            mainLabel.setTextColor(DEEP);
            mainIcon.setIconColor(DEEP);
            mainIcon.setIconType(XIcon.PLAY);
        }
    }

    // ================================================================ 工具方法

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }
        return view;
    }

    private LinearLayout.LayoutParams top(int dpValue) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC);
        lp.topMargin = dp(dpValue);
        return lp;
    }

    private GradientDrawable cardBg(float radius, int color, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(radius));
        drawable.setColor(color);
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private GradientDrawable dotBg(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable pillBg(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(20));
        drawable.setColor(color);
        return drawable;
    }

    private RippleDrawable rippleBg(float radius, int color, int strokeColor) {
        GradientDrawable content = cardBg(radius, color, strokeColor);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(radius));
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    private int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}