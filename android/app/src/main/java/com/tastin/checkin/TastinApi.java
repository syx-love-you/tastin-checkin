package com.tastin.checkin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** 塔斯汀签到 API 客户端。只访问 sss-web.tastientech.com。 */
public final class TastinApi {

    private static final String BASE = "https://sss-web.tastientech.com";
    /**
     * 单次请求超时。后台定时执行时系统只给广播接收器很短的时间窗口，
     * 20 秒会直接把整个窗口耗光，所以压到 8 秒——正常响应通常 1 秒内。
     */
    private static final int TIMEOUT_MS = 8000;

    private final String token;
    private final String phone;
    private final String shopId;
    private final String version;
    private long deadlineMs;

    public TastinApi(String token, String phone, String shopId, String version) {
        this.token = token;
        this.phone = phone == null ? "" : phone;
        this.shopId = shopId == null || shopId.isEmpty() ? "28416" : shopId;
        this.version = version == null || version.isEmpty() ? "3.78.0" : version;
    }

    /** 为一次完整签到设置总截止时间，后续请求会自动使用剩余时间作为超时。 */
    public TastinApi withDeadline(long deadlineMs) {
        this.deadlineMs = deadlineMs;
        return this;
    }

    private int requestTimeoutMs() {
        if (deadlineMs <= 0L) return TIMEOUT_MS;
        long remaining = deadlineMs - System.currentTimeMillis();
        if (remaining <= 0L) return 0;
        return (int) Math.min(TIMEOUT_MS, Math.max(1L, remaining));
    }

    public static final class Result {
        public final int code;
        public final String msg;
        public final JSONObject body;

        Result(int code, String msg, JSONObject body) {
            this.code = code;
            this.msg = msg;
            this.body = body;
        }

        boolean ok() {
            return code == 200;
        }

        JSONObject result() {
            return body == null ? null : body.optJSONObject("result");
        }
    }

