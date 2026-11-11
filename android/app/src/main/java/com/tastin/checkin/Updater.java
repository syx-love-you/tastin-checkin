package com.tastin.checkin;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 应用内更新。
 *
 * 安全模型（重要）：
 * - 版本清单从固定 URL 拉取，内容里带 APK 的 SHA-256
 * - 下载后先校验 SHA-256，再交给系统安装器
 * - 安装器还会做签名校验：签名不一致系统直接拒绝安装
 *
 * 所以即使清单被篡改指向恶意 APK，也过不了签名这一关——
 * 攻击者拿不到我们的 keystore，就无法构造出能覆盖安装的包。
 * SHA-256 只是提前拦住损坏/被中间人替换的下载，不是唯一防线。
 */
public final class Updater {

    /**
     * 版本清单地址，按顺序尝试，谁先成功用谁。
     *
     * 主地址是 GitHub raw；国内 raw 偶尔被 DNS 污染或超时，
     * 所以再挂一个 jsDelivr（GitHub 的公共 CDN）兜底。
     * 两个都失败就当作"没有更新"，静默忽略，不打扰用户。
     */
    private static final String[] MANIFEST_URLS = buildManifestUrls();

    private static String[] buildManifestUrls() {
        String primary = BuildConfig.UPDATE_MANIFEST_URL;
        String fallback = BuildConfig.UPDATE_MANIFEST_FALLBACK;
        if (fallback == null || fallback.trim().isEmpty()) {
            return new String[]{primary};
        }
        return new String[]{primary, fallback};
    }

    private static final int TIMEOUT_MS = 15000;

    public static final class Info {
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String sha256;
        public final String notes;
        public final boolean force;

        Info(JSONObject o) {
            versionCode = o.optInt("versionCode", 0);
            versionName = o.optString("versionName", "");
            apkUrl = o.optString("apkUrl", "");
            sha256 = o.optString("sha256", "").toLowerCase();
            notes = o.optString("notes", "");
            force = o.optBoolean("force", false);
        }

        boolean valid() {
            return versionCode > 0 && !apkUrl.isEmpty() && apkUrl.startsWith("https://");
        }
    }

    private Updater() {}

    public static int currentVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? (int) pi.getLongVersionCode()
                    : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 检查更新。返回 null 表示无网络 / 解析失败 / 已是最新，都不打扰用户。 */
    public static Info check(Context ctx) {
        for (String url : MANIFEST_URLS) {
            Info info = checkOne(ctx, url);
            if (info != null) return info;
        }
        return null;
    }

    /** 从单个地址检查。null 表示这个地址不可用或没有新版。 */
    private static Info checkOne(Context ctx, String manifestUrl) {
        try {
            String text = httpGet(manifestUrl);
            if (text == null) return null;
            JSONObject o = new JSONObject(text.trim());
            Info info = new Info(o);
            if (!info.valid()) return null;
            return info.versionCode > currentVersionCode(ctx) ? info : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 下载 APK 到私有目录并校验 SHA-256。
     * 返回 null 表示下载失败或校验不通过。
     *
     * 逐个尝试候选地址：清单里的 apkUrl、自动推导的 jsDelivr 镜像。
     * 每个下载完都校验 SHA-256，任何一个通过即用。
     */
    public static File download(Context ctx, Info info, Progress cb) {
        for (String url : candidates(info.apkUrl)) {
            File f = downloadOne(ctx, info, url, cb);
            if (f != null) return f;
        }
        return null;
    }

    /** apkUrl 及其镜像候选 */
    private static String[] candidates(String apkUrl) {
        String cdn = toJsdelivr(apkUrl);
        if (cdn != null && !cdn.equals(apkUrl)) {
            return new String[]{apkUrl, cdn};
        }
        return new String[]{apkUrl};
    }

    /** 把 raw.githubusercontent.com 地址转成 jsDelivr 等价地址，失败返回 null */
    private static String toJsdelivr(String url) {
        String prefix = "https://raw.githubusercontent.com/";
        if (!url.startsWith(prefix)) return null;
        String[] parts = url.substring(prefix.length()).split("/", 4);
        if (parts.length < 4) return null;
        String user = parts[0], repo = parts[1], branch = parts[2], path = parts[3];
        if (user.isEmpty() || repo.isEmpty() || branch.isEmpty() || path.isEmpty()) return null;
        return "https://cdn.jsdelivr.net/gh/" + user + "/" + repo + "@" + branch + "/" + path;
    }

    private static File downloadOne(Context ctx, Info info, String url, Progress cb) {
        File out = new File(ctx.getCacheDir(), "update-" + info.versionCode + ".apk");
        if (out.exists() && !out.delete()) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) return null;

            int total = conn.getContentLength();
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            try (InputStream in = conn.getInputStream();
                 FileOutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n, done = 0;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (cb != null && total > 0) cb.onProgress(done * 100 / total);
                }
            }

            String actual = hex(md.digest());
            if (!actual.equals(info.sha256)) {
                out.delete();
                return null;
            }
            return out;
        } catch (Exception e) {
            if (out.exists()) out.delete();
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), "UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 本机安装包的签名 SHA-256，用于发布时核对 */
    public static String signingSha256(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = pi.signingInfo == null ? null
                        : pi.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(),
                        PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            if (sigs == null || sigs.length == 0) return "";
            return hex(MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray()));
        } catch (Exception e) {
            return "";
        }
    }

    public interface Progress {
        void onProgress(int percent);
    }
}
