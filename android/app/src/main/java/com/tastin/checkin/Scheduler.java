package com.tastin.checkin;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 每日定时 + 失败重试。
 *
 * Android 12+ 用精确闹钟需要 SCHEDULE_EXACT_ALARM 权限被真正授予
 * （默认是关闭的，要用户去系统设置里开）。没权限时不能调
 * setExactAndAllowWhileIdle，否则直接抛异常、闹钟根本排不上。
 * 所以这里先检查权限，没开就退回不精确的 setAndAllowWhileIdle。
 */
public final class Scheduler {

    private static final String TAG = "TastinScheduler";

    private static final int REQ_DAILY = 1001;
    private static final int REQ_RETRY = 1002;
    private static final int REQ_CATCHUP = 1003;

    /** 首次重试延迟，之后按指数退避：1h → 2h → 4h */
    private static final long RETRY_BASE_MS = 60L * 60L * 1000L;
    /** 当天最多重试几次 */
    private static final int MAX_RETRIES = 3;

    private Scheduler() {}

    /** 是否拥有精确闹钟权限（Android 12+ 需要，低版本恒为 true） */
    public static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    /** 跳转到系统的「闹钟和提醒」授权页 */
    public static void openExactAlarmSettings(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            // 个别 ROM 没有这个页面，退到应用详情页
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    private static PendingIntent pi(Context ctx, int req, String action) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, req, i, flags);
    }

    /**
     * 排一个闹钟。有精确权限就用精确的，没有就退回容忍延迟的方式。
     * 返回是否成功（不抛异常，全部兜住）。
     */
    private static boolean arm(Context ctx, long at, PendingIntent p) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;

        // 先试精确
        if (canScheduleExact(ctx)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, at, p);
                }
                return true;
            } catch (SecurityException e) {
                Log.w(TAG, "精确闹钟被拒，退回非精确", e);
            } catch (Exception e) {
                Log.w(TAG, "排精确闹钟失败，退回非精确", e);
            }
        }

        // 没有精确权限：用不带 AlarmClock 图标、Doze 下也能唤醒的方式
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, p);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "排闹钟彻底失败", e);
            return false;
        }
    }

    /**
     * 排下一次每日签到。
     *
     * clearRetry=true 表示"用户主动重设"，要清掉待重试；
     * 应用回到前台、开机恢复这类场景必须传 false，否则会把
     * 已经排好的 1 小时后重试冲掉，失败重试就形同虚设。
     */
    public static boolean scheduleDaily(Context ctx, boolean clearRetry) {
        Config cfg = new Config(ctx);
        if (!cfg.configured()) return false;

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;

        // 闹钟之外的兜底：进程被划掉后靠系统心跳把签到捞回来
        HeartbeatJob.ensureScheduled(ctx);

        String today = TastinApi.today();
        if (!today.equals(cfg.retryDate())) {
            try {
                am.cancel(pi(ctx, REQ_RETRY, AlarmReceiver.ACTION_RETRY));
            } catch (Exception ignored) {}
            cfg.setRetryCount(0);
            cfg.setRetryDate("");
            cfg.setRetryAt(0L);
        }

        if (clearRetry) {
            try {
                am.cancel(pi(ctx, REQ_RETRY, AlarmReceiver.ACTION_RETRY));
            } catch (Exception ignored) {}
            cfg.setRetryCount(0);
            cfg.setRetryDate("");
            cfg.setRetryAt(0L);
            // 用户重新保存时间后，这是一个新的待执行定时任务。
            cfg.setScheduledDate("");
        }

        long at = nextDailyAt(cfg.hour(), cfg.minute());
        boolean ok = arm(ctx, at, pi(ctx, REQ_DAILY, AlarmReceiver.ACTION_RUN));
        Log.i(TAG, "每日闹钟 " + (ok ? "已排 " : "排失败 ") + at + " exact=" + canScheduleExact(ctx));
        return ok;
    }

    /** 用户保存设置时用：重设并清掉旧的重试 */
    public static boolean scheduleDaily(Context ctx) {
        return scheduleDaily(ctx, true);
    }

    /**
     * 安排一次重试。指数退避：第 n 次失败后等 2^(n-1) 小时
     * （1h → 2h → 4h），避免网络持续异常时反复无效请求。
     */
    public static boolean scheduleRetry(Context ctx) {
        Config cfg = new Config(ctx);
        String today = TastinApi.today();

        if (!today.equals(cfg.retryDate())) {
            cfg.setRetryDate(today);
            cfg.setRetryCount(0);
        }
        int n = cfg.retryCount();
        if (n >= MAX_RETRIES) {
            cfg.setRetryAt(0L);
            return false;
        }

        cfg.setRetryCount(n + 1);
        cfg.setRetryDate(today);
        long delay = RETRY_BASE_MS << n;    // 1h, 2h, 4h
        long at = System.currentTimeMillis() + delay;
        cfg.setRetryAt(at);
        Log.i(TAG, "第 " + (n + 1) + " 次重试排在 " + (delay / 3600_000L) + " 小时后");
        boolean ok = arm(ctx, at, pi(ctx, REQ_RETRY, AlarmReceiver.ACTION_RETRY));
        if (!ok) {
            // 闹钟没排上时清掉等待时间，让 JobScheduler 心跳可以接手。
            cfg.setRetryAt(0L);
        }
        return ok;
    }

    /**
     * token 失效时停止今天的自动重试。
     * 重试不会恢复登录态，还会让心跳每 15 分钟无效唤醒一次。
     */
    public static void suspendRetriesForToday(Context ctx) {
        Config cfg = new Config(ctx);
        cfg.setRetryDate(TastinApi.today());
        cfg.setRetryCount(MAX_RETRIES);
        cfg.setRetryAt(0L);
    }

    /**
     * 心跳和补签共用的判断：今天已过签到点、尚未成功，且当前没有等待中的重试。
     */
    public static boolean shouldRunRecovery(Context ctx) {
        Config cfg = new Config(ctx);
        if (!cfg.configured() || cfg.scheduledToday() || !isTodaySlotPassed(ctx)) return false;

        String today = TastinApi.today();
        if (!today.equals(cfg.retryDate())) return true;
        if (cfg.retryCount() >= MAX_RETRIES) return false;

        long retryAt = cfg.retryAt();
        return retryAt <= 0L || retryAt <= System.currentTimeMillis();
    }

    /**
     * 开机、改时间或权限变化后，如果今天已经错过签到点，立即补排一次。
     */
    public static boolean scheduleCatchupIfNeeded(Context ctx) {
        if (!shouldRunRecovery(ctx)) return false;
        long at = System.currentTimeMillis() + 5_000L;
        return arm(ctx, at, pi(ctx, REQ_CATCHUP, AlarmReceiver.ACTION_CATCHUP));
    }

    /** 下一次每日签到的时刻；今天的已过就排明天 */
    private static long nextDailyAt(int hour, int minute) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        // 留 30 秒余量，避免恰好卡在整分钟那一刻被系统判定为"已过"。
        // 注意要用加上余量之后的时间来比较：否则在 07:00:00~07:00:30
        // 之间打开 App 会把今天待触发的闹钟当成"已过"，改成明天。
        long t = c.getTimeInMillis() + 30_000L;
        if (t <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_MONTH, 1);
            t = c.getTimeInMillis() + 30_000L;
        }
        return t;
    }

    public static long nextDailyAt(Context ctx) {
        Config cfg = new Config(ctx);
        return nextDailyAt(cfg.hour(), cfg.minute());
    }

    /**
     * 今天的定时时刻是否已经过了（含 30 秒余量）。
     * 用于补跑判断：已过说明「今天这一轮要么跑过了要么被 force stop 吞了」。
     */
    public static boolean isTodaySlotPassed(Context ctx) {
        Config cfg = new Config(ctx);
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.set(Calendar.HOUR_OF_DAY, cfg.hour());
        c.set(Calendar.MINUTE, cfg.minute());
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() + 30_000L <= System.currentTimeMillis();
    }

    /** 查询每日闹钟是否还登记在系统中（用于后台诊断，不做虚假承诺） */
    public static boolean isDailyAlarmRegistered(Context ctx) {
        return hasPendingRunAlarm(ctx);
    }

    /** 通过 PendingIntent 是否已创建来反推闹钟是否登记在系统中 */
    private static boolean hasPendingRunAlarm(Context ctx) {
        try {
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent p = PendingIntent.getBroadcast(ctx, REQ_DAILY,
                    new Intent(ctx, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_RUN),
                    flags);
            return p != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(pi(ctx, REQ_DAILY, AlarmReceiver.ACTION_RUN));
            am.cancel(pi(ctx, REQ_RETRY, AlarmReceiver.ACTION_RETRY));
            am.cancel(pi(ctx, REQ_CATCHUP, AlarmReceiver.ACTION_CATCHUP));
        } catch (Exception ignored) {}
    }

    /** 重启 / 升级后恢复定时。重启会把所有闹钟清空，重试也一并丢了，所以只需重排每日 */
    public static void restore(Context ctx) {
        Config cfg = new Config(ctx);
        if (cfg.configured()) {
            scheduleDaily(ctx, false);
            scheduleCatchupIfNeeded(ctx);
        }
    }
}
