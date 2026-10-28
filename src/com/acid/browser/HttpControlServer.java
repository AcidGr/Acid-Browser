package com.acid.browser;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Embedded HTTP Control Gateway on 127.0.0.1:3090
 * Exposes atomic Agentic Browser endpoints for LLM Agent Loop or DSH integration:
 *  - GET  /api/status
 *  - GET/POST /api/observe
 *  - POST /api/navigate
 *  - POST /api/click
 *  - POST /api/fill
 *  - POST /api/select
 *  - POST /api/scroll
 *  - POST /api/eval
 *  - POST /api/mode
 *  - POST /api/tabs
 *  - POST /api/userscripts
 */
public class HttpControlServer {
    private static final String TAG = "AcidHttpServer";
    public static final int DEFAULT_PORT = 3090;

    private final BrowserController mController;
    private final int mPort;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mPool = Executors.newCachedThreadPool();

    private ServerSocket mServerSocket;
    private volatile boolean mRunning = false;

    public HttpControlServer(BrowserController controller, int port) {
        mController = controller;
        mPort = port;
    }

    public void start() {
        if (mRunning) return;
        mRunning = true;
        mPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mServerSocket = new ServerSocket(mPort, 32, InetAddress.getByName("127.0.0.1"));
                    mController.emitLog("[GATEWAY] Acid HTTP Gateway listening on 127.0.0.1:" + mPort);
                    while (mRunning) {
                        final Socket client = mServerSocket.accept();
                        mPool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(client);
                            }
                        });
                    }
                } catch (Exception e) {
                    if (mRunning) {
                        Log.e(TAG, "Server error", e);
                    }
                }
            }
        });
    }

    public void stop() {
        mRunning = false;
        try {
            if (mServerSocket != null) {
                mServerSocket.close();
            }
        } catch (Exception ignored) {}
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();

            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int b;
            int matched = 0;
            while ((b = is.read()) != -1) {
                headerBuf.write(b);
                if ((matched == 0 || matched == 2) && b == '\r') matched++;
                else if ((matched == 1 || matched == 3) && b == '\n') matched++;
                else if (b == '\n') {
                    byte[] raw = headerBuf.toByteArray();
                    if (raw.length >= 2 && raw[raw.length - 2] == '\n') break;
                    matched = 0;
                } else matched = 0;
                if (matched == 4) break;
            }
            if (headerBuf.size() == 0) {
                socket.close();
                return;
            }

            String headerText = headerBuf.toString(StandardCharsets.ISO_8859_1.name());
            String[] lines = headerText.split("\r?\n");
            String requestLine = lines.length > 0 ? lines[0] : "";
            if (requestLine.isEmpty()) {
                socket.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0].toUpperCase() : "GET";
            String fullPath = parts.length > 1 ? parts[1] : "/";

            String path = fullPath;
            String queryStr = "";
            int qIdx = fullPath.indexOf('?');
            if (qIdx >= 0) {
                path = fullPath.substring(0, qIdx);
                queryStr = fullPath.substring(qIdx + 1);
            }
            Map<String, String> query = parseQuery(queryStr);

            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                String headerLine = lines[i];
                int colon = headerLine.indexOf(':');
                if (colon > 0) {
                    String k = headerLine.substring(0, colon).trim().toLowerCase();
                    String v = headerLine.substring(colon + 1).trim();
                    if ("content-length".equals(k)) {
                        try {
                            contentLength = Integer.parseInt(v);
                        } catch (Exception ignored) {}
                    }
                }
            }

            String bodyStr = "";
            if (contentLength > 0) {
                byte[] bodyBytes = new byte[contentLength];
                int total = 0;
                while (total < contentLength) {
                    int n = is.read(bodyBytes, total, contentLength - total);
                    if (n == -1) break;
                    total += n;
                }
                bodyStr = new String(bodyBytes, 0, total, StandardCharsets.UTF_8);
            }

            if ("OPTIONS".equals(method)) {
                writeResponse(os, 200, "{\"ok\":true}");
                socket.close();
                return;
            }

            JSONObject bodyJson = new JSONObject();
            if (!bodyStr.trim().isEmpty()) {
                try {
                    bodyJson = new JSONObject(bodyStr);
                } catch (Exception ignored) {}
            }

            JSONObject resp = routeRequest(method, path, query, bodyJson);
            writeResponse(os, 200, resp.toString());
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("error", e.getMessage());
                writeResponse(socket.getOutputStream(), 500, err.toString());
            } catch (Exception ignored) {}
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    private Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<String, String>();
        if (q == null || q.isEmpty()) return map;
        String[] pairs = q.split("&");
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                try {
                    String k = java.net.URLDecoder.decode(p.substring(0, eq), "UTF-8");
                    String v = java.net.URLDecoder.decode(p.substring(eq + 1), "UTF-8");
                    map.put(k, v);
                } catch (Exception ignored) {}
            } else if (!p.isEmpty()) {
                map.put(p, "true");
            }
        }
        return map;
    }

    private JSONObject routeRequest(String method, String path, Map<String, String> query, final JSONObject body) throws Exception {
        if ("/api/status".equals(path) || "/".equals(path)) {
            JSONObject st = new JSONObject();
            st.put("ok", true);
            st.put("product", "Acid");
            st.put("version", "0.1.0-alpha");
            st.put("browserMode", mController.isDesktopMode() ? "desktop" : "mobile");
            int cm = mController.getControlMode();
            st.put("controlMode", cm == AgentOverlayView.MODE_AGENT ? "agent" : (cm == AgentOverlayView.MODE_TAKEOVER_REQUESTED ? "takeover_requested" : "human"));
            st.put("takeoverReason", mController.getTakeoverReason());
            st.put("somEnabled", mController.isSomEnabled());
            st.put("blockedDeepLinks", mController.getBlockedDeepLinksCount());
            st.put("lastBlockedDeepLink", mController.getLastBlockedDeepLink());
            BrowserController.TabSession active = mController.getActiveTab();
            if (active != null) {
                st.put("activeTabId", active.id);
                st.put("url", active.url);
                st.put("title", active.title);
                st.put("isLoading", active.isLoading);
            }
            st.put("userscripts", mController.listUserscriptsJson());
            return st;
        }

        if ("/api/observe".equals(path)) {
            boolean som = body.optBoolean("som", "true".equalsIgnoreCase(query.get("som")) || mController.isSomEnabled());
            boolean vpOnly = body.has("viewportOnly") ? body.optBoolean("viewportOnly", true) : !"false".equalsIgnoreCase(query.get("viewportOnly"));
            boolean shot = body.optBoolean("screenshot", "true".equalsIgnoreCase(query.get("screenshot")));
            return mController.observeSync(som, vpOnly, shot);
        }

        if ("/api/navigate".equals(path)) {
            String url = body.optString("url", query.get("url"));
            long waitMs = body.optLong("waitMs", 3500);
            return mController.navigateSync(url, waitMs);
        }

        if ("/api/click".equals(path)) {
            String ref = body.optString("ref", query.get("ref"));
            String text = body.optString("text", query.get("text"));
            String selector = body.optString("selector", query.get("selector"));
            if ((ref != null && !ref.isEmpty()) || (text != null && !text.isEmpty()) || (selector != null && !selector.isEmpty())) {
                boolean useNative = body.optBoolean("native", true);
                JSONObject targetOpts = new JSONObject();
                if (ref != null && !ref.isEmpty()) targetOpts.put("ref", ref);
                if (text != null && !text.isEmpty()) targetOpts.put("text", text);
                if (selector != null && !selector.isEmpty()) targetOpts.put("selector", selector);
                targetOpts.put("exact", body.optBoolean("exact", false));
                return mController.clickTargetSync(targetOpts, useNative);
            } else if (body.has("x") && body.has("y")) {
                float x = (float) body.optDouble("x", 0);
                float y = (float) body.optDouble("y", 0);
                return mController.clickCoordinateSync(x, y);
            } else {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("error", "Provide 'ref', 'text', 'selector', or ('x', 'y') coordinates.");
                return err;
            }
        }

        if ("/api/fill".equals(path)) {
            if (body.has("fields")) {
                JSONArray fields = body.optJSONArray("fields");
                boolean submitLast = body.optBoolean("submitLast", false);
                return mController.fillFormSync(fields, submitLast);
            }
            String ref = body.optString("ref", query.get("ref"));
            String selector = body.optString("selector", query.get("selector"));
            String text = body.optString("text", body.optString("value", ""));
            boolean clear = body.optBoolean("clear", true);
            boolean submit = body.optBoolean("submit", false);
            JSONObject targetOpts = new JSONObject();
            if (ref != null && !ref.isEmpty()) targetOpts.put("ref", ref);
            if (selector != null && !selector.isEmpty()) targetOpts.put("selector", selector);
            return mController.fillTargetSync(targetOpts, text, clear, submit);
        }

        if ("/api/select".equals(path)) {
            String ref = body.optString("ref", query.get("ref"));
            String val = body.optString("value", body.optString("option", ""));
            return mController.selectOptionSync(ref, val);
        }

        if ("/api/scroll".equals(path)) {
            String dir = body.optString("direction", query.get("direction") != null ? query.get("direction") : "down");
            double amount = body.optDouble("amount", 0.75);
            String targetRef = body.optString("ref", null);
            return mController.scrollSync(dir, amount, targetRef);
        }

        if ("/api/wait".equals(path)) {
            String cond = body.optString("condition", query.get("condition") != null ? query.get("condition") : "stable");
            String target = body.optString("target", body.optString("selector", body.optString("text", query.get("target"))));
            long quietMs = body.optLong("quiet_ms", body.optLong("ms", 300));
            long timeoutMs = body.optLong("timeout_ms", 5000);
            return mController.waitSync(cond, target, quietMs, timeoutMs);
        }

        if ("/api/eval".equals(path)) {
            String script = body.optString("script", body.optString("code", query.get("code")));
            long timeout = body.optLong("timeout_ms", 10000);
            String raw = mController.evaluateJsAsync(script, timeout);
            try {
                return new JSONObject(raw);
            } catch (Exception e) {
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("result", raw);
                return res;
            }
        }

        if ("/api/mode".equals(path)) {
            final CountDownLatch latch = new CountDownLatch(1);
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (body.has("browserMode")) {
                        String bm = body.optString("browserMode", "mobile").toLowerCase();
                        boolean desktop = "desktop".equals(bm) || "pc".equals(bm);
                        boolean reload = body.optBoolean("reload", true);
                        mController.setDesktopMode(desktop, reload);
                    }
                    if (body.has("controlMode")) {
                        String cm = body.optString("controlMode", "human").toLowerCase();
                        String reason = body.optString("reason", "");
                        int targetMode = AgentOverlayView.MODE_HUMAN;
                        if ("agent".equals(cm)) targetMode = AgentOverlayView.MODE_AGENT;
                        else if ("takeover".equals(cm) || "takeover_requested".equals(cm)) targetMode = AgentOverlayView.MODE_TAKEOVER_REQUESTED;
                        mController.setControlMode(targetMode, reason);
                    }
                    if (body.has("som")) {
                        mController.setSomEnabled(body.optBoolean("som", false));
                    }
                    latch.countDown();
                }
            });
            latch.await(3000, TimeUnit.MILLISECONDS);
            return routeRequest("GET", "/api/status", query, new JSONObject());
        }

        if ("/api/tabs".equals(path)) {
            final String action = body.optString("action", "list").toLowerCase();
            final CountDownLatch latch = new CountDownLatch(1);
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if ("new".equals(action)) {
                        String url = body.optString("url", BrowserController.HOME_URL);
                        mController.createNewTab(url, true);
                    } else if ("switch".equals(action)) {
                        mController.switchToTab(body.optInt("tabId", 1));
                    } else if ("close".equals(action)) {
                        mController.closeTab(body.optInt("tabId", 1));
                    }
                    latch.countDown();
                }
            });
            latch.await(2000, TimeUnit.MILLISECONDS);

            JSONObject res = new JSONObject();
            res.put("ok", true);
            JSONArray arr = new JSONArray();
            List<BrowserController.TabSession> tabs = mController.getTabs();
            BrowserController.TabSession active = mController.getActiveTab();
            for (BrowserController.TabSession t : tabs) {
                JSONObject item = new JSONObject();
                item.put("id", t.id);
                item.put("title", t.title);
                item.put("url", t.url);
                item.put("active", active != null && active.id == t.id);
                arr.put(item);
            }
            res.put("tabs", arr);
            return res;
        }

        if ("/api/userscripts".equals(path)) {
            String action = body.optString("action", "list").toLowerCase();
            JSONObject res = new JSONObject();
            if ("save".equals(action) || "add".equals(action)) {
                boolean ok = mController.saveUserscript(body.optString("name"), body.optString("code"));
                res.put("ok", ok);
            } else if ("delete".equals(action)) {
                boolean ok = mController.deleteUserscript(body.optString("name"));
                res.put("ok", ok);
            } else {
                res.put("ok", true);
            }
            res.put("userscripts", mController.listUserscriptsJson());
            return res;
        }

        if ("/api/cookies".equals(path)) {
            String action = body.optString("action", query.get("action") != null ? query.get("action") : "get").toLowerCase();
            String targetUrl = body.optString("url", query.get("url"));
            if (targetUrl == null || targetUrl.isEmpty()) {
                BrowserController.TabSession active = mController.getActiveTab();
                targetUrl = active != null ? active.url : BrowserController.HOME_URL;
            }

            JSONObject res = new JSONObject();
            if ("clear".equals(action) || "delete".equals(action) || "DELETE".equals(method)) {
                mController.clearAllCookies();
                res.put("ok", true);
                res.put("message", "All cookies cleared");
                return res;
            }

            if ("set".equals(action) || "add".equals(action) || ("POST".equals(method) && (body.has("cookie") || body.has("cookies")))) {
                if (body.has("cookies")) {
                    JSONArray cArr = body.optJSONArray("cookies");
                    for (int i = 0; i < cArr.length(); i++) {
                        mController.setCookieForUrl(targetUrl, cArr.optString(i));
                    }
                } else {
                    String cVal = body.optString("cookie", body.optString("value", ""));
                    mController.setCookieForUrl(targetUrl, cVal);
                }
                res.put("ok", true);
                res.put("url", targetUrl);
                res.put("cookies", mController.getCookieForUrl(targetUrl));
                return res;
            }

            // Default: GET cookies
            String rawCookies = mController.getCookieForUrl(targetUrl);
            res.put("ok", true);
            res.put("url", targetUrl);
            res.put("raw", rawCookies != null ? rawCookies : "");
            JSONArray items = new JSONArray();
            if (rawCookies != null && !rawCookies.trim().isEmpty()) {
                String[] parts = rawCookies.split(";");
                for (String p : parts) {
                    int eq = p.indexOf('=');
                    JSONObject cObj = new JSONObject();
                    if (eq > 0) {
                        cObj.put("name", p.substring(0, eq).trim());
                        cObj.put("value", p.substring(eq + 1).trim());
                    } else {
                        cObj.put("name", p.trim());
                        cObj.put("value", "");
                    }
                    items.put(cObj);
                }
            }
            res.put("items", items);
            return res;
        }

        if ("/api/cookies/import".equals(path)) {
            // Batch import cookies (e.g. migrated from Via Browser Chromium DB).
            // Body: { "cookies": [ {host,name,value,path,secure,httpOnly,sameSite,expires}, ... ] }
            JSONArray arr = body.optJSONArray("cookies");
            JSONObject res = new JSONObject();
            if (arr == null) {
                res.put("ok", false);
                res.put("error", "Missing 'cookies' array in request body.");
                return res;
            }

            final int total = arr.length();
            final java.util.concurrent.atomic.AtomicInteger okCount = new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.concurrent.atomic.AtomicInteger failCount = new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.Set<String> touchedHosts = new java.util.HashSet<String>();

            for (int i = 0; i < total; i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) { failCount.incrementAndGet(); continue; }

                String host = c.optString("host", "");
                String name = c.optString("name", "");
                String value = c.optString("value", "");
                String cPath = c.optString("path", "/");
                boolean secure = c.optBoolean("secure", false);
                boolean httpOnly = c.optBoolean("httpOnly", false);
                int sameSite = c.optInt("sameSite", -1);
                long expires = c.optLong("expires", 0);

                if (host.isEmpty() || name.isEmpty()) { failCount.incrementAndGet(); continue; }

                String bareHost = host.startsWith(".") ? host.substring(1) : host;
                String scheme = secure ? "https" : "http";
                String url = scheme + "://" + bareHost + (cPath.startsWith("/") ? cPath : "/" + cPath);

                StringBuilder sb = new StringBuilder();
                sb.append(name).append("=").append(value).append("; Domain=").append(host);
                sb.append("; Path=").append(cPath.startsWith("/") ? cPath : "/" + cPath);
                if (secure) sb.append("; Secure");
                if (httpOnly) sb.append("; HttpOnly");
                if (expires > 0) {
                    java.text.SimpleDateFormat fmt =
                        new java.text.SimpleDateFormat("EEE, dd-MMM-yyyy HH:mm:ss zzz", java.util.Locale.US);
                    sb.append("; Expires=").append(fmt.format(new java.util.Date(expires * 1000L)));
                }
                if (sameSite == 0) sb.append("; SameSite=None");
                else if (sameSite == 1) sb.append("; SameSite=Lax");
                else if (sameSite == 2) sb.append("; SameSite=Strict");

                if (mController.setCookieForUrl(url, sb.toString())) {
                    okCount.incrementAndGet();
                    touchedHosts.add(host);
                } else {
                    failCount.incrementAndGet();
                }
            }

            // Force flush to disk so the cookies survive process restart
            mController.flushCookies();

            res.put("ok", failCount.get() == 0);
            res.put("total", total);
            res.put("imported", okCount.get());
            res.put("failed", failCount.get());
            res.put("domains", touchedHosts.size());
            return res;
        }

        JSONObject notFound = new JSONObject();
        notFound.put("ok", false);
        notFound.put("error", "Unknown endpoint: " + path);
        return notFound;
    }

    private void writeResponse(OutputStream os, int statusCode, String jsonBody) throws Exception {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        String statusText = statusCode == 200 ? "OK" : "Internal Server Error";
        String headers =
            "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Content-Length: " + bytes.length + "\r\n" +
            "Connection: close\r\n\r\n";
        os.write(headers.getBytes(StandardCharsets.UTF_8));
        os.write(bytes);
        os.flush();
    }
}
