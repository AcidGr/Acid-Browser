package com.acid.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Synchronous thread-safe Web Automation Driver.
 * Provides Puppeteer/CDP-like atomic automation operations for LLM Agent Loops and HTTP control gateways.
 */
public class WebAutomationDriver {

    private static final String TAG = "WebAutomationDriver";

    public interface AutomationHost {
        BrowserController.TabSession getActiveTab();
        BrowserController.TabSession getTargetTab();
        boolean isTargetTabActive();
        AgentOverlayView getOverlayView();
        UserscriptManager getUserscriptManager();
        void loadUrl(String url);
        void emitLog(String log);
        boolean isSomEnabled();
        boolean isDesktopMode();
        int getBlockedDeepLinksCount();
        String getLastBlockedDeepLink();
        int getTriggeredDownloadsCount();
        String getLastTriggeredDownloadInfo();
    }

    private final Context mContext;
    private final AutomationHost mHost;
    private final Handler mMainHandler;

    public WebAutomationDriver(Context context, AutomationHost host) {
        mContext = context;
        mHost = host;
        Handler h = null;
        try {
            if (Looper.getMainLooper() != null) {
                h = new Handler(Looper.getMainLooper());
            }
        } catch (Throwable ignored) {}
        mMainHandler = h;
    }

    private static boolean isMainLooper() {
        try {
            Looper main = Looper.getMainLooper();
            return main != null && Looper.myLooper() == main;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String unescapeEvaluateResult(String raw) {
        if (raw == null || "null".equals(raw)) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                Object val = new JSONTokener(trimmed).nextValue();
                return val != null ? val.toString() : "";
            } catch (Exception e) {
                Log.w(TAG, "evaluateJavascript result is not a JSON string literal; using raw text", e);
            }
        }
        return trimmed;
    }

