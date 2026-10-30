package com.acid.browser;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Model Context Protocol (MCP) Server on 127.0.0.1:3090.
 * Adheres strictly to the Anthropic MCP specification (JSON-RPC 2.0).
 *
 * Supported Transports:
 *  1. Server-Sent Events (SSE):
 *     - GET  /sse                 -> Establishes SSE stream and announces session endpoint
 *     - POST /message?sessionId=X -> Receives client JSON-RPC requests, pushes responses to SSE stream
 *  2. Direct Stateless HTTP:
 *     - POST /mcp                 -> Direct JSON-RPC 2.0 execution (ideal for scripts & curl)
 *     - GET  /                    -> Service discovery status summary
 *
 * Single Source of Truth (SSOT):
 *  Tool definitions and execution are 100% delegated to AcidSandboxTools (browser, read, write, edit, bash),
 *  ensuring zero discrepancy between in-app Agent and external MCP agents (Cursor, Claude Desktop, DSH).
 */
public class HttpControlServer {
    private static final String TAG = "AcidMcpServer";
    public static final int DEFAULT_PORT = 3090;
    public static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    public static final String MCP_SERVER_NAME = "AcidBrowser";
    public static final String MCP_SERVER_VERSION = "0.4.5";

    private final BrowserController mController;
    private final AcidSandboxTools mSandboxTools;
    private final int mPort;
    private final ExecutorService mPool = Executors.newCachedThreadPool();

    private ServerSocket mServerSocket;
    private volatile boolean mRunning = false;

    // Active SSE client sessions
    private final ConcurrentHashMap<String, SseSession> mSseSessions = new ConcurrentHashMap<String, SseSession>();

    private static class SseSession {
        final String id;
        final OutputStream os;
        final Socket socket;
        final long createdAt;

        SseSession(String id, OutputStream os, Socket socket) {
            this.id = id;
            this.os = os;
            this.socket = socket;
            this.createdAt = System.currentTimeMillis();
        }

