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

    // Starts directly on login.html (which auto-opens the active dashboard if already saved)
    private static final String LIVE_MEHF_START_URL = "https://mehf.in/login.html";
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MehFoundation::MultiAccountWakeLock");
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
                injectMultiAccountDaemonAndSwitcherBar(view);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.equals(LIVE_MEHF_START_URL)) {
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
            webView.loadUrl(LIVE_MEHF_START_URL);
        }
    }

    // Injects:
    // 1. Session Bridge (Restores localStorage -> sessionStorage so dashboards never kick user out)
    // 2. Floating Multi-Account Switcher Pill (Switch between 3 kids + staff account with 1 tap)
    // 3. Simultaneous Multi-Account Realtime Listener (Monitors ALL saved accounts at once!)
    private void injectMultiAccountDaemonAndSwitcherBar(WebView view) {
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
                "    bar.innerHTML = `<span style='width:8px;height:8px;border-radius:50%;background:#22c55e;display:inline-block;' title='All ${accs.length} accounts active'></span>` +" +
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
                "            if (msg && (Date.now() - (msg.timestamp || 0) < 30000) && window.AndroidAppBridge) {" +
                "              window.AndroidAppBridge.showSystemNotification(acc.name + ' के लिए नया संदेश (' + (msg.senderName || 'Notice') + ')', msg.message || 'नया चैट/नोटिस प्राप्त हुआ');" +
                "            }" +
                "          }" +
                "        });" +
                "      });" +
                "      if (window.location.pathname.indexOf('chat.html') === -1) {" +
                "        onSnapshot(doc(mod.db, 'calls', acc.docId), (callSnap) => {" +
                "          const c = callSnap.data();" +
                "          if (c && (c.type === 'call_offer' || c.type === 'conference_invite') && (Date.now() - (c.timestamp || 0) < 50000)) {" +
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
                "      if ((acc.role === 'school' || acc.role === 'admin') && window.AndroidSmsBridge && !window.__mehfSimSmsWatcherStarted) {" +
                "        window.__mehfSimSmsWatcherStarted = true;" +
                "        const sentSet = new Set();" +
                "        ['sms_queue', 'sms_logs'].forEach(colName => {" +
                "          onSnapshot(collection(mod.db, colName), (snap) => {" +
                "            snap.forEach(d => {" +
                "              const item = d.data();" +
                "              const st = String(item.status || 'pending').toLowerCase();" +
                "              if ((st === 'pending' || st === 'queued') && !sentSet.has(d.id)) {" +
                "                const phone = String(item.phone || item.mobile || item.phoneNumber || '').trim();" +
                "                const msg = String(item.message || item.text || '').trim();" +
                "                if (phone && msg) {" +
                "                  sentSet.add(d.id);" +
                "                  const res = window.AndroidSmsBridge.sendDirectSms(phone, msg);" +
                "                  updateDoc(doc(mod.db, colName, d.id), { status: String(res).startsWith('SENT') ? 'sent' : 'failed', sentAt: Date.now() }).catch(()=>{});" +
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

            NotificationChannel callChannel = new NotificationChannel(CALL_CHANNEL_ID, "Incoming Calls & Conferences", NotificationManager.IMPORTANCE_HIGH);
            callChannel.enableVibration(true);
            callChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(callChannel);

            NotificationChannel alertChannel = new NotificationChannel(ALERT_CHANNEL_ID, "Multi-Account Notices & Alerts", NotificationManager.IMPORTANCE_HIGH);
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
                        tts.speak("ध्यान दें, " + callerTitle, TextToSpeech.QUEUE_FLUSH, null, null);
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
