package com.ucnmqz.callsimulator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity implements Runnable {

    private WebView webView;
    private Vibrator vibrator;
    private Handler mainHandler;
    private PowerManager.WakeLock bgWakeLock;
    private volatile boolean isCallScreenImmersive = false;
    private volatile boolean pendingWakeUpCall = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper());
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
            window.setStatusBarColor(Color.parseColor("#090D16"));
            window.setNavigationBarColor(Color.parseColor("#090D16"));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#090D16"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#090D16"));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
        webView.loadUrl("file:///android_asset/index.html");
    }

    public void applyImmersiveMode(boolean immersive) {
        isCallScreenImmersive = immersive;
        pendingWakeUpCall = false;
        mainHandler.post(this);
    }

    @Override
    public void run() {
        if (pendingWakeUpCall) {
            pendingWakeUpCall = false;
            bringActivityToFrontAndRing();
            return;
        }
        Window window = getWindow();
        if (window == null) return;
        View decorView = window.getDecorView();
        if (isCallScreenImmersive) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        } else {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @SuppressLint("WakelockTimeout")
    public void scheduleBackgroundWakeUp(int seconds) {
        cancelBackgroundWakeUp();
        int delayMs = Math.max(1, seconds) * 1000;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                bgWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UCNCallSim::BgTimer");
                bgWakeLock.acquire(delayMs + 10000L);
            }
        } catch (Exception ignored) {
        }

        pendingWakeUpCall = true;
        mainHandler.postDelayed(this, delayMs);

        // 将本应用退回后台，让用户在手机前台正常刷其他软件或停留在手机真桌面
        moveTaskToBack(true);
    }

    public void cancelBackgroundWakeUp() {
        pendingWakeUpCall = false;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(this);
        }
        releaseBgWakeLock();
    }

    private void releaseBgWakeLock() {
        try {
            if (bgWakeLock != null && bgWakeLock.isHeld()) {
                bgWakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private void bringActivityToFrontAndRing() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock screenLock = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "UCNCallSim::ScreenWake"
                );
                screenLock.acquire(5000L);
            }
        } catch (Exception ignored) {
        }

        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(getTaskId(), ActivityManager.MOVE_TASK_WITH_HOME);
            }
        } catch (Exception ignored) {
        }

        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception ignored) {
        }

        if (webView != null) {
            webView.evaluateJavascript("window.onAndroidBackgroundAlarmFired && window.onAndroidBackgroundAlarmFired()", null);
        }
        releaseBgWakeLock();
    }

    public void openBrowserUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && isCallScreenImmersive) {
            applyImmersiveMode(true);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView != null) {
                webView.evaluateJavascript("window.handleAndroidBackKey && window.handleAndroidBackKey()", null);
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            stopNativeVibrate();
        }
        return super.onKeyDown(keyCode, event);
    }

    public void stopNativeVibrate() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.cancel();
            }
        } catch (Exception ignored) {
        }
    }

    public void startPatternVibrate(boolean loop) {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = new long[]{0, 650, 550, 650, 550};
            int repeat = loop ? 0 : -1;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, repeat));
            } else {
                vibrator.vibrate(pattern, repeat);
            }
        } catch (Exception ignored) {
        }
    }

    public void startOneShotVibrate(int ms) {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            int duration = Math.max(15, Math.min(ms, 500));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(duration);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        cancelBackgroundWakeUp();
        stopNativeVibrate();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    public static class AndroidBridge {
        private final MainActivity activity;

        public AndroidBridge(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void vibratePattern(boolean loop) {
            activity.startPatternVibrate(loop);
        }

        @JavascriptInterface
        public void vibrateOnce(int ms) {
            activity.startOneShotVibrate(ms);
        }

        @JavascriptInterface
        public void stopVibrate() {
            activity.stopNativeVibrate();
        }

        @JavascriptInterface
        public void setImmersive(boolean immersive) {
            activity.applyImmersiveMode(immersive);
        }

        @JavascriptInterface
        public void scheduleBackgroundCall(int seconds) {
            activity.scheduleBackgroundWakeUp(seconds);
        }

        @JavascriptInterface
        public void cancelBackgroundCall() {
            activity.cancelBackgroundWakeUp();
        }

        @JavascriptInterface
        public void openExternalUrl(String url) {
            activity.openBrowserUrl(url);
        }

        @JavascriptInterface
        public void exitApp() {
            activity.finish();
        }
    }
}
