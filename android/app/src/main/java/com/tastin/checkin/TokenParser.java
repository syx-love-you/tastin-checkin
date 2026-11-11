package com.tastin.checkin;

import org.json.JSONObject;

/** 解析扫码内容。支持三种格式，方便电脑端生成二维码时灵活选择。 */
public final class TokenParser {

    public final String token;
    public final String phone;

    private TokenParser(String token, String phone) {
        this.token = token;
        this.phone = phone;
    }

    public static TokenParser parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // 1) JSON: {"token":"...","phone":"..."}
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(s);
                String t = o.optString("token", "");
                String p = o.optString("phone", "");
                if (!t.isEmpty()) return new TokenParser(t, p);
            } catch (Exception ignored) {
                // 落到其他格式
            }
        }

        // 2) token|phone
        if (s.contains("|")) {
            String[] parts = s.split("\\|", 2);
            if (!parts[0].trim().isEmpty()) {
                return new TokenParser(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "");
            }
        }

        // 3) 裸 token
        return new TokenParser(s, "");
    }
}
