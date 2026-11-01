package com.acid.browser;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Handles cross-origin {@code GM_xmlhttpRequest} requests on background threads,
 * bypassing WebView CORS restrictions and supporting text, JSON, and binary (Blob/ArrayBuffer)
 * response transformations.
 */
public class UserscriptHttpHandler {

    private static final String TAG = "AcidUserscriptHttp";
    private static final int DEFAULT_TIMEOUT_MS = 15000;
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10 MB safe cap for JS evaluation

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** In-flight requests by script-chosen id, so GM_xmlhttpRequest(...).abort() works. */
    private final java.util.concurrent.ConcurrentHashMap<String, Inflight> mInflight =
        new java.util.concurrent.ConcurrentHashMap<String, Inflight>();

    private static final class Inflight {
        volatile boolean aborted = false;
        volatile JSONObject details;
        volatile HttpURLConnection conn;
        volatile AcidAsync.Handle handle;
    }

    public void executeRequest(final WebView webView, final JSONObject details) {
        if (details == null) return;

        final String reqId = details.optString("id", "");
        final Inflight flight = new Inflight();
        flight.details = details;
        if (!reqId.isEmpty()) mInflight.put(reqId, flight);

        // Fire-and-forget lifecycle events the script may listen to.
        dispatchCallback(webView, details, startedObject(), "onloadstart");

        flight.handle = AcidAsync.run(new AcidAsync.Task<JSONObject>() {
            @Override
            public JSONObject execute() throws Exception {
                return doHttpRequest(details, flight);
            }
        }, new AcidAsync.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject responseObj) {
                if (!reqId.isEmpty()) mInflight.remove(reqId);
                if (flight.aborted) return;
                dispatchCallback(webView, details, responseObj, "onreadystatechange");
                dispatchCallback(webView, details, responseObj, "onload");
            }

            @Override
            public void onError(Exception error) {
                if (!reqId.isEmpty()) mInflight.remove(reqId);
                if (flight.aborted) return;
                boolean timedOut = error instanceof java.net.SocketTimeoutException;
                Log.w(TAG, "GM_xmlhttpRequest " + (timedOut ? "timed out" : "failed"), error);
                JSONObject errObj = new JSONObject();
                try {
                    errObj.put("error", error.getMessage() != null ? error.getMessage() : "Network error");
                    errObj.put("status", 0);
                    errObj.put("statusText", timedOut ? "Timeout" : "Error");
                    errObj.put("readyState", 4);
                    errObj.put("finalUrl", details.optString("url", ""));
                } catch (Exception ignored) {}
                dispatchCallback(webView, details, errObj, timedOut ? "ontimeout" : "onerror");
            }
        });
    }

    /** Cancels a request started by {@link #executeRequest}; the script's onabort fires. */
    public void abort(WebView webView, String reqId) {
        if (reqId == null || reqId.isEmpty()) return;
        Inflight flight = mInflight.remove(reqId);
        if (flight == null) return;
        flight.aborted = true;
        try {
            if (flight.handle != null) flight.handle.cancel();
            if (flight.conn != null) flight.conn.disconnect();
        } catch (Exception ignored) {}
        JSONObject o = new JSONObject();
        try {
            o.put("readyState", 4);
            o.put("status", 0);
            o.put("statusText", "abort");
            o.put("finalUrl", flight.details != null ? flight.details.optString("url", "") : "");
        } catch (Exception ignored) {}
        if (flight.details != null) dispatchCallback(webView, flight.details, o, "onabort");
    }

    private static JSONObject startedObject() {
        JSONObject o = new JSONObject();
        try {
            o.put("readyState", 1);
            o.put("status", 0);
            o.put("statusText", "");
        } catch (Exception ignored) {}
        return o;
    }

    private JSONObject doHttpRequest(JSONObject details, Inflight flight) throws Exception {
        String urlStr = details.optString("url", "").trim();
        if (urlStr.isEmpty()) {
            throw new IllegalArgumentException("Missing request url");
        }

        String method = details.optString("method", "GET").toUpperCase();
        int timeout = details.optInt("timeout", DEFAULT_TIMEOUT_MS);
        if (timeout <= 0) timeout = DEFAULT_TIMEOUT_MS;

        String responseType = details.optString("responseType", "text").toLowerCase();
        boolean isBinary = "blob".equals(responseType) || "arraybuffer".equals(responseType) || "stream".equals(responseType);

        String ua = details.optString("v_ua", "");
        if (ua.isEmpty()) {
            ua = AcidHttp.userAgent();
        }

        JSONObject headers = details.optJSONObject("headers");
        String data = details.optString("data", null);

        String currentUrl = urlStr;
        HttpURLConnection conn = null;
        int statusCode = 0;
        String statusText = "";
        Map<String, List<String>> headerFields = null;

        boolean connected = false;
        for (int redirects = 0; redirects < 5; redirects++) {
            URL url = new URL(currentUrl);
            conn = (HttpURLConnection) url.openConnection();
            flight.conn = conn;
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", ua);

            // Inject site cookies if available
            try {
                CookieManager cm = CookieManager.getInstance();
                if (cm != null) {
                    String cookie = cm.getCookie(currentUrl);
                    if (cookie != null && !cookie.isEmpty()) {
                        conn.setRequestProperty("Cookie", cookie);
                    }
                }
            } catch (Throwable ignored) {}

            // Custom headers
            if (headers != null) {
                Iterator<?> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = (String) keys.next();
                    conn.setRequestProperty(key, headers.optString(key));
                }
            }

            // Request body for POST / PUT / PATCH
            if (data != null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                conn.setDoOutput(true);
                byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(bytes);
                    out.flush();
                } finally {
                    out.close();
                }
            }

            statusCode = conn.getResponseCode();
            statusText = conn.getResponseMessage();
            if (statusText == null) statusText = "";

            // Synchronize Set-Cookie headers back to CookieManager
            headerFields = conn.getHeaderFields();
            if (headerFields != null) {
                for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
                    String key = entry.getKey();
                    if (key != null && "set-cookie".equalsIgnoreCase(key)) {
                        for (String val : entry.getValue()) {
                            try {
                                CookieManager cm = CookieManager.getInstance();
                                if (cm != null && val != null) {
                                    cm.setCookie(currentUrl, val);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }

            // Handle cross-protocol redirects or when followRedirects fails
            if (statusCode == HttpURLConnection.HTTP_MOVED_PERM ||
                statusCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                statusCode == HttpURLConnection.HTTP_SEE_OTHER ||
                statusCode == 307 || statusCode == 308) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && !loc.trim().isEmpty()) {
                    currentUrl = new URL(new URL(currentUrl), loc.trim()).toString();
                    conn.disconnect();
                    if (statusCode == HttpURLConnection.HTTP_SEE_OTHER
                        || statusCode == HttpURLConnection.HTTP_MOVED_PERM
                        || statusCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                        method = "GET";
                        data = null;
                    }
                    continue;
                }
            }
            connected = true;
            break;
        }

        if (!connected || conn == null) {
            throw new java.io.IOException("Too many redirects (max 5)");
        }

        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                cm.flush();
            }
        } catch (Throwable ignored) {}

        // Extract response headers
        StringBuilder rawHeaders = new StringBuilder();
        headerFields = conn.getHeaderFields();
        String contentType = "application/octet-stream";
        if (headerFields != null) {
            for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
                String key = entry.getKey();
                if (key != null) {
                    for (String val : entry.getValue()) {
                        rawHeaders.append(key).append(": ").append(val).append("\r\n");
                        if ("content-type".equalsIgnoreCase(key) && val != null) {
                            contentType = val;
                        }
                    }
                }
            }
        }

        // Read response body safely with guaranteed resource cleanup
        byte[] bodyBytes = new byte[0];
        String finalUrl = conn.getURL().toString();
        InputStream in = null;
        try {
            if (statusCode >= 200 && statusCode < 400) {
                in = conn.getInputStream();
            } else {
                in = conn.getErrorStream();
            }

            if (in != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (bos.size() + n > MAX_RESPONSE_BYTES) {
                        break;
                    }
                    bos.write(buf, 0, n);
                }
                bodyBytes = bos.toByteArray();
            }
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
            conn.disconnect();
        }

        JSONObject resp = new JSONObject();
        resp.put("status", statusCode);
        resp.put("statusText", statusText);
        resp.put("readyState", 4);
        resp.put("responseHeaders", rawHeaders.toString());
        resp.put("finalUrl", finalUrl);
        resp.put("responseType", responseType);

        if (isBinary) {
            String base64 = Base64.encodeToString(bodyBytes, Base64.NO_WRAP);
            resp.put("responseDataUrl", "data:" + contentType + ";base64," + base64);
            resp.put("responseText", "");
        } else {
            resp.put("responseText", new String(bodyBytes, StandardCharsets.UTF_8));
        }

        return resp;
    }

    /**
     * Calls the page-side callback registered under {@code event} ({@code onload}, ...). The
     * names are random per request and were published by the injected closure.
     */
    private void dispatchCallback(
        final WebView webView,
        final JSONObject details,
        final JSONObject responseObj,
        final String event
    ) {
        if (webView == null || details == null || responseObj == null) return;
        final String cbName = details.optString(event, "");
        if (cbName.isEmpty()) return;

        final boolean last = !"onloadstart".equals(event) && !"onreadystatechange".equals(event)
            && !"onprogress".equals(event);

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String name = JSONObject.quote(cbName);
                    String js = "try{if(window.__acid_dispatch_http_cb){window.__acid_dispatch_http_cb("
                        + name + "," + responseObj.toString() + "," + last + ");}else{var _f=window["
                        + name + "];if(typeof _f==='function'){" + (last ? "delete window[" + name + "];" : "")
                        + "_f(" + responseObj.toString() + ");}}}catch(e){console.error('[AcidUserscriptHttpCallbackError]',e);}";
                    webView.evaluateJavascript(js, null);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to dispatch userscript HTTP callback", e);
                }
            }
        });
    }
}
