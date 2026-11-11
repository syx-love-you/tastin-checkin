package com.tastin.checkin;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地配置存储。token 和手机号用 Android Keystore（AES-GCM）加密后存放，
 * 密钥由系统 TEE 保管，配置文件被拷走也无法还原。旧版本遗留的 Base64 数据
 * 会自动识别并在下次写入时升级。
 */
public final class Config {

    private static final String PREFS = "tastin_config";
    private static final String K_TOKEN = "token";
    private static final String K_PHONE = "phone";
    private static final String K_SHOP = "shop_id";
    private static final String K_VERSION = "version";
    private static final String K_HOUR = "hour";
    private static final String K_MINUTE = "minute";
    private static final String K_RETRY_COUNT = "retry_count";
    private static final String K_RETRY_DATE = "retry_date";
    private static final String K_RETRY_AT = "retry_at";
    private static final String K_ACTIVITY = "activity_id";
    private static final String K_LAST = "last_run";
    private static final String K_LAST_MSG = "last_msg";
    private static final String K_LOG = "run_log";
    private static final String K_GUIDED = "bg_guided";
    private static final String K_BATTERY_GUIDED = "bg_battery_guided";
    private static final String K_AUTOSTART_GUIDED = "bg_autostart_guided";
    private static final String K_CATCHUP = "catchup_date";
    private static final String K_SCHEDULED = "scheduled_date";

    private final SharedPreferences sp;

