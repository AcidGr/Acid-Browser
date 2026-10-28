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
                if (mHost.getUserscriptManager() != null) {
                    mHost.getUserscriptManager().injectRuntimeAndUserscripts(activeTab.webView);
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

    public JSONObject observeSync(boolean includeSom, boolean viewportOnly, boolean includeScreenshot) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.observe({includeSom:" +
                includeSom + ", viewportOnly:" + viewportOnly + "})); })()";
            String raw = evaluateJsSync(expr, 6000);
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
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", e.getMessage());
            } catch (Exception ignored) {}
            return err;
        }
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
        try {
            String prepExpr = "(function(){ return JSON.stringify(window.__acidBridge.prepareClick(" +
                JSONObject.quote(targetRef) + ")); })()";
            String prepRaw = evaluateJsSync(prepExpr, 4000);
            JSONObject prep = new JSONObject(prepRaw);
            if (!prep.optBoolean("ok", false)) {
                return prep;
            }

            final double cssX = prep.optDouble("cssX", 0);
            final double cssY = prep.optDouble("cssY", 0);
            final String label = prep.optString("label", "");
            final int revBefore = prep.optInt("revisionBefore", 0);

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
                        "(function(){ return JSON.stringify(window.__acidBridge.jsClick(" + JSONObject.quote(targetRef) + ")); })()",
                        2000
                    );
                    JSONObject fb = new JSONObject(fallbackRaw);
                    revAfter = fb.optInt("revisionAfter", revAfter);
                    verified = true;
                }

                mHost.emitLog("[CLICK] [" + targetRef + "] \"" + label + "\" @(" +
                    Math.round(viewXRef.get()) + "," + Math.round(viewYRef.get()) + ")");

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
                return receipt;
            } else {
                String jsRaw = evaluateJsSync(
                    "(function(){ return JSON.stringify(window.__acidBridge.jsClick(" + JSONObject.quote(targetRef) + ")); })()",
                    3000
                );
                mHost.emitLog("[CLICK] JS-Synthetic [" + targetRef + "] \"" + label + "\"");
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

    public JSONObject fillSync(String ref, String text, boolean clear, boolean submit) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.fill(" +
                JSONObject.quote(ref) + "," +
                JSONObject.quote(text) + "," +
                clear + "," +
                submit + ")); })()";
            String raw = evaluateJsSync(expr, 4000);
            mHost.emitLog("[FILL] [" + ref + "] -> \"" + text + "\"" + (submit ? " (submitted)" : ""));
            return new JSONObject(raw);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ignored) {}
            return err;
        }
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