    public String evaluateJsSync(final String jsExpr, long timeoutMs) {
        // Enforce thread contract: never allow blocking latch.await on Android's UI message loop
        if (isMainLooper()) {
            Log.w(TAG, "evaluateJsSync called directly on MainLooper; dispatching asynchronously without latch await to prevent UI deadlock");
            BrowserController.TabSession targetTab = mHost.getTargetTab();
            if (targetTab == null || targetTab.webView == null) {
                return "{\"ok\":false,\"error\":\"NO_TARGET_TAB\"}";
            }
            try {
                targetTab.webView.evaluateJavascript(jsExpr, null);
                return "{\"ok\":true,\"dispatched\":true}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> ref = new AtomicReference<String>("{\"ok\":false,\"error\":\"TIMEOUT\"}");

        if (mMainHandler == null) {
            return "{\"ok\":false,\"error\":\"NO_HANDLER\"}";
        }

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    BrowserController.TabSession targetTab = mHost.getTargetTab();
                    if (targetTab == null || targetTab.webView == null) {
                        ref.set("{\"ok\":false,\"error\":\"NO_TARGET_TAB\"}");
                        latch.countDown();
                        return;
                    }
                    targetTab.webView.evaluateJavascript(jsExpr, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            ref.set(unescapeEvaluateResult(value));
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "evaluateJavascript crashed on UI thread: " + t.getMessage());
                    ref.set("{\"ok\":false,\"error\":\"EVAL_EXCEPTION: " + JSONObject.quote(String.valueOf(t.getMessage())) + "\"}");
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // preserve the interrupt for the caller
            Log.w(TAG, "Interrupted while waiting for a JS evaluation result", e);
        }
        return ref.get();
    }

    private static final String EVAL_PARSED_MARKER = "__acid_parsed__";

    /**
     * Shared in-page serializer: Element/NodeList/Map/Set/circular/function/BigInt/NaN safe.
     * Defines __acidSer(value, mode) -> JSON string payload.
     */
    private static final String EVAL_SERIALIZER_JS =
        "function __acidSer(v, mode){\n" +
        "  var MAXD=6, MAXN=200, MAXS=100000;\n" +
        "  var path=[];\n" +
        "  function isEl(x){ return typeof Element!=='undefined' && x instanceof Element; }\n" +
        "  function isList(x){ return (typeof NodeList!=='undefined' && x instanceof NodeList) || (typeof HTMLCollection!=='undefined' && x instanceof HTMLCollection); }\n" +
        "  function elDesc(el){\n" +
        "    var o={__type:'Element', tag:String(el.tagName||'').toLowerCase()};\n" +
        "    if (el.id) o.id=el.id;\n" +
        "    var cn=(typeof el.className==='string')?el.className:(el.getAttribute&&el.getAttribute('class'));\n" +
        "    if (cn) o.className=String(cn).substring(0,120);\n" +
        "    var t=String(el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim();\n" +
        "    if (t) o.text=t.substring(0,150);\n" +
        "    if (el.getAttribute){ var h=el.getAttribute('href'); if(h) o.href=h; var nm=el.getAttribute('name'); if(nm) o.name=nm; }\n" +
        "    if ('value' in el && typeof el.value==='string' && el.value) o.value=el.value.substring(0,150);\n" +
        "    return o;\n" +
        "  }\n" +
        "  function conv(x,d){\n" +
        "    var t=typeof x;\n" +
        "    if (x===null||t==='boolean'||t==='string') return x;\n" +
        "    if (t==='number') return isFinite(x)?x:String(x);\n" +
        "    if (t==='undefined') return null;\n" +
        "    if (t==='bigint') return x.toString()+'n';\n" +
        "    if (t==='symbol') return x.toString();\n" +
        "    if (t==='function') return '[Function '+(x.name||'anonymous')+']';\n" +
        "    if (path.indexOf(x)>=0) return '[Circular]';\n" +
        "    if (d>MAXD) return '[MaxDepth]';\n" +
        "    try {\n" +
        "      if (typeof window!=='undefined' && x===window) return '[Window '+location.href+']';\n" +
        "      if (typeof document!=='undefined' && x===document) return '[Document '+location.href+']';\n" +
        "      if (isEl(x)) return elDesc(x);\n" +
        "      if (typeof Node!=='undefined' && x instanceof Node) return {__type:x.nodeName, text:String(x.textContent||'').trim().substring(0,150)};\n" +
        "      if (x instanceof Error) return {__type:'Error', name:x.name, message:x.message};\n" +
        "      if (x instanceof Date) return isNaN(x.getTime())?'Invalid Date':x.toISOString();\n" +
        "      if (x instanceof RegExp) return String(x);\n" +
        "      if (typeof Promise!=='undefined' && x instanceof Promise) return '[Promise]';\n" +
        "      path.push(x);\n" +
        "      try {\n" +
        "        var i, out, n;\n" +
        "        if (typeof Map!=='undefined' && x instanceof Map){ out={__type:'Map', size:x.size, entries:[]}; n=0; x.forEach(function(val,key){ if(n++<MAXN) out.entries.push([conv(key,d+1),conv(val,d+1)]); }); return out; }\n" +
        "        if (typeof Set!=='undefined' && x instanceof Set){ out={__type:'Set', size:x.size, values:[]}; n=0; x.forEach(function(val){ if(n++<MAXN) out.values.push(conv(val,d+1)); }); return out; }\n" +
        "        if (Array.isArray(x) || isList(x) || (typeof ArrayBuffer!=='undefined' && ArrayBuffer.isView && ArrayBuffer.isView(x))){\n" +
        "          out=[]; n=x.length;\n" +
        "          for (i=0;i<n && i<MAXN;i++) out.push(conv(x[i],d+1));\n" +
        "          if (n>MAXN) out.push('[... '+(n-MAXN)+' more]');\n" +
        "          return out;\n" +
        "        }\n" +
        "        if (typeof x.toJSON==='function'){ try { return conv(x.toJSON(),d+1); } catch(e){} }\n" +
        "        out={}; var keys=Object.keys(x);\n" +
        "        for (i=0;i<keys.length && i<MAXN;i++){\n" +
        "          var k=keys[i], val;\n" +
        "          try { val=x[k]; } catch(e){ out[k]='[Unreadable]'; continue; }\n" +
        "          if (val===undefined) continue;\n" +
        "          out[k]=conv(val,d+1);\n" +
        "        }\n" +
        "        if (keys.length>MAXN) out.__truncatedKeys=keys.length-MAXN;\n" +
        "        return out;\n" +
        "      } finally { path.pop(); }\n" +
        "    } catch(e){ return '[Unserializable: '+String(e&&e.message||e)+']'; }\n" +
        "  }\n" +
        "  var rt;\n" +
        "  if (v===undefined) rt='undefined'; else if (v===null) rt='null'; else if (Array.isArray(v)) rt='array';\n" +
        "  else if (isEl(v)) rt='element'; else if (isList(v)) rt='nodelist'; else rt=typeof v;\n" +
        "  var s;\n" +
        "  try { s=JSON.stringify({ok:true, result:conv(v,0), result_type:rt, mode:mode}); }\n" +
        "  catch(e){ s=JSON.stringify({ok:true, result:String(v), result_type:rt, mode:mode, note:'stringify fallback: '+String(e&&e.message||e)}); }\n" +
        "  if (s.length>MAXS) s=JSON.stringify({ok:true, truncated:true, original_length:s.length, result_type:rt, mode:mode, result_preview:s.substring(0,MAXS)});\n" +
        "  return s;\n" +
        "}\n";

    private static String buildEvalWrapper(String callId, String body, String mode) {
        return "(function(){\n" +
            "var __acidCid=" + JSONObject.quote(callId) + ";\n" +
            "var __acidPartial=undefined;\n" +
            "window.__partial=function(v){ __acidPartial=v; return v; };\n" +
            "try {\n" +
            "  (async function(){\n" + body + "\n})().then(\n" +
            "    function(v){ var p; try { p=__acidSer(v," + JSONObject.quote(mode) + "); } catch(e){ p=__acidErr(e); } __acidSend(p); },\n" +
            "    function(e){ __acidSend(__acidErr(e)); });\n" +
            "} catch(e) { __acidSend(__acidErr(e)); }\n" +
            "return " + JSONObject.quote(EVAL_PARSED_MARKER) + ";\n" +
            "function __acidSend(p){\n" +
            "  (window.__acidEvalResults = window.__acidEvalResults || {})[__acidCid] = p;\n" +
            "}\n" +
            "function __acidErr(e){\n" +
            "  var stack = String(e && e.stack || '');\n" +
            "  var m = stack.match(/<anonymous>:(\\d+):(\\d+)/);\n" +
            "  var line = m ? Math.max(1, parseInt(m[1], 10) - 6) : undefined;\n" +
            "  var o={ok:false, error_code:'JS_EXCEPTION', error:String(e&&e.message||e), mode:" + JSONObject.quote(mode) + "};\n" +
            "  if (line !== undefined) {\n" +
            "    o.error_line = line;\n" +
            "    o.error = (e && e.name ? e.name + ': ' : '') + String(e && e.message || e) + ' (line ' + line + ')';\n" +
            "  }\n" +
            "  if (typeof __acidPartial !== 'undefined') {\n" +
            "    try { o.partial_result = JSON.parse(__acidSer(__acidPartial, 'partial')).result; } catch(ex){}\n" +
            "  }\n" +
            "  if (e && e.name) o.name=e.name;\n" +
            "  if (e && e.stack) o.stack=stack.split('\\n').slice(0,4).join('\\n');\n" +
            "  return JSON.stringify(o);\n" +
            "}\n" +
            EVAL_SERIALIZER_JS +
            "})()";
    }

    private static String evalError(String code, String message, long timeoutMs) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error_code", code);
            o.put("error", message);
            if (timeoutMs > 0) o.put("timeout_ms", timeoutMs);
        } catch (Exception e) {
            Log.w(TAG, "Failed to encode eval error payload (" + code + ")", e);
        }
        return o.toString();
    }

    /**
     * Evaluates user JS (expression, statements, or function body; async/await supported) and returns
     * a JSON string: {ok, result, result_type, mode} or {ok:false, error_code, error}.
     *
     * Candidates from {@link JsEvalShaper} are injected in order. A candidate with a syntax error is
     * rejected by the JS engine before any of it runs (evaluateJavascript yields null instead of the
     * parse marker), so falling through to the next shape never duplicates side effects.
     */
    public String evaluateJsAsync(final String userCode, long timeoutMs) {
        long effTimeout = Math.max(200, timeoutMs);
        long deadline = SystemClock.uptimeMillis() + effTimeout;
        List<JsEvalShaper.Candidate> candidates = JsEvalShaper.candidates(userCode);
        StringBuilder triedModes = new StringBuilder();

        for (JsEvalShaper.Candidate cand : candidates) {
            final String callId = "eval_" + System.nanoTime() + "_" + (int) (Math.random() * 100000);
            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0) {
                return evalError("ASYNC_TIMEOUT", "eval timed out before code could be injected", effTimeout);
            }
            String parsed = evaluateJsSync(buildEvalWrapper(callId, cand.body, cand.mode), Math.min(remaining, 3000));
            if (parsed != null && (parsed.contains("\"NO_ACTIVE_TAB\"") || parsed.contains("\"NO_TARGET_TAB\""))) {
                return evalError("NO_ACTIVE_TAB", "no active tab", 0);
            }
            boolean parsedOk = EVAL_PARSED_MARKER.equals(parsed);
            boolean injectTimedOut = parsed != null && parsed.contains("\"TIMEOUT\"");
            if (!parsedOk && !injectTimedOut) {
                // Syntax error for this shape: nothing executed, try the next shape.
                if (triedModes.length() > 0) triedModes.append(", ");
                triedModes.append(cand.mode);
                continue;
            }
            return pollEvalResult(callId, deadline, effTimeout);
        }

        JSONObject err = new JSONObject();
        try {
            err.put("ok", false);
            err.put("error_code", "SYNTAX_ERROR");
            err.put("error", diagnoseSyntaxError(userCode));
            err.put("tried_modes", triedModes.toString());
            err.put("hint", "Code was not executed. Pass an expression (e.g. document.title), statements whose last line is the value, or a function body with an explicit return.");
        } catch (Exception e) {
            Log.w(TAG, "Failed to encode syntax-error payload", e);
        }
        return err.toString();
    }

    private String pollEvalResult(String callId, long deadline, long effTimeout) {
        String q = JSONObject.quote(callId);
        String expr = "(function(){var m=window.__acidEvalResults; if(!m||!(" + q + " in m)) return null;" +
            " var p=m[" + q + "]; delete m[" + q + "]; return p;})()";
        while (SystemClock.uptimeMillis() < deadline) {
            String r = evaluateJsSync(expr, 1000);
            if (r != null && r.startsWith("{") && !r.contains("\"error\":\"TIMEOUT\"")) {
                return r;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return evalError("INTERRUPTED", "eval interrupted", 0);
            }
        }
        return evalError("ASYNC_TIMEOUT", "code started but its result did not settle within timeout", effTimeout);
    }

    /** Best-effort SyntaxError message via the AsyncFunction constructor (blocked on strict-CSP pages). */
    private String diagnoseSyntaxError(String userCode) {
        String code = userCode == null ? "" : userCode;
        String expr = "(function(){try{var AF=Object.getPrototypeOf(async function(){}).constructor;" +
            "new AF(" + JSONObject.quote(code) + ");return 'parse ok';}catch(e){" +
            "return (e&&e.name?e.name+': ':'')+String(e&&e.message||e);}})()";
        String r = evaluateJsSync(expr, 1500);
        if (r == null || r.isEmpty() || r.startsWith("{") || "parse ok".equals(r)) {
            return "SyntaxError (details unavailable)";
        }
        if (r.startsWith("EvalError")) {
            return "SyntaxError (details unavailable: page CSP blocks eval-based diagnostics)";
        }
        return r;
    }

    private JSONObject buildFallbackObservation(String errorCode, String message) {
        JSONObject err = new JSONObject();
        try {
            err.put("ok", false);
            err.put("error_code", errorCode);
            err.put("error", message);
            err.put("retryable", true);
            err.put("hint", "页面可能仍在加载中，请稍候重试或调用 browser(action='wait', condition='stable') 等待稳定");

            final JSONObject partial = new JSONObject();
            if (isMainLooper()) {
                try {
                    BrowserController.TabSession tab = mHost.getTargetTab();
                    if (tab != null && tab.webView != null) {
                        partial.put("url", tab.webView.getUrl() != null ? tab.webView.getUrl() : tab.url);
                        partial.put("title", tab.webView.getTitle() != null ? tab.webView.getTitle() : tab.title);
                        partial.put("progress", tab.webView.getProgress());
                        partial.put("isLoading", tab.isLoading);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to collect partial page metadata while building fallback observation", e);
                }
            } else {
                final CountDownLatch latch = new CountDownLatch(1);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            BrowserController.TabSession tab = mHost.getTargetTab();
                            if (tab != null && tab.webView != null) {
                                partial.put("url", tab.webView.getUrl() != null ? tab.webView.getUrl() : tab.url);
                                partial.put("title", tab.webView.getTitle() != null ? tab.webView.getTitle() : tab.title);
                                partial.put("progress", tab.webView.getProgress());
                                partial.put("isLoading", tab.isLoading);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to collect partial page metadata while building fallback observation", e);
                        }
                        latch.countDown();
                    }
                });
                try {
                    latch.await(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            err.put("partial", partial);

            String url = partial.optString("url", "about:blank");
            String title = partial.optString("title", "");
            String fallbackCompact = "=== [BROWSER STATE: " + errorCode + "] ===\n" +
                "Status: 页面暂时不可观测 (" + message + ")\n" +
                "URL: " + url + "\n" +
                "Title: " + title + "\n" +
                "Interactive Elements (0):\n" +
                "(暂无可用交互句柄，页面仍在渲染中，建议使用 browser(action='wait') 确认稳定)";
            err.put("compactText", fallbackCompact);
        } catch (Exception e) {
            Log.e(TAG, "Failed to build fallback observation (" + errorCode + ")", e);
        }
        return err;
    }

    public void ensureBridgeInjectedSync() {
        try {
            String check = evaluateJsSync("(function(){ return (window.__acidBridge && window.__acidBridge.__initialized) ? 'ok' : 'missing'; })()", 800);
            if (!"ok".equals(check)) {
                UserscriptManager um = mHost.getUserscriptManager();
                if (um != null) {
                    final String bridgeJs = um.getBridgeJs();
                    if (bridgeJs != null && !bridgeJs.isEmpty()) {
                        if (isMainLooper()) {
                            BrowserController.TabSession tab = mHost.getTargetTab();
                            if (tab != null && tab.webView != null) {
                                tab.webView.evaluateJavascript(bridgeJs, null);
                                if (mHost.isDesktopMode()) {
                                    tab.webView.evaluateJavascript(
                                        "if(window.__acidBridge&&window.__acidBridge.enforceDesktopViewport){window.__acidBridge.enforceDesktopViewport(true);}",
                                        null
                                    );
                                }
                            }
                        } else {
                            final CountDownLatch latch = new CountDownLatch(1);
                            mMainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        BrowserController.TabSession tab = mHost.getTargetTab();
                                        if (tab != null && tab.webView != null) {
                                            tab.webView.evaluateJavascript(bridgeJs, null);
                                            if (mHost.isDesktopMode()) {
                                                tab.webView.evaluateJavascript(
                                                    "if(window.__acidBridge&&window.__acidBridge.enforceDesktopViewport){window.__acidBridge.enforceDesktopViewport(true);}",
                                                    null
                                                );
                                            }
                                        }
                                    } catch (Throwable t) {
                                        Log.w(TAG, "Bridge re-injection on UI thread caught: " + t.getMessage());
                                    } finally {
                                        latch.countDown();
                                    }
                                }
                            });
                            try {
                                latch.await(1000, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
            } else if (mHost.isDesktopMode()) {
                evaluateJsSync("if(window.__acidBridge&&window.__acidBridge.enforceDesktopViewport){window.__acidBridge.enforceDesktopViewport(true);}", 500);
            }
        } catch (Exception e) {
            Log.w(TAG, "Bridge re-injection failed; automation may degrade for this page", e);
        }
    }

    private JSONObject buildDegradedObservation() {
        String degradedJs = "(function(){\n" +
            "try {\n" +
            "  var refs = window.__refs || {};\n" +
            "  var nodes = document.querySelectorAll('a, button, input, select, textarea, [role=button], [role=link]');\n" +
            "  var items = [];\n" +
            "  var lines = [];\n" +
            "  lines.push('Notice: [DEGRADED_JS_CHANNEL] Full agent_bridge unavailable on this page; auto-downgraded to inline JS channel. Element refs [e1..eN] and window.__refs remain usable.');\n" +
            "  lines.push('Page: ' + (document.title || ''));\n" +
            "  lines.push('URL: ' + location.href);\n" +
            "  lines.push('Interactive Elements (' + Math.min(nodes.length, 60) + '):');\n" +
            "  var count = 0;\n" +
            "  for (var i = 0; i < nodes.length && count < 60; i++) {\n" +
            "    var el = nodes[i];\n" +
            "    var tag = el.tagName.toLowerCase();\n" +
            "    var rect = el.getBoundingClientRect();\n" +
            "    if (rect.width < 4 || rect.height < 4) continue;\n" +
            "    count++;\n" +
            "    var ref = 'e' + count;\n" +
            "    refs[ref] = el;\n" +
            "    var label = (el.innerText || el.getAttribute('aria-label') || el.name || el.value || '').replace(/\\s+/g, ' ').trim().substring(0, 60);\n" +
            "    var desc = '  [' + ref + '] ' + tag + (label ? ' \"' + label + '\"' : '');\n" +
            "    if (tag === 'a' && el.href) desc += ' -> ' + el.getAttribute('href');\n" +
            "    lines.push(desc);\n" +
            "    items.push({ ref: ref, tag: tag, label: label, inViewport: true });\n" +
            "  }\n" +
            "  window.__refs = refs;\n" +
            "  return JSON.stringify({\n" +
            "    ok: true,\n" +
            "    degraded: true,\n" +
            "    warning: 'Full agent_bridge unavailable; downgraded to basic JS extraction.',\n" +
            "    url: location.href,\n" +
            "    title: document.title || '',\n" +
            "    elementCount: items.length,\n" +
            "    elements: items,\n" +
            "    compactText: lines.join('\\n')\n" +
            "  });\n" +
            "} catch(e) { return null; }\n" +
            "})()";
        String raw = evaluateJsSync(degradedJs, 2500);
        if (raw != null && raw.startsWith("{")) {
            try {
                return new JSONObject(raw);
            } catch (Exception e) {
                Log.w(TAG, "Degraded observation payload is not valid JSON; discarding", e);
            }
        }
        return null;
    }

    public JSONObject observeSync(boolean includeSom, boolean viewportOnly, boolean includeScreenshot) {
        ensureBridgeInjectedSync();
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.observe({includeSom:" +
                includeSom + ", viewportOnly:" + viewportOnly + "})); })()";
            String raw = evaluateJsSync(expr, 6000);
            // No target tab at all (the agent's tab was closed and it has not rebound yet).
            // Report it as such instead of falling through to the "degraded" path, which would
            // blame page loading and mislead the agent into waiting instead of switching tabs.
            if (raw != null && raw.contains("NO_TARGET_TAB")) {
                return buildFallbackObservation("NO_TARGET_TAB",
                    "智能体当前没有绑定的标签页（原标签页已被关闭）。请先调用 browser(action='tabs', tab_action='list') "
                    + "查看现有标签页，再用 tab_action='switch' 或 'new' 指定目标后继续。");
            }
            if (raw != null && !raw.trim().isEmpty() && !"null".equals(raw)) {
                JSONObject obj = new JSONObject(raw);
                if (obj.optBoolean("ok", false)) {
                    AgentOverlayView overlay = mHost.getOverlayView();
                    int cMode = overlay != null ? overlay.getControlMode() : AgentOverlayView.MODE_HUMAN;
                    obj.put("controlMode", cMode == AgentOverlayView.MODE_AGENT ? "agent" : "human");
                    obj.put("blockedDeepLinks", mHost.getBlockedDeepLinksCount());
                    String lastBlocked = mHost.getLastBlockedDeepLink();
                    if (lastBlocked != null && !lastBlocked.isEmpty()) {
                        obj.put("lastBlockedDeepLink", lastBlocked);
                    }
                    obj.put("triggeredDownloads", mHost.getTriggeredDownloadsCount());
                    String lastDl = mHost.getLastTriggeredDownloadInfo();
                    if (lastDl != null && !lastDl.isEmpty()) {
                        obj.put("lastTriggeredDownload", lastDl);
                    }
                    if (includeScreenshot) {
                        String b64 = captureScreenshotBase64Sync(75);
                        if (b64 != null) {
                            obj.put("screenshotBase64", b64);
                        }
                    }
                    return obj;
                }
            }
            JSONObject degraded = buildDegradedObservation();
            if (degraded != null) {
                return degraded;
            }
            return buildFallbackObservation("SNAPSHOT_EMPTY_RESULT", "页面未返回有效观察数据，可能正在加载");
        } catch (Exception e) {
            JSONObject degraded = buildDegradedObservation();
            if (degraded != null) {
                return degraded;
            }
            return buildFallbackObservation("SNAPSHOT_PARSE_ERROR", "快照解析失败: " + e.getMessage());
        }
    }

    /**
     * Waits for a condition. Returns a structured receipt:
     * {ok, satisfied, wait_satisfied, timed_out, condition, target, elapsed_ms, quiet_ms, timeout_ms,
     *  checks, last_check, error_code?, error?, hint?, url, title, snapshot}
     * ok == satisfied; a timeout is ok:false + timed_out:true + error_code WAIT_TIMEOUT.
     */
    public JSONObject waitConditionSync(String condition, String target, long quietMs, long timeoutMs) {
        ensureBridgeInjectedSync();
        long effectiveQuietMs = Math.max(50, quietMs > 0 ? quietMs : 300);
        long effectiveTimeout = Math.max(effectiveQuietMs, Math.min(30000, timeoutMs > 0 ? timeoutMs : 5000));
        long startTime = SystemClock.uptimeMillis();
        long deadline = startTime + effectiveTimeout;

        String cond = (condition != null && !condition.trim().isEmpty()) ? condition.toLowerCase().trim() : "stable";
        if ("time".equals(cond) || "sleep".equals(cond)) cond = "ms";
        if ("load".equals(cond) || "complete".equals(cond)) cond = "networkidle";
        if ("visible".equals(cond)) cond = "selector";
        if ("hidden".equals(cond) || "detached".equals(cond)) cond = "gone";
        String tgt = (target != null && !target.trim().isEmpty()) ? target : null;

        boolean satisfied = false;
        String errorCode = null;
        String errorMsg = null;
        int checks = 0;
        JSONObject lastCheck = null;
        long reportedQuiet = effectiveQuietMs;
        long reportedTimeout = effectiveTimeout;

        boolean known = "stable".equals(cond) || "networkidle".equals(cond) || "selector".equals(cond)
            || "text".equals(cond) || "gone".equals(cond) || "ms".equals(cond);

        if (!known) {
            errorCode = "INVALID_ARGUMENT";
            errorMsg = "Unknown wait condition '" + cond + "'. Use one of: stable, networkidle, selector, text, gone, ms.";
        } else if (("selector".equals(cond) || "text".equals(cond) || "gone".equals(cond)) && tgt == null) {
            errorCode = "INVALID_ARGUMENT";
            errorMsg = "Condition '" + cond + "' requires " + ("text".equals(cond) ? "'text'" : "'selector' (or 'text' for gone)") + ".";
        } else if ("ms".equals(cond)) {
            long sleepTime = quietMs > 0 ? quietMs : 1000;
            if (quietMs <= 0 && tgt != null) {
                try {
                    sleepTime = Long.parseLong(tgt.trim());
                } catch (NumberFormatException e) {
                    Log.w(TAG, "wait(ms) target is not a number: " + tgt + "; using " + sleepTime + "ms", e);
                }
            }
            sleepTime = Math.max(0, Math.min(30000, sleepTime));
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted during wait(ms)", e);
            }
            satisfied = true;
            reportedQuiet = 0;
            reportedTimeout = sleepTime;
        } else {
            boolean isFirst = true;
            while (true) {
                String checkExpr = "(function(){\n" +
                    "  if (!window.__acidBridge || !window.__acidBridge.checkCondition) return JSON.stringify({ok:false, error:'BRIDGE_NOT_READY'});\n" +
                    "  return JSON.stringify(window.__acidBridge.checkCondition(" +
                    JSONObject.quote(cond) + ", " +
                    (tgt != null ? JSONObject.quote(tgt) : "null") + ", " +
                    effectiveQuietMs + ", " +
                    isFirst + "));\n" +
                    "})()";
                checks++;
                String raw = evaluateJsSync(checkExpr, 1500);
                JSONObject res = null;
                if (raw != null && raw.startsWith("{")) {
                    try {
                        res = new JSONObject(raw);
                    } catch (Exception e) {
                        Log.w(TAG, "Wait condition probe returned invalid JSON; retrying", e);
                    }
                }
                if (res != null) {
                    lastCheck = res;
                    if (res.optBoolean("ok", false)) {
                        isFirst = false; // baseline is only established once the bridge actually answered
                        if (res.optBoolean("satisfied", false)) {
                            satisfied = true;
                            break;
                        }
                    } else {
                        String e = res.optString("error", "");
                        if ("NO_ACTIVE_TAB".equals(e)) {
                            errorCode = "NO_ACTIVE_TAB";
                            errorMsg = "no active tab";
                            break;
                        }
                        if (!"BRIDGE_NOT_READY".equals(e) && !"TIMEOUT".equals(e)) {
                            // Deterministic failure (bad selector, unknown condition...): retrying cannot help.
                            errorCode = "selector".equals(cond) ? "INVALID_SELECTOR" : "INVALID_ARGUMENT";
                            errorMsg = e;
                            break;
                        }
                    }
                }
                if (SystemClock.uptimeMillis() >= deadline) break;
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Wait-condition poll loop interrupted; returning current result", e);
                    break;
                }
                if (SystemClock.uptimeMillis() >= deadline) break;
            }
            if (!satisfied && errorCode == null) {
                if (lastCheck == null || "BRIDGE_NOT_READY".equals(lastCheck.optString("error", ""))) {
                    errorCode = "BRIDGE_NOT_READY";
                    errorMsg = "agent bridge never became available on this page within " + effectiveTimeout
                        + "ms (page still loading, or its CSP blocked bridge injection); condition could not be evaluated";
                } else {
                    errorCode = "WAIT_TIMEOUT";
                    errorMsg = "condition '" + cond + "' not satisfied within " + effectiveTimeout + "ms";
                }
            }
        }

        long elapsed = SystemClock.uptimeMillis() - startTime;
        JSONObject snapshot = observeSync(mHost.isSomEnabled(), true, false);
        JSONObject out = new JSONObject();
        try {
            out.put("ok", satisfied);
            out.put("satisfied", satisfied);
            out.put("wait_satisfied", satisfied); // backward-compatible alias (v0.2.7)
            out.put("timed_out", "WAIT_TIMEOUT".equals(errorCode) || "BRIDGE_NOT_READY".equals(errorCode));
            out.put("condition", cond);
            out.put("target", tgt != null ? tgt : JSONObject.NULL);
            out.put("elapsed_ms", elapsed);
            if ("stable".equals(cond) || "networkidle".equals(cond)) out.put("quiet_ms", reportedQuiet);
            out.put("timeout_ms", reportedTimeout);
            out.put("checks", checks);
            if (lastCheck != null) out.put("last_check", lastCheck);
            if (errorCode != null) {
                out.put("error_code", errorCode);
                out.put("error", errorMsg);
            }
            if ("WAIT_TIMEOUT".equals(errorCode)) {
                if ("stable".equals(cond) || "networkidle".equals(cond)) {
                    out.put("hint", "页面在超时内未能连续静默 " + effectiveQuietMs + "ms（持续有 DOM 变动），可增大 timeout_ms 或改用 selector/text 条件");
                } else {
                    out.put("hint", "超时未满足条件，可检查 selector/text 是否正确，或增大 timeout_ms");
                }
            } else if ("BRIDGE_NOT_READY".equals(errorCode)) {
                out.put("hint", "页面内的 agent bridge 未就绪（页面加载中或 CSP 拦截了注入），无法判断条件；可用 browser(action='eval') 直接检查页面状态");
            } else if ("INVALID_SELECTOR".equals(errorCode) || "INVALID_ARGUMENT".equals(errorCode)) {
                out.put("hint", "参数错误，已立即返回未等待；请修正后重试");
            }
            out.put("url", snapshot.optString("url", snapshot.optJSONObject("partial") != null
                ? snapshot.optJSONObject("partial").optString("url", "") : ""));
            out.put("title", snapshot.optString("title", snapshot.optJSONObject("partial") != null
                ? snapshot.optJSONObject("partial").optString("title", "") : ""));
            out.put("snapshot", snapshot);
        } catch (Exception e) {
            Log.w(TAG, "Failed to assemble wait receipt", e);
        }
        return out;
    }

    public JSONObject navigateSync(final String url, long waitSettleMs) {
        if (isMainLooper() || mMainHandler == null) {
            Log.w(TAG, "navigateSync called without async Looper wait; dispatching target loadUrl directly");
            BrowserController.TabSession target = mHost.getTargetTab();
            if (target != null && target.webView != null) {
                target.url = url;
                target.webView.loadUrl(url);
            } else if (target != null) {
                target.url = url;
            } else {
                mHost.loadUrl(url);
            }
            mHost.emitLog("[NAV] Navigated -> " + url);
            return observeSync(mHost.isSomEnabled(), true, false);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                BrowserController.TabSession target = mHost.getTargetTab();
                if (target != null && target.webView != null) {
                    target.url = url;
                    target.webView.loadUrl(url);
                } else {
                    mHost.loadUrl(url);
                }
                latch.countDown();
            }
        });
        try {
            latch.await(2000, TimeUnit.MILLISECONDS);
            long deadline = SystemClock.uptimeMillis() + Math.max(1200, waitSettleMs);
            Thread.sleep(450);
            while (SystemClock.uptimeMillis() < deadline) {
                final AtomicReference<Boolean> loadingRef = new AtomicReference<Boolean>(false);
                final CountDownLatch stepLatch = new CountDownLatch(1);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        BrowserController.TabSession activeTab = mHost.getTargetTab();
                        loadingRef.set(activeTab != null && activeTab.isLoading);
                        stepLatch.countDown();
                    }
                });
                stepLatch.await(500, TimeUnit.MILLISECONDS);
                if (!loadingRef.get()) {
                    Thread.sleep(300);
                    break;
                }
                Thread.sleep(250);
            }
        } catch (Exception e) {
            Log.w(TAG, "Navigate settle-wait interrupted; continuing with current page state", e);
        }
        mHost.emitLog("[NAV] Navigated -> " + url);
        return observeSync(mHost.isSomEnabled(), true, false);
    }

    public JSONObject clickRefSync(final String targetRef, final boolean useNativeMotionEvent) {
        JSONObject opts = new JSONObject();
        try {
            opts.put("ref", targetRef);
        } catch (Exception e) {
            Log.w(TAG, "Failed to encode click ref: " + targetRef, e);
        }
        return clickTargetSync(opts, useNativeMotionEvent);
    }

    public JSONObject clickTargetSync(final JSONObject targetOpts, final boolean useNativeMotionEvent) {
        ensureBridgeInjectedSync();
        try {
            String prepExpr = "(function(){ return JSON.stringify(window.__acidBridge.prepareClick(" +
                (targetOpts != null ? targetOpts.toString() : "{}") + ")); })()";
            String prepRaw = evaluateJsSync(prepExpr, 4000);
            JSONObject prep = new JSONObject(prepRaw);
            if (!prep.optBoolean("ok", false)) {
                return prep;
            }

            final double cssX = prep.optDouble("cssX", 0);
            final double cssY = prep.optDouble("cssY", 0);
            final String targetRef = prep.optString("ref", "target");
            final String label = prep.optString("label", "");
            final int revBefore = prep.optInt("revisionBefore", 0);
            final String urlBefore = prep.optString("urlBefore", "");
            final String activeBefore = prep.optString("activeTagBefore", "") + "#" + prep.optString("activeIdBefore", "");
            final boolean occluded = prep.optBoolean("occluded", false);
            final boolean reResolved = prep.optBoolean("re_resolved", false);
            final String matchedBy = prep.optString("matchedBy", "ref");
            final JSONObject hit = prep.optJSONObject("hit") != null ? prep.optJSONObject("hit") : new JSONObject();

            boolean isForeground = mHost.isTargetTabActive();
            if (useNativeMotionEvent && isForeground) {
                final AtomicReference<Float> viewXRef = new AtomicReference<Float>(0f);
                final AtomicReference<Float> viewYRef = new AtomicReference<Float>(0f);

                if (isMainLooper()) {
                    BrowserController.TabSession activeTab = mHost.getTargetTab();
                    if (activeTab != null) {
                        WebView wv = activeTab.webView;
                        float scale = wv.getScale();
                        if (scale <= 0.01f) {
                            scale = mContext.getResources().getDisplayMetrics().density;
                        }
                        final float vx = (float) (cssX * scale);
                        final float vy = (float) (cssY * scale);
                        viewXRef.set(vx);
                        viewYRef.set(vy);

                        AgentOverlayView overlay = mHost.getOverlayView();
                        if (overlay != null && mHost.isTargetTabActive()) {
                            overlay.showClickPulse(vx, vy, "[" + targetRef + "] " + label);
                        }
                        dispatchHardwareMotionTap(wv, vx, vy);
                    }
                } else {
                    final CountDownLatch tapLatch = new CountDownLatch(1);
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                BrowserController.TabSession activeTab = mHost.getTargetTab();
                                if (activeTab == null || activeTab.webView == null) {
                                    return;
                                }
                                WebView wv = activeTab.webView;
                                float scale = wv.getScale();
                                if (scale <= 0.01f) {
                                    scale = mContext.getResources().getDisplayMetrics().density;
                                }
                                final float vx = (float) (cssX * scale);
                                final float vy = (float) (cssY * scale);
                                viewXRef.set(vx);
                                viewYRef.set(vy);

                                AgentOverlayView overlay = mHost.getOverlayView();
                                if (overlay != null && mHost.isTargetTabActive()) {
                                    overlay.showClickPulse(vx, vy, "[" + targetRef + "] " + label);
                                }
                                dispatchHardwareMotionTap(wv, vx, vy);
                            } catch (Throwable t) {
                                Log.w(TAG, "Native motion tap failed on UI thread: " + t.getMessage());
                            } finally {
                                tapLatch.countDown();
                            }
                        }
                    });
                    try {
                        tapLatch.await(2000, TimeUnit.MILLISECONDS);
                        Thread.sleep(220);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                String stateRaw = evaluateJsSync("(function(){ return JSON.stringify(window.__acidBridge.getState()); })()", 2000);
                JSONObject stateAfter = new JSONObject(stateRaw);
                int revAfter = stateAfter.optInt("revision", revBefore);
                String urlAfter = stateAfter.optString("url", urlBefore);
                String activeAfter = stateAfter.optString("activeTag", "") + "#" + stateAfter.optString("activeId", "");

                boolean domChanged = (revAfter > revBefore);
                boolean urlChanged = !urlBefore.isEmpty() && !urlBefore.equals(urlAfter);
                boolean focusChanged = !activeBefore.equals(activeAfter);
                boolean effectChanged = domChanged || urlChanged || focusChanged;
                String strategy = "ANDROID_NATIVE_MOTION_EVENT";

                if (!effectChanged && !occluded) {
                    String fallbackRaw = evaluateJsSync(
                        "(function(){ return JSON.stringify(window.__acidBridge.jsClick(" +
                        (targetOpts != null ? targetOpts.toString() : "{}") + ")); })()",
                        2000
                    );
                    try {
                        JSONObject fb = new JSONObject(fallbackRaw);
                        JSONObject fbEffect = fb.optJSONObject("effect");
                        if (fbEffect != null) {
                            effectChanged = fbEffect.optBoolean("changed", false);
                            revAfter = fb.optInt("revisionAfter", revAfter);
                            domChanged = fbEffect.optInt("dom_mutations", 0) > 0;
                            urlChanged = fbEffect.optBoolean("url_changed", urlChanged);
                            focusChanged = fbEffect.optBoolean("focus_changed", focusChanged);
                        }
                        strategy = "DOM_SYNTHETIC_FALLBACK";
                    } catch (Exception e) {
                        Log.w(TAG, "JS-synthetic click fallback returned invalid JSON", e);
                    }
                }

                mHost.emitLog("[CLICK] [" + targetRef + "] \"" + label + "\" @(" +
                    Math.round(viewXRef.get()) + "," + Math.round(viewYRef.get()) + ")" +
                    (reResolved ? " (re-resolved via " + matchedBy + ")" : ""));

                JSONObject effect = new JSONObject();
                effect.put("changed", effectChanged);
                effect.put("dom_mutations", Math.max(0, revAfter - revBefore));
                effect.put("url_changed", urlChanged);
                effect.put("focus_changed", focusChanged);

                hit.put("strategy", strategy);
                hit.put("ref", targetRef);
                hit.put("occluded", occluded);
                hit.put("ok", true);

                boolean verified = effectChanged && !occluded;

                JSONObject receipt = new JSONObject();
                receipt.put("ok", true);
                receipt.put("ref", targetRef);
                receipt.put("label", label);
                receipt.put("verified", verified);
                receipt.put("re_resolved", reResolved);
                receipt.put("matchedBy", matchedBy);
                receipt.put("hit", hit);
                receipt.put("effect", effect);
                if (!verified) {
                    if (occluded) {
                        receipt.put("hint", "目标元素中心点被上层其他浮层或弹窗遮挡 (occluded=true)，请先关闭遮挡层");
                    } else {
                        receipt.put("hint", "点击已派发，但页面未检测到DOM变动、URL变化或焦点切换 (effect.changed=false)，可能该元素无动作绑定或处于禁用状态");
                    }
                }
                return receipt;
            } else {
                String jsRaw = evaluateJsSync(
                    "(function(){ return JSON.stringify(window.__acidBridge.jsClick(" +
                    (targetOpts != null ? targetOpts.toString() : "{}") + ")); })()",
                    3000
                );
                mHost.emitLog("[CLICK] JS-Synthetic [" + targetRef + "] \"" + label + "\"" +
                    (reResolved ? " (re-resolved via " + matchedBy + ")" : ""));
                return new JSONObject(jsRaw);
            }
        } catch (Exception e) {
            Log.e(TAG, "clickTargetSync failed for " + (targetOpts != null ? targetOpts.toString() : "{}"), e);
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", e.getMessage());
            } catch (Exception ee) {
                Log.w(TAG, "Failed to encode click error payload", ee);
            }
            return err;
        }
    }

    public JSONObject clickCoordinateSync(final float viewX, final float viewY) {
        final boolean isForeground = mHost.isTargetTabActive();
        if (isMainLooper()) {
            BrowserController.TabSession targetTab = mHost.getTargetTab();
            if (targetTab != null && isForeground) {
                AgentOverlayView overlay = mHost.getOverlayView();
                if (overlay != null) {
                    overlay.showClickPulse(viewX, viewY, "(" + Math.round(viewX) + "," + Math.round(viewY) + ")");
                }
                dispatchHardwareMotionTap(targetTab.webView, viewX, viewY);
            }
        } else {
            final CountDownLatch latch = new CountDownLatch(1);
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        BrowserController.TabSession targetTab = mHost.getTargetTab();
                        if (targetTab != null && targetTab.webView != null && isForeground) {
                            AgentOverlayView overlay = mHost.getOverlayView();
                            if (overlay != null) {
                                overlay.showClickPulse(viewX, viewY, "(" + Math.round(viewX) + "," + Math.round(viewY) + ")");
                            }
                            dispatchHardwareMotionTap(targetTab.webView, viewX, viewY);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Coordinate click failed on UI thread: " + t.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });
            try {
                latch.await(2000, TimeUnit.MILLISECONDS);
                Thread.sleep(200);
            } catch (Exception e) {
                Log.w(TAG, "Coordinate click wait interrupted", e);
            }
        }
        if (!isForeground) {
            evaluateJsSync("(function(){ var el = document.elementFromPoint(" + viewX + "," + viewY + "); if (el) el.click(); })()", 2000);
        }
        mHost.emitLog("[CLICK] Coordinate (" + Math.round(viewX) + ", " + Math.round(viewY) + ")" + (!isForeground ? " [bg-elementFromPoint]" : ""));
        JSONObject res = new JSONObject();
        try {
            res.put("ok", true);
            res.put("strategy", isForeground ? "ANDROID_RAW_COORDINATE" : "DOM_ELEMENT_FROM_POINT");
            res.put("viewX", Math.round(viewX));
            res.put("viewY", Math.round(viewY));
        } catch (Exception e) {
            Log.w(TAG, "Failed to encode coordinate click receipt", e);
        }
        return res;
    }

    private void dispatchHardwareMotionTap(final WebView wv, final float x, final float y) {
        final long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        wv.dispatchTouchEvent(down);
        down.recycle();

        mMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long upTime = SystemClock.uptimeMillis();
                MotionEvent up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0);
                wv.dispatchTouchEvent(up);
                up.recycle();
            }
        }, 40);
    }

    public JSONObject fillSync(JSONObject targetOpts, String text, boolean clear, boolean submit) {
        ensureBridgeInjectedSync();
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.fill(" +
                (targetOpts != null ? targetOpts.toString() : "{}") + "," +
                JSONObject.quote(text) + "," +
                clear + "," +
                submit + ")); })()";
            String raw = evaluateJsSync(expr, 4000);
            String ref = targetOpts != null ? targetOpts.optString("ref", targetOpts.optString("selector", targetOpts.optString("text", "field"))) : "field";
            mHost.emitLog("[FILL] [" + ref + "] -> \"" + text + "\"" + (submit ? " (submitted)" : ""));
            return new JSONObject(raw);
        } catch (Exception e) {
            Log.e(TAG, "fillSync failed", e);
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ee) {
                Log.w(TAG, "Failed to encode fill error payload", ee);
            }
            return err;
        }
    }

    public JSONObject fillSync(String ref, String text, boolean clear, boolean submit) {
        JSONObject opts = new JSONObject();
        try {
            opts.put("ref", ref);
        } catch (Exception e) {
            Log.w(TAG, "Failed to encode fill ref: " + ref, e);
        }
        return fillSync(opts, text, clear, submit);
    }

    public JSONObject fillFormSync(JSONArray fields, boolean submitLast) {
        ensureBridgeInjectedSync();
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.fillForm(" +
                fields.toString() + "," + submitLast + ")); })()";
            String raw = evaluateJsSync(expr, 5000);
            mHost.emitLog("[FORM] Batch-filled (" + fields.length() + " fields)");
            return new JSONObject(raw);
        } catch (Exception e) {
            Log.e(TAG, "fillFormSync failed for " + fields.length() + " field(s)", e);
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ee) {
                Log.w(TAG, "Failed to encode fillForm error payload", ee);
            }
            return err;
        }
    }

    public JSONObject selectOptionSync(String ref, String valueOrLabel) {
        ensureBridgeInjectedSync();
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.selectOption(" +
                JSONObject.quote(ref) + "," + JSONObject.quote(valueOrLabel) + ")); })()";
            String raw = evaluateJsSync(expr, 4000);
            mHost.emitLog("[SELECT] [" + ref + "] -> \"" + valueOrLabel + "\"");
            return new JSONObject(raw);
        } catch (Exception e) {
            Log.e(TAG, "selectOptionSync failed for [" + ref + "]", e);
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ee) {
                Log.w(TAG, "Failed to encode select error payload", ee);
            }
            return err;
        }
    }

    public JSONObject scrollSync(String direction, double amount, String targetRef) {
        ensureBridgeInjectedSync();
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.scroll(" +
                JSONObject.quote(direction) + "," + amount + "," +
                (targetRef == null ? "null" : JSONObject.quote(targetRef)) + ")); })()";
            String raw = evaluateJsSync(expr, 4000);
            mHost.emitLog("[SCROLL] " + direction + " (" + amount + ")");
            return new JSONObject(raw);
        } catch (Exception e) {
            Log.e(TAG, "scrollSync failed (" + direction + ")", e);
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ee) {
                Log.w(TAG, "Failed to encode scroll error payload", ee);
            }
            return err;
        }
    }

    public String captureScreenshotBase64Sync(final int jpegQuality) {
        if (isMainLooper()) {
            Bitmap bmp = null;
            try {
                BrowserController.TabSession activeTab = mHost.getTargetTab();
                if (activeTab != null && activeTab.webView != null && activeTab.webView.getWidth() > 0 && activeTab.webView.getHeight() > 0) {
                    WebView wv = activeTab.webView;
                    bmp = Bitmap.createBitmap(wv.getWidth(), wv.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    wv.draw(canvas);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos);
                    return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Screenshot error on main thread", t);
            } finally {
                if (bmp != null && !bmp.isRecycled()) {
                    try { bmp.recycle(); } catch (Throwable ignored) {}
                }
            }
            return null;
        }

        if (mMainHandler == null) {
            return null;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> b64Ref = new AtomicReference<String>(null);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    BrowserController.TabSession activeTab = mHost.getTargetTab();
                    if (activeTab != null && activeTab.webView != null && activeTab.webView.getWidth() > 0 && activeTab.webView.getHeight() > 0) {
                        WebView wv = activeTab.webView;
                        bmp = Bitmap.createBitmap(wv.getWidth(), wv.getHeight(), Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(bmp);
                        wv.draw(canvas);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos);
                        b64Ref.set(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Screenshot error", t);
                } finally {
                    if (bmp != null && !bmp.isRecycled()) {
                        try { bmp.recycle(); } catch (Throwable ignored) {}
                    }
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while waiting for a screenshot", e);
        }
        return b64Ref.get();
    }
}
