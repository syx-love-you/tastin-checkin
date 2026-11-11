package com.tastin.checkin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * 签到执行的前台服务。
 *
 * 为什么需要它：BroadcastReceiver 的 goAsync() 只有约 10 秒窗口，
 * 而一次签到要串行走「验 token → 补手机号 → 提交签到 → 查积分」，
 * 网络慢的时候很容易被系统砍断。前台服务不受这个窗口限制，
 * 能稳妥跑完整个流程，完成后立即停止，不做任何常驻。
 *
 * 通知是前台服务的必需条件（系统要求），同时也让用户看得见正在签到。
 */
public class CheckinService extends Service {

    private static final String TAG = "TastinService";
    private static final String CHANNEL_ID = "tastin_running";
    private static final int NOTIFY_ID = 2000;

    public static final String EXTRA_TRIGGER = "trigger";
    public static final String ACTION_STOP = "com.tastin.checkin.STOP_SERVICE";

    private PowerManager.WakeLock lock;

    /**
     * 启动签到服务。返回 false 表示服务没能起来（部分 ROM 禁止后台启动），
     * 调用方应退回广播内执行。
     */
    public static boolean start(Context ctx, String trigger) {
        Intent i = new Intent(ctx, CheckinService.class).putExtra(EXTRA_TRIGGER, trigger);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "前台服务启动被拒，退回广播内执行", t);
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须第一时间 startForeground：用 startForegroundService 启动后，
        // 系统要求 5 秒内调用，否则直接 ANR/崩溃。
        final String trigger = intent == null || intent.getStringExtra(EXTRA_TRIGGER) == null
                ? "定时" : intent.getStringExtra(EXTRA_TRIGGER);
        startForegroundCompat(trigger);

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tastin:service");
            lock.setReferenceCounted(false);
            lock.acquire(90_000L);
        }

        new Thread(() -> {
            try {
                CheckinRunner.Outcome out = CheckinRunner.run(this, trigger);
                Log.i(TAG, out.message);
                if (out.kind == CheckinRunner.FailKind.BUSY) {
                    return;
                } else if (!out.success && out.kind == CheckinRunner.FailKind.TOKEN) {
                    Scheduler.suspendRetriesForToday(this);
                    AlarmReceiver.notifyTokenExpired(this, out.message);
                    // token 失效重试无意义
                } else if (!out.success) {
                    Scheduler.scheduleRetry(this);
                    AlarmReceiver.notify(this, false, out.message);
                } else {
                    AlarmReceiver.notify(this, true, out.message);
                }
            } catch (Throwable t) {
                Log.e(TAG, "签到服务异常", t);
                try {
                    new Config(this).recordFail(trigger + "｜程序异常：" + t);
                } catch (Throwable ignored) {}
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
                stopForegroundCompat();
                stopSelf();
            }
        }).start();

        return START_NOT_STICKY;    // 不常驻；下一次靠闹钟/心跳再拉起
    }

    private void startForegroundCompat(String trigger) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "正在签到", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("塔斯汀签到")
                .setContentText(trigger + "签到执行中…")
                .setOngoing(true)
                .setContentIntent(tap);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFY_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFY_ID, b.build());
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground 失败", t);
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
