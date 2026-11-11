package com.tastin.checkin;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import java.io.File;

/** 调起系统安装器。 */
public final class Installer {

    private Installer() {}

    /** 是否已授予"安装未知应用"权限 */
    public static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ctx.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /** 跳转到系统的"安装未知应用"授权页 */
    public static void requestInstallPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception ignored) {
            // 个别 ROM 没有这个页面，交给用户手动去设置里找
        }
    }

    /** 调起安装器。返回 false 表示没能启动。 */
    public static boolean install(Context ctx, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
