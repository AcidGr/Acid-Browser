package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Autonomous In-App Agent Loop Engine for Acid Browser (Multi-Session Edition).
 * - Persists every session's UI bubbles (User, Thinking, Tool Call with full args & result, Assistant Markdown)
 *   and pruned OpenAI API message history via AcidSessionStore.
 * - Connects directly to any OpenAI-compatible Chat Completions endpoint.
 * - Supports DeepSeek R1 / Reasoner `reasoning_content` and inline `<think>` blocks.
 */
public class AcidAgentLoop {

    private static final String TAG = "AcidAgentLoop";

    private static final String PREF_AGENT = "acid_agent_config";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model_name";

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    public static final String DEFAULT_MODEL = "deepseek-flash";
    private static final int MAX_STEPS_PER_TURN = 35;

    public interface AgentLoopListener {
        void onLoopStateChanged(boolean running, boolean pausedForTakeover, int step);
        void onSessionLoaded(AcidSessionStore.AgentSession session);
        void onUiItemAdded(AcidSessionStore.UiMessageItem item);
        void onUiItemUpdated(AcidSessionStore.UiMessageItem item);
        void onContextUsageUpdated(int totalTokens, int promptTokens, int cachedTokens);
    }

    private final Context mContext;
    private final BrowserController mController;
    private final AcidSandboxTools mSandboxTools;
    private final AcidSessionStore mSessionStore;
    private final AcidModelStore mModelStore;
    private final AgentLoopListener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final JSONArray mToolsSchema;

    private volatile boolean mRunning = false;
    private volatile boolean mStopRequested = false;
    private volatile boolean mPausedForTakeover = false;
    private Thread mWorkerThread = null;

    public AcidAgentLoop(Context context, BrowserController controller, AgentLoopListener listener) {
        mContext = context;
        mController = controller;
        mSandboxTools = new AcidSandboxTools(controller);
        mSessionStore = new AcidSessionStore(context);
        mModelStore = new AcidModelStore(context);
        mListener = listener;
        mToolsSchema = AcidSandboxTools.buildToolsSchema();
    }

    public AcidSessionStore getSessionStore() {
        return mSessionStore;
    }

    public AcidModelStore getModelStore() {
        return mModelStore;
    }

    public AcidSessionStore.AgentSession getActiveSession() {
        return mSessionStore.getActiveSession();
    }

    // =========================================================================
    // Model Configuration & Selection Queries
    // =========================================================================

    public String getBaseUrl() {
        AcidModelStore.ConfiguredProvider p = mModelStore.getActiveProvider();
        if (p != null && !p.baseUrl.isEmpty()) {
            return p.baseUrl;
        }
        SharedPreferences sp = mContext.getSharedPreferences(PREF_AGENT, Context.MODE_PRIVATE);
        return sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
    }

    public String getApiKey() {
        AcidModelStore.ConfiguredProvider p = mModelStore.getActiveProvider();
        if (p != null) {
            return p.apiKey != null ? p.apiKey : "";
        }
        SharedPreferences sp = mContext.getSharedPreferences(PREF_AGENT, Context.MODE_PRIVATE);
        return sp.getString(KEY_API_KEY, "");
    }

    public String getModelName() {
        AcidModelStore.ModelEntry m = mModelStore.getActiveModel();
        if (m != null && !m.id.isEmpty()) {
            return m.id;
        }
        SharedPreferences sp = mContext.getSharedPreferences(PREF_AGENT, Context.MODE_PRIVATE);
        return sp.getString(KEY_MODEL, DEFAULT_MODEL);
    }

    public String getModelStatusDisplay() {
        return mModelStore.getStatusDisplayText();
    }

    public boolean isRunning() {
        return mRunning;
    }

    public boolean isPausedForTakeover() {
        return mPausedForTakeover;
    }

    public boolean isStopRequested() {
        return mStopRequested;
    }

    // =========================================================================
    // Multi-Session Management Operations
    // =========================================================================

