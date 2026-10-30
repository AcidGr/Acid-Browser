package com.acid.browser;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-performance native AdBlock and privacy tracker filter engine for Acid Browser.
 *
 * <p>Features:
 * <ul>
 *   <li>Standard ABP / EasyList syntax compatibility (||domain^, @@||whitelist^, ##selector, /path/).</li>
 *   <li>3-layer rule sources: Built-in offline core (3,142 rules), online GitHub subscriptions, and custom rules.</li>
 *   <li>Agent-editable: Agent can inspect webpage ads and append site rules to adblock/custom_rules.txt.</li>
 *   <li>Zero-overhead sub-millisecond hash matching at WebViewClient.shouldInterceptRequest.</li>
 *   <li>DOM element hiding injection (CSS display: none !important) to eliminate blank placeholders.</li>
 * </ul>
 */
public class AdBlockManager {

    private static final String TAG = "AcidAdBlock";

    public interface AdBlockCallback {
        void onSuccess(int totalRules);
        void onError(String message);
    }

    private static AdBlockManager sInstance;

    public static synchronized AdBlockManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new AdBlockManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private final Context mContext;
    private BrowserDataStore mDataStore;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final Set<String> mBlockedDomains = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> mWhiteListDomains = Collections.synchronizedSet(new HashSet<String>());
    private final List<String> mPathPatterns = new CopyOnWriteArrayList<String>();
    private final Map<String, List<String>> mDomainElementHiding = new ConcurrentHashMap<String, List<String>>();
    private final List<String> mGlobalElementHiding = new CopyOnWriteArrayList<String>();

    private final AtomicInteger mBlockedCount = new AtomicInteger(0);
    private boolean mEnabled = true;

    private int mBuiltinCount = 0;
    private int mSubCount = 0;
    private int mCustomCount = 0;

    private File mAdBlockDir;
    private File mCustomRulesFile;
    private File mSubscriptionFile;

    private AdBlockManager(Context context) {
        mContext = context;
        mDataStore = new BrowserDataStore(context);
        mEnabled = mDataStore.isAdBlockEnabled();
        mBlockedCount.set(mDataStore.getAdBlockBlockedCount());

        initDirectories();
        loadAllRulesAsync(null);
    }

    private void initDirectories() {
        File filesDir = mContext.getFilesDir();
        mAdBlockDir = new File(filesDir, "adblock");
        if (!mAdBlockDir.exists()) {
            mAdBlockDir.mkdirs();
        }
        mCustomRulesFile = new File(mAdBlockDir, "custom_rules.txt");
        mSubscriptionFile = new File(mAdBlockDir, "subscriptions.txt");

        if (!mCustomRulesFile.exists()) {
            try {
                FileOutputStream fos = new FileOutputStream(mCustomRulesFile);
                String header = "! Acid Browser Custom AdBlock Rules\n" +
                                "! 支持 ||domain^ (拦截域名), @@||domain^ (白名单), ##selector (元素隐藏)\n";
                fos.write(header.getBytes("UTF-8"));
                fos.close();
            } catch (Exception ignored) {}
        }
    }

    public synchronized void setWorkspaceDir(File workspaceDir) {
        if (workspaceDir != null && workspaceDir.exists()) {
            File wsAdblock = new File(workspaceDir, "adblock");
            if (!wsAdblock.exists()) {
                wsAdblock.mkdirs();
            }
            File wsCustom = new File(wsAdblock, "custom_rules.txt");
            if (!wsCustom.exists() && mCustomRulesFile != null && mCustomRulesFile.exists()) {
                copyFile(mCustomRulesFile, wsCustom);
            }
            boolean dirChanged = !wsAdblock.equals(mAdBlockDir);
            mAdBlockDir = wsAdblock;
            mCustomRulesFile = wsCustom;
            mSubscriptionFile = new File(wsAdblock, "subscriptions.txt");
            if (dirChanged) {
                loadAllRulesAsync(null);
            }
        }
    }

    private static void copyFile(File src, File dst) {
        try {
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();
        } catch (Exception ignored) {}
    }

    private final AcidSettings.KeyObserver<Boolean> mEnabledSettingObserver =
        new AcidSettings.KeyObserver<Boolean>() {
            @Override
            public void onSettingChanged(Boolean newValue) {
                mEnabled = newValue != null && newValue.booleanValue();
            }
        };

    public void bindDataStore(BrowserDataStore store) {
        if (store != null) {
            if (mDataStore != null) {
                mDataStore.unobserve(AcidSettings.ADBLOCK_ENABLED, mEnabledSettingObserver);
            }
            mDataStore = store;
            mDataStore.observe(AcidSettings.ADBLOCK_ENABLED, mEnabledSettingObserver);
            syncFromDataStore();
        }
    }

    public void syncFromDataStore() {
        if (mDataStore != null) {
            mEnabled = mDataStore.isAdBlockEnabled();
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        mDataStore.setAdBlockEnabled(enabled);
    }

    public int getBlockedCount() {
        return mBlockedCount.get();
    }

    public int getBuiltinRulesCount() {
        return mBuiltinCount;
    }

    public int getSubscriptionRulesCount() {
        return mSubCount;
    }

    public int getCustomRulesCount() {
        return mCustomCount;
    }

    public String getSubscriptionUrl() {
        return mDataStore.getAdBlockSubscriptionUrl();
    }

    public void setSubscriptionUrl(String url) {
        mDataStore.setAdBlockSubscriptionUrl(url);
    }

    public void loadAllRulesAsync(final Runnable onLoaded) {
        AcidAsync.run(new AcidAsync.Task<Void>() {
            @Override
            public Void execute() {
                loadAllRulesSync();
                return null;
            }
        }, new AcidAsync.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (onLoaded != null) {
                    onLoaded.run();
                }
            }

            @Override
            public void onError(Exception error) {
                Log.w(TAG, "Failed to reload adblock rules", error);
            }
        });
    }

    public synchronized void loadAllRulesSync() {
        Set<String> newBlocked = new HashSet<String>();
        Set<String> newWhiteList = new HashSet<String>();
        List<String> newPaths = new ArrayList<String>();
        Map<String, List<String>> newDomainHiding = new HashMap<String, List<String>>();
        List<String> newGlobalHiding = new ArrayList<String>();

        // 1. Load built-in asset rules
        int builtin = 0;
        try {
            InputStream is = mContext.getAssets().open("adblock/builtin_rules.txt");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (parseRuleLine(line, newBlocked, newWhiteList, newPaths, newDomainHiding, newGlobalHiding)) {
                    builtin++;
                }
            }
            br.close();
            is.close();
        } catch (Exception e) {
            Log.w(TAG, "Could not load assets/adblock/builtin_rules.txt: " + e.getMessage());
        }
        mBuiltinCount = builtin;

        // 2. Load online subscription rules if present
        int sub = 0;
        if (mSubscriptionFile != null && mSubscriptionFile.exists()) {
            try {
                FileInputStream fis = new FileInputStream(mSubscriptionFile);
                BufferedReader br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    if (parseRuleLine(line, newBlocked, newWhiteList, newPaths, newDomainHiding, newGlobalHiding)) {
                        sub++;
                    }
                }
                br.close();
                fis.close();
            } catch (Exception e) {
                Log.w(TAG, "Could not load subscriptions.txt: " + e.getMessage());
            }
        }
        mSubCount = sub;

        // 3. Load custom rules
        int custom = 0;
        if (mCustomRulesFile != null && mCustomRulesFile.exists()) {
            try {
                FileInputStream fis = new FileInputStream(mCustomRulesFile);
                BufferedReader br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    if (parseRuleLine(line, newBlocked, newWhiteList, newPaths, newDomainHiding, newGlobalHiding)) {
                        custom++;
                    }
                }
                br.close();
                fis.close();
            } catch (Exception e) {
                Log.w(TAG, "Could not load custom_rules.txt: " + e.getMessage());
            }
        }
        mCustomCount = custom;

        // Swap into active collections
        mBlockedDomains.clear();
        mBlockedDomains.addAll(newBlocked);

        mWhiteListDomains.clear();
        mWhiteListDomains.addAll(newWhiteList);

        mPathPatterns.clear();
        mPathPatterns.addAll(newPaths);

        mDomainElementHiding.clear();
        mDomainElementHiding.putAll(newDomainHiding);

        mGlobalElementHiding.clear();
        mGlobalElementHiding.addAll(newGlobalHiding);

        Log.i(TAG, "AdBlock loaded: " + (mBuiltinCount + mSubCount + mCustomCount) +
              " rules (Built-in: " + mBuiltinCount + ", Sub: " + mSubCount + ", Custom: " + mCustomCount +
              ", Unique domains: " + mBlockedDomains.size() + ")");
    }

    static boolean parseRuleLine(String rawLine,
                                 Set<String> blocked,
                                 Set<String> whiteList,
                                 List<String> paths,
                                 Map<String, List<String>> domainHiding,
                                 List<String> globalHiding) {
        if (rawLine == null) return false;
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) {
            return false;
        }

        // 1. Whitelist rule @@||...
        if (line.startsWith("@@||")) {
            String sub = line.substring(4);
            int caret = sub.indexOf('^');
            int slash = sub.indexOf('/');
            int end = sub.length();
            if (caret > 0 && caret < end) end = caret;
            if (slash > 0 && slash < end) end = slash;
            String domain = sub.substring(0, end).trim().toLowerCase();
            if (!domain.isEmpty()) {
                whiteList.add(domain);
                return true;
            }
            return false;
        }

        // 2. Element hiding rule (##)
        int hashIdx = line.indexOf("##");
        if (hashIdx >= 0) {
            String domainPart = line.substring(0, hashIdx).trim();
            String selector = line.substring(hashIdx + 2).trim();
            if (!selector.isEmpty()) {
                if (domainPart.isEmpty()) {
                    globalHiding.add(selector);
                } else {
                    String[] doms = domainPart.split(",");
                    for (int i = 0; i < doms.length; i++) {
                        String d = doms[i].trim().toLowerCase();
                        if (!d.isEmpty()) {
                            List<String> list = domainHiding.get(d);
                            if (list == null) {
                                list = new ArrayList<String>();
                                domainHiding.put(d, list);
                            }
                            list.add(selector);
                        }
                    }
                }
                return true;
            }
            return false;
        }

        // 3. Domain block rule ||domain^
        if (line.startsWith("||")) {
            String sub = line.substring(2);
            int caret = sub.indexOf('^');
            int slash = sub.indexOf('/');
            int colon = sub.indexOf(':');
            int end = sub.length();
            if (caret > 0 && caret < end) end = caret;
            if (slash > 0 && slash < end) end = slash;
            if (colon > 0 && colon < end) end = colon;
            String domain = sub.substring(0, end).trim().toLowerCase();
            if (!domain.isEmpty()) {
                blocked.add(domain);
                return true;
            }
            return false;
        }

        // 4. Path pattern /pattern/
        if (line.startsWith("/") && line.endsWith("/") && line.length() > 2) {
            paths.add(line.substring(1, line.length() - 1).toLowerCase());
            return true;
        }

        // 5. Bare domain without slash or wildcard
        if (!line.contains("/") && !line.contains("*") && line.contains(".")) {
            blocked.add(line.toLowerCase());
            return true;
        }

        return false;
    }

    static boolean matchesDomainHierarchy(String host, Set<String> domainSet) {
        if (host == null || domainSet == null || domainSet.isEmpty()) return false;
        String cur = host.toLowerCase();
        while (cur != null && !cur.isEmpty()) {
            if (domainSet.contains(cur)) return true;
            int dot = cur.indexOf('.');
            if (dot < 0 || dot == cur.length() - 1) break;
            cur = cur.substring(dot + 1);
        }
        return false;
    }

    public boolean isWhitelisted(String host) {
        if (host == null) return false;
        String cur = host.toLowerCase();
        if (cur.contains("accounts.google.") || cur.contains("myaccount.google.") || cur.contains("apis.google.com") || cur.contains("gstatic.com") || cur.contains("googleusercontent.com")) {
            return true;
        }
        return matchesDomainHierarchy(cur, mWhiteListDomains);
    }

    public boolean isBlocked(Uri uri) {
        if (!mEnabled || uri == null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();

        if (isWhitelisted(host)) {
            return false;
        }

        // Check domain hierarchy: e.g. pos.baidu.com -> baidu.com
        if (matchesDomainHierarchy(host, mBlockedDomains)) {
            return true;
        }

        // Check URL path patterns
        String uriStr = uri.toString().toLowerCase();
        for (int i = 0; i < mPathPatterns.size(); i++) {
            if (uriStr.contains(mPathPatterns.get(i))) {
                return true;
            }
        }

        return false;
    }

    public WebResourceResponse shouldIntercept(Uri uri) {
        if (isBlocked(uri)) {
            int count = mBlockedCount.incrementAndGet();
            if (count % 5 == 0 || count == 1) {
                mDataStore.setAdBlockBlockedCount(count);
            }
            Log.d(TAG, "[AdBlock Intercepted #" + count + "] " + uri);
            return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        }
        return null;
    }

    public void injectElementHidingCss(WebView view, String pageUrl) {
        if (!mEnabled || view == null || pageUrl == null) return;
        Uri uri = Uri.parse(pageUrl);
        String host = uri.getHost();
        if (host != null) host = host.toLowerCase();

        StringBuilder selectors = new StringBuilder();
        for (int i = 0; i < mGlobalElementHiding.size(); i++) {
            if (selectors.length() > 0) selectors.append(", ");
            selectors.append(mGlobalElementHiding.get(i));
        }

        if (host != null) {
            String cur = host;
            while (cur != null && !cur.isEmpty()) {
                List<String> list = mDomainElementHiding.get(cur);
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        if (selectors.length() > 0) selectors.append(", ");
                        selectors.append(list.get(i));
                    }
                }
                int dot = cur.indexOf('.');
                if (dot < 0 || dot == cur.length() - 1) break;
                cur = cur.substring(dot + 1);
            }
        }

        // General fallback mobile cleanup selectors
        if (selectors.length() > 0) selectors.append(", ");
        selectors.append(".ad, .ads, .ad-banner, .advertisement, [id^='ad_'], [id*='-ad-'], [class*='app-open'], [class*='bottom-banner']");

        String cssRule = selectors.toString() + " { display: none !important; height: 0 !important; visibility: hidden !important; pointer-events: none !important; }";
        String js = "(function() {" +
            "var s = document.getElementById('__acid_adblock_css__');" +
            "if (!s) {" +
            "  s = document.createElement('style');" +
            "  s.id = '__acid_adblock_css__';" +
            "  s.type = 'text/css';" +
            "  s.appendChild(document.createTextNode(" + JSONObject.quote(cssRule) + "));" +
            "  (document.head || document.documentElement).appendChild(s);" +
            "}" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    public String getCustomRulesText() {
        if (mCustomRulesFile == null || !mCustomRulesFile.exists()) {
            return "";
        }
        try {
            FileInputStream fis = new FileInputStream(mCustomRulesFile);
            BufferedReader br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            fis.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public boolean saveCustomRulesText(String text) {
        if (mCustomRulesFile == null) return false;
        try {
            FileOutputStream fos = new FileOutputStream(mCustomRulesFile);
            fos.write(text.getBytes("UTF-8"));
            fos.close();
            loadAllRulesSync();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save custom rules: " + e.getMessage());
            return false;
        }
    }

    public void reloadCustomRules() {
        loadAllRulesAsync(null);
    }

    public void downloadSubscription(final String urlStr, final AdBlockCallback callback) {
        setSubscriptionUrl(urlStr);
        AcidAsync.run(new AcidAsync.Task<Integer>() {
            @Override
            public Integer execute() throws Exception {
                AcidHttp.downloadToFile(urlStr, mSubscriptionFile, 15000, 20000, AcidHttp.DEFAULT_MAX_BYTES);
                loadAllRulesSync();
                return Integer.valueOf(mBuiltinCount + mSubCount + mCustomCount);
            }
        }, new AcidAsync.Callback<Integer>() {
            @Override
            public void onSuccess(Integer total) {
                if (callback != null) callback.onSuccess(total.intValue());
            }

            @Override
            public void onError(Exception e) {
                if (callback != null) callback.onError("下载失败: " + e.getMessage());
            }
        });
    }
}
