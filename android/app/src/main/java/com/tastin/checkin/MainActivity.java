package com.tastin.checkin;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIFY = 501;

    /** 通知点击进来时带上这个标记，表示要弹 token 失效对话框 */
    public static final String EXTRA_SHOW_TOKEN_DIALOG = "show_token_dialog";

    private EditText etToken;
    private Button btnRunNow;
    private TextView tvTime, tvStatus;
    private Config cfg;

    /** 用户去授权"安装未知应用"后，回来要继续的更新 */
    private Updater.Info pendingUpdate;
    /** 正在等待用户从哪个系统设置页返回 */
    private String waitingForSetting;
    private String pendingGuideSuccess;
    private boolean guideDismissedThisSession;
    private boolean openCheckinRunning;
    private String openCheckinDate;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() == null) {
                    toast("已取消扫码");
                    return;
                }
                TokenParser p = TokenParser.parse(result.getContents());
                if (p == null) {
                    toast("二维码内容无法识别");
                    return;
                }
                // 已有 token 时再扫，确认一次，避免误覆盖
                String existing = cfg.token();
                if (!existing.isEmpty() && !existing.equals(p.token)) {
                    new AlertDialog.Builder(this)
                            .setTitle("替换已有 token？")
                            .setMessage("当前已配置 token，扫码会覆盖它。\n\n"
                                    + "如果现在的签到是正常的，不需要重新扫。")
                            .setPositiveButton("替换", (d, w) -> applyScanned(p.token))
                            .setNegativeButton("取消", null)
                            .show();
                    return;
                }
                applyScanned(p.token);
            });

    private void applyScanned(String token) {
        if (!isValidToken(token)) {
            toast("二维码里的 token 格式不对");
            return;
        }
        etToken.setText(token);
        save(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cfg = new Config(this);
        etToken = findViewById(R.id.etToken);
        tvTime = findViewById(R.id.tvTime);
        tvStatus = findViewById(R.id.tvStatus);

        etToken.setText(cfg.token());
        updateTimeLabel();
        setupTokenGuard();

        tvTime.setOnClickListener(v -> pickTime());
        findViewById(R.id.btnScan).setOnClickListener(v -> startScan());
        findViewById(R.id.btnSave).setOnClickListener(v -> save(false));
        btnRunNow = findViewById(R.id.btnRunNow);
        btnRunNow.setOnClickListener(v -> runNow());
        findViewById(R.id.tvLogBtn).setOnClickListener(v -> showLogs());
        findViewById(R.id.tvVersion).setOnClickListener(v -> {
            toast("正在检查更新…");
            checkUpdate(false);
        });

        findViewById(R.id.tvHelpToggle).setOnClickListener(v -> showTokenGuide());

        // 没配置 token 时，打开 App 直接弹引导，省得用户不知道从哪开始
        if (!cfg.configured()) {
            showTokenGuide();
        }

        askNotifyPermission();
        refreshStatus();

        ((TextView) findViewById(R.id.tvVersion)).setText(
                "版本 " + Updater.currentVersionName(this) + " · 点此检查更新");

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_SHOW_TOKEN_DIALOG, false)) {
            showTokenDialog();
        }

        // 启动时静默检查更新，有新版才提示，失败不打扰
        checkUpdate(true);
    }

    // ================= 应用内更新 =================

    private void checkUpdate(boolean silent) {
        pool.execute(() -> {
            final Updater.Info info = Updater.check(this);
            ui.post(() -> {
                if (info == null) {
                    if (!silent) toast("当前已是最新版本 "
                            + Updater.currentVersionName(this));
                    return;
                }
                showUpdateDialog(info);
            });
        });
    }

    private void showUpdateDialog(final Updater.Info info) {
        String msg = "当前版本：" + Updater.currentVersionName(this)
                + "\n新版本：" + info.versionName;
        if (!info.notes.isEmpty()) {
            msg += "\n\n更新内容：\n" + info.notes;
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("立即更新", (d, w) -> startUpdate(info));
        // 强制更新时不给"稍后"
        if (!info.force) {
            b.setNegativeButton("稍后", null);
        }
        b.setCancelable(!info.force);
        b.show();
    }

    private void startUpdate(final Updater.Info info) {
        if (!Installer.canInstall(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要授权")
                    .setMessage("安装更新需要允许本应用安装应用。\n\n"
                            + "点「去设置」找到「塔斯汀签到」，打开「允许安装未知应用」，"
                            + "然后回来再点一次更新。")
                    .setPositiveButton("去设置", (d, w) -> {
                        Installer.requestInstallPermission(this);
                        pendingUpdate = info;
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        doDownload(info);
    }

    private void doDownload(final Updater.Info info) {
        final ProgressDialog dlg = new ProgressDialog(this);
        dlg.setTitle("正在下载更新");
        dlg.setMessage("0%");
        dlg.setCancelable(false);
        dlg.show();

        pool.execute(() -> {
            File apk = Updater.download(this, info, pct ->
                    ui.post(() -> dlg.setMessage(pct + "%")));
            ui.post(() -> {
                dlg.dismiss();
                if (apk == null) {
                    toast("下载失败或校验不通过，请稍后重试");
                    return;
                }
                if (!Installer.install(this, apk)) {
                    toast("无法启动安装器，请检查系统权限");
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 从系统设置（精确闹钟/电池/安装权限）返回时，权限可能刚被打开，重排一次。
        // 不动重试闹钟：否则用户一打开 App 就把待重试冲掉了。
        if (cfg != null && cfg.configured()) {
            Scheduler.scheduleDaily(this, false);
            if (waitingForSetting != null) {
                finishGuideAfterSettings();
            } else {
                maybeCheckinOnOpen();
                if (needsBackgroundSetup() && !guideDismissedThisSession) {
                    ensureBackgroundSetup("后台设置已完成，自动签到可以正常工作");
                }
            }
        }
        refreshStatus();
        setRunButtonBusy(CheckinRunner.isRunning() || openCheckinRunning);

        // 从"安装未知应用"设置页回来，继续之前的更新
        if (pendingUpdate != null && Installer.canInstall(this)) {
            Updater.Info i = pendingUpdate;
            pendingUpdate = null;
            doDownload(i);
        }
    }

    /**
     * 打开应用时先查本地成功记录。日志状态比网络校验 token 更快，也不消耗接口请求。
     * 每个自然日只自动触发一次，失败后交给现有重试队列。
     */
    private void maybeCheckinOnOpen() {
        String today = TastinApi.today();
        if (!cfg.configured() || openCheckinRunning || today.equals(openCheckinDate)) return;
        openCheckinDate = today;
        if (cfg.signedToday()) return;

        openCheckinRunning = true;
        setRunButtonBusy(true);
        tvStatus.setText("检测到今天还没签到，正在签到…");
        toast("今天还没签到，正在自动签到…");
        pool.execute(() -> {
            CheckinRunner.Outcome out;
            try {
                out = CheckinRunner.run(this, "打开应用",
                        msg -> ui.post(() -> tvStatus.setText(msg)));
            } catch (Throwable t) {
                cfg.recordFail("打开应用｜程序异常：" + t);
                out = new CheckinRunner.Outcome(false,
                        "签到异常：" + t.getMessage(), CheckinRunner.FailKind.OTHER);
            }
            final CheckinRunner.Outcome result = out;
            ui.post(() -> {
                if (result.kind == CheckinRunner.FailKind.BUSY) {
                    toast(result.message);
                } else if (!result.success && result.kind == CheckinRunner.FailKind.TOKEN) {
                    Scheduler.suspendRetriesForToday(this);
                    showTokenDialog();
                } else {
                    boolean retry = !result.success && Scheduler.scheduleRetry(this);
                    toast(result.message + (!result.success
                            ? (retry ? "，稍后自动重试" : "，今日重试次数已用完")
                            : ""));
                }
                openCheckinRunning = false;
                setRunButtonBusy(CheckinRunner.isRunning());
                refreshStatus();
            });
        });
    }

    /**
     * token 失效对话框。这是唯一会打断用户的弹窗——
     * 因为不处理的话，之后每天的签到都不会成功。
     */
    private void showTokenDialog() {
        new AlertDialog.Builder(this)
                .setTitle("token 已失效")
                .setMessage("需要重新获取一次 token，否则签到会一直失败。\n\n"
                        + "在电脑上：\n"
                        + "1. 打开微信，进塔斯汀小程序\n"
                        + "2. 双击「提取Token.exe」（小程序开着就行，不用重开）\n"
                        + "3. 回到这里点「去扫码」对准二维码")
                .setPositiveButton("去扫码", (d, w) -> startScan())
                .setNegativeButton("稍后", null)
                .show();
    }

    // ================= 分步引导 =================

    private static final String REPO_URL = "https://github.com/syx-love-you/tastin-checkin";
    private static final String RELEASES_URL = REPO_URL + "/releases";

    /** 每一步：标题、正文、主按钮文案。主按钮为空表示"下一步"。 */
    private static final String[][] GUIDE_STEPS = {
            {
                    "为什么要用电脑",
                    "token 是你账号的钥匙，绑定你的微信身份，只能由你本人获取。\n\n"
                            + "手机上的微信有证书校验，普通 App 读不到它的网络请求，"
                            + "所以要借一台电脑来取。\n\n"
                            + "整个过程大约 2 分钟，取一次能用很久，不用天天弄。",
                    ""
            },
            {
                    "第 1 步 / 共 4 步：下载电脑抓取工具",
                    "在电脑上打开浏览器，访问 Releases 页面：\n\n"
                            + RELEASES_URL + "\n\n"
                            + "下载「tastin-checkin-1.0-tool.zip」，"
                            + "这就是电脑抓取工具。\n\n"
                            + "把它解压到任意文件夹，后面会使用里面的「提取Token.exe」。\n\n"
                            + "点下面的「下一步」会自动复制下载页网址，方便你粘贴到电脑浏览器。",
                    "复制下载页"
            },
            {
                    "第 2 步 / 共 4 步：打开小程序",
                    "在电脑上：\n\n"
                            + "1. 打开微信，搜索「塔斯汀」\n"
                            + "2. 进入小程序，等页面加载完\n\n"
                            + "小程序保持打开就行，不用关掉重开。"
                            + "只要它没被关闭，就能取到 token。",
                    ""
            },
            {
                    "第 3 步 / 共 4 步：运行提取",
                    "双击解压出来的「提取Token.exe」。\n\n"
                            + "约 2 秒出结果，会自动弹出一个二维码页面。\n\n"
                            + "什么都不用安装。若提示权限不足，右键选「以管理员身份运行」。",
                    ""
            },
            {
                    "第 4 步 / 共 4 步：扫码导入",
                    "电脑上出现二维码页面后，回到本页点下面的「去扫码」，"
                            + "对准电脑屏幕上的二维码。\n\n"
                            + "扫到后会自动验证并保存，提示「token 有效，当前 XX 积分」就成功了。",
                    ""
            },
    };

    // ================= token 防护 =================

    /**
     * token 校验规则必须与电脑端 grab.c 的 looks_like_token() 完全一致：
     * sss 开头、仅 [0-9a-z-]、至少 3 个连字符、至少 8 位数字、总长 20~72。
     * 两端不一致会出现「exe 抓到了、App 却拒收还原」的死局。
     */
    private static final java.util.regex.Pattern TOKEN_PATTERN =
            java.util.regex.Pattern.compile("^sss[0-9a-z-]{17,69}$");

    private boolean isValidToken(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (!TOKEN_PATTERN.matcher(s).matches()) return false;
        if (s.length() - s.replace("-", "").length() < 3) return false;
        int digits = 0;
        for (int i = 3; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits++;
        }
        return digits >= 8;
    }

    /**
     * token 只能通过扫码写入，不允许手动编辑。
     *
     * 理由：这是 39 位随机串，手动改极易出错且难排查（改错一个字就整个签到失败）。
     * 输入框全程不可聚焦 —— 点它不会弹键盘，长按也无法进入编辑。
     * 需要换 token 就重新扫码，扫码时会二次确认。
     */
    private void setupTokenGuard() {
        // 允许聚焦（这样才能长按粘贴），但屏蔽软键盘
        etToken.setFocusable(true);
        etToken.setFocusableInTouchMode(true);
        etToken.setCursorVisible(true);
        etToken.setLongClickable(true);
        etToken.setShowSoftInputOnFocus(false);   // 关键：点它不弹键盘

        // 拦截硬键盘输入（外接键盘、扫码枪等），只放行粘贴
        etToken.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            // 放行粘贴快捷键
            boolean paste = keyCode == android.view.KeyEvent.KEYCODE_V &&
                    event.isCtrlPressed();
            if (paste) return false;
            // 导航键放行
            if (keyCode == android.view.KeyEvent.KEYCODE_DEL ||
                keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_TAB) {
                return false;
            }
            toast("token 不支持手动输入，请扫码或粘贴");
            return true;    // 拦下其他按键
        });

        // 粘贴后校验，格式不对就还原
        etToken.addTextChangedListener(new android.text.TextWatcher() {
            private boolean reverting = false;

            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(android.text.Editable e) {
                if (reverting) return;
                String t = e.toString().trim();
                if (t.isEmpty()) return;
                if (!isValidToken(t)) {
                    reverting = true;
                    int oldLen = t.length();
                    String good = cfg.token();
                    e.replace(0, oldLen, good);
                    reverting = false;
                    toast("token 格式不对，已还原");
                }
            }
        });
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("repo", text));
        }
    }

    private void showTokenGuide() {
        showGuideStep(0);
    }

    private void showGuideStep(final int index) {
        if (index >= GUIDE_STEPS.length) return;
        final String[] step = GUIDE_STEPS[index];
        final boolean isLast = index == GUIDE_STEPS.length - 1;

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView progress = new TextView(this);
        progress.setText("●".repeat(index + 1) + "○".repeat(GUIDE_STEPS.length - index - 1));
        progress.setTextSize(12f);
        progress.setTextColor(0xFFC42D24);
        progress.setPadding(0, 0, 0, pad / 2);
        box.addView(progress);

        TextView body = new TextView(this);
        body.setText(step[1]);
        body.setTextSize(15f);
        body.setLineSpacing(0f, 1.35f);
        body.setTextColor(0xFF333333);
        body.setTextIsSelectable(true);
        box.addView(body);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(step[0])
                .setView(box);

        // 主按钮永远是"前进"，有网址的步骤先复制再前进
        if (isLast) {
            b.setPositiveButton("去扫码", (d, w) -> startScan());
        } else {
            b.setPositiveButton("下一步", (d, w) -> {
                if (!step[2].isEmpty()) {
                    copyToClipboard(RELEASES_URL);
                    toast("下载页网址已复制，可粘贴到电脑浏览器");
                }
                showGuideStep(index + 1);
            });
        }

        if (index > 0) {
            b.setNegativeButton("上一步", (d, w) -> showGuideStep(index - 1));
        }

        // 关闭 = 真的关掉，不再跳到别的步骤
        b.setNeutralButton("关闭", null);

        b.show();
    }


    private void updateTimeLabel() {
        tvTime.setText(String.format(Locale.CHINA, "%02d:%02d", cfg.hour(), cfg.minute()));
    }

    private void pickTime() {
        TimePickerDialog dlg = new TimePickerDialog(this,
                (view, h, m) -> {
                    cfg.setHour(h);
                    cfg.setMinute(m);
                    updateTimeLabel();
                    if (cfg.configured()) {
                        Scheduler.scheduleDaily(this);
                    }
                    refreshStatus();
                },
                cfg.hour(), cfg.minute(), true);
        dlg.show();
    }

    private void startScan() {
        ScanOptions opt = new ScanOptions();
        opt.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        opt.setPrompt("对准电脑上的二维码");
        opt.setBeepEnabled(false);
        // 锁定竖屏：扫电脑屏幕时横屏反直觉，而且读者通常是竖着举手机
        opt.setOrientationLocked(true);
        opt.setBarcodeImageEnabled(false);
        // 用自定义的竖屏 CaptureActivity，覆盖库默认的横屏主题
        opt.setCaptureActivity(PortraitCaptureActivity.class);
        scanLauncher.launch(opt);
    }

    private void askNotifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            toast("没给通知权限，签到结果不会提醒（功能仍可用）");
        }
        if (requestCode == REQ_NOTIFY && cfg != null) refreshStatus();
    }

    private void save(boolean fromScan) {
        String token = etToken.getText().toString().trim();
        if (TextUtils.isEmpty(token)) {
            toast("请扫码，或长按输入框粘贴 token");
            return;
        }
        if (!isValidToken(token)) {
            toast("token 格式不对，请重新扫码");
            return;
        }

        if (fromScan) {
            // 扫来的 token 先验证，有效才保存并启用定时
            verifyThenSave(token);
            return;
        }

        boolean tokenChanged = !token.equals(cfg.token());
        cfg.setToken(token);
        if (tokenChanged) cfg.setActivityId(-1);

        Scheduler.scheduleDaily(this);
        ensureBackgroundSetup("已保存，每天 " + String.format(Locale.CHINA, "%02d:%02d",
                cfg.hour(), cfg.minute()) + " 自动签到");
        refreshStatus();
        openCheckinDate = null;
        if (cfg.signedToday()) {
            verifyAsync();
        } else {
            maybeCheckinOnOpen();
        }
    }

    /**
     * 扫码后的流程：验证 → 有效才保存 + 启用定时。
     * 无效的 token 不会被写入，避免把原来能用的配置覆盖掉。
     */
    private void verifyThenSave(final String token) {
        tvStatus.setText("正在验证 token…");
        pool.execute(() -> {
            TastinApi api = new TastinApi(token, "", "", "");
            String state = api.checkToken();
            final int point = "ok".equals(state) ? api.getPoint() : -1;

            ui.post(() -> {
                if ("ok".equals(state)) {
                    cfg.setToken(token);
                    cfg.setActivityId(-1);
                    Scheduler.scheduleDaily(this);
                    ensureBackgroundSetup(point >= 0
                            ? "token 有效，当前 " + point + " 积分。已保存并启用定时签到"
                            : "token 有效，已保存并启用定时签到");
                    openCheckinDate = null;
                    maybeCheckinOnOpen();
                } else if ("expired".equals(state)) {
                    // 不写入，保留原配置
                    toast("token 无效或已过期，未保存");
                } else {
                    toast("网络不通，暂时无法验证，请稍后重试");
                }
                refreshStatus();
            });
        });
    }

    /** 保存后立刻验证 token，避免用户以为配好了其实没配好 */
    private void verifyAsync() {
        tvStatus.setText("正在验证 token…");
        pool.execute(() -> {
            String state = cfg.api().checkToken();
            int point = "ok".equals(state) ? cfg.api().getPoint() : -1;
            ui.post(() -> {
                if ("ok".equals(state)) {
                    toast(point >= 0 ? "token 有效，当前 " + point + " 积分" : "token 有效");
                } else if ("expired".equals(state)) {
                    toast("token 无效或已过期，请重新抓取");
                } else {
                    toast("网络不通，稍后会自动重试");
                }
                refreshStatus();
            });
        });
    }

    private void runNow() {
        if (CheckinRunner.isRunning() || openCheckinRunning) {
            toast("签到正在进行，请稍候");
            return;
        }
        String token = etToken.getText().toString().trim();
        if (TextUtils.isEmpty(token)) {
            toast("请扫码，或长按输入框粘贴 token");
            return;
        }
        cfg.setToken(token);

        setRunButtonBusy(true);
        tvStatus.setText("准备签到…");
        pool.execute(() -> {
            CheckinRunner.Outcome out;
            try {
                out = CheckinRunner.run(this, "手动",
                        msg -> ui.post(() -> tvStatus.setText(msg)));
            } catch (Throwable t) {
                cfg.recordFail("手动｜程序异常：" + t);
                out = new CheckinRunner.Outcome(false,
                        "签到异常：" + t.getMessage(), CheckinRunner.FailKind.OTHER);
            }
            final CheckinRunner.Outcome result = out;
            ui.post(() -> {
                if (result.kind == CheckinRunner.FailKind.BUSY) {
                    toast(result.message);
                } else if (!result.success && result.kind == CheckinRunner.FailKind.TOKEN) {
                    showTokenDialog();
                } else {
                    toast(result.message);
                }
                setRunButtonBusy(CheckinRunner.isRunning());
                refreshStatus();
            });
        });
    }

    private void setRunButtonBusy(boolean busy) {
        if (btnRunNow == null) return;
        btnRunNow.setEnabled(!busy);
        btnRunNow.setAlpha(busy ? 0.55f : 1f);
    }

    /**
     * 保存后检查"能不能在后台自动跑"。
     * 一次只引导一件事，说人话，不讲系统术语。
     */
    private boolean needsBackgroundSetup() {
        if (!Scheduler.canScheduleExact(this)) return true;
        if (!BackgroundGuard.ignoringBatteryOptimizations(this)
                && !cfg.batteryGuideAcknowledged()) return true;
        return BackgroundGuard.canOpenAutoStart(this) && !cfg.autoStartAcknowledged();
    }

    private void ensureBackgroundSetup(String successMsg) {
        pendingGuideSuccess = successMsg;
        guideDismissedThisSession = false;

        // ① 精确闹钟。没有它时，Doze 下可能完全错过签到时间。
        if (!Scheduler.canScheduleExact(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("允许精确闹钟")
                    .setMessage("每日签到需要系统在指定时间唤醒应用。\n\n"
                            + "请允许「闹钟和提醒」权限，否则锁屏后可能延迟很久才签到。")
                    .setPositiveButton("去允许", (d, w) -> {
                        waitingForSetting = "exact";
                        Scheduler.openExactAlarmSettings(this);
                        toast("允许后返回本页，应用会自动继续检查");
                    })
                    .setNegativeButton("暂不", (d, w) -> {
                        guideDismissedThisSession = true;
                        toast("没开精确闹钟，锁屏后签到可能延迟");
                    })
                    .setCancelable(false)
                    .show();
            return;
        }

        // ② 电池限制。部分 ROM 不会回报真实状态，所以尊重用户的完成标记。
        if (!BackgroundGuard.ignoringBatteryOptimizations(this)
                && !cfg.batteryGuideAcknowledged()) {
            new AlertDialog.Builder(this)
                    .setTitle("还差一步，才能自动签到")
                    .setMessage("自动签到需要应用在后台保持运行。\n\n"
                            + "点下面的按钮，把「塔斯汀签到」的后台运行方式"
                            + "设为「无限制」或「允许后台运行」就好。")
                    .setPositiveButton("去设置", (d, w) -> {
                        cfg.setBatteryGuideAcknowledged(true);
                        waitingForSetting = "battery";
                        if (!BackgroundGuard.requestIgnoreBatteryOptimizations(this)) {
                            // 系统不给弹窗时，退到应用详情页让用户手动开
                            BackgroundGuard.openAppDetails(this);
                        }
                        toast("设成「无限制」后返回本页");
                    })
                    .setNegativeButton("暂不", (d, w) -> {
                        guideDismissedThisSession = true;
                        toast("没开的话，锁屏后可能签不到");
                    })
                    .setCancelable(false)
                    .show();
            return;
        }

        // ③ 自启动（主要用于重启手机后能恢复）
        if (BackgroundGuard.canOpenAutoStart(this) && !cfg.autoStartAcknowledged()) {
            new AlertDialog.Builder(this)
                    .setTitle("最后一步：允许自启动")
                    .setMessage("这是为了让手机重启之后，自动签到还能继续生效。\n\n"
                            + "下面会打开自启动列表，找到「塔斯汀签到」，"
                            + "把右边的开关打开就行。")
                    .setPositiveButton("去打开", (d, w) -> {
                        cfg.setAutoStartAcknowledged(true);
                        cfg.setBgGuided(true);
                        if (!BackgroundGuard.openAutoStartSettings(this)) {
                            toast("没找到自启动列表，可在系统设置里搜索「自启动」");
                        }
                    })
                    .setNegativeButton("跳过", (d, w) -> {
                        cfg.setAutoStartAcknowledged(true);
                        cfg.setBgGuided(true);
                        toast(successMsg);
                    })
                    .setCancelable(false)
                    .show();
            return;
        }

        if (BackgroundGuard.ignoringBatteryOptimizations(this)) {
            cfg.setBatteryGuideAcknowledged(true);
        }
        cfg.setBgGuided(true);
        toast(successMsg);
    }

    /** 从系统设置返回后只检查刚才那一项，未完成就结束本轮引导，避免循环弹窗。 */
    private void finishGuideAfterSettings() {
        String step = waitingForSetting;
        waitingForSetting = null;

        if ("exact".equals(step)) {
            if (!Scheduler.canScheduleExact(this)) {
                guideDismissedThisSession = true;
                toast("精确闹钟还没开启，可稍后点状态栏重新设置");
                return;
            }
        } else if ("battery".equals(step)) {
            if (!BackgroundGuard.ignoringBatteryOptimizations(this)) {
                guideDismissedThisSession = true;
                toast("后台限制还没解除，可稍后点状态栏重新设置");
                return;
            }
        }

        ensureBackgroundSetup(pendingGuideSuccess == null
                ? "后台设置已完成，自动签到可以正常工作" : pendingGuideSuccess);
    }

    private void refreshStatus() {
        if (!cfg.configured()) {
            tvStatus.setText("尚未配置，点上面「扫码获取」");
            return;
        }

        StringBuilder sb = new StringBuilder();
        String last = cfg.lastRun();
        String msg = cfg.lastMsg();
        sb.append(last.isEmpty() ? "还没有运行记录" : "上次运行：" + last);
        if (!msg.isEmpty()) sb.append("\n").append(msg);

        long next = Scheduler.nextDailyAt(this);
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        sb.append("\n\n下次签到：").append(f.format(new Date(next)));

        boolean exact = Scheduler.canScheduleExact(this);
        boolean battery = BackgroundGuard.ignoringBatteryOptimizations(this);
        boolean heartbeat = HeartbeatJob.isScheduled(this);
        if (exact && battery && heartbeat) {
            sb.append("\n后台保障：正常");
        } else {
            sb.append("\n后台保障：⚠ 需检查");
            if (!exact) sb.append("\n· 精确闹钟未开启");
            if (!battery) {
                sb.append(cfg.batteryGuideAcknowledged()
                        ? "\n· 省电限制：请确认已设为「无限制」"
                        : "\n· 省电限制未解除");
            }
            if (!heartbeat) sb.append("\n· 后台心跳未登记");
        }
        if (BackgroundGuard.canOpenAutoStart(this) && !cfg.autoStartAcknowledged()) {
            sb.append("\n· 自启动尚未确认");
        }
        if (!BackgroundGuard.notificationsEnabled(this)) {
            sb.append("\n通知权限：未允许（签到结果不会提醒）");
        }
        // 真实状态：闹钟是否还登记在系统里（被强停后会消失，这是系统行为）
        sb.append(Scheduler.isDailyAlarmRegistered(this)
                ? "\n定时任务：已登记"
                : "\n定时任务：⚠ 未登记（重新打开应用或保存一次即可恢复）");
        sb.append("\n失败会自动重试（1、2、4 小时后），每天最多 3 次");

        tvStatus.setText(sb.toString());

        // 状态区整块可点：没设置过就直接引导
        tvStatus.setOnClickListener(v -> {
            if (!cfg.configured()) return;
            if (!BackgroundGuard.notificationsEnabled(this)) {
                askNotifyPermission();
                return;
            }
            if (!battery) cfg.setBatteryGuideAcknowledged(false);
            if (BackgroundGuard.canOpenAutoStart(this) && !cfg.autoStartAcknowledged()) {
                cfg.setAutoStartAcknowledged(false);
            }
            HeartbeatJob.ensureScheduled(this);
            Scheduler.scheduleDaily(this, false);
            ensureBackgroundSetup("设置完成，可以正常自动签到了");
        });

        pool.execute(() -> {
            String extra = CheckinRunner.describe(this);
            ui.post(() -> tvStatus.append("\n" + extra));
        });
    }

    /** 日志查看：最近 30 天的签到记录 */
    private void showLogs() {
        java.util.List<String> logs = cfg.logs();

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, pad / 2);
        tv.setTextIsSelectable(true);
        tv.setTextSize(13f);
        tv.setLineSpacing(0f, 1.3f);
        // 正文缩进：和上面那行的勾叉对齐
        int indentPx = (int) (26 * getResources().getDisplayMetrics().density);

        if (logs.isEmpty()) {
            tv.setText("还没有记录。\n\n每天签到后会自动记在这里，\n成功和失败都会记，保留 30 天。");
            tv.setTextSize(14f);
            tv.setTextColor(0xFF888888);
            sv.addView(tv);
        } else {
            android.text.SpannableStringBuilder sp = new android.text.SpannableStringBuilder();
            int okCount = 0, failCount = 0;
            String lastDay = null;

            for (String line : logs) {
                int bar = line.indexOf('|');
                String time = bar > 0 ? line.substring(0, bar) : "";
                String what = bar > 0 ? line.substring(bar + 1) : line;
                boolean fail = what.startsWith("✗");
                if (fail) failCount++; else okCount++;

                // 按天分组，跨天的插一个日期小标题
                String day = time.length() >= 10 ? time.substring(0, 10) : "";
                if (!day.equals(lastDay)) {
                    if (sp.length() > 0) sp.append("\n");
                    int s = sp.length();
                    sp.append(day).append("  ").append(weekday(day)).append("\n");
                    sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            s, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF888888),
                            s, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    lastDay = day;
                }

                String hhmm = time.length() > 11 ? time.substring(11) : "";
                String body = fail ? what.substring(1).trim() : what;

                int s = sp.length();
                sp.append("  ").append(fail ? "✗ " : "✓ ").append(hhmm).append("\n");
                sp.setSpan(new android.text.style.ForegroundColorSpan(
                                fail ? 0xFFC42D24 : 0xFF2E7D32),
                        s, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                s = sp.length();
                sp.append(body).append("\n");
                // 悬挂缩进：正文换行后也对齐，不会跑到最左边
                sp.setSpan(new android.text.style.LeadingMarginSpan.Standard(indentPx, indentPx),
                        s, s + body.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new android.text.style.ForegroundColorSpan(
                                fail ? 0xFFC42D24 : 0xFF333333),
                        s, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            android.text.SpannableStringBuilder head = new android.text.SpannableStringBuilder();
            head.append("成功 ").append(String.valueOf(okCount)).append(" 次")
                .append("　失败 ").append(String.valueOf(failCount)).append(" 次")
                .append("　共 ").append(String.valueOf(logs.size())).append(" 条\n\n");
            head.setSpan(new android.text.style.ForegroundColorSpan(0xFF888888),
                    0, head.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.insert(0, head);
            tv.setText(sp);
            sv.addView(tv);
        }

        final android.widget.ScrollView scrollView = sv;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("签到日志（最近 30 天）")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", (d, w) -> {
                    new AlertDialog.Builder(this)
                            .setTitle("清空日志？")
                            .setMessage("清空后无法恢复。")
                            .setPositiveButton("清空", (d2, w2) -> {
                                cfg.clearLogs();
                                refreshStatus();
                                toast("日志已清空");
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .create();
        // TextView 里的超链接/选中会让滚动位置漂移，打开时强制回到顶部
        dlg.setOnShowListener(d -> scrollView.post(() -> scrollView.scrollTo(0, 0)));
        dlg.show();
    }

    /** 2026-11-08 → 周六 */
    private static String weekday(String day) {
        try {
            java.text.SimpleDateFormat in =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            in.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            java.util.Date d = in.parse(day);
            java.text.SimpleDateFormat out =
                    new java.text.SimpleDateFormat("EEE", Locale.CHINA);
            out.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            return out.format(d);
        } catch (Exception e) {
            return "";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
