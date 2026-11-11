package com.tastin.checkin;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * 兜底心跳：每 15 分钟检查「今天该签但没签 → 立刻补」。
 *
 * JobScheduler 的任务登记在 system_server 中，进程被划掉后仍可由系统拉起。
 */
public class HeartbeatJob extends JobService {

    private static final String TAG = "TastinHeartbeat";
    public static final int JOB_ID = 3001;
    /** 15 分钟：Android 允许的最小周期 */
    private static final long INTERVAL_MS = 15L * 60L * 1000L;

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                Context app = getApplicationContext();
                if (Scheduler.shouldRunRecovery(app)) {
                    Log.i(TAG, "心跳检测到漏签，开始补签");
                    // 心跳能运行说明进程已被系统拉起，顺手把下一次每日闹钟重新登记。
                    Scheduler.scheduleDaily(app, false);
                    CheckinRunner.Outcome out = CheckinRunner.run(app, "补签");
                    if (out.kind == CheckinRunner.FailKind.BUSY) {
                        Log.i(TAG, "已有签到在执行，心跳本次跳过");
                    } else if (!out.success && out.kind == CheckinRunner.FailKind.TOKEN) {
                        Scheduler.suspendRetriesForToday(app);
                        AlarmReceiver.notifyTokenExpired(app, out.message);
                    } else if (!out.success) {
                        boolean retry = Scheduler.scheduleRetry(app);
                        AlarmReceiver.notify(app, false, out.message
                                + (retry ? "\n\n稍后自动重试" : "\n\n今日重试次数已用完，明天继续"));
                    } else {
                        AlarmReceiver.notify(app, true, out.message);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "心跳任务异常", t);
            } finally {
                // 周期任务由系统自动重排，这里传 false；传 true 会立刻再跑一次
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;    // 被系统打断（如内存压力）时要求重新排队
    }

    /** 排/重排心跳任务。周期任务会随重启和升级丢失，所以开机和应用启动时都要调。 */
    public static void ensureScheduled(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (isScheduled(ctx)) return;

            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(ctx, HeartbeatJob.class))
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build();
            js.schedule(job);
            Log.i(TAG, "心跳任务已排，周期 " + (INTERVAL_MS / 60000) + " 分钟");
        } catch (Throwable t) {
            Log.e(TAG, "排心跳任务失败", t);
        }
    }

    public static boolean isScheduled(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return false;
            for (JobInfo ji : js.getAllPendingJobs()) {
                if (ji.getId() == JOB_ID) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 用户关掉定时/清空 token 时一并撤掉，避免无意义唤醒 */
    public static void cancel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Throwable ignored) {}
    }
}
