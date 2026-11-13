package jp.siztec.portalkiosk;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 共有端末用の専用表示。設定した接続先（ポータルの共有端末URL）だけを全画面で表示する。
 * 管理者は、画面の左下を素早く7回タップし、管理者PINを入力して設定変更・解除を行う。
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private static final int REQ_CAMERA = 2;
    private static final int ADMIN_TAPS = 7;
    private static final long ADMIN_TAP_WINDOW_MS = 5000;
    private static final long RETRY_MS = 10000;
    private static final int MAX_PIN_FAILURES = 5;
    private static final long LOCKOUT_MS = 60000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingCameraRequest;
    private boolean errorShown;
    private int adminTaps;
    private long firstTapAt;
    private int pinFailures;
    private long lockedUntil;

    private static final long UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final long UPDATE_TICK_MS = 60L * 60 * 1000;
    private static final long UPDATE_FIRST_DELAY_MS = 30000;
    private static final long UPDATE_IDLE_MS = 60000;
    private static final long UPDATE_IDLE_RETRY_MS = 30000;

    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile long lastTouchAt = SystemClock.elapsedRealtime();
    private boolean updateScheduled;

    /** 更新の確認（6時間に1回まで）。新しい版があれば、操作がないときにインストールする。 */
    private final Runnable updateTick = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, UPDATE_TICK_MS);
            android.content.SharedPreferences p = getSharedPreferences("kiosk_update", MODE_PRIVATE);
            long now = System.currentTimeMillis();
            if (now - p.getLong("last_check", 0) < UPDATE_CHECK_INTERVAL_MS) {
                return;
            }
            p.edit().putLong("last_check", now).apply();
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final Updater.Staged staged = Updater.fetch(MainActivity.this);
                        if (staged != null) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    installWhenIdle(staged);
                                }
                            });
                        }
                    } catch (Exception e) {
                        android.util.Log.w("PortalKiosk", "更新の確認に失敗しました: " + e.getClass().getSimpleName());
                    }
                }
            });
        }
    };

    private void installWhenIdle(final Updater.Staged staged) {
        if (SystemClock.elapsedRealtime() - lastTouchAt < UPDATE_IDLE_MS) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    installWhenIdle(staged);
                }
            }, UPDATE_IDLE_RETRY_MS);
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Updater.install(MainActivity.this, staged.apk);
                } catch (Exception e) {
                    android.util.Log.w("PortalKiosk", "更新のインストールに失敗しました: " + e.getClass().getSimpleName());
                }
            }
        });
    }

    private void scheduleUpdates() {
        if (updateScheduled || !Updater.isEnabled() || !KioskPolicy.isDeviceOwner(this)) {
            return;
        }
        updateScheduled = true;
        handler.postDelayed(updateTick, UPDATE_FIRST_DELAY_MS);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        lastTouchAt = SystemClock.elapsedRealtime();
        return super.dispatchTouchEvent(ev);
    }

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (web != null) {
                errorShown = false;
                web.loadUrl(Config.url(MainActivity.this));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Config.isConfigured(this)) {
            KioskPolicy.apply(this);
            KioskPolicy.startLock(this);
            scheduleUpdates();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(updateTick);
        updateScheduled = false;
        worker.shutdown();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack() && !errorShown) {
            web.goBack();
        }
        // 戻る先がないときは何もしない（アプリを閉じさせない）
    }

    // ---- 画面の組み立て ----

    private void render() {
        root.removeAllViews();
        if (web != null) {
            handler.removeCallbacks(retry);
            web.destroy();
            web = null;
        }
        if (Config.isConfigured(this)) {
            showWeb();
        } else {
            showSetup();
        }
    }

    private void showSetup() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        form.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("共有端末の初期設定");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        form.addView(title);

        TextView note = new TextView(this);
        note.setText("管理者が設定します。接続先は、ポータルの共有端末管理で表示された専用URLを入力します。");
        note.setPadding(0, dp(8), 0, dp(16));
        form.addView(note);

        final EditText urlInput = new EditText(this);
        urlInput.setHint("接続先URL（https://…/s/…）");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        form.addView(urlInput);

        final EditText pinInput = new EditText(this);
        pinInput.setHint("管理者PIN（数字" + Config.MIN_PIN_LENGTH + "桁以上）");
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setSingleLine(true);
        form.addView(pinInput);

        final TextView error = new TextView(this);
        error.setTextColor(Color.rgb(0xB0, 0x00, 0x20));
        error.setPadding(0, dp(8), 0, dp(8));
        form.addView(error);

        Button save = new Button(this);
        save.setText("保存して開始");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = urlInput.getText().toString();
                String pin = pinInput.getText().toString();
                String message = Config.validateUrl(url);
                if (message == null) {
                    message = Config.validatePin(pin);
                }
                if (message != null) {
                    error.setText(message);
                    return;
                }
                Config.save(MainActivity.this, url, pin);
                render();
                KioskPolicy.apply(MainActivity.this);
                KioskPolicy.startLock(MainActivity.this);
            }
        });
        form.addView(save);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        root.addView(form, lp);
    }

    private void showWeb() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new KioskWebViewClient());
        web.setWebChromeClient(new KioskChromeClient());
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 左下の見えない領域：管理者用（素早く7回タップ）
        View hotspot = new View(this);
        hotspot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAdminTap();
            }
        });
        root.addView(hotspot, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM | Gravity.START));

        errorShown = false;
        web.loadUrl(Config.url(this));
    }

    // ---- 管理者操作 ----

    private void onAdminTap() {
        long now = SystemClock.elapsedRealtime();
        if (adminTaps == 0 || now - firstTapAt > ADMIN_TAP_WINDOW_MS) {
            adminTaps = 0;
            firstTapAt = now;
        }
        adminTaps++;
        if (adminTaps >= ADMIN_TAPS) {
            adminTaps = 0;
            askAdminPin();
        }
    }

    private void askAdminPin() {
        if (SystemClock.elapsedRealtime() < lockedUntil) {
            Toast.makeText(this, "しばらくしてから、もう一度操作してください。", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("管理者PIN");
        new AlertDialog.Builder(this)
                .setTitle("管理者メニュー")
                .setView(input)
                .setPositiveButton("開く", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (Config.verifyPin(MainActivity.this, input.getText().toString())) {
                            pinFailures = 0;
                            showAdminMenu();
                            return;
                        }
                        pinFailures++;
                        if (pinFailures >= MAX_PIN_FAILURES) {
                            pinFailures = 0;
                            lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS;
                        }
                        Toast.makeText(MainActivity.this, "PINが違います。", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showAdminMenu() {
        final String[] items = {"接続先とPINを設定し直す", "再読み込み", "キオスクを終了（デバイスオーナー解除）"};
        new AlertDialog.Builder(this)
                .setTitle("管理者メニュー")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            Config.clear(MainActivity.this);
                            render();
                        } else if (which == 1) {
                            errorShown = false;
                            if (web != null) {
                                web.loadUrl(Config.url(MainActivity.this));
                            }
                        } else {
                            confirmRelease();
                        }
                    }
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void confirmRelease() {
        new AlertDialog.Builder(this)
                .setTitle("キオスクを終了しますか")
                .setMessage("固定表示とデバイスオーナーを解除します。解除後は、通常の端末として使えます。")
                .setPositiveButton("終了する", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        KioskPolicy.release(MainActivity.this);
                        Config.clear(MainActivity.this);
                        render();
                        Toast.makeText(MainActivity.this, "キオスクを終了しました。", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ---- WebView ----

    private boolean isAllowedUri(Uri uri) {
        String host = Config.allowedHost(this);
        return host != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && host.equalsIgnoreCase(uri.getHost());
    }

    private void showOfflinePage() {
        errorShown = true;
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;text-align:center;padding:48px 16px;color:#333'>"
                + "<h2>接続できません</h2><p>ネットワークを確認しています。つながると自動で表示します。</p></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_MS);
    }

    private final class KioskWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // 設定した接続先のホスト以外は開かない
            return !isAllowedUri(request.getUrl());
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                showOfflinePage();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                showOfflinePage();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!errorShown) {
                handler.removeCallbacks(retry);
            }
        }
    }

    private final class KioskChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // カメラのみ、設定した接続先のページにだけ許可する。マイク等は許可しない。
                    boolean cameraOnly = request.getResources().length > 0;
                    for (String r : request.getResources()) {
                        if (!PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            cameraOnly = false;
                        }
                    }
                    if (!cameraOnly || !isAllowedUri(request.getOrigin())) {
                        request.deny();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        pendingCameraRequest = request;
                        requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                FileChooserParams fileChooserParams) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            try {
                startActivityForResult(fileChooserParams.createIntent(), REQ_FILE);
            } catch (Exception e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA || pendingCameraRequest == null) {
            return;
        }
        PermissionRequest request = pendingCameraRequest;
        pendingCameraRequest = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            request.grant(request.getResources());
        } else {
            request.deny();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
