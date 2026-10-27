package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Autonomous In-App Agent Loop Engine for Acid Browser.
 * - Connects directly to any OpenAI-compatible Chat Completions endpoint (DeepSeek, OpenAI, OpenRouter, local gateway, etc.)
 * - Executes multi-step ReAct / Tool-Calling loops up to 35 steps per turn
 * - Sliding-Window Context Pruner: retains full DOM observations only for the latest 2 browser tool results
 * - Supports DeepSeek R1 / Reasoner `reasoning_content` preservation
 * - Coordinates with AgentOverlayView for breathing border and Human Takeover pause/resume
 */
public class AcidAgentLoop {

    private static final String PREF_AGENT = "acid_agent_config";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model_name";

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    public static final String DEFAULT_MODEL = "deepseek-chat";
    private static final int MAX_STEPS_PER_TURN = 35;

    public interface AgentLoopListener {
        void onLoopStateChanged(boolean running, boolean pausedForTakeover, int step);
        void onUserMessage(String text);
        void onThinking(String reasoningText);
        void onToolCall(String toolName, String argsSummary);
        void onToolResult(String toolName, String resultPreview);
        void onAssistantReply(String content);
        void onLoopError(String errorMsg);
    }

    private final Context mContext;
    private final BrowserController mController;
    private final AcidSandboxTools mSandboxTools;
    private final AgentLoopListener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final List<JSONObject> mConversationHistory = new ArrayList<JSONObject>();
    private final JSONArray mToolsSchema;

    private volatile boolean mRunning = false;
    private volatile boolean mStopRequested = false;
    private volatile boolean mPausedForTakeover = false;
    private Thread mWorkerThread = null;

    public AcidAgentLoop(Context context, BrowserController controller, AgentLoopListener listener) {
        mContext = context;
        mController = controller;
        mSandboxTools = new AcidSandboxTools(controller);
        mListener = listener;
        mToolsSchema = AcidSandboxTools.buildToolsSchema();
    }

    // =========================================================================
    // Model Configuration (BYOK Persistence)
    // =========================================================================

    public String getBaseUrl() {
        SharedPreferences sp = mContext.getSharedPreferences(PREF_AGENT, Context.MODE_PRIVATE);
        return sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
    }

    public String getApiKey() {
        SharedPreferences sp = mContext.getSharedPreferences(PREF_AGENT, Context.MODE_PRIVATE);
        return sp.getString(KEY_API_KEY, "");
    }

    public String getModelName() {
        SharedPreferences sp = mContext.getSharedPreferences(PREF_AGENT, Context.MODE_PRIVATE);
        return sp.getString(KEY_MODEL, DEFAULT_MODEL);
    }

