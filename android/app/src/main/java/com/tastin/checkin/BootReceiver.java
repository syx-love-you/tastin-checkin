package com.tastin.checkin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 系统级事件后恢复定时任务。
 *
 * - 开机 / 应用升级：闹钟和周期性任务都会被清空，必须重排
 * - 系统时间被修改 / 时区切换：已排的闹钟是按绝对时刻算的，会错位，必须重算
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case "android.intent.action.QUICKBOOT_POWERON":
                Scheduler.restore(context.getApplicationContext());
                HeartbeatJob.ensureScheduled(context.getApplicationContext());
                break;

            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
                // 签到时间按"本地钟点"定义，时区或系统时钟变了就要重算
                Scheduler.restore(context.getApplicationContext());
                break;

            case "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED":
                // 用户在系统设置里刚授予精确闹钟权限，立刻用精确闹钟重排
                Scheduler.restore(context.getApplicationContext());
                break;

            default:
                break;
        }
    }
}
