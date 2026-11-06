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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /**
     * Immutable-after-publish snapshot of parsed ABP rules so WebView IO threads can read
     * concurrently with zero locking, zero empty-window flicker, and zero index-bounds races.
     */
    static final class RuleSet {
        final Set<String> blockedDomains = new HashSet<String>();
        final Set<String> whiteListDomains = new HashSet<String>();
        final Map<String, List<String>> domainBlockedPaths = new HashMap<String, List<String>>();
        final Map<String, List<String>> domainWhiteListPaths = new HashMap<String, List<String>>();
        final List<String> pathPatterns = new ArrayList<String>();
        final Map<String, List<String>> domainElementHiding = new HashMap<String, List<String>>();
        final List<String> globalElementHiding = new ArrayList<String>();
    }

    private final Context mContext;
    private volatile BrowserDataStore mDataStore;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private volatile RuleSet mRules = new RuleSet();

    private final AtomicInteger mBlockedCount = new AtomicInteger(0);
    private volatile boolean mEnabled = true;

    private volatile int mBuiltinCount = 0;
    private volatile int mSubCount = 0;
    private volatile int mCustomCount = 0;

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
            String header = "! Acid Browser Custom AdBlock Rules\n" +
                            "! 支持 ||domain^ (拦截域名), @@||domain^ (白名单), ##selector (元素隐藏)\n";
            AcidIo.writeFileUtf8Safe(mCustomRulesFile, header);
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

    public int getAppLaunchPolicy() {
        return mDataStore != null ? mDataStore.getAppLaunchPolicy() : AcidSettings.APP_LAUNCH_ASK;
    }

    public void setAppLaunchPolicy(int policy) {
        if (mDataStore != null) {
            mDataStore.setAppLaunchPolicy(policy);
        }
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
        RuleSet next = new RuleSet();

        // 1. Load built-in asset rules
        int builtin = 0;
        try {
            InputStream is = mContext.getAssets().open("adblock/builtin_rules.txt");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (parseRuleLine(line, next)) {
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
                    if (parseRuleLine(line, next)) {
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
                    if (parseRuleLine(line, next)) {
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

        // Atomic single-reference swap into active snapshot
        mRules = next;

        Log.i(TAG, "AdBlock loaded: " + (mBuiltinCount + mSubCount + mCustomCount) +
              " rules (Built-in: " + mBuiltinCount + ", Sub: " + mSubCount + ", Custom: " + mCustomCount +
              ", Unique domains: " + next.blockedDomains.size() + ")");
    }

    private static final Set<String> COMMON_PUBLIC_SUFFIXES = new HashSet<String>(Arrays.asList(
        "com", "cn", "net", "org", "edu", "gov", "io", "cc", "me", "info", "top", "xyz", "biz", "app", "dev", "ai",
        "com.cn", "net.cn", "org.cn", "edu.cn", "gov.cn", "co.uk", "org.uk", "co.jp", "ne.jp", "com.hk", "org.hk",
        "com.tw", "com.sg", "com.au", "co.kr", "co.in", "com.br", "github.io", "gitlab.io", "vercel.app", "pages.dev"
    ));

    public static boolean isPublicSuffixOrTld(String domain) {
        if (domain == null || domain.trim().isEmpty()) return true;
        String d = domain.trim().toLowerCase(Locale.ROOT);
        if (!d.contains(".")) return true; // Single label like "com", "cn", "localhost" is a TLD
        return COMMON_PUBLIC_SUFFIXES.contains(d);
    }

    static boolean isSafeCssSelector(String selector) {
        if (selector == null) return false;
        String s = selector.trim();
        if (s.isEmpty() || s.length() > 512) return false;
        // Reject CSS block breakouts and uBlock/ABP procedural scriptlet extensions
        if (s.indexOf('{') >= 0 || s.indexOf('}') >= 0 || s.indexOf(';') >= 0
            || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return false;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("+js(") || lower.startsWith("^") || lower.contains(":-abp-")
            || lower.contains(":has-text(") || lower.contains(":xpath(") || lower.contains(":style(")) {
            return false;
        }
        return true;
    }

    private static void addScopedEntry(Map<String, List<String>> map, String key, String value) {
        List<String> list = map.get(key);
        if (list == null) {
            list = new ArrayList<String>();
            map.put(key, list);
        }
        list.add(value);
    }

    static boolean parseRuleLine(String rawLine,
                                 Set<String> blocked,
                                 Set<String> whiteList,
                                 List<String> paths,
                                 Map<String, List<String>> domainHiding,
                                 List<String> globalHiding) {
        RuleSet tmp = new RuleSet();
        boolean ok = parseRuleLine(rawLine, tmp);
        if (ok) {
            if (blocked != null) blocked.addAll(tmp.blockedDomains);
            if (whiteList != null) whiteList.addAll(tmp.whiteListDomains);
            if (paths != null) paths.addAll(tmp.pathPatterns);
            if (domainHiding != null) {
                for (Map.Entry<String, List<String>> e : tmp.domainElementHiding.entrySet()) {
                    for (String sel : e.getValue()) {
                        addScopedEntry(domainHiding, e.getKey(), sel);
                    }
                }
            }
            if (globalHiding != null) globalHiding.addAll(tmp.globalElementHiding);
        }
        return ok;
    }

    static boolean parseRuleLine(String rawLine, RuleSet out) {
        if (rawLine == null || out == null) return false;
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) {
            return false;
        }

        // Skip unsupported cosmetic exception / procedural rules (#@#, #?#, #$#)
        if (line.contains("#@#") || line.contains("#?#") || line.contains("#$#")) {
            return false;
        }

        // 1. Element hiding rule (##)
        int hashIdx = line.indexOf("##");
        if (hashIdx >= 0) {
            String domainPart = line.substring(0, hashIdx).trim();
            String selector = line.substring(hashIdx + 2).trim();
            if (!isSafeCssSelector(selector)) {
                return false;
            }
            if (domainPart.isEmpty()) {
                out.globalElementHiding.add(selector);
                return true;
            }
            String[] doms = domainPart.split(",");
            boolean addedAny = false;
            boolean hasNegationOnly = true;
            for (int i = 0; i < doms.length; i++) {
                String d = doms[i].trim().toLowerCase(Locale.ROOT);
                if (d.isEmpty()) continue;
                if (d.startsWith("~")) continue; // Skip negated domain tokens instead of storing "~domain"
                hasNegationOnly = false;
                addScopedEntry(out.domainElementHiding, d, selector);
                addedAny = true;
            }
            if (hasNegationOnly) {
                return false;
            }
            return addedAny;
        }

        // Strip network filter options ($...) and skip context-restricted options ($domain=, $popup, $csp, etc.)
        // so scoped rules never over-expand into global whole-site blocks or whitelists.
        if (!(line.startsWith("/") && line.endsWith("/") && line.length() > 2)) {
            int dollarIdx = line.lastIndexOf('$');
            if (dollarIdx >= 0) {
                String opts = line.substring(dollarIdx + 1).trim().toLowerCase(Locale.ROOT);
                if (opts.contains("domain=") || opts.contains("~") || opts.contains("badfilter")
                    || opts.contains("elemhide") || opts.contains("generichide") || opts.contains("specifichide")
                    || opts.contains("csp") || opts.contains("popup") || opts.contains("document")
                    || opts.contains("rewrite") || opts.contains("redirect")) {
                    return false;
                }
                line = line.substring(0, dollarIdx).trim();
                if (line.isEmpty()) return false;
            }
        }

        // 2. Whitelist rule @@||...
        if (line.startsWith("@@||")) {
            return parseDomainAnchorRule(line.substring(4), out.whiteListDomains, out.domainWhiteListPaths);
        }

        // 3. Domain anchor block rule ||...
        if (line.startsWith("||")) {
            return parseDomainAnchorRule(line.substring(2), out.blockedDomains, out.domainBlockedPaths);
        }

        // 4. Path pattern /pattern/
        if (line.startsWith("/") && line.endsWith("/") && line.length() > 2) {
            // Preserve slash boundaries (e.g. "/ads/") so substring matching never collaterally
            // blocks legitimate URLs containing words like "downloads" or "uploads".
            out.pathPatterns.add(line.toLowerCase(Locale.ROOT));
            return true;
        }

        // 5. Bare domain without slash or wildcard
        if (!line.contains("/") && !line.contains("*") && line.contains(".")) {
            String domain = line.trim().toLowerCase(Locale.ROOT);
            if (!isPublicSuffixOrTld(domain)) {
                out.blockedDomains.add(domain);
                return true;
            }
        }

        return false;
    }

    private static boolean parseDomainAnchorRule(String sub,
                                                 Set<String> wholeDomainSet,
                                                 Map<String, List<String>> scopedPathMap) {
        if (sub == null || sub.isEmpty()) return false;
        int caret = sub.indexOf('^');
        int slash = sub.indexOf('/');
        int colon = sub.indexOf(':');
        int qmark = sub.indexOf('?');
        int hostEnd = sub.length();
        if (caret >= 0 && caret < hostEnd) hostEnd = caret;
        if (slash >= 0 && slash < hostEnd) hostEnd = slash;
        if (colon >= 0 && colon < hostEnd) hostEnd = colon;
        if (qmark >= 0 && qmark < hostEnd) hostEnd = qmark;

        String domain = sub.substring(0, hostEnd).trim().toLowerCase(Locale.ROOT);
        if (domain.isEmpty() || domain.contains("*") || isPublicSuffixOrTld(domain)) {
            return false;
        }

        String remainder = sub.substring(hostEnd).trim();
        // Strip port if specified (e.g. ":8080/ads.js" -> "/ads.js")
        if (remainder.startsWith(":")) {
            int nextSlash = remainder.indexOf('/');
            int nextCaret = remainder.indexOf('^');
            int cut = -1;
            if (nextSlash >= 0 && nextCaret >= 0) cut = Math.min(nextSlash, nextCaret);
            else if (nextSlash >= 0) cut = nextSlash;
            else if (nextCaret >= 0) cut = nextCaret;
            remainder = cut >= 0 ? remainder.substring(cut) : "";
        }

        // Pure whole-domain rule: "||adserver.com", "||adserver.com^", or "||adserver.com/"
        if (remainder.isEmpty() || "^".equals(remainder) || "/".equals(remainder) || "^/".equals(remainder)) {
            wholeDomainSet.add(domain);
            return true;
        }

        // Path-scoped rule (e.g. "||youtube.com/get_midroll_info" or "@@||bing.com/fd/ls/GLinkPing.aspx?"):
        // Never escalate to a whole-domain block/whitelist! Match path prefix under this domain hierarchy.
        if (remainder.startsWith("^")) {
            remainder = remainder.substring(1);
        }
        while (remainder.endsWith("^")) {
            remainder = remainder.substring(0, remainder.length() - 1);
        }
        if (remainder.isEmpty() || remainder.contains("*")) {
            return false;
        }
        if (!remainder.startsWith("/") && !remainder.startsWith("?")) {
            return false;
        }
        addScopedEntry(scopedPathMap, domain, remainder.toLowerCase(Locale.ROOT));
        return true;
    }

    static boolean matchesDomainHierarchy(String host, Set<String> domainSet) {
        if (host == null || domainSet == null || domainSet.isEmpty()) return false;
        String cur = host.toLowerCase(Locale.ROOT);
        while (cur != null && !cur.isEmpty()) {
            if (isPublicSuffixOrTld(cur)) {
                break; // Guard against collateral blocking when climbing up to TLD or public suffixes
            }
            if (domainSet.contains(cur)) return true;
            int dot = cur.indexOf('.');
            if (dot < 0 || dot == cur.length() - 1) break;
            cur = cur.substring(dot + 1);
        }
        return false;
    }

    private static boolean matchesDomainScopedPath(String host, String pathAndQueryLower, Map<String, List<String>> scopedMap) {
        if (host == null || pathAndQueryLower == null || scopedMap == null || scopedMap.isEmpty()) return false;
        String cur = host.toLowerCase(Locale.ROOT);
        while (cur != null && !cur.isEmpty()) {
            if (isPublicSuffixOrTld(cur)) break;
            List<String> prefixes = scopedMap.get(cur);
            if (prefixes != null) {
                for (int i = 0; i < prefixes.size(); i++) {
                    String p = prefixes.get(i);
                    if (pathAndQueryLower.startsWith(p) || pathAndQueryLower.contains(p)) {
                        return true;
                    }
                }
            }
            int dot = cur.indexOf('.');
            if (dot < 0 || dot == cur.length() - 1) break;
            cur = cur.substring(dot + 1);
        }
        return false;
    }

    private static boolean equalsHostOrSubdomain(String host, String rootDomain) {
        return host.equals(rootDomain) || host.endsWith("." + rootDomain);
    }

    public boolean isWhitelisted(String host) {
        return isHostWhitelisted(host, mRules);
    }

    static boolean isHostWhitelisted(String host, RuleSet rules) {
        if (host == null) return false;
        String cur = host.toLowerCase(Locale.ROOT).trim();
        while (cur.endsWith(".")) {
            cur = cur.substring(0, cur.length() - 1).trim();
        }
        if (equalsHostOrSubdomain(cur, "apis.google.com")
            || equalsHostOrSubdomain(cur, "gstatic.com")
            || equalsHostOrSubdomain(cur, "googleusercontent.com")
            || cur.startsWith("accounts.google.")
            || cur.contains(".accounts.google.")
            || cur.startsWith("myaccount.google.")
            || cur.contains(".myaccount.google.")) {
            return true;
        }
        return rules != null && matchesDomainHierarchy(cur, rules.whiteListDomains);
    }

    public boolean isBlocked(Uri uri) {
        if (!mEnabled || uri == null) return false;
        return isUriBlocked(uri, mRules);
    }

    static boolean isUriBlocked(Uri uri, RuleSet rules) {
        if (uri == null || rules == null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT).trim();
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1).trim();
        }

        if (isHostWhitelisted(host, rules)) {
            return false;
        }

        String uriStr = uri.toString().toLowerCase(Locale.ROOT);
        String pathAndQuery = extractPathAndQueryLower(uriStr);

        // 1. Scoped path exception (e.g. @@||bing.com/fd/ls/GLinkPing.aspx?)
        if (matchesDomainScopedPath(host, pathAndQuery, rules.domainWhiteListPaths)) {
            return false;
        }

        // 2. Whole-domain block hierarchy: e.g. pos.baidu.com -> baidu.com
        if (matchesDomainHierarchy(host, rules.blockedDomains)) {
            return true;
        }

        // 3. Domain-scoped path block: e.g. ||youtube.com/get_midroll_info
        if (matchesDomainScopedPath(host, pathAndQuery, rules.domainBlockedPaths)) {
            return true;
        }

        // 4. Global URL path patterns
        List<String> patterns = rules.pathPatterns;
        for (int i = 0; i < patterns.size(); i++) {
            if (uriStr.contains(patterns.get(i))) {
                return true;
            }
        }

        return false;
    }

    private static String extractPathAndQueryLower(String lowerUrl) {
        if (lowerUrl == null) return "/";
        int schemeIdx = lowerUrl.indexOf("://");
        int start = schemeIdx >= 0 ? schemeIdx + 3 : 0;
        int slash = lowerUrl.indexOf('/', start);
        int qmark = lowerUrl.indexOf('?', start);
        if (slash >= 0 && (qmark < 0 || slash < qmark)) {
            return lowerUrl.substring(slash);
        }
        if (qmark >= 0) {
            return "/" + lowerUrl.substring(qmark);
        }
        return "/";
    }

    public WebResourceResponse shouldIntercept(Uri uri) {
        if (isBlocked(uri)) {
            int count = mBlockedCount.incrementAndGet();
            BrowserDataStore store = mDataStore;
            if (store != null && (count % 5 == 0 || count == 1)) {
                store.setAdBlockBlockedCount(count);
            }
            Log.d(TAG, "[AdBlock Intercepted #" + count + "] " + uri);
            return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        }
        return null;
    }

    static String buildIsolatedElementHidingCss(RuleSet rules, String host) {
        StringBuilder css = new StringBuilder();
        final String decl = " { display: none !important; height: 0 !important; visibility: hidden !important; pointer-events: none !important; }\n";
        if (rules != null) {
            List<String> globals = rules.globalElementHiding;
            for (int i = 0; i < globals.size(); i++) {
                String sel = globals.get(i);
                if (isSafeCssSelector(sel)) {
                    css.append(sel).append(decl);
                }
            }
            if (host != null) {
                String cur = host.toLowerCase(Locale.ROOT);
                while (cur != null && !cur.isEmpty()) {
                    List<String> list = rules.domainElementHiding.get(cur);
                    if (list != null) {
                        for (int i = 0; i < list.size(); i++) {
                            String sel = list.get(i);
                            if (isSafeCssSelector(sel)) {
                                css.append(sel).append(decl);
                            }
                        }
                    }
                    int dot = cur.indexOf('.');
                    if (dot < 0 || dot == cur.length() - 1) break;
                    cur = cur.substring(dot + 1);
                }
            }
        }
        // General fallback mobile cleanup selectors
        css.append(".ads, .ad-banner, .advertisement, [id^='ad_'], [id^='ad-'], [class*='app-open']").append(decl);
        return css.toString();
    }

    public void injectElementHidingCss(WebView view, String pageUrl) {
        if (!mEnabled || view == null || pageUrl == null) return;
        Uri uri = Uri.parse(pageUrl);
        String host = uri.getHost();
        if (host != null) host = host.toLowerCase(Locale.ROOT);

        String cssRules = buildIsolatedElementHidingCss(mRules, host);
        String js = "(function() {" +
            "var root = document.head || document.documentElement;" +
            "if (!root) return;" +
            "var s = document.getElementById('__acid_adblock_css__');" +
            "if (!s) {" +
            "  s = document.createElement('style');" +
            "  s.id = '__acid_adblock_css__';" +
            "  s.type = 'text/css';" +
            "  s.textContent = " + JSONObject.quote(cssRules) + ";" +
            "  root.appendChild(s);" +
            "} else {" +
            "  s.textContent = " + JSONObject.quote(cssRules) + ";" +
            "}" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    public String getCustomRulesText() {
        return AcidIo.readFileUtf8OrEmpty(mCustomRulesFile, AcidHttp.DEFAULT_MAX_BYTES);
    }

    public boolean saveCustomRulesText(String text) {
        if (mCustomRulesFile == null) return false;
        if (!AcidIo.writeFileUtf8Safe(mCustomRulesFile, text)) {
            return false;
        }
        loadAllRulesSync();
        return true;
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