    public void startNewSession() {
        stopLoop();
        final AcidSessionStore.AgentSession s = mSessionStore.createNewSession();
        if (mListener != null) {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onSessionLoaded(s);
                }
            });
        }
    }

    public void switchSession(String sessionId) {
        stopLoop();
        final AcidSessionStore.AgentSession s = mSessionStore.switchToSession(sessionId);
        if (mListener != null && s != null) {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onSessionLoaded(s);
                }
            });
        }
    }

    public void deleteSession(String sessionId) {
        if (mRunning && getActiveSession() != null && getActiveSession().id.equals(sessionId)) {
            stopLoop();
        }
        mSessionStore.deleteSession(sessionId);
        final AcidSessionStore.AgentSession active = mSessionStore.getActiveSession();
        if (mListener != null) {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onSessionLoaded(active);
                }
            });
        }
    }

    public void stopLoop() {
        mStopRequested = true;
        mPausedForTakeover = false;
        if (mWorkerThread != null) {
            try {
                mWorkerThread.interrupt();
            } catch (Exception e) {
                Log.w(TAG, "Failed to interrupt the agent worker thread", e);
            }
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mController.setAgentBoundTabId(-1);
                if (mController.getControlMode() == AgentOverlayView.MODE_AGENT) {
                    mController.setControlMode(AgentOverlayView.MODE_HUMAN, "");
                }
                if (mListener != null) {
                    mListener.onLoopStateChanged(false, false, 0);
                }
            }
        });
    }

    /**
     * Starts a new user task turn or resumes after human takeover.
     */
    public void submitUserTask(final String userPrompt) {
        if (mRunning) {
            addErrorItem("Agent 正在运行中，请先点击停止按钮中断当前任务。");
            return;
        }
        AcidModelStore.ConfiguredProvider activeProvider = mModelStore.getActiveProvider();
        AcidModelStore.ModelEntry activeModel = mModelStore.getActiveModel();
        if (activeProvider == null || activeModel == null) {
            addErrorItem("尚未装载任何可用模型！请点击浏览器右下角 [功能 -> 模型配置] 添加供应商与模型。");
            return;
        }
        String apiKey = getApiKey();
        String baseUrl = getBaseUrl();
        if (apiKey.isEmpty() && !baseUrl.contains("127.0.0.1") && !baseUrl.contains("localhost")) {
            addErrorItem("当前供应商 [" + activeProvider.displayName + "] 尚未配置 API Key！请点击浏览器右下角 [功能 -> 模型配置] 填入 API Key，或点击面板右上角切换其他已配置模型。");
            return;
        }

        mStopRequested = false;
        mPausedForTakeover = false;
        mRunning = true;

        // Bind the current active tab to this session/loop turn
        BrowserController.TabSession activeTab = mController.getActiveTab();
        if (activeTab != null) {
            mController.setAgentBoundTabId(activeTab.id);
        }

        final AcidSessionStore.AgentSession session = mSessionStore.getActiveSession();
        mSessionStore.updateTitleIfDefault(userPrompt);

        // Create and display User UI bubble
        AcidSessionStore.UiMessageItem userItem = new AcidSessionStore.UiMessageItem();
        userItem.type = AcidSessionStore.TYPE_USER;
        userItem.text = userPrompt;
        userItem.timestamp = System.currentTimeMillis();
        synchronized (mSessionStore) {
            session.uiItems.add(userItem);
            mSessionStore.saveActiveSession();
        }
        notifyItemAdded(userItem);

        mWorkerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runAgentLoopInternal(session, userPrompt);
            }
        }, "AcidAgentLoopThread");
        mWorkerThread.start();
    }

    public void resumeFromTakeover() {
        if (!mPausedForTakeover || mRunning) return;
        if (mController.getAgentBoundTabId() <= 0) {
            BrowserController.TabSession activeTab = mController.getActiveTab();
            if (activeTab != null) {
                mController.setAgentBoundTabId(activeTab.id);
            }
        }
        submitUserTask("我已完成手动操作，请观察当前最新页面并继续完成任务。");
    }

    private String buildSystemPrompt() {
        String wsPath = mSandboxTools.getWorkspaceDir().getAbsolutePath();
        return "You are Acid Agent, an autonomous AI agent living natively inside the Acid Android Browser.\n" +
            "You have full access to 5 core tools inside the app:\n" +
            "1. `browser`: Inspect and control the live WebView (`observe`, `navigate`, `click`, `fill`, `select`, `scroll`, `wait`, `eval`, `set_mode`, `tabs`, `back`, `reload`, `request_takeover`).\n" +
            "2. `read`: Read files in your private sandbox workspace (`" + wsPath + "`).\n" +
            "3. `write`: Create or overwrite files in the sandbox (including HTML reports, scraped data, or `userscripts/<name>.user.js` persistent browser features).\n" +
            "4. `edit`: Perform exact string replacements in sandbox files.\n" +
            "5. `bash`: Execute shell commands (`/system/bin/sh`) inside the sandbox workspace.\n\n" +
            "## On-Demand Skills\n" +
            "- When asked to develop, create, or tweak a userscript or browser feature, FIRST call `read(file_path='skills/userscript_dev.md')` to load the complete authoring skill (metadata block, SVG icon whitelist, runtime boundaries, no GM_* APIs, and code templates). Do NOT read it during regular browsing tasks.\n\n" +
            "## Operational Rules\n" +
            "- **Closed-Loop Web Navigation**: Every `browser` action (`navigate`, `click`, `fill`, `select`, `scroll`, `wait`, `set_mode`) automatically returns the updated page observation (`[e1..eN]` interactive element handles and text excerpt). Always inspect the returned `[eN]` handles before issuing your next click or fill.\n" +
            "- **Deterministic Waiting**: When interacting with dynamic SPAs, waiting for AJAX requests, or waiting for a confirmation message/selector, use `browser(action='wait', condition='stable'|'selector'|'text'|'gone'|'ms', ...)` instead of busy looping.\n" +
            "- **Desktop vs Mobile Identity**: If a mobile webpage blocks content, hides buttons, or forces app download banners, immediately call `browser(action='set_mode', mode='desktop')` to switch to 1280px PC mode.\n" +
            "- **Sandbox & Local Reports**: You can save scraped data, write local HTML pages into the workspace via `write`, and display them directly in the browser via `browser(action='navigate', url='workspace://report.html')`.\n" +
            "- **Persistent Features & Userscripts**: When the user requests a reusable feature (e.g. reading mode, layout tweaks, automation), use `write(file_path='userscripts/<name>.user.js', content='...')` rather than one-off `eval`. Consult `skills/userscript_dev.md` for header metadata.\n" +
            "- **Ad & Nuisance Blocking**: When the user asks you to block ads, popups, or nuisance overlays on the current site, inspect the element via `browser(action='snapshot')`, derive the standard ABP syntax rule (e.g. `domain.com##.ad-class` or `||ad-domain.com^`), and append it to `adblock/custom_rules.txt` using `edit` or `write` for permanent hot-reloaded blocking. You can also run `browser(action='eval', script='document.querySelector(\"...\").remove()')` to make it disappear instantly.\n" +
            "- **Browser Settings Configuration & Hot-Reload**: The full browser settings are stored in `settings.json` in your workspace (`" + wsPath + "/settings.json`). You can read it via `read(file_path='settings.json')` and modify settings (" + AcidSettings.describeJsonSchemaForPrompt() + ") via `write` or `edit`. Any change to `settings.json` automatically live-reloads into the active browser instance without restarting.\n" +
            "- **Bookmarks Management & Hot-Reload**: User bookmarks are stored in `bookmarks.json` in your workspace (`" + wsPath + "/bookmarks.json`). You can read them (`read`), add new bookmarks, or organize them via `write` or `edit`. Any change to `bookmarks.json` automatically live-reloads into the browser.\n" +
            "- **Human-in-the-Loop Takeover**: When encountering login passwords, SMS codes, CAPTCHAs, or payment confirmations, call `browser(action='request_takeover', reason='...')` and stop so the user can take over.\n" +
            "- Format your final explanations clearly using Markdown (headings, bold, lists, code blocks).";
    }

    private void runAgentLoopInternal(AcidSessionStore.AgentSession session, String userPrompt) {
        try {
            JSONObject initObs = mController.observeSync(mController.isSomEnabled(), true, false);
            String initSnapshot = initObs != null ? initObs.optString("compactText", "") : "";

            synchronized (mSessionStore) {
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", userPrompt +
                    (initSnapshot.isEmpty() ? "" : "\n\n[Current Browser State]\n" + initSnapshot));
                session.apiMessages.add(userMsg);
                mSessionStore.saveActiveSession();
            }
            notifyContextUsage(session);

            // Unlimited ReAct autonomous loop: continues until task completed, stopped by user, or takeover requested
            for (int step = 1; ; step++) {
                if (mStopRequested) break;
                notifyState(true, false, step);

                JSONArray payloadMessages = buildPrunedMessagesForRequest(session);
                JSONObject responseMsg = callChatCompletionsStream(session, payloadMessages, step);

                if (mStopRequested || responseMsg == null) break;

                String reasoning = responseMsg.optString("reasoning_content", "");
                String content = responseMsg.isNull("content") ? "" : responseMsg.optString("content", "");
                JSONArray toolCalls = responseMsg.optJSONArray("tool_calls");

                // Save assistant API message
                JSONObject assistantEntry = new JSONObject();
                assistantEntry.put("role", "assistant");
                if (!content.isEmpty()) {
                    assistantEntry.put("content", content);
                } else {
                    assistantEntry.put("content", JSONObject.NULL);
                }
                if (reasoning != null && !reasoning.isEmpty()) {
                    assistantEntry.put("reasoning_content", reasoning);
                }
                if (toolCalls != null && toolCalls.length() > 0) {
                    assistantEntry.put("tool_calls", toolCalls);
                }
                synchronized (mSessionStore) {
                    session.apiMessages.add(assistantEntry);
                    mSessionStore.saveActiveSession();
                }
                notifyContextUsage(session);

                if (toolCalls == null || toolCalls.length() == 0) {
                    break;
                }

                // Activate anti-touch lock on-demand only when the agent calls browser manipulation tool
                boolean hasBrowserTool = false;
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject tc = toolCalls.optJSONObject(i);
                    if (tc != null) {
                        JSONObject fn = tc.optJSONObject("function");
                        if (fn != null && "browser".equals(fn.optString("name"))) {
                            hasBrowserTool = true;
                            break;
                        }
                    }
                }
                if (hasBrowserTool && mController.getControlMode() != AgentOverlayView.MODE_AGENT) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mController.setControlMode(AgentOverlayView.MODE_AGENT, "");
                        }
                    });
                }

                boolean takeoverTriggered = false;
                for (int i = 0; i < toolCalls.length(); i++) {
                    if (mStopRequested) break;
                    JSONObject tc = toolCalls.getJSONObject(i);
                    String callId = tc.optString("id", "call_" + step + "_" + i);
                    JSONObject fn = tc.optJSONObject("function");
                    String toolName = fn != null ? fn.optString("name", "") : "";
                    String rawArgs = fn != null ? fn.optString("arguments", "{}") : "{}";

                    JSONObject parsedArgs;
                    String prettyArgs;
                    try {
                        parsedArgs = new JSONObject(rawArgs);
                        prettyArgs = parsedArgs.toString(2);
                    } catch (Exception e) {
                        parsedArgs = new JSONObject();
                        prettyArgs = rawArgs;
                    }

                    // Create Tool Call UI bubble in running state
                    AcidSessionStore.UiMessageItem toolUiItem = new AcidSessionStore.UiMessageItem();
                    toolUiItem.type = AcidSessionStore.TYPE_TOOL;
                    toolUiItem.toolCallId = callId;
                    toolUiItem.toolName = toolName;
                    toolUiItem.toolSummary = summarizeToolArgs(toolName, parsedArgs);
                    toolUiItem.toolArgsJson = prettyArgs;
                    toolUiItem.toolResult = "正在执行...";
                    toolUiItem.isRunning = true;
                    toolUiItem.timestamp = System.currentTimeMillis();
                    synchronized (mSessionStore) {
                        session.uiItems.add(toolUiItem);
                    }
                    notifyItemAdded(toolUiItem);

                    // Execute tool
                    AcidSandboxTools.ToolExecResult execRes = mSandboxTools.executeTool(toolName, parsedArgs);

                    // Update Tool Call UI bubble with complete result
                    toolUiItem.isRunning = false;
                    toolUiItem.toolResult = execRes.outputText;
                    toolUiItem.isError = execRes.outputText != null && execRes.outputText.startsWith("Error:");
                    notifyItemUpdated(toolUiItem);

                    JSONObject toolMsg = new JSONObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", callId);
                    toolMsg.put("name", toolName);
                    toolMsg.put("content", execRes.outputText);
                    synchronized (mSessionStore) {
                        session.apiMessages.add(toolMsg);
                        mSessionStore.saveActiveSession();
                    }

                    if (execRes.requestedTakeover) {
                        takeoverTriggered = true;
                        mPausedForTakeover = true;
                        break;
                    }
                }

                if (takeoverTriggered) {
                    break;
                }
            }
        } catch (final Exception e) {
            if (!mStopRequested) {
                addErrorItem("请求异常: " + e.getMessage());
            }
        } finally {
            mRunning = false;
            final boolean paused = mPausedForTakeover;
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!paused) {
                        mController.setAgentBoundTabId(-1);
                    }
                    if (!paused && mController.getControlMode() == AgentOverlayView.MODE_AGENT) {
                        mController.setControlMode(AgentOverlayView.MODE_HUMAN, "");
                    }
                    if (mListener != null) {
                        mListener.onLoopStateChanged(false, paused, 0);
                    }
                }
            });
        }
    }

    private void addErrorItem(String errorMsg) {
        AcidSessionStore.AgentSession session = mSessionStore.getActiveSession();
        AcidSessionStore.UiMessageItem errItem = new AcidSessionStore.UiMessageItem();
        errItem.type = AcidSessionStore.TYPE_ERROR;
        errItem.text = errorMsg;
        errItem.isError = true;
        errItem.timestamp = System.currentTimeMillis();
        synchronized (mSessionStore) {
            session.uiItems.add(errItem);
            mSessionStore.saveActiveSession();
        }
        notifyItemAdded(errItem);
    }

    private JSONArray buildPrunedMessagesForRequest(AcidSessionStore.AgentSession session) throws Exception {
        JSONArray arr = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", buildSystemPrompt());
        arr.put(sys);

        synchronized (mSessionStore) {
            List<Integer> browserToolIndices = new ArrayList<Integer>();
            for (int i = 0; i < session.apiMessages.size(); i++) {
                JSONObject m = session.apiMessages.get(i);
                if ("tool".equals(m.optString("role")) && "browser".equals(m.optString("name"))) {
                    browserToolIndices.add(i);
                }
            }
            int keepThreshold = browserToolIndices.size() > 2
                ? browserToolIndices.get(browserToolIndices.size() - 2)
                : -1;

            for (int i = 0; i < session.apiMessages.size(); i++) {
                JSONObject orig = session.apiMessages.get(i);
                if (i < keepThreshold && "tool".equals(orig.optString("role")) && "browser".equals(orig.optString("name"))) {
                    JSONObject pruned = new JSONObject(orig.toString());
                    String full = orig.optString("content", "");
                    pruned.put("content", compactOldObservation(full));
                    arr.put(pruned);
                } else {
                    arr.put(orig);
                }
            }
        }
        return arr;
    }

    private String compactOldObservation(String full) {
        if (full == null || full.length() < 300) return full;

        // 1. If it is an eval result, tabs list, or general text/error, DO NOT treat it as a DOM snapshot
        if (!full.contains("Interactive Elements (")) {
            // If it is extraordinarily huge (>8192 chars), apply DSH-style head/tail retention
            if (full.length() > 8192) {
                return full.substring(0, 4096) +
                    "\n\n[... historical output middle pruned (" + (full.length() - 5120) + " chars) ...]\n\n" +
                    full.substring(full.length() - 1024);
            }
            return full;
        }

        // 2. It is a DOM snapshot. Preserve headers, receipt, page title/URL/mode metadata,
        // but replace the obsolete interactive element handle table [e1..eN] with a clear notice.
        String[] lines = full.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean insideElements = false;
        int elementCount = 0;
        int keptLines = 0;

        for (String line : lines) {
            if (line.startsWith("Interactive Elements (")) {
                insideElements = true;
                try {
                    int p1 = line.indexOf('(');
                    int p2 = line.indexOf(')');
                    if (p1 >= 0 && p2 > p1) {
                        elementCount = Integer.parseInt(line.substring(p1 + 1, p2).trim());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not parse expired element count from history line", e);
                }
                sb.append("Interactive Elements: [").append(elementCount > 0 ? elementCount : "historical")
                  .append(" elements expired from earlier step; refer to latest observation]\n");
                continue;
            }
            if (insideElements) {
                if (line.startsWith("  [e") || line.startsWith("  [#")) {
                    continue; // Skip individual stale element lines
                } else {
                    insideElements = false;
                }
            }

            if (line.startsWith("Page Text Excerpt:")) {
                sb.append(line).append("\n");
                keptLines++;
                continue;
            }

            // For the body text excerpt under Page Text Excerpt in old snapshots, cap it to 400 chars
            if (keptLines > 0 && !insideElements) {
                if (line.length() > 400) {
                    sb.append(line.substring(0, 360)).append("... [excerpt truncated]\n");
                } else {
                    sb.append(line).append("\n");
                }
                continue;
            }

            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private static class StreamingToolCall {
        int index;
        String id = "";
        String type = "function";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }

    private JSONObject callChatCompletionsStream(
        AcidSessionStore.AgentSession session,
        JSONArray messages,
        int step
    ) throws Exception {
        String base = getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String endpoint = base.endsWith("/chat/completions") ? base : (base + "/chat/completions");

        JSONObject reqBody = new JSONObject();
        reqBody.put("model", getModelName());
        reqBody.put("messages", messages);
        reqBody.put("tools", mToolsSchema);
        reqBody.put("temperature", 0.2);
        reqBody.put("stream", true);

        try {
            JSONObject streamOpts = new JSONObject();
            streamOpts.put("include_usage", true);
            reqBody.put("stream_options", streamOpts);
        } catch (Exception ignored) {}

        String effort = mModelStore.getSelectedReasoningEffort();
        if (effort != null && !AcidModelStore.EFFORT_OFF.equals(effort) && !AcidModelStore.EFFORT_DEFAULT.equals(effort)) {
            if (AcidModelStore.EFFORT_LOW.equals(effort) ||
                AcidModelStore.EFFORT_MEDIUM.equals(effort) ||
                AcidModelStore.EFFORT_HIGH.equals(effort)) {
                reqBody.put("reasoning_effort", effort);
            } else if (AcidModelStore.EFFORT_MAX.equals(effort)) {
                reqBody.put("reasoning_effort", "high");
            }
        }

        byte[] payload = reqBody.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "text/event-stream");
        String key = getApiKey();
        if (!key.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }

        OutputStream os = conn.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream es = conn.getErrorStream();
            String errStr = readStreamUtf8(es);
            conn.disconnect();
            throw new Exception("HTTP " + code + ": " + (errStr.length() > 300 ? errStr.substring(0, 300) : errStr));
        }

        InputStream is = conn.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

        StringBuilder reasoningSb = new StringBuilder();
        StringBuilder contentSb = new StringBuilder();
        Map<Integer, StreamingToolCall> toolCallsMap = new TreeMap<Integer, StreamingToolCall>();

        AcidSessionStore.UiMessageItem currentThinkItem = null;
        AcidSessionStore.UiMessageItem currentReplyItem = null;

        long lastUiUpdate = 0;
        final long THROTTLE_MS = 60;
        boolean insideThinkTag = false;

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (mStopRequested) {
                    conn.disconnect();
                    break;
                }
                line = line.trim();
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }

                JSONObject chunk;
                try {
                    chunk = new JSONObject(data);
                } catch (Exception e) {
                    continue;
                }

                if (chunk.has("usage") && !chunk.isNull("usage")) {
                    JSONObject u = chunk.optJSONObject("usage");
                    if (u != null) {
                        int pt = u.optInt("prompt_tokens", 0);
                        int ct = u.optInt("completion_tokens", 0);
                        int tt = u.optInt("total_tokens", pt + ct);
                        int cached = 0;
                        JSONObject ptd = u.optJSONObject("prompt_tokens_details");
                        if (ptd != null) {
                            cached = ptd.optInt("cached_tokens", 0);
                        }
                        if (cached == 0) {
                            cached = u.optInt("prompt_cache_hit_tokens", 0);
                        }
                        session.lastPromptTokens = pt;
                        session.lastCompletionTokens = ct;
                        session.lastTotalTokens = tt;
                        session.lastCachedTokens = cached;
                        notifyContextUsage(session);
                    }
                }

                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject choice0 = choices.optJSONObject(0);
                if (choice0 == null) continue;
                JSONObject delta = choice0.optJSONObject("delta");
                if (delta == null) continue;

                // 1. Process reasoning_content (DeepSeek R1 / Reasoner native)
                String rChunk = delta.isNull("reasoning_content") ? "" : delta.optString("reasoning_content", "");
                if ("null".equals(rChunk)) rChunk = "";
                if (!rChunk.isEmpty()) {
                    if (currentThinkItem == null) {
                        currentThinkItem = new AcidSessionStore.UiMessageItem();
                        currentThinkItem.type = AcidSessionStore.TYPE_THINKING;
                        currentThinkItem.isRunning = true;
                        currentThinkItem.timestamp = System.currentTimeMillis();
                        synchronized (mSessionStore) {
                            session.uiItems.add(currentThinkItem);
                        }
                        notifyItemAdded(currentThinkItem);
                    }
                    reasoningSb.append(rChunk);
                    long now = System.currentTimeMillis();
                    if (now - lastUiUpdate >= THROTTLE_MS) {
                        lastUiUpdate = now;
                        currentThinkItem.text = reasoningSb.toString();
                        notifyItemUpdated(currentThinkItem);
                    }
                }

                // 2. Process content (including handling potential inline <think> tags)
                String cChunk = delta.isNull("content") ? "" : delta.optString("content", "");
                if ("null".equals(cChunk)) cChunk = "";
                if (!cChunk.isEmpty()) {
                    if (cChunk.contains("<think>")) {
                        insideThinkTag = true;
                        int s = cChunk.indexOf("<think>");
                        String before = cChunk.substring(0, s);
                        String after = cChunk.substring(s + 7);
                        if (!before.isEmpty()) {
                            contentSb.append(before);
                        }
                        cChunk = after;
                    }

                    if (insideThinkTag) {
                        if (cChunk.contains("</think>")) {
                            insideThinkTag = false;
                            int e = cChunk.indexOf("</think>");
                            String inside = cChunk.substring(0, e);
                            String after = cChunk.substring(e + 8);
                            if (!inside.isEmpty()) {
                                if (currentThinkItem == null) {
                                    currentThinkItem = new AcidSessionStore.UiMessageItem();
                                    currentThinkItem.type = AcidSessionStore.TYPE_THINKING;
                                    currentThinkItem.isRunning = true;
                                    currentThinkItem.timestamp = System.currentTimeMillis();
                                    synchronized (mSessionStore) {
                                        session.uiItems.add(currentThinkItem);
                                    }
                                    notifyItemAdded(currentThinkItem);
                                }
                                reasoningSb.append(inside);
                            }
                            if (currentThinkItem != null) {
                                currentThinkItem.isRunning = false;
                                currentThinkItem.text = reasoningSb.toString().trim();
                                notifyItemUpdated(currentThinkItem);
                            }
                            cChunk = after;
                        } else {
                            if (currentThinkItem == null) {
                                currentThinkItem = new AcidSessionStore.UiMessageItem();
                                currentThinkItem.type = AcidSessionStore.TYPE_THINKING;
                                currentThinkItem.isRunning = true;
                                currentThinkItem.timestamp = System.currentTimeMillis();
                                synchronized (mSessionStore) {
                                    session.uiItems.add(currentThinkItem);
                                }
                                notifyItemAdded(currentThinkItem);
                            }
                            reasoningSb.append(cChunk);
                            long now = System.currentTimeMillis();
                            if (now - lastUiUpdate >= THROTTLE_MS) {
                                lastUiUpdate = now;
                                currentThinkItem.text = reasoningSb.toString();
                                notifyItemUpdated(currentThinkItem);
                            }
                            cChunk = "";
                        }
                    }

                    if (!cChunk.isEmpty()) {
                        if (currentThinkItem != null && currentThinkItem.isRunning) {
                            currentThinkItem.isRunning = false;
                            currentThinkItem.text = reasoningSb.toString().trim();
                            notifyItemUpdated(currentThinkItem);
                        }

                        if (currentReplyItem == null) {
                            currentReplyItem = new AcidSessionStore.UiMessageItem();
                            currentReplyItem.type = AcidSessionStore.TYPE_ASSISTANT;
                            currentReplyItem.isRunning = true;
                            currentReplyItem.timestamp = System.currentTimeMillis();
                            synchronized (mSessionStore) {
                                session.uiItems.add(currentReplyItem);
                            }
                            notifyItemAdded(currentReplyItem);
                        }
                        contentSb.append(cChunk);
                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate >= THROTTLE_MS) {
                            lastUiUpdate = now;
                            currentReplyItem.text = contentSb.toString();
                            notifyItemUpdated(currentReplyItem);
                        }
                    }
                }

                // 3. Process tool_calls delta chunks
                JSONArray toolCallsDelta = delta.optJSONArray("tool_calls");
                if (toolCallsDelta != null) {
                    for (int i = 0; i < toolCallsDelta.length(); i++) {
                        JSONObject tc = toolCallsDelta.optJSONObject(i);
                        if (tc == null) continue;
                        int idx = tc.optInt("index", toolCallsMap.size());
                        StreamingToolCall call = toolCallsMap.get(idx);
                        if (call == null) {
                            call = new StreamingToolCall();
                            call.index = idx;
                            toolCallsMap.put(idx, call);
                        }
                        if (tc.has("id")) {
                            String tid = tc.optString("id", "");
                            if (!tid.isEmpty()) call.id = tid;
                        }
                        if (tc.has("type")) {
                            String tType = tc.optString("type", "");
                            if (!tType.isEmpty()) call.type = tType;
                        }
                        JSONObject fn = tc.optJSONObject("function");
                        if (fn != null) {
                            if (fn.has("name")) {
                                String name = fn.optString("name", "");
                                if (!name.isEmpty()) call.name += name;
                            }
                            if (fn.has("arguments")) {
                                String args = fn.optString("arguments", "");
                                if (!args.isEmpty()) call.arguments.append(args);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (currentThinkItem != null && currentThinkItem.isRunning) {
                currentThinkItem.isRunning = false;
                notifyItemUpdated(currentThinkItem);
            }
            if (currentReplyItem != null && currentReplyItem.isRunning) {
                currentReplyItem.isRunning = false;
                notifyItemUpdated(currentReplyItem);
            }
            throw e;
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {}
            conn.disconnect();
        }

        // Finalize thinking item
        if (currentThinkItem != null) {
            String finR = reasoningSb.toString().trim();
            if (finR.isEmpty() || "null".equals(finR)) {
                synchronized (mSessionStore) {
                    session.uiItems.remove(currentThinkItem);
                }
                currentThinkItem.text = "";
                currentThinkItem.isRunning = false;
                notifyItemUpdated(currentThinkItem);
            } else {
                currentThinkItem.isRunning = false;
                currentThinkItem.text = finR;
                notifyItemUpdated(currentThinkItem);
            }
        }

        // Finalize reply item
        if (currentReplyItem != null) {
            currentReplyItem.isRunning = false;
            currentReplyItem.text = contentSb.toString();
            notifyItemUpdated(currentReplyItem);
        }

        // Assemble tool calls
        JSONArray assembledToolCalls = null;
        if (!toolCallsMap.isEmpty()) {
            assembledToolCalls = new JSONArray();
            for (StreamingToolCall call : toolCallsMap.values()) {
                JSONObject tc = new JSONObject();
                tc.put("id", (call.id != null && !call.id.isEmpty()) ? call.id : ("call_" + step + "_" + call.index));
                tc.put("type", (call.type != null && !call.type.isEmpty()) ? call.type : "function");
                JSONObject fn = new JSONObject();
                fn.put("name", call.name != null ? call.name : "");
                fn.put("arguments", call.arguments.toString());
                tc.put("function", fn);
                assembledToolCalls.put(tc);
            }
        }

        JSONObject resultMsg = new JSONObject();
        resultMsg.put("role", "assistant");
        String finalContent = contentSb.toString();
        if (!finalContent.isEmpty()) {
            resultMsg.put("content", finalContent);
        } else {
            resultMsg.put("content", JSONObject.NULL);
        }
        String finalReasoning = reasoningSb.toString().trim();
        if (!finalReasoning.isEmpty()) {
            resultMsg.put("reasoning_content", finalReasoning);
        }
        if (assembledToolCalls != null && assembledToolCalls.length() > 0) {
            resultMsg.put("tool_calls", assembledToolCalls);
        }
        return resultMsg;
    }

    private String readStreamUtf8(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        is.close();
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static String summarizeToolArgs(String name, JSONObject args) {
        if ("browser".equals(name)) {
            String act = args.optString("action", "observe");
            if ("navigate".equals(act)) return "navigate · " + args.optString("url");
            if ("click".equals(act)) return "click · [" + args.optString("ref", args.optString("x") + "," + args.optString("y")) + "]";
            if ("fill".equals(act)) return "fill · [" + args.optString("ref") + "] \"" + args.optString("text") + "\"";
            if ("select".equals(act)) return "select · [" + args.optString("ref") + "] \"" + args.optString("text") + "\"";
            if ("scroll".equals(act)) return "scroll · " + args.optString("direction", "down");
            if ("wait".equals(act)) return "wait · " + args.optString("condition", "stable") + (args.has("selector") ? " [" + args.optString("selector") + "]" : (args.has("text") ? " \"" + args.optString("text") + "\"" : ""));
            if ("set_mode".equals(act)) return "set_mode · " + args.optString("mode");
            if ("tabs".equals(act)) return "tabs · " + args.optString("tab_action", "list");
            if ("eval".equals(act)) return "eval · JS";
            return act;
        }
        if ("read".equals(name) || "write".equals(name) || "edit".equals(name)) {
            return args.optString("file_path", "");
        }
        if ("bash".equals(name)) {
            String cmd = args.optString("command", "");
            return cmd.length() > 45 ? cmd.substring(0, 45) + "..." : cmd;
        }
        return args.toString();
    }

    private void notifyState(final boolean running, final boolean paused, final int step) {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onLoopStateChanged(running, paused, step);
            }
        });
    }

    private void notifyItemAdded(final AcidSessionStore.UiMessageItem item) {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onUiItemAdded(item);
            }
        });
    }

    private void notifyItemUpdated(final AcidSessionStore.UiMessageItem item) {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onUiItemUpdated(item);
            }
        });
    }

    public void notifyContextUsage(final AcidSessionStore.AgentSession session) {
        if (session == null || mListener == null) return;
        final int tt = session.lastTotalTokens > 0 ? session.lastTotalTokens : computeEstimatedTokens(session);
        final int pt = session.lastPromptTokens > 0 ? session.lastPromptTokens : tt;
        final int cached = session.lastCachedTokens;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onContextUsageUpdated(tt, pt, cached);
            }
        });
    }

    public int computeEstimatedTokens(AcidSessionStore.AgentSession session) {
        if (session == null) return 0;
        if (session.lastTotalTokens > 0) return session.lastTotalTokens;
        int chars = 0;
        synchronized (mSessionStore) {
            for (int i = 0; i < session.apiMessages.size(); i++) {
                JSONObject m = session.apiMessages.get(i);
                if (m != null) {
                    chars += m.optString("content", "").length();
                    chars += m.optString("reasoning_content", "").length();
                    JSONArray tc = m.optJSONArray("tool_calls");
                    if (tc != null) chars += tc.toString().length();
                }
            }
        }
        return Math.max(1, (int) Math.round(chars / 2.0));
    }
}
