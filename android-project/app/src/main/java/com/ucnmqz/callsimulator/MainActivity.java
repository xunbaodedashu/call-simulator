package com.ucnmqz.callsimulator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
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

public class MainActivity extends Activity implements Handler.Callback {

    private static final String CALL_CHANNEL_ID = "ucn_incoming_call_channel";
    private static final int CALL_NOTIF_ID = 10086;

    private static final int MSG_APPLY_IMMERSIVE = 1;
    private static final int MSG_SCHEDULE_BG_CALL = 2;
    private static final int MSG_WAKE_AND_RING = 3;
    private static final int MSG_CLEANUP_AFTER_RING = 4;

    private WebView webView;
    private Vibrator vibrator;
    private Ringtone systemRingtone;
    private Handler mainHandler;
    private PowerManager.WakeLock bgWakeLock;
    private View keepAliveOverlayView;
    private volatile boolean isCallScreenImmersive = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper(), this);
        createCallNotificationChannel();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("from_bg_alarm", false)) {
            if (webView != null) {
                webView.evaluateJavascript("window.onAndroidBackgroundAlarmFired && window.onAndroidBackgroundAlarmFired()", null);
            }
        }
    }

    private void createCallNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(
                            CALL_CHANNEL_ID,
                            "模拟来电全屏唤醒",
                            NotificationManager.IMPORTANCE_HIGH
                    );
                    ch.setDescription("用于在后台定时到点时弹出全屏模拟来电界面");
                    ch.enableVibration(true);
                    ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                    nm.createNotificationChannel(ch);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public boolean checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    public void openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
            }
        }
    }

    private void attachInvisibleOverlayAnchor() {
        if (!checkOverlayPermission() || keepAliveOverlayView != null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            View v = new View(this);
            v.setBackgroundColor(Color.TRANSPARENT);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    1,
                    1,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;
            wm.addView(v, params);
            keepAliveOverlayView = v;
        } catch (Exception ignored) {
        }
    }

    private void detachInvisibleOverlayAnchor() {
        if (keepAliveOverlayView == null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.removeViewImmediate(keepAliveOverlayView);
            }
        } catch (Exception ignored) {
        }
        keepAliveOverlayView = null;
    }

    public void applyImmersiveMode(boolean immersive) {
        isCallScreenImmersive = immersive;
        if (mainHandler != null) {
            mainHandler.sendEmptyMessage(MSG_APPLY_IMMERSIVE);
        }
    }

    public void scheduleBackgroundWakeUp(int seconds) {
        if (mainHandler != null) {
            Message msg = mainHandler.obtainMessage(MSG_SCHEDULE_BG_CALL, seconds, 0);
            mainHandler.sendMessage(msg);
        }
    }

    @SuppressLint("WakelockTimeout")
    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_APPLY_IMMERSIVE: {
                Window window = getWindow();
                if (window != null) {
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
                return true;
            }
            case MSG_SCHEDULE_BG_CALL: {
                cancelBackgroundWakeUp();
                int delayMs = Math.max(1, msg.arg1) * 1000;
                try {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        bgWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UCNCallSim::BgTimer");
                        bgWakeLock.acquire(delayMs + 15000L);
                    }
                } catch (Exception ignored) {
                }

                attachInvisibleOverlayAnchor();
                mainHandler.sendEmptyMessageDelayed(MSG_WAKE_AND_RING, delayMs);
                moveTaskToBack(true);
                return true;
            }
            case MSG_WAKE_AND_RING: {
                bringActivityToFrontAndRing();
                return true;
            }
            case MSG_CLEANUP_AFTER_RING: {
                detachInvisibleOverlayAnchor();
                cancelCallNotification();
                releaseBgWakeLock();
                return true;
            }
            default:
                return false;
        }
    }

    public void cancelBackgroundWakeUp() {
        if (mainHandler != null) {
            mainHandler.removeMessages(MSG_WAKE_AND_RING);
            mainHandler.removeMessages(MSG_CLEANUP_AFTER_RING);
        }
        detachInvisibleOverlayAnchor();
        cancelCallNotification();
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

    @SuppressWarnings("deprecation")
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
                screenLock.acquire(8000L);
            }
        } catch (Exception ignored) {
        }

        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        launchIntent.putExtra("from_bg_alarm", true);

        try {
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent fullScreenPi = PendingIntent.getActivity(this, 1, launchIntent, piFlags);

            Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CALL_CHANNEL_ID)
                    : new Notification.Builder(this);
            nb.setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle("📞 来电提醒")
                    .setContentText("点击立即返回全屏来电界面")
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setContentIntent(fullScreenPi)
                    .setFullScreenIntent(fullScreenPi, true);

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(CALL_NOTIF_ID, nb.build());
            }
            fullScreenPi.send();
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
            startActivity(launchIntent);
        } catch (Exception ignored) {
        }

        if (webView != null) {
            webView.evaluateJavascript("window.onAndroidBackgroundAlarmFired && window.onAndroidBackgroundAlarmFired()", null);
        }

        if (mainHandler != null) {
            mainHandler.sendEmptyMessageDelayed(MSG_CLEANUP_AFTER_RING, 2500L);
        }
    }

    private void cancelCallNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(CALL_NOTIF_ID);
            }
        } catch (Exception ignored) {
        }
    }

    public void playNativeRingtone() {
        try {
            stopNativeRingtone();
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri != null) {
                systemRingtone = RingtoneManager.getRingtone(getApplicationContext(), uri);
                if (systemRingtone != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        systemRingtone.setLooping(true);
                    }
                    systemRingtone.play();
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void stopNativeRingtone() {
        try {
            if (systemRingtone != null && systemRingtone.isPlaying()) {
                systemRingtone.stop();
            }
        } catch (Exception ignored) {
        }
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
        if (hasFocus) {
            cancelCallNotification();
            if (isCallScreenImmersive) {
                applyImmersiveMode(true);
            }
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
            stopNativeRingtone();
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
        stopNativeRingtone();
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
        public boolean hasOverlayPermission() {
            return activity.checkOverlayPermission();
        }

        @JavascriptInterface
        public void requestOverlayPermission() {
            activity.openOverlayPermissionSettings();
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
