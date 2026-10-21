package com.mehf.smsgateway;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
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
import androidx.core.app.NotificationCompat;

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
    private static final String LIVE_MEHF_START_URL = "https://mehf.in/chat.html";
    private static final String LIVE_MEHF_LOGIN_URL = "https://mehf.in/login.html";
    private static final String LIVE_MEHF_CHAT_URL = "https://mehf.in/chat.html";
    private static final String CALL_CHANNEL_ID = "mehf_incoming_calls_channel";
    private static final String ALERT_CHANNEL_ID = "mehf_activity_alerts_channel";
    private static final String SERVICE_CHANNEL_ID = "mehf_keep_alive_service_channel";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Lock screen par call / alerts uthane ke liye flags
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

        // Background WakeLock taaki app deep sleep mein na jaye
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

        // TextToSpeech initialization (Hindi voice support)
        if (tts == null) {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(new Locale("hi", "IN"));
                }
            });
        }

        // Reuse existing persistent WebView so state, Firebase listeners & chats stay 100% alive in memory
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

        // Agar phone reboot hone par BootReceiver ne app ko auto-start kiya hai, toh background mein rakhna
        if (getIntent() != null && getIntent().getBooleanExtra("auto_boot", false)) {
            getIntent().removeExtra("auto_boot");
            moveTaskToBack(true);
        }
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
                if (failingUrl != null && (failingUrl.equals(LIVE_MEHF_START_URL) || failingUrl.equals(LIVE_MEHF_LOGIN_URL))) {
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
                "  const effectiveId = sessionStorage.getItem('loggedInUserDocId') || localStorage.getItem('loggedInUserDocId');" +
                "  const effectiveRole = sessionStorage.getItem('loggedInUserRole') || localStorage.getItem('loggedInUserRole') || 'admin';" +
                "  const path = window.location.pathname;" +
                "  const redirects = { admin: '/admin-dashboard.html', school: '/school-dashboard.html', teacher: '/teacher-dashboard.html', student: '/student-dashboard.html', volunteer: '/volunteer-dashboard.html', driver: '/transport-dashboard.html' };" +
                "" +
                "  if (!effectiveId && path.indexOf('login.html') === -1) {" +
                "    window.location.href = '/login.html';" +
                "    return;" +
                "  }" +
                "  if (path.indexOf('login.html') !== -1) {" +
                "    sessionStorage.removeItem('mehf_landed_chat_first');" +
                "  } else if (effectiveId && path.indexOf('dashboard') !== -1 && !sessionStorage.getItem('mehf_landed_chat_first')) {" +
                "    sessionStorage.setItem('mehf_landed_chat_first', '1');" +
                "    window.location.replace('/chat.html');" +
                "    return;" +
                "  } else if (path.indexOf('chat.html') !== -1) {" +
                "    sessionStorage.setItem('mehf_landed_chat_first', '1');" +
                "  }" +
                "" +
                "  /* 1. Top Smart Toggle Button (Chat <-> Dashboard) & SMS Panel */" +
                "  if (effectiveId && path.indexOf('login.html') === -1 && !document.getElementById('mehfTopNavSwitcher')) {" +
                "    const topBar = document.createElement('div');" +
                "    topBar.id = 'mehfTopNavSwitcher';" +
                "    topBar.style.cssText = 'position:fixed;top:6px;left:50%;transform:translateX(-50%);z-index:999995;background:rgba(15,23,42,0.95);border:1.5px solid #38bdf8;border-radius:999px;padding:4px 12px;display:flex;align-items:center;gap:8px;box-shadow:0 6px 20px rgba(0,0,0,0.5);backdrop-filter:blur(8px);font-family:sans-serif;white-space:nowrap;';" +
                "    if (!document.getElementById('mehfBodyStylePatcher')) {" +
                "      const bStyle = document.createElement('style');" +
                "      bStyle.id = 'mehfBodyStylePatcher';" +
                "      bStyle.innerHTML = 'body { padding-top: 52px !important; padding-bottom: 20px !important; }';" +
                "      document.head.appendChild(bStyle);" +
                "    }" +
                "    const isChatPage = path.indexOf('chat.html') !== -1;" +
                "    const dashUrl = redirects[effectiveRole] || '/admin-dashboard.html';" +
                "    let navHtml = isChatPage ? " +
                "      \"<a href='\" + dashUrl + \"' id='mehfSwitchDashBtn' style='color:#fde68a;font-size:11px;font-weight:800;text-decoration:none;display:flex;align-items:center;gap:4px;padding:3px 10px;border-radius:999px;background:rgba(56,189,248,0.2);'>📊 डैशबोर्ड पर जाएं</a>\" :" +
                "      \"<a href='/chat.html' style='color:#fde68a;font-size:11px;font-weight:800;text-decoration:none;display:flex;align-items:center;gap:4px;padding:3px 10px;border-radius:999px;background:rgba(56,189,248,0.2);'>💬 चैट पर जाएं</a>\";" +
                "    if (effectiveRole === 'admin' || effectiveRole === 'school') {" +
                "      navHtml += \"<span style='color:#475569;font-size:10px;'>|</span><button id='mehfOpenSmsModalBtn' style='background:rgba(34,197,94,0.2);color:#4ade80;border:1.5px solid #22c55e;border-radius:999px;padding:3px 8px;font-size:10px;font-weight:800;cursor:pointer;'>📩 SMS पैनल (<span id='mehfSmsBadgeCount'>0</span>)</button>\";" +
                "    }" +
                "    topBar.innerHTML = navHtml;" +
                "    document.body.appendChild(topBar);" +
                "    const dBtn = document.getElementById('mehfSwitchDashBtn');" +
                "    if (dBtn) { dBtn.addEventListener('click', function() { sessionStorage.setItem('mehf_landed_chat_first', '1'); }); }" +
                "    const smsBtn = document.getElementById('mehfOpenSmsModalBtn');" +
                "    if (smsBtn) { smsBtn.addEventListener('click', function() { if (window.__mehfToggleSmsManagerModal) window.__mehfToggleSmsManagerModal(); }); }" +
                "  }" +
                "" +
                "  /* 2. Bottom Multi-Account Switcher (KEVAL Dashboard screen par dikhega, Chat par nahi) */" +
                "  if (accs.length > 0 && path.indexOf('login.html') === -1 && path.indexOf('chat.html') === -1 && !document.getElementById('mehfFloatingAccountSwitcher')) {" +
                "    const bar = document.createElement('div');" +
                "    bar.id = 'mehfFloatingAccountSwitcher';" +
                "    bar.style.cssText = 'position:fixed;bottom:12px;right:12px;z-index:999990;background:rgba(15,23,42,0.95);border:1.5px solid #fbbf24;border-radius:999px;padding:4px 10px;display:flex;align-items:center;gap:6px;box-shadow:0 10px 25px rgba(0,0,0,0.5);backdrop-filter:blur(8px);font-family:sans-serif;';" +
                "    let opts = accs.map(a => \"<option value='\" + a.docId + \"' \" + (a.docId === effectiveId ? \"selected\" : \"\") + \">👤 \" + a.name + \" (\" + a.role.toUpperCase() + \")</option>\").join('');" +
                "    opts += \"<option value='__ADD__'>➕ नया खाता...</option>\";" +
                "    bar.innerHTML = \"<span style='width:6px;height:6px;border-radius:50%;background:#22c55e;display:inline-block;'></span><select id='mehfGlobalAccSel' style='background:transparent;color:#fde68a;border:none;font-size:10px;font-weight:800;outline:none;max-width:130px;'>\" + opts + \"</select>\";" +
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
                "        window.location.href = '/chat.html';" +
                "      }" +
                "    });" +
                "  }" +
                "" +
                "  if (window.__mehfMultiAccountDaemonRunning) return;" +
                "  window.__mehfMultiAccountDaemonRunning = true;" +
                "" +
                "  import('/firebase-config.js?v=100').then(async (mod) => {" +
                "    if (!mod || !mod.db) return;" +
                "    const { doc, collection, query, where, onSnapshot, updateDoc } = await import('https://www.gstatic.com/firebasejs/10.8.0/firebase-firestore.js');" +
                "    const pageName = (window.location.pathname.split('/').pop() || 'home').replace('.html', '').toUpperCase();" +
                "    if (effectiveId) {" +
                "      updateDoc(doc(mod.db, 'users', effectiveId), { isUsingApp: true, isOnline: true, lastSeen: Date.now(), lastAppActive: Date.now(), lastFeatureUsed: 'Android App (' + pageName + ')' }).catch(()=>{});" +
                "    }" +
                "    const handledCallRooms = new Set();" +
                "    window.__mehfSmsItemsMap = new Map();" +
                "" +
                "    window.__mehfSendSingleSmsItem = async function(itemKey) {" +
                "      const entry = window.__mehfSmsItemsMap.get(itemKey);" +
                "      if (!entry || !window.AndroidSmsBridge) return false;" +
                "      const res = window.AndroidSmsBridge.sendDirectSms(entry.phone, entry.msg);" +
                "      const ok = String(res).startsWith('SENT');" +
                "      await updateDoc(doc(mod.db, entry.colName, entry.id), {" +
                "        status: ok ? 'sent' : 'failed'," +
                "        sentAt: Date.now()," +
                "        gatewayResponse: String(res)" +
                "      }).catch(()=>{});" +
                "      return ok;" +
                "    };" +
                "" +
                "    window.__mehfIgnoreSingleSmsItem = async function(itemKey) {" +
                "      const entry = window.__mehfSmsItemsMap.get(itemKey);" +
                "      if (!entry) return;" +
                "      await updateDoc(doc(mod.db, entry.colName, entry.id), {" +
                "        status: 'ignored'," +
                "        ignoredAt: Date.now()" +
                "      }).catch(()=>{});" +
                "    };" +
                "" +
                "    window.__mehfSendAllPendingSmsOnly = async function() {" +
                "      for (const [key, entry] of window.__mehfSmsItemsMap.entries()) {" +
                "        if (entry.status === 'pending' || entry.status === 'queued') {" +
                "          await window.__mehfSendSingleSmsItem(key);" +
                "        }" +
                "      }" +
                "    };" +
                "" +
                "    window.__mehfRenderSmsModalContent = function() {" +
                "      const badge = document.getElementById('mehfSmsBadgeCount');" +
                "      let pendingList = [];" +
                "      let failedList = [];" +
                "      window.__mehfSmsItemsMap.forEach((val, key) => {" +
                "        if (val.status === 'pending' || val.status === 'queued') pendingList.push({ key, ...val });" +
                "        else if (val.status === 'failed') failedList.push({ key, ...val });" +
                "      });" +
                "      if (badge) badge.textContent = (pendingList.length + failedList.length);" +
                "      const container = document.getElementById('mehfSmsModalBody');" +
                "      if (!container) return;" +
                "      let html = \"<div style='margin-bottom:14px;padding:10px;background:rgba(30,41,59,0.9);border:1px solid #38bdf8;border-radius:10px;display:flex;justify-content:space-between;align-items:center;'><div><div style='color:#38bdf8;font-weight:800;font-size:13px;'>⏳ Pending SMS: \" + pendingList.length + \"</div></div><button onclick='window.__mehfSendAllPendingSmsOnly()' \" + (pendingList.length === 0 ? \"disabled\" : \"\") + \" style='background:\" + (pendingList.length > 0 ? \"#0284c7\" : \"#334155\") + \";color:#fff;border:none;border-radius:8px;padding:7px 12px;font-size:12px;font-weight:800;cursor:pointer;'>📤 सभी Pending भेजें</button></div>\";" +
                "      html += \"<div style='color:#f87171;font-weight:800;font-size:13px;margin-bottom:8px;'>⚠️ Failed SMS (\" + failedList.length + \"):</div>\";" +
                "      if (failedList.length === 0) {" +
                "        html += \"<div style='color:#64748b;font-size:12px;text-align:center;padding:12px;background:rgba(15,23,42,0.6);border-radius:8px;'>कोई Failed SMS नहीं है।</div>\";" +
                "      } else {" +
                "        failedList.forEach(item => {" +
                "          html += \"<div style='margin-bottom:8px;padding:9px;background:rgba(15,23,42,0.85);border:1px solid #ef4444;border-radius:8px;display:flex;justify-content:space-between;align-items:center;gap:8px;'><div style='flex:1;overflow:hidden;'><div style='color:#fde68a;font-weight:800;font-size:12px;'>📞 \" + item.phone + \"</div><div style='color:#cbd5e1;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;'>\" + item.msg + \"</div></div><div style='display:flex;gap:5px;'><button onclick=\\\"window.__mehfSendSingleSmsItem('\" + item.key + \"')\\\" style='background:#16a34a;color:#fff;border:none;border-radius:6px;padding:5px 9px;font-size:11px;font-weight:800;cursor:pointer;'>🔄 Send</button><button onclick=\\\"window.__mehfIgnoreSingleSmsItem('\" + item.key + \"')\\\" style='background:#475569;color:#cbd5e1;border:none;border-radius:6px;padding:5px 8px;font-size:11px;font-weight:700;cursor:pointer;'>✖ Ignore</button></div></div>\";" +
                "        });" +
                "      }" +
                "      container.innerHTML = html;" +
                "    };" +
                "" +
                "    window.__mehfToggleSmsManagerModal = function() {" +
                "      let modal = document.getElementById('mehfSmsManagerModal');" +
                "      if (modal) {" +
                "        modal.style.display = modal.style.display === 'none' ? 'flex' : 'none';" +
                "        window.__mehfRenderSmsModalContent();" +
                "        return;" +
                "      }" +
                "      modal = document.createElement('div');" +
                "      modal.id = 'mehfSmsManagerModal';" +
                "      modal.style.cssText = 'position:fixed;inset:0;z-index:999999;background:rgba(0,0,0,0.75);display:flex;align-items:center;justify-content:center;padding:16px;font-family:sans-serif;';" +
                "      modal.innerHTML = \"<div style='width:100%;max-width:440px;max-height:82vh;background:#0f172a;border:1.5px solid #38bdf8;border-radius:14px;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 15px 35px rgba(0,0,0,0.7);'><div style='padding:12px 15px;background:#1e293b;border-bottom:1px solid #334155;display:flex;justify-content:space-between;align-items:center;'><span style='color:#fde68a;font-weight:900;font-size:14px;'>📩 MEHF Smart SMS Gateway</span><button onclick=\\\"document.getElementById('mehfSmsManagerModal').style.display='none'\\\" style='background:transparent;color:#94a3b8;border:none;font-size:16px;font-weight:900;cursor:pointer;'>✕</button></div><div id='mehfSmsModalBody' style='padding:14px;overflow-y:auto;flex:1;'></div></div>\";" +
                "      document.body.appendChild(modal);" +
                "      window.__mehfRenderSmsModalContent();" +
                "    };" +
                "" +
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
                "" +
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
                "" +
                "      onSnapshot(doc(mod.db, 'calls', acc.docId), (callSnap) => {" +
                "        const c = callSnap.data();" +
                "        if (c && c.roomName && !handledCallRooms.has(c.roomName) && (c.type === 'call_offer' || c.type === 'conference_invite') && (Date.now() - (c.timestamp || 0) < 45000)) {" +
                "          handledCallRooms.add(c.roomName);" +
                "          localStorage.setItem('mehf_active_account_doc_id_v2', acc.docId);" +
                "          localStorage.setItem('loggedInUserDocId', acc.docId);" +
                "          sessionStorage.setItem('loggedInUserDocId', acc.docId);" +
                "          if (window.AndroidAppBridge) {" +
                "            window.AndroidAppBridge.triggerIncomingCallRing(acc.name + ' के लिए कॉल: ' + (c.callerName || c.callerId), 'उठाने के लिए टैप करें');" +
                "          }" +
                "          if (window.location.pathname.indexOf('chat.html') === -1) {" +
                "            window.location.href = '/chat.html?uid=' + encodeURIComponent(acc.docId);" +
                "          }" +
                "        }" +
                "      });" +
                "" +
                "      if ((acc.role === 'school' || acc.role === 'admin') && window.AndroidSmsBridge && !window.__mehfSimSmsWatcherStarted) {" +
                "        window.__mehfSimSmsWatcherStarted = true;" +
                "        const autoAttemptedSet = new Set();" +
                "        ['sms_logs', 'sms_queue'].forEach(colName => {" +
                "          onSnapshot(collection(mod.db, colName), (snap) => {" +
                "            snap.forEach(d => {" +
                "              const item = d.data();" +
                "              const st = String(item.status || 'pending').toLowerCase().trim();" +
                "              const itemSchool = String(item.school || item.schoolId || item.schoolUsername || 'na').toLowerCase().trim();" +
                "              const myUser = String(acc.username || '').toLowerCase().trim();" +
                "              const myDoc = String(acc.docId || '').toLowerCase().trim();" +
                "              if (acc.role === 'admin' || itemSchool === 'na' || itemSchool === '' || itemSchool === myUser || itemSchool === myDoc) {" +
                "                const phone = String(item.phone || item.mobile || item.phoneNumber || item.to || '').trim();" +
                "                const msg = String(item.msg || item.message || item.text || item.body || '').trim();" +
                "                const itemKey = colName + '__' + d.id;" +
                "                if (phone && msg) {" +
                "                  if (st === 'pending' || st === 'queued' || st === 'failed') {" +
                "                    window.__mehfSmsItemsMap.set(itemKey, { colName, id: d.id, phone, msg, status: st });" +
                "                  } else {" +
                "                    window.__mehfSmsItemsMap.delete(itemKey);" +
                "                  }" +
                "                  if ((st === 'pending' || st === 'queued') && !autoAttemptedSet.has(itemKey)) {" +
                "                    autoAttemptedSet.add(itemKey);" +
                "                    window.__mehfSendSingleSmsItem(itemKey);" +
                "                  }" +
                "                }" +
                "              }" +
                "            });" +
                "            window.__mehfRenderSmsModalContent();" +
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

    public static class MehfKeepAliveService extends android.app.Service {
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            String channelId = "mehf_keep_alive_service_channel";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "MEHF 24x7 Background Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) manager.createNotificationChannel(channel);
            }

            Notification notification = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("Meh Foundation Active")
                    .setContentText("24x7 SMS Gateway & Chat daemon running...")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1001, notification);
            }
            return START_STICKY;
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            super.onTaskRemoved(rootIntent);
            Intent restartServiceIntent = new Intent(getApplicationContext(), MehfKeepAliveService.class);
            restartServiceIntent.setPackage(getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartServiceIntent);
            } else {
                startService(restartServiceIntent);
            }
        }

        @Override
        public android.os.IBinder onBind(Intent intent) {
            return null;
        }
    }

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();

            if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

                try {
                    Intent serviceIntent = new Intent(context, MehfKeepAliveService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }

                    Intent activityIntent = new Intent(context, MainActivity.class);
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    activityIntent.putExtra("auto_boot", true);
                    context.startActivity(activityIntent);
                } catch (Exception ignored) {}
            }
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
        if (persistentWebView != null) {
            String currentUrl = persistentWebView.getUrl();
            if (currentUrl != null && (currentUrl.contains("chat.html") || currentUrl.contains("login.html"))) {
                moveTaskToBack(true);
            } else if (persistentWebView.canGoBack()) {
                persistentWebView.goBack();
            } else {
                persistentWebView.loadUrl(LIVE_MEHF_CHAT_URL);
            }
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (persistentWebView != null && persistentWebView.getParent() instanceof ViewGroup) {
            ((ViewGroup) persistentWebView.getParent()).removeView(persistentWebView);
        }
        super.onDestroy();
    }
}