    public Config(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String enc(String s) {
        return SecureStore.encrypt(s);
    }

    private static String dec(String s) {
        return SecureStore.decrypt(s);
    }

    public String token() { return dec(sp.getString(K_TOKEN, "")); }
    public void setToken(String v) { sp.edit().putString(K_TOKEN, enc(v)).apply(); }

    public String phone() { return dec(sp.getString(K_PHONE, "")); }
    public void setPhone(String v) { sp.edit().putString(K_PHONE, enc(v)).apply(); }

    public String shopId() { return sp.getString(K_SHOP, "28416"); }
    public void setShopId(String v) { sp.edit().putString(K_SHOP, v).apply(); }

    public String version() { return sp.getString(K_VERSION, "3.78.0"); }
    public void setVersion(String v) { sp.edit().putString(K_VERSION, v).apply(); }

    public int hour() { return sp.getInt(K_HOUR, 7); }
    public void setHour(int v) { sp.edit().putInt(K_HOUR, v).apply(); }

    public int minute() { return sp.getInt(K_MINUTE, 0); }
    public void setMinute(int v) { sp.edit().putInt(K_MINUTE, v).apply(); }

    /** 当天已重试次数，用于限制 1 小时一次的重试 */
    public int retryCount() { return sp.getInt(K_RETRY_COUNT, 0); }
    public void setRetryCount(int v) { sp.edit().putInt(K_RETRY_COUNT, v).apply(); }

    public String retryDate() { return sp.getString(K_RETRY_DATE, ""); }
    public void setRetryDate(String v) { sp.edit().putString(K_RETRY_DATE, v).apply(); }

    /** 下一次允许重试的绝对时间；0 表示当前没有待重试任务 */
    public long retryAt() { return sp.getLong(K_RETRY_AT, 0L); }
    public void setRetryAt(long v) { sp.edit().putLong(K_RETRY_AT, v).apply(); }

    /** 缓存的活动 ID，避免每次运行都探测 */
    public int activityId() { return sp.getInt(K_ACTIVITY, -1); }
    public void setActivityId(int v) { sp.edit().putInt(K_ACTIVITY, v).apply(); }

    public String lastRun() { return sp.getString(K_LAST, ""); }
    public String lastMsg() { return sp.getString(K_LAST_MSG, ""); }

    /**
     * 后台权限引导是否已经走过。
     *
     * 为什么需要这个：国产 ROM 的「无限制」设置不会写进 AOSP 的 doze 白名单，
     * isIgnoringBatteryOptimizations() 在小米上永远返回 false。
     * 只靠那个判断会导致每次打开都弹引导，所以记一个"已经引导过"的标记。
     */
    public boolean bgGuided() { return sp.getBoolean(K_GUIDED, false); }
    public void setBgGuided(boolean v) { sp.edit().putBoolean(K_GUIDED, v).apply(); }

    /** 是否已经带用户处理过电池限制；部分国产 ROM 不会回报真实白名单状态 */
    public boolean batteryGuideAcknowledged() {
        return sp.getBoolean(K_BATTERY_GUIDED, bgGuided());
    }
    public void setBatteryGuideAcknowledged(boolean v) {
        sp.edit().putBoolean(K_BATTERY_GUIDED, v).apply();
    }

    /** 自启动开关无法读取，只能记录用户是否已经完成或跳过这一步 */
    public boolean autoStartAcknowledged() {
        return sp.getBoolean(K_AUTOSTART_GUIDED, bgGuided());
    }
    public void setAutoStartAcknowledged(boolean v) {
        sp.edit().putBoolean(K_AUTOSTART_GUIDED, v).apply();
    }

    /**
     * 今天是否已经做过「漏签补跑」。
     *
     * 为什么需要：从最近任务划掉 App 会被系统 force stop，当天所有闹钟被取消，
     * 定时签到不会运行。补跑在下次打开 App 时触发，这个标记保证一天只补一次，
     * 不会每次进界面都重跑。
     */
    public String catchupDate() { return sp.getString(K_CATCHUP, ""); }
    public void setCatchupDate(String v) { sp.edit().putString(K_CATCHUP, v).apply(); }

    /** 今天是否已经由定时、补签或重试入口完成过一轮签到。 */
    public boolean scheduledToday() {
        return TastinApi.today().equals(sp.getString(K_SCHEDULED, ""));
    }
    public void setScheduledDate(String v) { sp.edit().putString(K_SCHEDULED, v).apply(); }

    /** 保留多少天的日志 */
    public static final int LOG_KEEP_DAYS = 30;
    /** 硬上限，防止一天内被疯狂触发把存储撑爆 */
    private static final int LOG_MAX_LINES = 300;

    /** 记一条失败日志（只写日志，不更新"上次运行成功"状态） */
    public void recordFail(String msg) {
        appendLog("✗ " + msg);
    }

    /** 成功：状态栏用 msg，日志可用 logMsg 带上触发来源 */
    public void recordRun(String msg) {
        recordRun(msg, msg);
    }

    public void recordRun(String msg, String logMsg) {
        sp.edit()
                .putString(K_LAST, TastinApi.today())
                .putString(K_LAST_MSG, msg)
                .putInt(K_RETRY_COUNT, 0)
                .putString(K_RETRY_DATE, "")
                .putLong(K_RETRY_AT, 0L)
                .apply();
        appendLog(logMsg);
    }

    /**
     * 追加一条日志。格式：2026-11-08 07:00|签到成功，连续 3 天，当前 50 积分
     *
     * 保留策略按「天」算而不是按「条」：失败也会记日志，一天可能好几条，
     * 只留 30 条的话实际不到 30 天。这里丢掉早于 30 天前的行，再用行数硬上限兜底。
     */
    private void appendLog(String msg) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        String line = f.format(new java.util.Date()) + "|" + msg.replace("\n", " ");

        String old = sp.getString(K_LOG, "");
        StringBuilder sb = new StringBuilder();
        if (!old.isEmpty()) sb.append(old).append("\n");
        sb.append(line);

        String[] lines = sb.toString().split("\n");

        // 只保留 30 天内的（最新的一定在今天，所以从后往前扫，遇到超期就停）
        String cutoff = dayString(System.currentTimeMillis() - LOG_KEEP_DAYS * 86400_000L);
        int start = lines.length - 1;
        for (int i = lines.length - 1; i >= 0; i--) {
            String day = lines[i].length() >= 10 ? lines[i].substring(0, 10) : "";
            if (day.compareTo(cutoff) < 0) break;
            start = i;
        }
        // 行数硬上限
        if (lines.length - start > LOG_MAX_LINES) start = lines.length - LOG_MAX_LINES;

        StringBuilder cut = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (cut.length() > 0) cut.append("\n");
            cut.append(lines[i]);
        }
        sp.edit().putString(K_LOG, cut.toString()).apply();
    }

    private static String dayString(long millis) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new java.util.Date(millis));
    }

    /** 取日志，最新的在前 */
    public java.util.List<String> logs() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String all = sp.getString(K_LOG, "");
        if (all.isEmpty()) return out;
        String[] lines = all.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) out.add(lines[i]);
        }
        return out;
    }

    public void clearLogs() {
        sp.edit().putString(K_LOG, "").apply();
    }

    public boolean signedToday() {
        return TastinApi.today().equals(lastRun())
                && lastMsg() != null
                && !lastMsg().isEmpty();
    }

    public boolean configured() {
        return !token().isEmpty();
    }

    public TastinApi api() {
        return new TastinApi(token(), phone(), shopId(), version());
    }
}
