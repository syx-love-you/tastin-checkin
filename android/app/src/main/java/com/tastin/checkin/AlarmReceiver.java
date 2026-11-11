package com.tastin.checkin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 定时触发入口。三种事件：
 * - RUN_NOW：每日签到（也用于界面上的"立即签到"）
 * - RETRY：上次失败后 1 小时的重试
 * - 完成后再排下一次每日签到
 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN = "com.tastin.checkin.RUN_NOW";
    public static final String ACTION_RETRY = "com.tastin.checkin.RETRY";
    public static final String ACTION_CATCHUP = "com.tastin.checkin.CATCHUP";

    private static final String CHANNEL_ID = "tastin_checkin";
    private static final String CHANNEL_ALERT = "tastin_alert";
    private static final int NOTIFY_ID = 2001;
    private static final int NOTIFY_ALERT_ID = 2002;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final String action = intent == null ? ACTION_RUN : intent.getAction();
        final boolean isRetry = ACTION_RETRY.equals(action);
        final boolean isCatchup = ACTION_CATCHUP.equals(action);
        final String trigger = isRetry ? "重试" : (isCatchup ? "补签" : "定时");

        // 先排明天的闹钟，保证链条不因本次失败而中断（幂等，重复排无副作用）
        if (!isRetry) {
            Scheduler.scheduleDaily(app, false);
        }

        // 真正的签到交给短时前台服务：广播接收器只有约 10 秒窗口，
        // 网络慢时会被系统砍断，前台服务不受这个限制。
        boolean started = CheckinService.start(app, trigger);
        if (!started) {
            // 服务被 ROM 拦住（后台启动限制）：退回广播内执行
            final PendingResult pending = goAsync();
            runInlineInternal(app, trigger, isRetry, pending);
        }
    }

    /**
     * 前台服务无法启动时的降级路径：直接在广播里跑完（受 goAsync 时间窗约束）。
     */
    private static void runInlineInternal(Context app, String trigger, boolean isRetry,
                                          PendingResult pending) {
        POOL.execute(() -> {
            try {
                if (!hasNetwork(app)) {
                    new Config(app).recordFail(trigger + "｜手机没连上网络，本次跳过");
                    handleFailure(app, "手机没连上网络，本次跳过", isRetry);
                    return;
                }

                CheckinRunner.Outcome out = CheckinRunner.run(app, trigger);
                Log.i("TastinCheckin", out.message);

                if (out.kind == CheckinRunner.FailKind.BUSY) {
                    return;
                } else if (out.success) {
                    notify(app, true, out.message);
                } else if (out.kind == CheckinRunner.FailKind.TOKEN) {
                    Scheduler.suspendRetriesForToday(app);
                    notifyTokenExpired(app, out.message);
                } else {
                    handleFailure(app, out.message, isRetry);
                }
            } catch (Throwable t) {
                Log.e("TastinCheckin", "签到异常", t);
                new Config(app).recordFail(trigger + "｜程序异常：" + t);
                handleFailure(app, "签到异常：" + t.getMessage(), isRetry);
            } finally {
                pending.finish();
            }
        });
    }

    /** 失败时按指数退避排下一次重试（1h → 2h → 4h）；到上限就不再排，只在通知里说明 */
    private static void handleFailure(Context ctx, String message, boolean wasRetry) {
        boolean scheduled = Scheduler.scheduleRetry(ctx);
        if (scheduled) {
            notify(ctx, false, message + "\n\n稍后自动重试");
        } else {
            notify(ctx, false, message + "\n\n今日重试次数已用完，明天继续");
        }
    }

    /** 没网就不唤醒 CPU 做无用功 */
    private static boolean hasNetwork(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    static void notify(Context ctx, boolean success, String message) {
        post(ctx, success, message, CHANNEL_ID, "签到结果",
                NotificationManager.IMPORTANCE_DEFAULT, NOTIFY_ID);
    }

    /** token 失效：用高优先级渠道，横幅弹出提醒用户必须处理 */
    static void notifyTokenExpired(Context ctx, String message) {
        post(ctx, false, message + "\n\n点这里重新获取 token", CHANNEL_ALERT,
                "需要处理", NotificationManager.IMPORTANCE_HIGH, NOTIFY_ALERT_ID);
    }

    private static void post(Context ctx, boolean success, String message,
                             String channelId, String channelName, int importance, int id) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(channelId, channelName, importance);
            if (importance >= NotificationManager.IMPORTANCE_HIGH) {
                ch.enableVibration(true);
            }
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(ctx, MainActivity.class);
        if (importance >= NotificationManager.IMPORTANCE_HIGH) {
            open.putExtra(MainActivity.EXTRA_SHOW_TOKEN_DIALOG, true);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent tap = PendingIntent.getActivity(ctx, id, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, channelId)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(success ? "塔斯汀签到成功" : "塔斯汀签到需要处理")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(tap)
                .setAutoCancel(true);
        if (importance >= NotificationManager.IMPORTANCE_HIGH) {
            b.setPriority(Notification.PRIORITY_HIGH);
        }
        nm.notify(id, b.build());
    }
}
