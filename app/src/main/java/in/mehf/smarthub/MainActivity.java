package in.mehf.smarthub;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsManager;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private Ringtone activeRingtone;
    private Vibrator vibrator;
    private TextToSpeech tts;

    private static final String LIVE_MEHF_WEBSITE_URL = "https://mehf.in";
    private static final String LIVE_MEHF_CHAT_URL = "https://mehf.in/chat.html";
    private static final String CALL_CHANNEL_ID = "mehf_incoming_calls_channel";
    private static final String ALERT_CHANNEL_ID = "mehf_activity_alerts_channel";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MehFoundation::BackgroundSmsAndCallWakeLock");
            wakeLock.acquire();
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        createNotificationChannels();
        requestRequiredRuntimePermissions();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("hi", "IN"));
            }
        });

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new NativeSmsGatewayBridge(), "AndroidSmsBridge");
        webView.addJavascriptInterface(new NativeAppNotificationBridge(), "AndroidAppBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                injectGlobalRealtimeAndSmsGatewayMonitor(view);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.equals(LIVE_MEHF_WEBSITE_URL)) {
                    view.loadUrl("file:///android_asset/chat.html");
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                request.addRequestHeader("cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("Downloading file from Meh foundation...");
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setTitle(fileName);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) dm.enqueue(request);
            } catch (Exception ignored) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        });

        handleLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent != null && intent.getStringExtra("target_url") != null) {
            webView.loadUrl(intent.getStringExtra("target_url"));
        } else if (webView.getUrl() == null) {
            webView.loadUrl(LIVE_MEHF_WEBSITE_URL);
        }
    }

    // Runs in background across ALL pages of mehf.in:
    // 1. Dispatches pending SMS from `sms_queue` & `sms_logs` via SIM automatically for School/Admin
    // 2. Monitors incoming calls & user updates (Attendance, Fee, Result, Admit Card)
    private void injectGlobalRealtimeAndSmsGatewayMonitor(WebView view) {
        String js = "(function() {" +
                "if (window.__mehfGlobalNativeMonitorActive) return;" +
                "window.__mehfGlobalNativeMonitorActive = true;" +
                "if (window.location.pathname.indexOf('chat.html') !== -1) return;" +
                "try {" +
                "  import('/firebase-config.js').then(async (mod) => {" +
                "    if (!mod || !mod.db) return;" +
                "    const { doc, collection, onSnapshot, updateDoc } = await import('https://www.gstatic.com/firebasejs/10.8.0/firebase-firestore.js');" +
                "    const activeId = localStorage.getItem('mehf_active_account_doc_id_v2') || localStorage.getItem('mehf_user_doc_id') || localStorage.getItem('userDocId') || localStorage.getItem('username');" +
                "    const activeRole = (localStorage.getItem('mehf_user_role') || localStorage.getItem('role') || '').toLowerCase();" +
                "    const activeSchool = localStorage.getItem('mehf_school_id') || 'NA';" +
                "    if (activeId) {" +
                "      const cleanId = String(activeId).replace(/^\"|\"$/g, '').split('@')[0].trim();" +
                "      onSnapshot(doc(mod.db, 'calls', cleanId), (cSnap) => {" +
                "        const c = cSnap.data();" +
                "        if (c && (c.type === 'call_offer' || c.type === 'conference_invite') && (Date.now() - (c.timestamp || 0) < 50000)) {" +
                "          if (window.AndroidAppBridge) {" +
                "            window.AndroidAppBridge.triggerIncomingCallRing('Incoming Call: ' + (c.callerName || c.callerId), 'Tap to answer call');" +
                "          }" +
                "          window.location.href = '/chat.html?uid=' + encodeURIComponent(cleanId);" +
                "        }" +
                "      });" +
                "    }" +
                "    if ((activeRole === 'school' || activeRole === 'admin') && window.AndroidSmsBridge) {" +
                "      const sentSet = new Set();" +
                "      const watchSmsCol = (colName) => {" +
                "        onSnapshot(collection(mod.db, colName), (snap) => {" +
                "          snap.forEach((d) => {" +
                "            const item = d.data();" +
                "            const st = String(item.status || 'pending').toLowerCase();" +
                "            const sId = item.schoolId || item.schoolUsername || 'NA';" +
                "            if ((st === 'pending' || st === 'queued') && !sentSet.has(d.id)) {" +
                "              if (activeRole === 'admin' || sId === 'NA' || sId === activeSchool) {" +
                "                const phone = String(item.phone || item.mobile || item.phoneNumber || '').trim();" +
                "                const msg = String(item.message || item.text || '').trim();" +
                "                if (phone && msg) {" +
                "                  sentSet.add(d.id);" +
                "                  const res = window.AndroidSmsBridge.sendDirectSms(phone, msg);" +
                "                  updateDoc(doc(mod.db, colName, d.id), { status: String(res).startsWith('SENT') ? 'sent' : 'failed', sentAt: Date.now() }).catch(()=>{});" +
                "                }" +
                "              }" +
                "            }" +
                "          });" +
                "        });" +
                "      };" +
                "      watchSmsCol('sms_queue');" +
                "      watchSmsCol('sms_logs');" +
                "    }" +
                "  }).catch(()=>{});" +
                "} catch(e){}" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel callChannel = new NotificationChannel(CALL_CHANNEL_ID, "Incoming Calls & Conferences", NotificationManager.IMPORTANCE_HIGH);
            callChannel.enableVibration(true);
            callChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(callChannel);

            NotificationChannel alertChannel = new NotificationChannel(ALERT_CHANNEL_ID, "School & SMS Alerts", NotificationManager.IMPORTANCE_HIGH);
            alertChannel.enableVibration(true);
            alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(alertChannel);
        }
    }

    public class NativeAppNotificationBridge {

        @JavascriptInterface
        public void enableCallAudioMode(boolean active) {
            runOnUiThread(() -> {
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am == null) return;
                    if (active) {
                        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        am.setSpeakerphoneOn(true);
                        am.setMicrophoneMute(false);
                        int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0);
                    } else {
                        am.setSpeakerphoneOn(false);
                        am.setMode(AudioManager.MODE_NORMAL);
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void triggerIncomingCallRing(String callerTitle, String subtitle) {
            runOnUiThread(() -> {
                try {
                    if (tts != null) {
                        tts.speak("कॉल आ रही है: " + callerTitle, TextToSpeech.QUEUE_FLUSH, null, null);
                    }
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        PowerManager.WakeLock screenLock = pm.newWakeLock(
                                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                                "MehFoundation::IncomingCallScreenWake"
                        );
                        screenLock.acquire(35000);
                    }

                    Intent bringToFront = new Intent(MainActivity.this, MainActivity.class);
                    bringToFront.putExtra("target_url", LIVE_MEHF_CHAT_URL);
                    bringToFront.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(bringToFront);

                    stopIncomingCallRing();
                    Uri ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                    activeRingtone = RingtoneManager.getRingtone(getApplicationContext(), ringUri);
                    if (activeRingtone != null) activeRingtone.play();
                    if (vibrator != null) vibrator.vibrate(new long[]{0, 800, 400, 800}, 0);

                    showHighPriorityNotification(callerTitle, subtitle, 9001, CALL_CHANNEL_ID, LIVE_MEHF_CHAT_URL);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopIncomingCallRing() {
            runOnUiThread(() -> {
                try {
                    if (activeRingtone != null && activeRingtone.isPlaying()) activeRingtone.stop();
                    if (vibrator != null) vibrator.cancel();
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(9001);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void showSystemNotification(String title, String message) {
            runOnUiThread(() -> {
                if (tts != null) tts.speak(title, TextToSpeech.QUEUE_ADD, null, null);
                int id = (int) (System.currentTimeMillis() % 100000);
                showHighPriorityNotification(title, message, id, ALERT_CHANNEL_ID, null);
            });
        }
    }

    private void showHighPriorityNotification(String title, String body, int notifId, String channelId, String targetUrl) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent intent = new Intent(this, MainActivity.class);
            if (targetUrl != null) intent.putExtra("target_url", targetUrl);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pendingIntent = PendingIntent.getActivity(this, notifId, intent, flags);

            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, channelId)
                    : new Notification.Builder(this).setPriority(Notification.PRIORITY_MAX);

            builder.setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setFullScreenIntent(pendingIntent, true);

            nm.notify(notifId, builder.build());
        } catch (Exception ignored) {}
    }

    public class NativeSmsGatewayBridge {
        @JavascriptInterface
        public String sendDirectSms(String phoneNumber, String messageBody) {
            try {
                SmsManager smsManager = SmsManager.getDefault();
                ArrayList<String> parts = smsManager.divideMessage(messageBody);
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
                return "SENT";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }
    }

    private void requestRequiredRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.POST_NOTIFICATIONS
            }, 101);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
