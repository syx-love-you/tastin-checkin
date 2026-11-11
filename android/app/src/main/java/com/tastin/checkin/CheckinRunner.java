package com.tastin.checkin;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** 签到流程编排，与 UI 解耦，供 Worker 和前台页面共用。 */
public final class CheckinRunner {

    private static final String TAG = "TastinCheckin";
    private static final long TOTAL_TIMEOUT_MS = 25_000L;
    private static final ReentrantLock RUN_LOCK = new ReentrantLock();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** 失败原因分类，决定要不要打扰用户 */
    public enum FailKind {
        NONE,        // 成功
        TOKEN,       // token 失效——必须提示用户重新抓取
        NETWORK,     // 网络问题——自动重试即可，不打扰
        OTHER,       // 其他业务错误
        BUSY         // 已有签到正在执行
    }

    public static final class Outcome {
        public final boolean success;
        public final String message;
        public final FailKind kind;

        Outcome(boolean success, String message, FailKind kind) {
            this.success = success;
            this.message = message;
            this.kind = kind;
        }

        Outcome(boolean success, String message) {
            this(success, message, success ? FailKind.NONE : FailKind.OTHER);
        }
    }

    public interface ProgressListener {
        void onProgress(String message);
    }

    private CheckinRunner() {}

    public static boolean isRunning() {
        return RUNNING.get();
    }

    private static void progress(ProgressListener listener, String message) {
        if (listener != null) listener.onProgress(message);
    }

    /**
     * 失败时同时给出两份文案：
     * userMsg 用于通知栏/Toast，简短；logMsg 写进日志，带步骤和服务端原文，便于事后排查。
     */
    private static Outcome fail(Config cfg, String userMsg, String logMsg, FailKind kind) {
        cfg.recordFail(logMsg);
        return new Outcome(false, userMsg, kind);
    }

    /** 服务端偶尔会吐整页 HTML（被 WAF 拦），日志里只留开头 */
    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace("\n", " ").replace("\r", " ").trim();
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }

    /** 手动触发（界面上点「立即签到」） */
    public static Outcome run(Context ctx) {
        return run(ctx, "", null);
    }

    public static Outcome run(Context ctx, String trigger) {
        return run(ctx, trigger, null);
    }

    /**
     * 同一时间只允许一次签到。重复触发立即返回 BUSY，不排队等待，
     * 避免用户看到长时间无变化的“签到中”。
     */
    public static Outcome run(Context ctx, String trigger, ProgressListener listener) {
        if (!RUN_LOCK.tryLock()) {
            return new Outcome(false, "签到正在进行，请稍候", FailKind.BUSY);
        }
        RUNNING.set(true);
        try {
            return runInternal(ctx, trigger, listener);
        } finally {
            RUNNING.set(false);
            RUN_LOCK.unlock();
        }
    }

    private static Outcome runInternal(Context ctx, String trigger, ProgressListener listener) {
        long deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS;
        Config cfg = new Config(ctx);
        String tag = trigger == null || trigger.isEmpty() ? "" : trigger + "｜";
        if (!cfg.configured()) {
            return fail(cfg, "还没配置 token，请先扫码获取",
                    tag + "未配置 token，本次没有执行", FailKind.TOKEN);
        }
        TastinApi api = cfg.api().withDeadline(deadline);

        // 先验 token，才能把「登录态问题」和「业务问题」分开
        progress(listener, "正在验证登录状态…");
        String state = api.checkToken();
        if ("network".equals(state)) {
            return fail(cfg, "网络不通，连不上塔斯汀服务器",
                    tag + "连不上塔斯汀服务器（无网络，或被风控拦截），本次没签到", FailKind.NETWORK);
        }
        if ("expired".equals(state)) {
            return fail(cfg, "token 已失效，需要重新扫码获取",
                    tag + "token 已失效，登录不进去，需要重新扫码", FailKind.TOKEN);
        }

        // 补齐手机号（接口要这个字段，拿到后缓存）
        if (cfg.phone().isEmpty()) {
            progress(listener, "正在获取账号信息…");
            String p = api.fetchPhone();
            if (p != null && !p.isEmpty()) cfg.setPhone(p);
        }

        // 优先用缓存的活动 ID；失效才重新探测，避免每天白扫 30 个 ID
        int activityId = cfg.activityId();
        progress(listener, "正在提交签到…");
        String[] r = activityId > 0 ? api.sign(activityId) : null;

        if (r == null || "failed".equals(r[0])) {
            String why = r == null
                    ? "还没有缓存过活动 ID"
                    : "活动 " + activityId + " 返回「" + brief(r[1]) + "」";

            progress(listener, "正在查找当前签到活动…");
            int found = api.discoverActivityId(74);
            if (found <= 0) {
                return fail(cfg, "没找到正在进行的签到活动",
                        tag + "找不到签到活动：" + why
                                + "；重新探测 74–103 也全部无效（活动可能已结束）", FailKind.OTHER);
            }
            cfg.setActivityId(found);
            progress(listener, "正在提交签到…");
            r = api.sign(found);
        }

        String status = r[0];
        String msg = brief(r[1]);

        if ("ok".equals(status)) {
            progress(listener, "正在读取积分和奖励…");
            int point = api.getPoint();
            String full = msg + (point >= 0 ? "，当前 " + point + " 积分" : "");
            cfg.recordRun(full, tag + full);
            if (isScheduledTrigger(trigger) || Scheduler.isTodaySlotPassed(ctx)) {
                cfg.setScheduledDate(TastinApi.today());
            }
            return new Outcome(true, full);
        }

        if ("network".equals(status)) {
            return fail(cfg, "网络错误，签到没完成",
                    tag + "提交签到时网络断了（" + msg + "），本次没签到", FailKind.NETWORK);
        }
        // 签到接口返回登录态相关的错误，归到 token 类，提示用户重新扫码
        if (msg.contains("登录") || msg.contains("token") || msg.contains("失效")) {
            return fail(cfg, msg, tag + "签到被拒：「" + msg + "」，登录状态有问题",
                    FailKind.TOKEN);
        }
        return fail(cfg, msg, tag + "签到被服务器拒绝：「" + msg + "」", FailKind.OTHER);
    }

    private static boolean isScheduledTrigger(String trigger) {
        return "定时".equals(trigger) || "补签".equals(trigger) || "重试".equals(trigger);
    }

    /** 仅用于 UI 展示的活动状态摘要 */
    public static String describe(Context ctx) {
        Config cfg = new Config(ctx);
        if (!cfg.configured()) return "未配置";
        TastinApi api = cfg.api();
        int aid = api.discoverActivityId(74);
        if (aid <= 0) return "未找到活动";
        JSONObject info = api.signInfo(aid);
        if (info == null) return "查询失败";
        JSONObject act = info.optJSONObject("activityInfo");
        JSONObject member = info.optJSONObject("signMemberInfo");
        StringBuilder sb = new StringBuilder();
        if (act != null) {
            sb.append(act.optString("name", "未知活动"));
        }
        if (member != null) {
            sb.append("\n漏签 ").append(member.optInt("missDays", 0)).append(" 天")
              .append("　补签卡 ").append(member.optInt("reSignCardNum", 0)).append(" 张");
        }
        return sb.toString();
    }
}
