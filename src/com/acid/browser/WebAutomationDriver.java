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
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentHashMap;
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
        AgentOverlayView getOverlayView();
        UserscriptManager getUserscriptManager();
        void loadUrl(String url);
        void emitLog(String log);
        boolean isSomEnabled();
        int getBlockedDeepLinksCount();
        String getLastBlockedDeepLink();
    }

    private final Context mContext;
    private final AutomationHost mHost;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AcidNativeHostBridge mNativeBridge = new AcidNativeHostBridge();

    public static class AcidNativeHostBridge {
        private final ConcurrentHashMap<String, AtomicReference<String>> mResults =
            new ConcurrentHashMap<String, AtomicReference<String>>();
        private final ConcurrentHashMap<String, CountDownLatch> mLatches =
            new ConcurrentHashMap<String, CountDownLatch>();

        public void prepareCall(String callId, CountDownLatch latch, AtomicReference<String> ref) {
            mLatches.put(callId, latch);
            mResults.put(callId, ref);
        }

        public void cleanupCall(String callId) {
            mLatches.remove(callId);
            mResults.remove(callId);
        }

        @JavascriptInterface
        public void resolve(String callId, String jsonPayload) {
            AtomicReference<String> ref = mResults.get(callId);
            if (ref != null) {
                ref.set(jsonPayload);
            }
            CountDownLatch latch = mLatches.get(callId);
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    public AcidNativeHostBridge getNativeHostBridge() {
        return mNativeBridge;
    }

    public WebAutomationDriver(Context context, AutomationHost host) {
        mContext = context;
        mHost = host;
    }

    private String unescapeEvaluateResult(String raw) {
        if (raw == null || "null".equals(raw)) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                Object val = new JSONTokener(trimmed).nextValue();
                return val != null ? val.toString() : "";
            } catch (Exception ignored) {}
        }
        return trimmed;
    }

    public String evaluateJsSync(final String jsExpr, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> ref = new AtomicReference<String>("{\"ok\":false,\"error\":\"TIMEOUT\"}");

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                BrowserController.TabSession activeTab = mHost.getActiveTab();
                if (activeTab == null) {
                    ref.set("{\"ok\":false,\"error\":\"NO_ACTIVE_TAB\"}");
                    latch.countDown();
                    return;
                }
                activeTab.webView.evaluateJavascript(jsExpr, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        ref.set(unescapeEvaluateResult(value));
                        latch.countDown();
                    }
                });
            }
        });

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        return ref.get();
    }

    public String evaluateJsAsync(final String userCode, long timeoutMs) {
        final String callId = "eval_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultRef = new AtomicReference<String>(null);
        mNativeBridge.prepareCall(callId, latch, resultRef);

        String trimmed = (userCode != null) ? userCode.trim() : "";
        String innerBody;
        if (trimmed.contains("return ") || trimmed.contains("\n") || trimmed.contains(";") ||
            trimmed.startsWith("const ") || trimmed.startsWith("let ") || trimmed.startsWith("var ")) {
            innerBody = trimmed;
        } else {
            innerBody = "return (" + trimmed + ");";
        }

        final String wrappedJs = "(function() {\n" +
            "  var __cid = " + JSONObject.quote(callId) + ";\n" +
            "  try {\n" +
            "    (async function() {\n" +
            "      try {\n" +
            "        var v = await (async function() {\n" + innerBody + "\n})();\n" +
            "        if (v === undefined) v = null;\n" +
            "        if (v && typeof v === 'object' && v instanceof Element) {\n" +
            "          v = { tag: v.tagName, id: v.id || '', className: v.className || '', text: (v.innerText || '').substring(0, 150) };\n" +
            "        }\n" +
            "        var payload = JSON.stringify({ ok: true, result: v });\n" +
            "        if (window.__acidNativeHost && window.__acidNativeHost.resolve) {\n" +
            "          window.__acidNativeHost.resolve(__cid, payload);\n" +
            "        }\n" +
            "      } catch (e) {\n" +
            "        var errPayload = JSON.stringify({ ok: false, error: String(e && e.message || e) });\n" +
            "        if (window.__acidNativeHost && window.__acidNativeHost.resolve) {\n" +
            "          window.__acidNativeHost.resolve(__cid, errPayload);\n" +
            "        }\n" +
            "      }\n" +
            "    })();\n" +
            "  } catch (topErr) {\n" +
            "    if (window.__acidNativeHost && window.__acidNativeHost.resolve) {\n" +
            "      window.__acidNativeHost.resolve(__cid, JSON.stringify({ ok: false, error: 'SYNTAX_ERROR: ' + String(topErr) }));\n" +
            "    }\n" +
            "  }\n" +
            "})();";

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                BrowserController.TabSession activeTab = mHost.getActiveTab();
                if (activeTab == null || activeTab.webView == null) {
                    resultRef.set("{\"ok\":false,\"error\":\"NO_ACTIVE_TAB\"}");
                    latch.countDown();
                    return;
                }
                activeTab.webView.evaluateJavascript(wrappedJs, null);
            }
        });

        try {
            boolean done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done || resultRef.get() == null) {
                return "{\"ok\":false,\"error\":\"ASYNC_TIMEOUT\",\"timeout_ms\":" + timeoutMs + "}";
            }
            return resultRef.get();
        } catch (InterruptedException e) {
            return "{\"ok\":false,\"error\":\"INTERRUPTED\"}";
        } finally {
            mNativeBridge.cleanupCall(callId);
        }
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
            final CountDownLatch latch = new CountDownLatch(1);
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        BrowserController.TabSession tab = mHost.getActiveTab();
                        if (tab != null && tab.webView != null) {
                            partial.put("url", tab.webView.getUrl() != null ? tab.webView.getUrl() : tab.url);
                            partial.put("title", tab.webView.getTitle() != null ? tab.webView.getTitle() : tab.title);
                            partial.put("progress", tab.webView.getProgress());
                            partial.put("isLoading", tab.isLoading);
                        }
                    } catch (Exception ignored) {}
                    latch.countDown();
                }
            });
            latch.await(500, TimeUnit.MILLISECONDS);
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
        } catch (Exception ignored) {}
        return err;
    }

    public JSONObject observeSync(boolean includeSom, boolean viewportOnly, boolean includeScreenshot) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.observe({includeSom:" +
                includeSom + ", viewportOnly:" + viewportOnly + "})); })()";
            String raw = evaluateJsSync(expr, 6000);
            if (raw == null || raw.trim().isEmpty() || "null".equals(raw)) {
                return buildFallbackObservation("SNAPSHOT_EMPTY_RESULT", "页面未返回有效观察数据，可能正在加载");
            }
            JSONObject obj = new JSONObject(raw);
            AgentOverlayView overlay = mHost.getOverlayView();
            int cMode = overlay != null ? overlay.getControlMode() : AgentOverlayView.MODE_HUMAN;
            obj.put("controlMode", cMode == AgentOverlayView.MODE_AGENT ? "agent" : "human");
            obj.put("blockedDeepLinks", mHost.getBlockedDeepLinksCount());
            String lastBlocked = mHost.getLastBlockedDeepLink();
            if (lastBlocked != null && !lastBlocked.isEmpty()) {
                obj.put("lastBlockedDeepLink", lastBlocked);
            }
            if (includeScreenshot) {
                String b64 = captureScreenshotBase64Sync(75);
                if (b64 != null) {
                    obj.put("screenshotBase64", b64);
                }
            }
            return obj;
        } catch (Exception e) {
            return buildFallbackObservation("SNAPSHOT_PARSE_ERROR", "快照解析失败: " + e.getMessage());
        }
    }

    public JSONObject waitConditionSync(String condition, String target, long quietMs, long timeoutMs) {
        long effectiveQuietMs = Math.max(50, quietMs > 0 ? quietMs : 300);
        long effectiveTimeout = Math.max(effectiveQuietMs, Math.min(30000, timeoutMs > 0 ? timeoutMs : 5000));
        long startTime = SystemClock.uptimeMillis();
        long deadline = startTime + effectiveTimeout;

        String cond = (condition != null && !condition.isEmpty()) ? condition.toLowerCase().trim() : "stable";

        if ("ms".equals(cond) || "time".equals(cond)) {
            long sleepTime = Math.max(50, Math.min(effectiveTimeout, quietMs > 0 ? quietMs : (target != null && !target.isEmpty() ? Long.parseLong(target) : 1000)));
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException ignored) {}
            long elapsed = SystemClock.uptimeMillis() - startTime;
            JSONObject obs = observeSync(mHost.isSomEnabled(), true, false);
            try {
                obs.put("wait_satisfied", true);
                obs.put("wait_condition", "ms");
                obs.put("elapsed_ms", elapsed);
            } catch (Exception ignored) {}
            return obs;
        }

        boolean satisfied = false;
        boolean isFirst = true;
        while (SystemClock.uptimeMillis() < deadline) {
            String checkExpr = "(function(){\n" +
                "  if (!window.__acidBridge || !window.__acidBridge.checkCondition) return JSON.stringify({ok:false, error:'BRIDGE_NOT_READY'});\n" +
                "  return JSON.stringify(window.__acidBridge.checkCondition(" +
                JSONObject.quote(cond) + ", " +
                (target != null ? JSONObject.quote(target) : "null") + ", " +
                effectiveQuietMs + ", " +
                isFirst + "));\n" +
                "})()";
            isFirst = false;

            String raw = evaluateJsSync(checkExpr, 1500);
            if (raw != null && !raw.isEmpty()) {
                try {
                    JSONObject res = new JSONObject(raw);
                    if (res.optBoolean("satisfied", false)) {
                        satisfied = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException ignored) {
                break;
            }
        }

        long elapsed = SystemClock.uptimeMillis() - startTime;
        JSONObject obs = observeSync(mHost.isSomEnabled(), true, false);
        try {
            obs.put("wait_satisfied", satisfied);
            obs.put("wait_condition", cond);
            obs.put("wait_target", target != null ? target : "");
            obs.put("elapsed_ms", elapsed);
            obs.put("quiet_ms", effectiveQuietMs);
            obs.put("timeout_ms", effectiveTimeout);
            if (!satisfied) {
                obs.put("hint", "等待超时未满足条件，页面可能仍未更新或变动持续未达静默阈值 (" + effectiveQuietMs + "ms)");
            }
        } catch (Exception ignored) {}
        return obs;
    }

    public JSONObject navigateSync(final String url, long waitSettleMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mHost.loadUrl(url);
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
                        BrowserController.TabSession activeTab = mHost.getActiveTab();
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
        } catch (Exception ignored) {}
        mHost.emitLog("[NAV] Navigated -> " + url);
        return observeSync(mHost.isSomEnabled(), true, false);
    }

    public JSONObject clickRefSync(final String targetRef, final boolean useNativeMotionEvent) {
        JSONObject opts = new JSONObject();
        try { opts.put("ref", targetRef); } catch (Exception ignored) {}
        return clickTargetSync(opts, useNativeMotionEvent);
    }

    public JSONObject clickTargetSync(final JSONObject targetOpts, final boolean useNativeMotionEvent) {
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
            final boolean reResolved = prep.optBoolean("re_resolved", false);
            final String matchedBy = prep.optString("matchedBy", "ref");
            final JSONObject hit = prep.optJSONObject("hit");

            if (useNativeMotionEvent) {
                final CountDownLatch tapLatch = new CountDownLatch(1);
                final AtomicReference<Float> viewXRef = new AtomicReference<Float>(0f);
                final AtomicReference<Float> viewYRef = new AtomicReference<Float>(0f);

                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        BrowserController.TabSession activeTab = mHost.getActiveTab();
                        if (activeTab == null) {
                            tapLatch.countDown();
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
                        if (overlay != null) {
                            overlay.showClickPulse(vx, vy, "[" + targetRef + "] " + label);
                        }
                        dispatchHardwareMotionTap(wv, vx, vy);
                        tapLatch.countDown();
                    }
                });
                tapLatch.await(2000, TimeUnit.MILLISECONDS);
                Thread.sleep(220);

                String stateRaw = evaluateJsSync("(function(){ return JSON.stringify(window.__acidBridge.getState()); })()", 2000);
                JSONObject stateAfter = new JSONObject(stateRaw);
                int revAfter = stateAfter.optInt("revision", revBefore);
                boolean verified = (revAfter > revBefore);

                if (!verified) {
                    String fallbackRaw = evaluateJsSync(
                        "(function(){ return JSON.stringify(window.__acidBridge.jsClick(" +
                        (targetOpts != null ? targetOpts.toString() : "{}") + ")); })()",
                        2000
                    );
                    JSONObject fb = new JSONObject(fallbackRaw);
                    revAfter = fb.optInt("revisionAfter", revAfter);
                    verified = true;
                }

                mHost.emitLog("[CLICK] [" + targetRef + "] \"" + label + "\" @(" +
                    Math.round(viewXRef.get()) + "," + Math.round(viewYRef.get()) + ")" +
                    (reResolved ? " (re-resolved via " + matchedBy + ")" : ""));

                JSONObject receipt = new JSONObject();
                receipt.put("ok", true);
                receipt.put("ref", targetRef);
                receipt.put("label", label);
                receipt.put("strategy", "ANDROID_NATIVE_MOTION_EVENT");
                receipt.put("viewX", Math.round(viewXRef.get()));
                receipt.put("viewY", Math.round(viewYRef.get()));
                receipt.put("revisionBefore", revBefore);
                receipt.put("revisionAfter", revAfter);
                receipt.put("verified", verified);
                receipt.put("re_resolved", reResolved);
                receipt.put("matchedBy", matchedBy);
                if (hit != null) {
                    receipt.put("hit", hit);
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
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", e.getMessage());
            } catch (Exception ignored) {}
            return err;
        }
    }

    public JSONObject clickCoordinateSync(final float viewX, final float viewY) {
        final CountDownLatch latch = new CountDownLatch(1);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                BrowserController.TabSession activeTab = mHost.getActiveTab();
                if (activeTab != null) {
                    AgentOverlayView overlay = mHost.getOverlayView();
                    if (overlay != null) {
                        overlay.showClickPulse(viewX, viewY, "(" + Math.round(viewX) + "," + Math.round(viewY) + ")");
                    }
                    dispatchHardwareMotionTap(activeTab.webView, viewX, viewY);
                }
                latch.countDown();
            }
        });
        try {
            latch.await(2000, TimeUnit.MILLISECONDS);
            Thread.sleep(200);
        } catch (Exception ignored) {}
        mHost.emitLog("[CLICK] Coordinate (" + Math.round(viewX) + ", " + Math.round(viewY) + ")");
        JSONObject res = new JSONObject();
        try {
            res.put("ok", true);
            res.put("strategy", "ANDROID_RAW_COORDINATE");
            res.put("viewX", Math.round(viewX));
            res.put("viewY", Math.round(viewY));
        } catch (Exception ignored) {}
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
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ignored) {}
            return err;
        }
    }

    public JSONObject fillSync(String ref, String text, boolean clear, boolean submit) {
        JSONObject opts = new JSONObject();
        try { opts.put("ref", ref); } catch (Exception ignored) {}
        return fillSync(opts, text, clear, submit);
    }

    public JSONObject fillFormSync(JSONArray fields, boolean submitLast) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.fillForm(" +
                fields.toString() + "," + submitLast + ")); })()";
            String raw = evaluateJsSync(expr, 5000);
            mHost.emitLog("[FORM] Batch-filled (" + fields.length() + " fields)");
            return new JSONObject(raw);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ignored) {}
            return err;
        }
    }

    public JSONObject selectOptionSync(String ref, String valueOrLabel) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.selectOption(" +
                JSONObject.quote(ref) + "," + JSONObject.quote(valueOrLabel) + ")); })()";
            String raw = evaluateJsSync(expr, 4000);
            mHost.emitLog("[SELECT] [" + ref + "] -> \"" + valueOrLabel + "\"");
            return new JSONObject(raw);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ignored) {}
            return err;
        }
    }

    public JSONObject scrollSync(String direction, double amount, String targetRef) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.scroll(" +
                JSONObject.quote(direction) + "," + amount + "," +
                (targetRef == null ? "null" : JSONObject.quote(targetRef)) + ")); })()";
            String raw = evaluateJsSync(expr, 4000);
            mHost.emitLog("[SCROLL] " + direction + " (" + amount + ")");
            return new JSONObject(raw);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ignored) {}
            return err;
        }
    }

    public String captureScreenshotBase64Sync(final int jpegQuality) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> b64Ref = new AtomicReference<String>(null);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    BrowserController.TabSession activeTab = mHost.getActiveTab();
                    if (activeTab != null && activeTab.webView.getWidth() > 0 && activeTab.webView.getHeight() > 0) {
                        WebView wv = activeTab.webView;
                        Bitmap bmp = Bitmap.createBitmap(wv.getWidth(), wv.getHeight(), Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(bmp);
                        wv.draw(canvas);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos);
                        bmp.recycle();
                        b64Ref.set(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Screenshot error", e);
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        return b64Ref.get();
    }
}