    public void saveConfig(String baseUrl, String apiKey, String modelName) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_AGENT, Context.MODE_PRIVATE).edit();
        ed.putString(KEY_BASE_URL, baseUrl != null && !baseUrl.trim().isEmpty() ? baseUrl.trim() : DEFAULT_BASE_URL);
        ed.putString(KEY_API_KEY, apiKey != null ? apiKey.trim() : "");
        ed.putString(KEY_MODEL, modelName != null && !modelName.trim().isEmpty() ? modelName.trim() : DEFAULT_MODEL);
        ed.apply();
    }

    public boolean isRunning() {
        return mRunning;
    }

    public boolean isPausedForTakeover() {
        return mPausedForTakeover;
    }

    public void clearConversation() {
        stopLoop();
        synchronized (mConversationHistory) {
            mConversationHistory.clear();
        }
    }

    public void stopLoop() {
        mStopRequested = true;
        mPausedForTakeover = false;
        if (mWorkerThread != null) {
            try {
                mWorkerThread.interrupt();
            } catch (Exception ignored) {}
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
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
            notifyError("Agent 正在运行中，请先停止当前任务。");
            return;
        }
        String apiKey = getApiKey();
        String baseUrl = getBaseUrl();
        if (apiKey.isEmpty() && !baseUrl.contains("127.0.0.1") && !baseUrl.contains("localhost")) {
            notifyError("未配置 API Key！请先点击右上角 [模型设置] 填入 API Key。");
            return;
        }

        mStopRequested = false;
        mPausedForTakeover = false;
        mRunning = true;

        notifyUserMessage(userPrompt);

        // Attach live current browser snapshot summary to the user message so step 1 has immediate context
        mWorkerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runAgentLoopInternal(userPrompt);
            }
        }, "AcidAgentLoopThread");
        mWorkerThread.start();
    }

    public void resumeFromTakeover() {
        if (!mPausedForTakeover || mRunning) return;
        submitUserTask("我已完成手动操作，请观察当前最新页面并继续完成任务。");
    }

    private String buildSystemPrompt() {
        String wsPath = mSandboxTools.getWorkspaceDir().getAbsolutePath();
        return "You are Acid Agent, an autonomous AI agent living natively inside the Acid Android Browser.\n" +
            "You have full access to 5 core tools inside the app:\n" +
            "1. `browser`: Inspect and control the live WebView (`observe`, `navigate`, `click`, `fill`, `select`, `scroll`, `eval`, `set_mode`, `tabs`, `back`, `reload`, `request_takeover`).\n" +
            "2. `read`: Read files in your private sandbox workspace (`" + wsPath + "`).\n" +
            "3. `write`: Create or overwrite files in the sandbox (including HTML reports or `userscripts/*.user.js` browser plugins).\n" +
            "4. `edit`: Perform exact string replacements in sandbox files.\n" +
            "5. `bash`: Execute shell commands (`/system/bin/sh`) inside the sandbox workspace.\n\n" +
            "## Operational Rules\n" +
            "- **Closed-Loop Web Navigation**: Every `browser` action (`navigate`, `click`, `fill`, `select`, `scroll`, `set_mode`) automatically returns the updated page observation (`[e1..eN]` interactive element handles and text excerpt). Always inspect the returned `[eN]` handles before issuing your next click or fill.\n" +
            "- **Desktop vs Mobile Identity**: If a mobile webpage blocks content, hides buttons, or forces app download banners, immediately call `browser(action='set_mode', mode='desktop')` to switch to 1280px PC mode.\n" +
            "- **Sandbox & Local Reports**: You can save scraped data, write local HTML pages into the workspace via `write`, and display them directly in the browser via `browser(action='navigate', url='workspace://report.html')`.\n" +
            "- **Userscript Self-Programming**: All `.js` files in `userscripts/` inside your workspace are automatically injected into every webpage. You can write custom userscripts to modify pages or block annoyances.\n" +
            "- **Human-in-the-Loop Takeover**: When encountering login passwords, SMS codes, CAPTCHAs, or payment confirmations, call `browser(action='request_takeover', reason='...')` and stop so the user can take over.";
    }

    private void runAgentLoopInternal(String userPrompt) {
        try {
            // Switch WebView overlay to AI driving mode (breathing border + anti-touch lock)
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    mController.setControlMode(AgentOverlayView.MODE_AGENT, "");
                }
            });

            // Capture initial page context
            JSONObject initObs = mController.observeSync(mController.isSomEnabled(), true, false);
            String initSnapshot = initObs != null ? initObs.optString("compactText", "") : "";

            synchronized (mConversationHistory) {
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", userPrompt +
                    (initSnapshot.isEmpty() ? "" : "\n\n[Current Browser State]\n" + initSnapshot));
                mConversationHistory.add(userMsg);
            }

            for (int step = 1; step <= MAX_STEPS_PER_TURN; step++) {
                if (mStopRequested) break;
                notifyState(true, false, step);

                JSONArray payloadMessages = buildPrunedMessagesForRequest();
                JSONObject responseMsg = callChatCompletionsApi(payloadMessages);

                if (mStopRequested || responseMsg == null) break;

                String reasoning = responseMsg.optString("reasoning_content", "");
                if (reasoning != null && !reasoning.trim().isEmpty()) {
                    notifyThinking(reasoning.trim());
                }

                String content = responseMsg.isNull("content") ? "" : responseMsg.optString("content", "");
                // Extract inline <think>...</think> if model emits it inside content
                if (content.contains("<think>") && content.contains("</think>")) {
                    int s = content.indexOf("<think>");
                    int e = content.indexOf("</think>");
                    if (e > s) {
                        String inlineThink = content.substring(s + 7, e).trim();
                        if (!inlineThink.isEmpty() && (reasoning == null || reasoning.isEmpty())) {
                            notifyThinking(inlineThink);
                        }
                        content = (content.substring(0, s) + content.substring(e + 8)).trim();
                    }
                }

                JSONArray toolCalls = responseMsg.optJSONArray("tool_calls");

                // Store assistant message into conversation history
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
                synchronized (mConversationHistory) {
                    mConversationHistory.add(assistantEntry);
                }

                if (!content.isEmpty()) {
                    notifyAssistantReply(content);
                }

                // If no tool calls, the agent has finished its turn!
                if (toolCalls == null || toolCalls.length() == 0) {
                    break;
                }

                // Execute each tool call sequentially
                boolean takeoverTriggered = false;
                for (int i = 0; i < toolCalls.length(); i++) {
                    if (mStopRequested) break;
                    JSONObject tc = toolCalls.getJSONObject(i);
                    String callId = tc.optString("id", "call_" + step + "_" + i);
                    JSONObject fn = tc.optJSONObject("function");
                    String toolName = fn != null ? fn.optString("name", "") : "";
                    String rawArgs = fn != null ? fn.optString("arguments", "{}") : "{}";

                    JSONObject parsedArgs;
                    try {
                        parsedArgs = new JSONObject(rawArgs);
                    } catch (Exception e) {
                        parsedArgs = new JSONObject();
                    }

                    notifyToolCall(toolName, summarizeToolArgs(toolName, parsedArgs));
                    AcidSandboxTools.ToolExecResult execRes = mSandboxTools.executeTool(toolName, parsedArgs);
                    notifyToolResult(toolName, summarizeToolOutput(execRes.outputText));

                    JSONObject toolMsg = new JSONObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", callId);
                    toolMsg.put("name", toolName);
                    toolMsg.put("content", execRes.outputText);
                    synchronized (mConversationHistory) {
                        mConversationHistory.add(toolMsg);
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
                notifyError("Loop Error: " + e.getMessage());
            }
        } finally {
            mRunning = false;
            final boolean paused = mPausedForTakeover;
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
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

    /**
     * Sliding-Window Context Pruner:
     * Keeps full DOM observations only for the most recent 2 browser tool results;
     * older browser observations are compacted to a 2-line summary so 35-step loops never overflow context.
     */
    private JSONArray buildPrunedMessagesForRequest() throws Exception {
        JSONArray arr = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", buildSystemPrompt());
        arr.put(sys);

        synchronized (mConversationHistory) {
            // Find indices of all `browser` tool outputs
            List<Integer> browserToolIndices = new ArrayList<Integer>();
            for (int i = 0; i < mConversationHistory.size(); i++) {
                JSONObject m = mConversationHistory.get(i);
                if ("tool".equals(m.optString("role")) && "browser".equals(m.optString("name"))) {
                    browserToolIndices.add(i);
                }
            }
            int keepThreshold = browserToolIndices.size() > 2
                ? browserToolIndices.get(browserToolIndices.size() - 2)
                : -1;

            for (int i = 0; i < mConversationHistory.size(); i++) {
                JSONObject orig = mConversationHistory.get(i);
                if (i < keepThreshold && "tool".equals(orig.optString("role")) && "browser".equals(orig.optString("name"))) {
                    JSONObject pruned = new JSONObject(orig.toString());
                    String full = orig.optString("content", "");
                    String firstLines = compactOldObservation(full);
                    pruned.put("content", firstLines);
                    arr.put(pruned);
                } else {
                    arr.put(orig);
                }
            }
        }
        return arr;
    }

    private String compactOldObservation(String full) {
        if (full == null || full.length() < 320) return full;
        String[] lines = full.split("\n");
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String line : lines) {
            if (line.startsWith("=== [BROWSER") || line.startsWith("Receipt:") ||
                line.startsWith("Page:") || line.startsWith("URL:") || line.startsWith("Mode:")) {
                sb.append(line).append("\n");
                kept++;
            }
            if (kept >= 5) break;
        }
        sb.append("[Historical DOM element list pruned to save context tokens]");
        return sb.toString();
    }

    private JSONObject callChatCompletionsApi(JSONArray messages) throws Exception {
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

        byte[] payload = reqBody.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        String key = getApiKey();
        if (!key.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }

        OutputStream os = conn.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String respStr = readStreamUtf8(is);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + (respStr.length() > 300 ? respStr.substring(0, 300) : respStr));
        }

        JSONObject respJson = new JSONObject(respStr);
        JSONArray choices = respJson.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("Empty choices from LLM API: " + respStr);
        }
        return choices.getJSONObject(0).getJSONObject("message");
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

    private String summarizeToolArgs(String name, JSONObject args) {
        if ("browser".equals(name)) {
            String act = args.optString("action", "");
            if ("navigate".equals(act)) return "navigate -> " + args.optString("url");
            if ("click".equals(act)) return "click [" + args.optString("ref", args.optString("x") + "," + args.optString("y")) + "]";
            if ("fill".equals(act)) return "fill [" + args.optString("ref") + "] = \"" + args.optString("text") + "\"";
            if ("select".equals(act)) return "select [" + args.optString("ref") + "] = \"" + args.optString("text") + "\"";
            if ("scroll".equals(act)) return "scroll " + args.optString("direction", "down");
            if ("set_mode".equals(act)) return "set_mode -> " + args.optString("mode");
            return act;
        }
        if ("read".equals(name) || "write".equals(name) || "edit".equals(name)) {
            return args.optString("file_path", "");
        }
        if ("bash".equals(name)) {
            String cmd = args.optString("command", "");
            return cmd.length() > 70 ? cmd.substring(0, 70) + "..." : cmd;
        }
        return args.toString();
    }

    private String summarizeToolOutput(String out) {
        if (out == null) return "";
        String[] lines = out.split("\n");
        StringBuilder sb = new StringBuilder();
        int maxLines = Math.min(lines.length, 6);
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append("\n");
        }
        if (lines.length > maxLines) {
            sb.append("... (+").append(lines.length - maxLines).append(" lines)");
        }
        return sb.toString().trim();
    }

    // =========================================================================
    // UI Dispatch Helpers
    // =========================================================================

    private void notifyState(final boolean running, final boolean paused, final int step) {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onLoopStateChanged(running, paused, step);
            }
        });
    }

    private void notifyUserMessage(final String text) {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onUserMessage(text);
            }
        });
    }

    private void notifyThinking(final String text) {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onThinking(text);
            }
        });
    }

    private void notifyToolCall(final String tool, final String summary) {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onToolCall(tool, summary);
            }
        });
    }

    private void notifyToolResult(final String tool, final String preview) {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onToolResult(tool, preview);
            }
        });
    }

    private void notifyAssistantReply(final String content) {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onAssistantReply(content);
            }
        });
    }

    private void notifyError(final String err) {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onLoopError(err);
            }
        });
    }
}