        synchronized boolean sendEvent(String eventName, String data) {
            try {
                StringBuilder sb = new StringBuilder();
                if (eventName != null && !eventName.isEmpty()) {
                    sb.append("event: ").append(eventName).append("\r\n");
                }
                sb.append("data: ").append(data).append("\r\n\r\n");
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    public HttpControlServer(BrowserController controller, int port) {
        mController = controller;
        mSandboxTools = new AcidSandboxTools(controller);
        mPort = port;
    }

    public HttpControlServer(BrowserController controller, AcidSandboxTools sandboxTools, int port) {
        mController = controller;
        mSandboxTools = sandboxTools != null ? sandboxTools : new AcidSandboxTools(controller);
        mPort = port;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public synchronized void start() {
        if (mRunning) return;
        mRunning = true;
        mPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mServerSocket = new ServerSocket(mPort, 32, InetAddress.getByName("127.0.0.1"));
                    mController.emitLog("[MCP] Acid MCP Server listening on 127.0.0.1:" + mPort);
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
                        Log.e(TAG, "MCP Server error", e);
                    }
                } finally {
                    mRunning = false;
                }
            }
        });
    }

    public synchronized void stop() {
        mRunning = false;
        try {
            if (mServerSocket != null) {
                mServerSocket.close();
                mServerSocket = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to close the MCP server socket", e);
        }
        // Close all active SSE sessions
        for (SseSession s : mSseSessions.values()) {
            try {
                s.socket.close();
            } catch (Exception e) {
                Log.w(TAG, "Failed to close SSE session socket " + s.id, e);
            }
        }
        mSseSessions.clear();
        mController.emitLog("[MCP] Acid MCP Server stopped (port " + mPort + " released)");
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();

            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            int b;
            int consecutiveNewlines = 0;
            while ((b = is.read()) != -1) {
                headerBuffer.write(b);
                if (b == '\n') {
                    consecutiveNewlines++;
                    if (consecutiveNewlines == 2) break;
                } else if (b != '\r') {
                    consecutiveNewlines = 0;
                }
            }

            String headerText = new String(headerBuffer.toByteArray(), StandardCharsets.US_ASCII);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0 || lines[0].trim().isEmpty()) {
                socket.close();
                return;
            }

            String[] reqLine = lines[0].split(" ");
            if (reqLine.length < 2) {
                socket.close();
                return;
            }
            String method = reqLine[0].toUpperCase();
            String fullPath = reqLine[1];

            Map<String, String> headers = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
                }
            }

            // Parse path and query
            String path = fullPath;
            Map<String, String> query = new HashMap<String, String>();
            int qIdx = fullPath.indexOf('?');
            if (qIdx >= 0) {
                path = fullPath.substring(0, qIdx);
                String qStr = fullPath.substring(qIdx + 1);
                for (String part : qStr.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) {
                        try {
                            query.put(java.net.URLDecoder.decode(part.substring(0, eq), "UTF-8"),
                                      java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
                        } catch (Exception e) {
                            Log.w(TAG, "Skipping malformed query parameter: " + part, e);
                        }
                    }
                }
            }

            // CORS preflight
            if ("OPTIONS".equals(method)) {
                sendCorsPreflightResponse(os);
                socket.close();
                return;
            }

            // Read request body if Content-Length present
            byte[] bodyBytes = new byte[0];
            if (headers.containsKey("content-length")) {
                int len = 0;
                try {
                    len = Integer.parseInt(headers.get("content-length"));
                } catch (Exception e) {
                    Log.w(TAG, "Ignoring non-numeric Content-Length: " + headers.get("content-length"), e);
                }
                if (len > 0) {
                    bodyBytes = new byte[len];
                    int readTotal = 0;
                    while (readTotal < len) {
                        int r = is.read(bodyBytes, readTotal, len - readTotal);
                        if (r < 0) break;
                        readTotal += r;
                    }
                }
            }

            // 1. MCP Standard SSE Transport: GET /sse
            if ("GET".equals(method) && "/sse".equals(path)) {
                handleSseConnection(socket, os);
                return; // keep connection open, do not close socket
            }

            // 2. MCP Standard SSE Message endpoint: POST /message
            if ("POST".equals(method) && ("/message".equals(path) || path.startsWith("/message"))) {
                handleMcpMessagePost(os, query, bodyBytes);
                socket.close();
                return;
            }

            // 3. MCP Direct Stateless RPC: POST /mcp or POST /
            if ("POST".equals(method) && ("/mcp".equals(path) || "/".equals(path))) {
                handleDirectMcpPost(os, bodyBytes);
                socket.close();
                return;
            }

            // 4. MCP Status & Service Discovery: GET / or GET /status
            if ("GET".equals(method) && ("/".equals(path) || "/status".equals(path))) {
                handleMcpStatusGet(os);
                socket.close();
                return;
            }

            // Unknown endpoint
            sendJsonResponse(os, 404, new JSONObject()
                .put("ok", false)
                .put("error", "Not Found")
                .put("hint", "Acid MCP Server endpoints: GET /sse (SSE stream), POST /message?sessionId=X (RPC via SSE), POST /mcp (Direct JSON-RPC 2.0).")
            );
            socket.close();

        } catch (Exception e) {
            Log.w(TAG, "Failed to handle MCP client request", e);
            try {
                socket.close();
            } catch (Exception ee) {
                Log.w(TAG, "Failed to close client socket after an error", ee);
            }
        }
    }

    private void sendCorsPreflightResponse(OutputStream os) throws Exception {
        String resp = "HTTP/1.1 204 No Content\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type, Authorization, Accept\r\n" +
            "Access-Control-Max-Age: 86400\r\n\r\n";
        os.write(resp.getBytes(StandardCharsets.US_ASCII));
        os.flush();
    }

    private void sendJsonResponse(OutputStream os, int statusCode, JSONObject json) throws Exception {
        byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
        String statusText = (statusCode == 200) ? "OK" : ((statusCode == 404) ? "Not Found" : "Internal Error");
        String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: " + payload.length + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n";
        os.write(headers.getBytes(StandardCharsets.US_ASCII));
        os.write(payload);
        os.flush();
    }

    private void handleMcpStatusGet(OutputStream os) throws Exception {
        JSONObject status = new JSONObject();
        status.put("ok", true);
        status.put("server", MCP_SERVER_NAME);
        status.put("version", MCP_SERVER_VERSION);
        status.put("protocol", "Model Context Protocol (MCP)");
        status.put("mcp_spec_version", MCP_PROTOCOL_VERSION);
        status.put("active_sse_sessions", mSseSessions.size());

        JSONObject endpoints = new JSONObject();
        endpoints.put("sse_transport", "GET /sse");
        endpoints.put("message_transport", "POST /message?sessionId={id}");
        endpoints.put("direct_rpc", "POST /mcp");
        status.put("endpoints", endpoints);

        JSONArray toolsList = new JSONArray();
        toolsList.put("browser (Web Automation: navigate, click, fill, select, scroll, wait, eval, tabs)");
        toolsList.put("read (Read sandbox file)");
        toolsList.put("write (Write sandbox file)");
        toolsList.put("edit (Replace string in sandbox file)");
        toolsList.put("bash (Execute shell command)");
        status.put("tools", toolsList);

        sendJsonResponse(os, 200, status);
    }

    private void handleSseConnection(Socket socket, OutputStream os) throws Exception {
        socket.setKeepAlive(true);
        socket.setSoTimeout(0);

        String sessionId = "mcp_session_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 100000);
        SseSession session = new SseSession(sessionId, os, socket);
        mSseSessions.put(sessionId, session);
        mController.emitLog("[MCP] SSE Client connected (session: " + sessionId + ")");

        String sseHeaders = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache, no-transform\r\n" +
            "Connection: keep-alive\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n";
        os.write(sseHeaders.getBytes(StandardCharsets.US_ASCII));
        os.flush();

        session.sendEvent("endpoint", "/message?sessionId=" + sessionId);

        try {
            InputStream is = socket.getInputStream();
            while (mRunning && !socket.isClosed()) {
                if (socket.isInputShutdown() || socket.isOutputShutdown()) break;
                Thread.sleep(15000);
                boolean ok = session.sendEvent("ping", "{}");
                if (!ok) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "SSE stream ended for session " + sessionId, e);
        } finally {
            mSseSessions.remove(sessionId);
            try { socket.close(); } catch (Exception e) {
                Log.w(TAG, "Failed to close SSE socket for session " + sessionId, e);
            }
            mController.emitLog("[MCP] SSE Client disconnected (session: " + sessionId + ")");
        }
    }

    private void handleMcpMessagePost(OutputStream os, Map<String, String> query, byte[] bodyBytes) throws Exception {
        String sessionId = query.get("sessionId");
        String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8).trim();
        if (bodyStr.isEmpty()) {
            sendJsonResponse(os, 400, new JSONObject().put("error", "Empty body"));
            return;
        }

        JSONObject reqJson;
        try {
            reqJson = new JSONObject(bodyStr);
        } catch (Exception e) {
            sendJsonResponse(os, 400, new JSONObject().put("error", "Invalid JSON: " + e.getMessage()));
            return;
        }

        JSONObject rpcResponse = processJsonRpc(reqJson);

        SseSession sse = (sessionId != null) ? mSseSessions.get(sessionId) : null;
        if (sse != null && rpcResponse != null) {
            sse.sendEvent("message", rpcResponse.toString());
            JSONObject ack = new JSONObject();
            ack.put("status", "accepted");
            ack.put("sessionId", sessionId);
            sendJsonResponse(os, 202, ack);
        } else {
            if (rpcResponse != null) {
                sendJsonResponse(os, 200, rpcResponse);
            } else {
                sendJsonResponse(os, 204, new JSONObject());
            }
        }
    }

    private void handleDirectMcpPost(OutputStream os, byte[] bodyBytes) throws Exception {
        String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8).trim();
        if (bodyStr.isEmpty()) {
            sendJsonResponse(os, 400, new JSONObject().put("error", "Empty JSON-RPC body"));
            return;
        }

        JSONObject reqJson;
        try {
            reqJson = new JSONObject(bodyStr);
        } catch (Exception e) {
            sendJsonResponse(os, 400, new JSONObject().put("error", "Invalid JSON: " + e.getMessage()));
            return;
        }

        JSONObject rpcResponse = processJsonRpc(reqJson);
        if (rpcResponse != null) {
            sendJsonResponse(os, 200, rpcResponse);
        } else {
            sendJsonResponse(os, 200, new JSONObject().put("jsonrpc", "2.0").put("result", "acknowledged"));
        }
    }

    /**
     * Executes standard JSON-RPC 2.0 MCP commands:
     * - initialize
     * - notifications/initialized
     * - ping
     * - tools/list
     * - tools/call
     */
    private JSONObject processJsonRpc(JSONObject req) {
        Object id = req.opt("id");
        String method = req.optString("method", "");

        if (id == null && method.startsWith("notifications/")) {
            return null;
        }

        JSONObject resp = new JSONObject();
        try {
            resp.put("jsonrpc", "2.0");
            if (id != null) {
                resp.put("id", id);
            } else {
                resp.put("id", JSONObject.NULL);
            }

            if ("initialize".equals(method)) {
                JSONObject result = new JSONObject();
                result.put("protocolVersion", MCP_PROTOCOL_VERSION);

                JSONObject capabilities = new JSONObject();
                JSONObject toolsCap = new JSONObject();
                toolsCap.put("listChanged", false);
                capabilities.put("tools", toolsCap);
                result.put("capabilities", capabilities);

                JSONObject serverInfo = new JSONObject();
                serverInfo.put("name", MCP_SERVER_NAME);
                serverInfo.put("version", MCP_SERVER_VERSION);
                result.put("serverInfo", serverInfo);

                resp.put("result", result);
                return resp;
            }

            if ("ping".equals(method)) {
                resp.put("result", new JSONObject());
                return resp;
            }

            if ("tools/list".equals(method)) {
                JSONObject result = new JSONObject();
                result.put("tools", AcidSandboxTools.buildMcpToolsList());
                resp.put("result", result);
                return resp;
            }

            if ("tools/call".equals(method)) {
                JSONObject params = req.optJSONObject("params");
                String toolName = params != null ? params.optString("name", "") : "";
                JSONObject toolArgs = params != null ? params.optJSONObject("arguments") : new JSONObject();
                if (toolArgs == null) toolArgs = new JSONObject();

                // Marked as MCP so self-extension tools can refuse the call: this gateway
                // is reachable from any page on the device and must not be able to install
                // persistent scripts.
                AcidSandboxTools.ToolExecResult execRes =
                    mSandboxTools.executeTool(toolName, toolArgs, AcidSandboxTools.ToolSource.MCP);

                JSONObject result = new JSONObject();
                JSONArray content = new JSONArray();
                JSONObject textItem = new JSONObject();
                textItem.put("type", "text");
                textItem.put("text", execRes.outputText != null ? execRes.outputText : "");
                content.put(textItem);
                result.put("content", content);
                result.put("isError", execRes.outputText != null && execRes.outputText.startsWith("Error:"));

                resp.put("result", result);
                return resp;
            }

            JSONObject err = new JSONObject();
            err.put("code", -32601);
            err.put("message", "Method not found: " + method);
            resp.put("error", err);
            return resp;

        } catch (Exception e) {
            Log.e(TAG, "JSON-RPC handler failed", e);
            try {
                JSONObject err = new JSONObject();
                err.put("code", -32603);
                err.put("message", "Internal JSON-RPC Error: " + e.getMessage());
                resp.put("error", err);
            } catch (Exception ee) {
                Log.w(TAG, "Failed to encode JSON-RPC internal error response", ee);
            }
            return resp;
        }
    }
}
