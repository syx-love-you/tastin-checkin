package com.tastin.checkin;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 后台运行权限。
 *
 * 签到是在后台自动跑的，手机一旦省电就容易把它掐掉。
 * 这里不向用户解释底层机制，只负责检测"还差什么"和"把用户送到对应开关"。
 */
public final class BackgroundGuard {

    private BackgroundGuard() {}

    /** 通知权限只影响结果提醒，不影响签到本身 */
    public static boolean notificationsEnabled(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 是否已允许后台不受省电限制 */
    public static boolean ignoringBatteryOptimizations(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    /** 打开系统的「不受省电限制」授权弹窗 */
    public static boolean requestIgnoreBatteryOptimizations(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (ctx.getPackageManager().resolveActivity(i, 0) == null) return false;
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 打开本应用的系统详情页（电池/省电开关通常在这里面） */
    public static boolean openAppDetails(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String[][] AUTO_START_TARGETS = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
    };

    public static boolean canOpenAutoStart(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String[] t : AUTO_START_TARGETS) {
            Intent i = new Intent();
            i.setClassName(t[0], t[1]);
            if (pm.resolveActivity(i, 0) != null) return true;
        }
        return false;
    }

    /** 打开自启动管理页；返回 false 表示这个机型没有统一入口 */
    public static boolean openAutoStartSettings(Context ctx) {
        for (String[] t : AUTO_START_TARGETS) {
            try {
                Intent i = new Intent();
                i.setClassName(t[0], t[1]);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (ctx.getPackageManager().resolveActivity(i, 0) == null) continue;
                ctx.startActivity(i);
                return true;
            } catch (Exception ignored) {
                // 换下一个
            }
        }
        return false;
    }

    /** 可检测的后台权限缺口；自启动开关无法通过 AOSP API 读取 */
    public static int missingCount(Context ctx) {
        int n = 0;
        if (!Scheduler.canScheduleExact(ctx)) n++;
        if (!ignoringBatteryOptimizations(ctx)) n++;
        if (!notificationsEnabled(ctx)) n++;
        return n;
    }
}
