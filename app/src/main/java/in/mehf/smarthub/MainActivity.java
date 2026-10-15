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
    private boolean ttsReady = false;
    private String lastRingingCallRoom = "";

    // Start directly on Login/Smart Hub Entry (Redirects to Dashboard after login)
    private static final String LIVE_MEHF_START_URL = "https://mehf.in/chat.html";
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MehFoundation::BackgroundWakeLock");
            wakeLock.acquire();
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        createNotificationChannels();
        requestRequiredRuntimePermissions();

        // Initialize Hindi/Indian English Voice Announcement Engine (TTS)
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int res = tts.setLanguage(new Locale("hi", "IN"));
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                tts.setSpeechRate(0.95f);
                ttsReady = true;
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
        // Mobile-friendly responsive viewport settings (Fixes giant desktop zoom on mobile)
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
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
                injectMobileResponsiveAndAuthGuardScript(view);
                injectGlobalRealtimeMonitorScript(view);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.startsWith("https://mehf.in")) {
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

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
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
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                }
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

    // FIXED: Prevents infinite reload loop when an incoming call brings Activity to front!
    private void handleLaunchIntent(Intent intent) {
        String currentUrl = webView.getUrl();
        if (intent != null && intent.getStringExtra("target_url") != null) {
            String target = intent.getStringExtra("target_url");
            intent.removeExtra("target_url");
            // Only load if we are NOT already on chat.html (Stops refreshing while call is ringing!)
            if (currentUrl == null || !currentUrl.contains("chat.html")) {
                webView.loadUrl(target);
            }
        } else if (currentUrl == null) {
            webView.loadUrl(LIVE_MEHF_START_URL);
        }
    }

    private void speakAnnouncement(String text) {
        try {
            if (tts != null && ttsReady && text != null && !text.isEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts.speak(text, TextToSpeech.QUEUE_ADD, null, "mehf_tts_" + System.currentTimeMillis());
                } else {
                    tts.speak(text, TextToSpeech.QUEUE_ADD, null);
                }
            }
        } catch (Exception ignored) {}
    }

    // 1. Enforces Mobile-Friendly UI on ALL website pages + Syncs Login across Dashboard & Chat + Blocks unauthenticated access
    private void injectMobileResponsiveAndAuthGuardScript(WebView view) {
        String js = "(function() {" +
                // A. Ensure proper mobile viewport meta tag on every page
                "var vp = document.querySelector('meta[name=\"viewport\"]');" +
                "if (!vp) {" +
                "  vp = document.createElement('meta');" +
                "  vp.name = 'viewport';" +
                "  document.head.appendChild(vp);" +
                "}" +
                "vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';" +
                // B. Inject Global Mobile-Friendly CSS fix so tables/dashboards never overflow horizontally
                "if (!document.getElementById('mehf-mobile-responsive-fix')) {" +
                "  var st = document.createElement('style');" +
                "  st.id = 'mehf-mobile-responsive-fix';" +
                "  st.innerHTML = 'html, body { max-width: 100vw !important; overflow-x: hidden !important; } " +
                "  img, video, canvas, iframe { max-width: 100% !important; height: auto; } " +
                "  @media (max-width: 768px) { " +
                "    table { display: block; width: 100% !important; overflow-x: auto !important; -webkit-overflow-scrolling: touch; } " +
                "    .container, .main-content, .dashboard-container, .wrapper, .content { width: 100% !important; max-width: 100vw !important; padding-left: 10px !important; padding-right: 10px !important; margin-left: 0 !important; box-sizing: border-box !important; } " +
                "  }';" +
                "  document.head.appendChild(st);" +
                "}" +
                // C. Universal Session Sync: Sync login keys between Dashboard pages and chat.html
                "try {" +
                "  var activeId = localStorage.getItem('mehf_active_account_doc_id_v2') || localStorage.getItem('mehf_user_doc_id') || localStorage.getItem('userId') || localStorage.getItem('username') || sessionStorage.getItem('mehf_active_doc_id');" +
                "  var activeRole = localStorage.getItem('mehf_user_role') || localStorage.getItem('role') || localStorage.getItem('userRole') || sessionStorage.getItem('mehf_active_role');" +
                "  if (!activeId) {" +
                "    var rawUser = localStorage.getItem('currentUser') || localStorage.getItem('loggedInUser') || localStorage.getItem('user') || sessionStorage.getItem('currentUser');" +
                "    if (rawUser) {" +
                "      try {" +
                "        var parsed = JSON.parse(rawUser);" +
                "        activeId = parsed.docId || parsed.id || parsed.uid || parsed.username;" +
                "        activeRole = activeRole || parsed.role;" +
                "      } catch(e){}" +
                "    }" +
                "  }" +
                "  if (activeId) {" +
                "    localStorage.setItem('mehf_active_account_doc_id_v2', activeId);" +
                "    localStorage.setItem('mehf_user_doc_id', activeId);" +
                "    localStorage.setItem('username', activeId);" +
                "    sessionStorage.setItem('mehf_active_doc_id', activeId);" +
                "    if (activeRole) {" +
                "      localStorage.setItem('mehf_user_role', activeRole);" +
                "      localStorage.setItem('role', activeRole);" +
                "      sessionStorage.setItem('mehf_active_role', activeRole);" +
                "    }" +
                "  } else {" +
                "    var path = window.location.pathname.toLowerCase();" +
                "    if (path.indexOf('chat.html') === -1 && path.indexOf('login') === -1) {" +
                "      window.location.replace('https://mehf.in/chat.html');" +
                "    }" +
                "  }" +
                "} catch(e){}" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    // 2. Global Real-Time Monitor on ANY page for Attendance, Fee, Result, Admit Card & Calls
    private void injectGlobalRealtimeMonitorScript(WebView view) {
        String js = "(function() {" +
                "if (window.__mehfGlobalNativeMonitorActive) return;" +
                "window.__mehfGlobalNativeMonitorActive = true;" +
                "if (window.location.pathname.indexOf('chat.html') !== -1) return;" +
                "try {" +
                "  import('/firebase-config.js').then(async (mod) => {" +
                "    if (!mod || !mod.db) return;" +
                "    const { doc, onSnapshot } = await import('https://www.gstatic.com/firebasejs/10.8.0/firebase-firestore.js');" +
                "    let accs = [];" +
                "    try { accs = JSON.parse(localStorage.getItem('mehf_persistent_accounts_v2') || '[]'); } catch(e){}" +
                "    const singleId = localStorage.getItem('mehf_active_account_doc_id_v2') || localStorage.getItem('mehf_user_doc_id');" +
                "    if (singleId && !accs.some(a => a.docId === singleId)) accs.push({ docId: singleId, name: 'User' });" +
                "    accs.forEach(acc => {" +
                "      let prev = null;" +
                "      onSnapshot(doc(mod.db, 'users', acc.docId), (snap) => {" +
                "        if (!snap.exists()) return;" +
                "        const cur = snap.data();" +
                "        if (prev && window.AndroidAppBridge) {" +
                "          if (cur.attendanceStatus !== prev.attendanceStatus || cur.lastAttendanceDate !== prev.lastAttendanceDate) {" +
                "            window.AndroidAppBridge.showSystemNotification((cur.name || acc.docId) + ' की हाजिरी अपडेट हुई है', 'आज की स्थिति: ' + (cur.attendanceStatus || 'Updated'));" +
                "          }" +
                "          if (cur.paidFee !== prev.paidFee || cur.dueFee !== prev.dueFee) {" +
                "            window.AndroidAppBridge.showSystemNotification((cur.name || acc.docId) + ' का फीस रिकॉर्ड अपडेट हुआ है', 'कृपया डैशबोर्ड में रसीद देखें');" +
                "          }" +
                "          if (cur.resultPublished !== prev.resultPublished || cur.marksUpdated !== prev.marksUpdated) {" +
                "            window.AndroidAppBridge.showSystemNotification((cur.name || acc.docId) + ' का परीक्षा परिणाम जारी हो गया है', 'अभी अपना रिजल्ट देखें');" +
                "          }" +
                "          if (cur.admitCardGenerated !== prev.admitCardGenerated || cur.admitCardUrl !== prev.admitCardUrl) {" +
                "            window.AndroidAppBridge.showSystemNotification((cur.name || acc.docId) + ' का एडमिट कार्ड जारी हो गया है', 'अभी डाउनलोड करें');" +
                "          }" +
                "        }" +
                "        prev = Object.assign({}, cur);" +
                "      });" +
                "      let lastHandledRoom = '';" +
                "      onSnapshot(doc(mod.db, 'calls', acc.docId), (cSnap) => {" +
                "        const c = cSnap.data();" +
                "        if (c && (c.type === 'call_offer' || c.type === 'conference_invite') && c.roomName !== lastHandledRoom && (Date.now() - (c.timestamp || 0) < 45000)) {" +
                "          lastHandledRoom = c.roomName || 'room';" +
                "          if (window.AndroidAppBridge) {" +
                "            window.AndroidAppBridge.triggerIncomingCallRing((c.callerName || c.callerId) + ' का कॉल आ रहा है', 'कॉल उठाने के लिए टैप करें');" +
                "          }" +
                "          window.location.href = 'https://mehf.in/chat.html';" +
                "        }" +
                "      });" +
                "    });" +
                "  }).catch(()=>{});" +
                "} catch(e){}" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel callChannel = new NotificationChannel(
                    CALL_CHANNEL_ID,
                    "Incoming Calls & Live Conferences",
                    NotificationManager.IMPORTANCE_HIGH
            );
            callChannel.setDescription("Rings & Speaks even when screen is locked");
            callChannel.enableVibration(true);
            callChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(callChannel);

            NotificationChannel alertChannel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Attendance, Fee, Result & Admit Card Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
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
        public void speakVoiceAlert(String speechText) {
            runOnUiThread(() -> speakAnnouncement(speechText));
        }

        @JavascriptInterface
        public void triggerIncomingCallRing(String callerTitle, String subtitle) {
            runOnUiThread(() -> {
                try {
                    // Prevent duplicate re-triggering for the same call title while already ringing
                    if (activeRingtone != null && activeRingtone.isPlaying() && callerTitle != null && callerTitle.equals(lastRingingCallRoom)) {
                        return;
                    }
                    lastRingingCallRoom = callerTitle != null ? callerTitle : "";

                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        PowerManager.WakeLock screenLock = pm.newWakeLock(
                                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                                "MehFoundation::IncomingCallScreenWake"
                        );
                        screenLock.acquire(35000);
                    }

                    // Bring Activity to front WITHOUT passing target_url if already on chat.html (Prevents Page Refresh Loop!)
                    Intent bringToFront = new Intent(MainActivity.this, MainActivity.class);
                    String curUrl = webView != null ? webView.getUrl() : null;
                    if (curUrl == null || !curUrl.contains("chat.html")) {
                        bringToFront.putExtra("target_url", LIVE_MEHF_CHAT_URL);
                    }
                    bringToFront.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(bringToFront);

                    if (activeRingtone != null && activeRingtone.isPlaying()) {
                        activeRingtone.stop();
                    }
                    Uri ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                    activeRingtone = RingtoneManager.getRingtone(getApplicationContext(), ringUri);
                    if (activeRingtone != null) {
                        activeRingtone.play();
                    }
                    if (vibrator != null) {
                        long[] pattern = {0, 800, 400, 800, 400, 800};
                        vibrator.vibrate(pattern, 0);
                    }

                    // Announce Caller Name via Voice TTS
                    speakAnnouncement("मेह फाउंडेशन अलर्ट: " + callerTitle + "। कृपया कॉल रिसीव करें।");

                    showHighPriorityNotification(callerTitle, subtitle, 9001, CALL_CHANNEL_ID, null);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopIncomingCallRing() {
            runOnUiThread(() -> {
                try {
                    lastRingingCallRoom = "";
                    if (activeRingtone != null && activeRingtone.isPlaying()) {
                        activeRingtone.stop();
                    }
                    if (vibrator != null) {
                        vibrator.cancel();
                    }
                    if (tts != null) {
                        tts.stop();
                    }
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(9001);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void showSystemNotification(String title, String message) {
            runOnUiThread(() -> {
                int id = (int) (System.currentTimeMillis() % 100000);
                speakAnnouncement("मेह फाउंडेशन सूचना: " + title + "। " + message);
                showHighPriorityNotification(title, message, id, ALERT_CHANNEL_ID, null);
            });
        }
    }

    private void showHighPriorityNotification(String title, String body, int notifId, String channelId, String targetUrl) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent intent = new Intent(this, MainActivity.class);
            if (targetUrl != null) {
                intent.putExtra("target_url", targetUrl);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(this, notifId, intent, flags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, channelId);
            } else {
                builder = new Notification.Builder(this);
                builder.setPriority(Notification.PRIORITY_MAX);
            }

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
            String[] permissions = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.POST_NOTIFICATIONS
            };
            requestPermissions(permissions, 101);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