    private Result request(String method, String path, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            int timeout = requestTimeoutMs();
            if (timeout <= 0) return new Result(-1, "签到总时长超时", null);
            URL url = new URL(BASE + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("user-token", token);
            conn.setRequestProperty("gray-shop-id", shopId);
            conn.setRequestProperty("channel", "1");
            conn.setRequestProperty("version", version);
            conn.setRequestProperty("xweb_xhr", "1");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/120.0.0.0 Mobile Safari/537.36 "
                            + "MicroMessenger/8.0.50 MiniProgramEnv/Android");

            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int httpCode = conn.getResponseCode();
            InputStream in = httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);
            JSONObject json = null;
            try {
                json = new JSONObject(text);
            } catch (Exception ignored) {
                // WAF 返回 HTML 时解析失败，保留原文用于判断
            }
            String msg = json != null ? json.optString("msg", "") : text;
            int code = json != null ? json.optInt("code", httpCode) : httpCode;
            return new Result(code, msg, json);
        } catch (Exception e) {
            return new Result(-1, "网络错误: " + e.getMessage(), null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /** 200 有效 / "network" / "expired" */
    public String checkToken() {
        Result r = request("POST", "/api/wx/point/myPoint", new JSONObject());
        if (r.ok()) return "ok";
        if (r.code == -1) return "network";
        if (r.code == 403 || r.code == 405 || r.msg.contains("<html") || r.msg.contains("<!doctype"))
            return "network";
        return "expired";
    }

    public int getPoint() {
        Result r = request("POST", "/api/wx/point/myPoint", new JSONObject());
        JSONObject res = r.result();
        return res == null ? -1 : res.optInt("point", -1);
    }

    public String fetchPhone() {
        Result r = request("GET", "/api/intelligence/member/getMemberDetail/sign", null);
        JSONObject res = r.result();
        if (res == null) return null;
        String p = res.optString("phone", "");
        return p.isEmpty() ? res.optString("phoneEncrypted", null) : p;
    }

    /** 探测当前有效活动 ID，失败返回 -1 */
    public int discoverActivityId(int startFrom) {
        try {
            JSONObject banner = new JSONObject();
            banner.put("shopId", Integer.parseInt(shopId));
            banner.put("birthday", "");
            banner.put("gender", 0);
            banner.put("phone", phone);
            Result br = request("POST", "/api/minic/shop/intelligence/banner/c/list/sign", banner);
            JSONObject res = br.result();
            if (br.ok() && br.body != null) {
                JSONArray arr = br.body.optJSONArray("result");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item == null) continue;
                        if ("SIGN".equals(item.optString("jumpCode"))) {
                            String para = item.optString("jumpPara", "{}");
                            JSONObject p = new JSONObject(para);
                            int aid = p.optInt("activityId", 0);
                            if (aid > 0) return aid;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 落到逐个探测
        }

        long now = System.currentTimeMillis();
        // 逐个探测是最后的兜底手段，每次都要发一个请求。
        // 后台有执行窗口限制，所以加上硬性时间预算，超时就放弃，留给重试去补。
        long deadline = now + 12_000L;
        if (deadlineMs > 0L) deadline = Math.min(deadline, deadlineMs);
        for (int aid = startFrom; aid < startFrom + 30; aid++) {
            if (System.currentTimeMillis() > deadline) break;
            try {
                JSONObject body = new JSONObject();
                body.put("activityId", aid);
                Result r = request("POST", "/api/sign/member/signInfoV2", body);
                JSONObject res = r.result();
                if (res == null) continue;
                JSONObject info = res.optJSONObject("activityInfo");
                if (info == null) continue;
                long st = info.optLong("startTime", 0);
                long et = info.optLong("endTime", 0);
                if (st <= now && now <= et) return aid;
            } catch (Exception ignored) {
                // 继续下一个
            }
        }
        return -1;
    }

    public JSONObject signInfo(int activityId) {
        try {
            JSONObject body = new JSONObject();
            body.put("activityId", activityId);
            return request("POST", "/api/sign/member/signInfoV2", body).result();
        } catch (Exception e) {
            return null;
        }
    }

    /** 返回 [状态, 信息]：ok / expired / network / failed */
    public String[] sign(int activityId) {
        try {
            JSONObject body = new JSONObject();
            body.put("activityId", activityId);
            body.put("memberName", "");
            body.put("memberPhone", phone);
            Result r = request("POST", "/api/sign/member/signV2/sign", body);

            if (r.ok() && r.result() != null) {
                JSONObject res = r.result();
                int days = res.optInt("continuousNum", 0);
                StringBuilder detail = new StringBuilder("签到成功，连续 " + days + " 天");
                JSONArray rewards = res.optJSONArray("rewardInfoList");
                Set<String> rewardNames = new LinkedHashSet<>();
                if (rewards != null) {
                    for (int i = 0; i < rewards.length(); i++) {
                        JSONObject rw = rewards.optJSONObject(i);
                        if (rw == null) continue;
                        appendReward(rw, rewardNames);
                    }
                }

                // 少数版本只返回积分，券信息在 signInfo 的 rewardName 里。
                if (!containsCoupon(rewardNames)) {
                    JSONObject info = signInfo(activityId);
                    JSONObject member = info == null ? null : info.optJSONObject("signMemberInfo");
                    String rewardName = member == null ? "" : member.optString("rewardName", "").trim();
                    if (!rewardName.isEmpty()
                            && (rewardNames.isEmpty() || rewardName.contains("券"))) {
                        rewardNames.add(rewardName);
                    }
                }
                if (!rewardNames.isEmpty()) {
                    detail.append("，获得：").append(String.join("、", rewardNames));
                }
                return new String[]{"ok", detail.toString()};
            }
            String msg = r.msg == null ? "" : r.msg;
            if (msg.contains("签过") || msg.contains("已签")) {
                return new String[]{"ok", "今天已经签过了"};
            }
            if (r.code == -1) return new String[]{"network", "网络错误: " + msg};
            return new String[]{"failed", msg};
        } catch (Exception e) {
            return new String[]{"failed", "异常: " + e.getMessage()};
        }
    }

    private static boolean containsCoupon(Set<String> rewards) {
        for (String reward : rewards) {
            if (reward.contains("券")) return true;
        }
        return false;
    }

    private static void appendReward(JSONObject reward, Set<String> out) {
        String rewardName = reward.optString("rewardName", "").trim();
        int point = reward.optInt("point", 0);
        boolean hasCoupon = false;

        JSONArray coupons = reward.optJSONArray("couponInfo");
        if (coupons != null) {
            for (int i = 0; i < coupons.length(); i++) {
                JSONObject coupon = coupons.optJSONObject(i);
                if (coupon == null) continue;
                addCoupon(coupon, rewardName, out);
                hasCoupon = true;
            }
        } else {
            JSONObject coupon = reward.optJSONObject("couponInfo");
            if (coupon != null) {
                addCoupon(coupon, rewardName, out);
                hasCoupon = true;
            }
        }

        int rewardType = reward.optInt("rewardType", 0);
        if (!hasCoupon && !rewardName.isEmpty()
                && (rewardType == 1 || rewardName.contains("券"))) {
            out.add(rewardName);
        }
        if (point > 0) out.add("积分 +" + point);
    }

    private static void addCoupon(JSONObject coupon, String fallbackName, Set<String> out) {
        String name = coupon.optString("name", fallbackName).trim();
        if (name.isEmpty()) name = fallbackName;
        String content = coupon.optString("couponContent", "").trim();
        int num = coupon.optInt("num", 1);
        if (name.isEmpty()) return;

        String label = name;
        if (!content.isEmpty() && !name.contains(content)) {
            label += "（" + content + "）";
        }
        if (num > 1) label += " ×" + num;
        out.add(label);
    }

    public static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new Date());
    }
}
