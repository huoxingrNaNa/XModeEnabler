package com.operit.xmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 通知栏快捷操作的接收器（v1.3）。
 *
 * <p>只处理两个动作：
 * <ul>
 *   <li>{@link #ACTION_FIRE}：子线程调 {@link MonitorService#fire()} 投递一次 mode=3；</li>
 *   <li>{@link #ACTION_STOP}：停止常驻监控。</li>
 * </ul>
 * 不声明 exported，仅本应用内的 PendingIntent 能触发。
 */
public class NotifyActionReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "com.operit.xmode.action.FIRE";
    public static final String ACTION_STOP = "com.operit.xmode.action.STOP";

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        Log.i(MonitorService.TAG, "notify action " + action);
        if (ACTION_FIRE.equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean ok = MonitorService.fire();
                    MonitorService.logEvent(ok ? "通知按钮：已投递" : "通知按钮：投递失败");
                }
            }, "notify-fire").start();
        } else if (ACTION_STOP.equals(action)) {
            try {
                context.stopService(new Intent(context, MonitorService.class));
            } catch (Throwable t) {
                Log.w(MonitorService.TAG, "notify stop failed", t);
            }
        }
    }
}
