package in.mehf.smarthub;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // Static persistent WebView wrapped in MutableContextWrapper so <select> popups & dialogs ALWAYS work!
    private static WebView persistentWebView;
    private static MutableContextWrapper webViewContextWrapper;
    private static PowerManager.WakeLock wakeLock;
    private static Ringtone activeRingtone;
    private static Vibrator vibrator;
    private static TextToSpeech tts;
    private ValueCallback<Uri[]> filePathCallback;

    private static long lastCallRingTriggerTime = 0;

    private static final int FILE_CHOOSER_RESULT_CODE = 202;
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String LIVE_MEHF_START_URL = "https://mehf.in/login.html";
    private static final String LIVE_MEHF_CHAT_URL = "https://mehf.in/chat.html";
    private static final String CALL_CHANNEL_ID = "mehf_incoming_calls_channel";
    private static final String ALERT_CHANNEL_ID = "mehf_activity_alerts_channel";
    private static final String SERVICE_CHANNEL_ID = "mehf_keep_alive_service_channel";

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
        if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MehFoundation::BackgroundWakeLock");
            wakeLock.acquire();
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        createNotificationChannels();
        requestRequiredRuntimePermissions();
        requestBatteryOptimizationExemption();
        startKeepAliveForegroundService();

        if (tts == null) {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(new Locale("hi", "IN"));
                }
            });
        }

        // Reuse existing persistent WebView so state, Firebase listeners & chats stay 100% alive in memory
        // Using MutableContextWrapper bound to 'this' Activity ensures <select> dropdowns & popups ALWAYS open!
        if (persistentWebView == null) {
            webViewContextWrapper = new MutableContextWrapper(this);
            persistentWebView = new WebView(webViewContextWrapper);
            configureWebView(persistentWebView);
            persistentWebView.loadUrl(LIVE_MEHF_START_URL);
        } else {
            if (webViewContextWrapper != null) {
                webViewContextWrapper.setBaseContext(this);
            }
            if (persistentWebView.getParent() instanceof ViewGroup) {
                ((ViewGroup) persistentWebView.getParent()).removeView(persistentWebView);
            }
        }

        attachWebChromeAndDownloadHandlers(persistentWebView);
        setContentView(persistentWebView);
        handleLaunchIntent(getIntent());
    }

    private void configureWebView(WebView wv) {
        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(wv, true);

        wv.addJavascriptInterface(new NativeSmsGatewayBridge(), "AndroidSmsBridge");
        wv.addJavascriptInterface(new NativeAppNotificationBridge(), "AndroidAppBridge");

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                injectMultiAccountDaemonAndSmsMonitor(view);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.equals(LIVE_MEHF_START_URL)) {
                    view.loadUrl("file:///android_asset/chat.html");
                }
            }
        });
    }

    private void attachWebChromeAndDownloadHandlers(WebView wv) {
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try {
                        requestRequiredRuntimePermissions();
                        request.grant(request.getResources());
                    } catch (Exception ignored) {}
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(final String origin, final GeolocationPermissions.Callback callback) {
                runOnUiThread(() -> {
                    requestRequiredRuntimePermissions();
                    callback.invoke(origin, true, false);
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
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
    }

    private void startKeepAliveForegroundService() {
        try {
            Intent serviceIntent = new Intent(this, MehfKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception ignored) {}
    }

    private void requestBatteryOptimizationExemption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getDataString() != null) {
                results = new Uri[]{Uri.parse(data.getDataString())};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(Intent intent) {
        String currentUrl = persistentWebView != null ? persistentWebView.getUrl() : null;
        if (intent != null && intent.getStringExtra("target_url") != null) {
            String target = intent.getStringExtra("target_url");
            intent.removeExtra("target_url");
            if (currentUrl == null || !currentUrl.contains("chat.html")) {
                persistentWebView.loadUrl(target);
            }
        } else if (currentUrl == null) {
            persistentWebView.loadUrl(LIVE_MEHF_START_URL);
        }
    }

        // 3. Simultaneous Multi-Account Realtime Listener (Monitors ALL saved accounts at once!)
    private void injectMultiAccountDaemonAndSmsMonitor(WebView view) {
        String js = "(function() {" +
                "try {" +
                "  const savedRaw = localStorage.getItem('mehf_persistent_accounts_v2');" +
                "  let accs = savedRaw ? JSON.parse(savedRaw) : [];" +
                "  const activeId = localStorage.getItem('mehf_active_account_doc_id_v2') || localStorage.getItem('loggedInUserDocId') || sessionStorage.getItem('loggedInUserDocId');" +
                "  const curId = sessionStorage.getItem('loggedInUserDocId');" +
                "  const curUser = sessionStorage.getItem('loggedInUserUsername');" +
                "  const curRole = sessionStorage.getItem('loggedInUserRole');" +
                "  const curName = sessionStorage.getItem('loggedInUserName');" +
                "  if (curId && curRole) {" +
                "    localStorage.setItem('loggedInUserDocId', curId);" +
                "    localStorage.setItem('loggedInUserUsername', curUser || curId);" +
                "    localStorage.setItem('loggedInUserRole', curRole);" +
                "    localStorage.setItem('loggedInUserName', curName || curId);" +
                "    localStorage.setItem('mehf_active_account_doc_id_v2', curId);" +
                "    if (!accs.some(a => a.docId === curId)) {" +
                "      accs.push({ docId: curId, username: curUser || curId, role: curRole, name: curName || curId, savedAt: Date.now() });" +
                "      localStorage.setItem('mehf_persistent_accounts_v2', JSON.stringify(accs));" +
                "    }" +
                "  } else if (activeId && accs.length > 0) {" +
                "    const found = accs.find(a => a.docId === activeId) || accs[0];" +
                "    if (found) {" +
                "      sessionStorage.setItem('loggedInUserDocId', found.docId);" +
                "      sessionStorage.setItem('loggedInUserUsername', found.username);" +
                "      sessionStorage.setItem('loggedInUserRole', found.role);" +
                "      sessionStorage.setItem('loggedInUserName', found.name);" +
                "    }" +
                "  }" +
                "  const redirects = { admin: '/admin-dashboard.html', school: '/school-dashboard.html', teacher: '/teacher-dashboard.html', student: '/student-dashboard.html', volunteer: '/volunteer-dashboard.html', driver: '/transport-dashboard.html' };" +
                "  if (accs.length > 0 && window.location.pathname.indexOf('login.html') === -1 && !document.getElementById('mehfFloatingAccountSwitcher')) {" +
                "    const bar = document.createElement('div');" +
                "    bar.id = 'mehfFloatingAccountSwitcher';" +
                "    bar.style.cssText = 'position:fixed;bottom:12px;right:12px;z-index:999990;background:rgba(15,23,42,0.95);border:1.5px solid #fbbf24;border-radius:999px;padding:5px 10px;display:flex;align-items:center;gap:6px;box-shadow:0 10px 25px rgba(0,0,0,0.5);backdrop-filter:blur(8px);font-family:sans-serif;';" +
                "    let opts = accs.map(a => `<option value='${a.docId}' ${a.docId === (sessionStorage.getItem('loggedInUserDocId') || activeId) ? 'selected' : ''}>👤 ${a.name} (${a.role.toUpperCase()})</option>`).join('');" +
                "    opts += `<option value='__ADD__'>➕ नया खाता जोड़ें...</option>`;" +
                "    bar.innerHTML = `<span style='width:8px;height:8px;border-radius:50%;background:#22c55e;display:inline-block;'></span>` +" +
                "                    `<select id='mehfGlobalAccSel' style='background:transparent;color:#fde68a;border:none;font-size:11px;font-weight:800;outline:none;max-width:155px;'>${opts}</select>`;" +
                "    document.body.appendChild(bar);" +
                "    document.getElementById('mehfGlobalAccSel').addEventListener('change', function() {" +
                "      if (this.value === '__ADD__') { window.location.href = '/login.html?add=1'; return; }" +
                "      const target = accs.find(a => a.docId === this.value);" +
                "      if (target) {" +
                "        localStorage.setItem('mehf_active_account_doc_id_v2', target.docId);" +
                "        localStorage.setItem('loggedInUserDocId', target.docId);" +
                "        localStorage.setItem('loggedInUserUsername', target.username);" +
                "        localStorage.setItem('loggedInUserRole', target.role);" +
                "        localStorage.setItem('loggedInUserName', target.name);" +
                "        sessionStorage.setItem('loggedInUserDocId', target.docId);" +
                "        sessionStorage.setItem('loggedInUserUsername', target.username);" +
                "        sessionStorage.setItem('loggedInUserRole', target.role);" +
                "        sessionStorage.setItem('loggedInUserName', target.name);" +
                "        window.location.href = redirects[target.role] || '/other-dashboard.html';" +
                "      }" +
                "    });" +
                "  }" +
                "  if (window.__mehfMultiAccountDaemonRunning) return;" +
                "  window.__mehfMultiAccountDaemonRunning = true;" +
                "  import('/firebase-config.js?v=100').then(async (mod) => {" +
                "    if (!mod || !mod.db) return;" +
                "    const { doc, collection, query, where, onSnapshot, updateDoc } = await import('https://www.gstatic.com/firebasejs/10.8.0/firebase-firestore.js');" +
                "    const pageName = (window.location.pathname.split('/').pop() || 'home').replace('.html', '').toUpperCase();" +
                "    const activeDoc = sessionStorage.getItem('loggedInUserDocId') || localStorage.getItem('loggedInUserDocId');" +
                "    if (activeDoc) {" +
                "      updateDoc(doc(mod.db, 'users', activeDoc), { isUsingApp: true, isOnline: true, lastSeen: Date.now(), lastAppActive: Date.now(), lastFeatureUsed: 'Android App (' + pageName + ')' }).catch(()=>{});" +
                "    }" +
                "    const handledCallRooms = new Set();" +
                "    accs.forEach(acc => {" +
                "      let prevUserSnap = null;" +
                "      onSnapshot(doc(mod.db, 'users', acc.docId), (snap) => {" +
                "        if (!snap.exists()) return;" +
                "        const cur = snap.data();" +
                "        const label = (cur.name || acc.name || acc.username) + ' (' + (acc.role || '').toUpperCase() + ')';" +
                "        if (prevUserSnap && window.AndroidAppBridge) {" +
                "          if (cur.attendanceStatus !== prevUserSnap.attendanceStatus || cur.lastAttendanceDate !== prevUserSnap.lastAttendanceDate) {" +
                "            window.AndroidAppBridge.showSystemNotification('हाजिरी अपडेट: ' + label, 'आज की उपस्थिति स्थिति: ' + (cur.attendanceStatus || 'Updated'));" +
                "          }" +
                "          if (cur.paidFee !== prevUserSnap.paidFee || cur.dueFee !== prevUserSnap.dueFee) {" +
                "            window.AndroidAppBridge.showSystemNotification('फीस अपडेट: ' + label, 'फीस खाते में नया अपडेट हुआ है।');" +
                "          }" +
                "          if (cur.resultPublished !== prevUserSnap.resultPublished || cur.marksUpdated !== prevUserSnap.marksUpdated) {" +
                "            window.AndroidAppBridge.showSystemNotification('रिजल्ट जारी: ' + label, 'परीक्षा परिणाम अपडेट किया गया है।');" +
                "          }" +
                "          if (cur.admitCardGenerated !== prevUserSnap.admitCardGenerated || cur.admitCardUrl !== prevUserSnap.admitCardUrl) {" +
                "            window.AndroidAppBridge.showSystemNotification('एडमिट कार्ड जारी: ' + label, 'आपका एडमिट कार्ड तैयार है।');" +
                "          }" +
                "        }" +
                "        prevUserSnap = Object.assign({}, cur);" +
                "      });" +
                "      let chatInitLoaded = false;" +
                "      onSnapshot(query(collection(mod.db, 'chats'), where('receiverId', '==', acc.docId)), (cSnap) => {" +
                "        if (!chatInitLoaded) { chatInitLoaded = true; return; }" +
                "        cSnap.docChanges().forEach(change => {" +
                "          if (change.type === 'added') {" +
                "            const msg = change.doc.data();" +
                "            if (msg && (Date.now() - (msg.timestamp || 0) < 35000) && window.AndroidAppBridge) {" +
                "              window.AndroidAppBridge.showSystemNotification(acc.name + ' के लिए नया संदेश (' + (msg.senderName || 'Notice') + ')', msg.message || msg.msg || 'नया चैट/नोटिस प्राप्त हुआ');" +
                "            }" +
                "          }" +
                "        });" +
                "      });" +
                "      if (window.location.pathname.indexOf('chat.html') === -1) {" +
                "        onSnapshot(doc(mod.db, 'calls', acc.docId), (callSnap) => {" +
                "          const c = callSnap.data();" +
                "          if (c && c.roomName && !handledCallRooms.has(c.roomName) && (c.type === 'call_offer' || c.type === 'conference_invite') && (Date.now() - (c.timestamp || 0) < 45000)) {" +
                "            handledCallRooms.add(c.roomName);" +
                "            localStorage.setItem('mehf_active_account_doc_id_v2', acc.docId);" +
                "            localStorage.setItem('loggedInUserDocId', acc.docId);" +
                "            sessionStorage.setItem('loggedInUserDocId', acc.docId);" +
                "            if (window.AndroidAppBridge) {" +
                "              window.AndroidAppBridge.triggerIncomingCallRing(acc.name + ' के लिए कॉल: ' + (c.callerName || c.callerId), 'उठाने के लिए टैप करें');" +
                "            }" +
                "            window.location.href = '/chat.html?uid=' + encodeURIComponent(acc.docId);" +
                "          }" +
                "        });" +
                "      }" +
                "      if ((acc.role === 'school' || acc.role === 'admin') && window.AndroidSmsBridge && !window.__mehfSimSmsWatcherStarted && window.location.pathname.indexOf('chat.html') === -1) {" +
                "        window.__mehfSimSmsWatcherStarted = true;" +
                "        const sentSet = new Set();" +
                "        ['sms_logs', 'sms_queue'].forEach(colName => {" +
                "          onSnapshot(collection(mod.db, colName), (snap) => {" +
                "            snap.forEach(d => {" +
                "              const item = d.data();" +
                "              const st = String(item.status || 'pending').toLowerCase().trim();" +
                "              const itemSchool = String(item.school || item.schoolId || item.schoolUsername || 'na').toLowerCase().trim();" +
                "              const myUser = String(acc.username || '').toLowerCase().trim();" +
                "              const myDoc = String(acc.docId || '').toLowerCase().trim();" +
                "              if ((st === 'pending' || st === 'queued') && !sentSet.has(d.id)) {" +
                "                if (acc.role === 'admin' || itemSchool === 'na' || itemSchool === '' || itemSchool === myUser || itemSchool === myDoc) {" +
                "                  const phone = String(item.phone || item.mobile || item.phoneNumber || item.to || '').trim();" +
                "                  const msg = String(item.msg || item.message || item.text || item.body || '').trim();" +
                "                  if (phone && msg) {" +
                "                    sentSet.add(d.id);" +
                "                    const res = window.AndroidSmsBridge.sendDirectSms(phone, msg);" +
                "                    const ok = String(res).startsWith('SENT');" +
                "                    updateDoc(doc(mod.db, colName, d.id), { status: ok ? 'sent' : 'failed', sentAt: Date.now(), gatewayResponse: String(res) }).catch(()=>{});" +
                "                  }" +
                "                }" +
                "              }" +
                "            });" +
                "          });" +
                "        });" +
                "      }" +
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
            callChannel.setDescription("Rings even when screen is locked");
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

            NotificationChannel serviceChannel = new NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "Meh foundation Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Keeps Calls, Notices & Auto-SMS active 24x7");
            nm.createNotificationChannel(serviceChannel);
        }
    }

    // 24x7 Immortal Foreground Service so Android never kills the app or resets WebView to 0
    public static class MehfKeepAliveService extends Service {
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            try {
                Intent openIntent = new Intent(this, MainActivity.class);
                openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int pFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pFlags |= PendingIntent.FLAG_IMMUTABLE;
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pFlags);

                Notification.Builder builder;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder = new Notification.Builder(this, SERVICE_CHANNEL_ID);
                } else {
                    builder = new Notification.Builder(this);
                }

                Notification notification = builder
                        .setSmallIcon(android.R.drawable.sym_def_app_icon)
                        .setContentTitle("Meh foundation Smart Hub Active")
                        .setContentText("24x7 लाइव कॉल, नोटिस अलर्ट एवं ऑटो-SMS गेटवे चालू है")
                        .setOngoing(true)
                        .setContentIntent(pendingIntent)
                        .build();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(7701, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(7701, notification);
                }
            } catch (Exception ignored) {}
            return START_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
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
                        int maxCallVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCallVol, 0);
                        int maxMusicVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0);
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
                    long now = System.currentTimeMillis();
                    if (now - lastCallRingTriggerTime < 6000) {
                        return;
                    }
                    lastCallRingTriggerTime = now;

                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        PowerManager.WakeLock screenLock = pm.newWakeLock(
                                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                                "MehFoundation::IncomingCallScreenWake"
                        );
                        screenLock.acquire(35000);
                    }

                    if (tts != null) {
                        tts.speak("ध्यान दें, " + callerTitle, TextToSpeech.QUEUE_FLUSH, null, null);
                    }

                    stopIncomingCallRing();
                    Uri ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                    activeRingtone = RingtoneManager.getRingtone(getApplicationContext(), ringUri);
                    if (activeRingtone != null) {
                        activeRingtone.play();
                    }
                    if (vibrator != null) {
                        long[] pattern = {0, 800, 400, 800, 400, 800};
                        vibrator.vibrate(pattern, 0);
                    }

                    showHighPriorityNotification(callerTitle, subtitle, 9001, CALL_CHANNEL_ID, LIVE_MEHF_CHAT_URL);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopIncomingCallRing() {
            runOnUiThread(() -> {
                try {
                    if (activeRingtone != null && activeRingtone.isPlaying()) {
                        activeRingtone.stop();
                    }
                    if (vibrator != null) {
                        vibrator.cancel();
                    }
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(9001);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void showSystemNotification(String title, String message) {
            runOnUiThread(() -> {
                if (tts != null) {
                    tts.speak(title + ". " + message, TextToSpeech.QUEUE_ADD, null, null);
                }
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

    // Modern Android 12/14/15/16 Dual-SIM + PendingIntent Delivery Verified SMS Bridge
    public class NativeSmsGatewayBridge {
        @JavascriptInterface
        public String sendDirectSms(String phoneNumber, String messageBody) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                        requestRequiredRuntimePermissions();
                        return "ERROR: SEND_SMS Permission Not Granted";
                    }
                }

                if (phoneNumber == null || messageBody == null) {
                    return "ERROR: Empty phone or message";
                }

                // Clean phone number for Indian mobile carriers (+91XXXXXXXXXX or 10-digit)
                String digitsOnly = phoneNumber.replaceAll("[^0-9]", "");
                if (digitsOnly.length() > 10 && digitsOnly.startsWith("91")) {
                    digitsOnly = digitsOnly.substring(digitsOnly.length() - 10);
                } else if (digitsOnly.length() > 10 && digitsOnly.startsWith("0")) {
                    digitsOnly = digitsOnly.substring(digitsOnly.length() - 10);
                }
                if (digitsOnly.length() < 10) {
                    return "ERROR: Invalid Phone Number (" + phoneNumber + ")";
                }
                String finalAddress = "+91" + digitsOnly;

                SmsManager smsManager = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    smsManager = getApplicationContext().getSystemService(SmsManager.class);
                }
                if (smsManager == null) {
                    int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
                    if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
                    } else {
                        smsManager = SmsManager.getDefault();
                    }
                }

                int pFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pFlags |= PendingIntent.FLAG_IMMUTABLE;
                }

                ArrayList<String> parts = smsManager.divideMessage(messageBody);
                if (parts.size() <= 1) {
                    PendingIntent sentPI = PendingIntent.getBroadcast(getApplicationContext(), (int) (System.currentTimeMillis() % 10000), new Intent("MEHF_SMS_SENT"), pFlags);
                    smsManager.sendTextMessage(finalAddress, null, messageBody, sentPI, null);
                } else {
                    ArrayList<PendingIntent> sentIntents = new ArrayList<>();
                    for (int i = 0; i < parts.size(); i++) {
                        PendingIntent pi = PendingIntent.getBroadcast(getApplicationContext(), i, new Intent("MEHF_SMS_SENT_" + i), pFlags);
                        sentIntents.add(pi);
                    }
                    smsManager.sendMultipartTextMessage(finalAddress, null, parts, sentIntents, null);
                }
                return "SENT";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }
    }

    private void requestRequiredRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<>();
            String[] allPerms = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE
            };

            for (String p : allPerms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(p);
                }
            }

            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.POST_NOTIFICATIONS);
                }
            }

            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (persistentWebView != null && persistentWebView.canGoBack()) {
            persistentWebView.goBack();
        } else {
            // Move app background instead of destroying Activity so WebView & listeners stay 100% alive
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        // Detach persistentWebView cleanly so it is never destroyed when activity restarts
        if (persistentWebView != null && persistentWebView.getParent() instanceof ViewGroup) {
            ((ViewGroup) persistentWebView.getParent()).removeView(persistentWebView);
        }
        super.onDestroy();
    }
}
