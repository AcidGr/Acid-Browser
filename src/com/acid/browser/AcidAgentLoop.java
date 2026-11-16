package com.acid.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    public static final String DEFAULT_MODEL = "deepseek-flash";

    public interface AgentLoopListener {
        void onLoopStateChanged(boolean running, boolean pausedForTakeover, int step);
        void onSessionLoaded(AcidSessionStore.AgentSession session);
        void onUiItemAdded(AcidSessionStore.UiMessageItem item);
        void onUiItemUpdated(AcidSessionStore.UiMessageItem item);
        void onContextUsageUpdated(int totalTokens, int promptTokens, int cachedTokens);
    }

    public static final int DEFAULT_MAX_LOOP_STEPS = 200;

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
    private volatile int mMaxLoopSteps = DEFAULT_MAX_LOOP_STEPS;
    private Thread mWorkerThread = null;
    private volatile HttpURLConnection mActiveConnection = null;
    private final java.util.concurrent.atomic.AtomicInteger mRunGeneration = new java.util.concurrent.atomic.AtomicInteger(0);

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
        return (p != null && !p.baseUrl.isEmpty()) ? p.baseUrl : DEFAULT_BASE_URL;
    }

    public String getApiKey() {
        AcidModelStore.ConfiguredProvider p = mModelStore.getActiveProvider();
        return (p != null && p.apiKey != null) ? p.apiKey : "";
    }

    public String getModelName() {
        AcidModelStore.ModelEntry m = mModelStore.getActiveModel();
        return (m != null && !m.id.isEmpty()) ? m.id : DEFAULT_MODEL;
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

    public int getMaxLoopSteps() {
        return mMaxLoopSteps;
    }

    public void setMaxLoopSteps(int maxSteps) {
        mMaxLoopSteps = Math.max(1, maxSteps);
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
        if ((mRunning || mPausedForTakeover) && getActiveSession() != null && getActiveSession().id.equals(sessionId)) {
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

    private boolean isRunCancelled(int runGen) {
        return mStopRequested || mPausedForTakeover || mRunGeneration.get() != runGen;
    }

    private static void disconnectQuietly(final HttpURLConnection conn) {
        if (conn == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        }, "acid-agent-disconnect");
        t.setDaemon(true);
        t.start();
    }

    public void stopLoop() {
        mStopRequested = true;
        mPausedForTakeover = false;
        mRunning = false;
        final int stopGen = mRunGeneration.incrementAndGet();

        final HttpURLConnection conn = mActiveConnection;
        mActiveConnection = null;
        disconnectQuietly(conn);

        if (mWorkerThread != null) {
            try {
                mWorkerThread.interrupt();
            } catch (Exception e) {
                Log.w(TAG, "Failed to interrupt the agent worker thread", e);
            }
        }

        AcidSessionStore.AgentSession active = mSessionStore != null ? mSessionStore.getActiveSession() : null;
        if (active != null) {
            closePendingRunningUiItems(active, "[任务已由用户手动停止]");
            sanitizeAndAlignApiMessages(active, mSessionStore);
        }

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mRunGeneration.get() != stopGen || mRunning) {
                    return;
                }
                mController.setAgentBoundTabId(-1);
                int mode = mController.getControlMode();
                if (mode == AgentOverlayView.MODE_AGENT || mode == AgentOverlayView.MODE_TAKEOVER_REQUESTED) {
                    mController.setControlMode(AgentOverlayView.MODE_HUMAN, "");
                }
                if (mListener != null) {
                    mListener.onLoopStateChanged(false, false, 0);
                }
            }
        });
    }

    /**
     * Pauses the active Agent loop immediately for user takeover (symmetric with AI-requested takeover).
     * Brakes active network/worker threads, preserves tab binding, aligns tool messages, and flips
     * the system state into MODE_TAKEOVER_REQUESTED so the user can freely interact with the page
     * and tap "交还AI" to resume seamlessly.
     */
    public void pauseForTakeover(final String reason) {
        if (!mRunning) return;
        mStopRequested = false;
        mPausedForTakeover = true;
        mRunning = false;
        final int pauseGen = mRunGeneration.incrementAndGet();

        final HttpURLConnection conn = mActiveConnection;
        mActiveConnection = null;
        disconnectQuietly(conn);

        if (mWorkerThread != null) {
            try {
                mWorkerThread.interrupt();
            } catch (Exception e) {
                Log.w(TAG, "Failed to interrupt the agent worker thread on takeover pause", e);
            }
        }

        AcidSessionStore.AgentSession active = mSessionStore != null ? mSessionStore.getActiveSession() : null;
        if (active != null) {
            closePendingRunningUiItems(active, "[用户接管，任务等待]");
            sanitizeAndAlignApiMessages(active, mSessionStore);
        }

        final String finalReason = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "用户主动接管";
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mRunGeneration.get() != pauseGen || mRunning || !mPausedForTakeover) {
                    return;
                }
                mController.setControlMode(AgentOverlayView.MODE_TAKEOVER_REQUESTED, finalReason);
                if (mListener != null) {
                    mListener.onLoopStateChanged(false, true, 0);
                }
            }
        });
    }

    public int getRunGeneration() {
        return mRunGeneration.get();
    }

    /**
     * Resolves and transitions any dangling transient UI bubbles (e.g. isRunning == true) into a
     * definitive closed state upon user stoppage, takeover, or unexpected abort.
     */
    public void closePendingRunningUiItems(AcidSessionStore.AgentSession session, String stopNotice) {
        if (session == null) return;
        List<AcidSessionStore.UiMessageItem> runningItemsToNotify = new ArrayList<AcidSessionStore.UiMessageItem>();
        if (mSessionStore != null) {
            synchronized (mSessionStore) {
                for (AcidSessionStore.UiMessageItem item : session.uiItems) {
                    if (item != null && item.isRunning) {
                        item.isRunning = false;
                        if (AcidSessionStore.TYPE_TOOL.equals(item.type)) {
                            if (item.toolResult == null || item.toolResult.contains("正在执行")) {
                                item.toolResult = stopNotice != null ? stopNotice : "[执行结束]";
                            }
                        } else if (AcidSessionStore.TYPE_THINKING.equals(item.type)) {
                            if (item.text == null || item.text.trim().isEmpty()) {
                                item.text = stopNotice != null ? stopNotice : "[思考结束]";
                            }
                        } else if (AcidSessionStore.TYPE_ASSISTANT.equals(item.type)) {
                            if (item.text == null || item.text.trim().isEmpty()) {
                                item.text = stopNotice != null ? stopNotice : "[回答结束]";
                            }
                        }
                        runningItemsToNotify.add(item);
                    }
                }
                if (!runningItemsToNotify.isEmpty()) {
                    mSessionStore.saveSession(session);
                }
            }
        } else {
            for (AcidSessionStore.UiMessageItem item : session.uiItems) {
                if (item != null && item.isRunning) {
                    item.isRunning = false;
                    runningItemsToNotify.add(item);
                }
            }
        }
        for (AcidSessionStore.UiMessageItem item : runningItemsToNotify) {
            notifyItemUpdated(item);
        }
    }

    private void closeAbortedStreamingItems(
        AcidSessionStore.AgentSession session,
        AcidSessionStore.UiMessageItem thinkItem,
        AcidSessionStore.UiMessageItem replyItem
    ) {
        List<AcidSessionStore.UiMessageItem> updated = new ArrayList<AcidSessionStore.UiMessageItem>(2);
        Object lock = mSessionStore != null ? (Object) mSessionStore : (Object) session;
        if (lock != null) {
            synchronized (lock) {
                if (thinkItem != null && thinkItem.isRunning) {
                    thinkItem.isRunning = false;
                    updated.add(thinkItem);
                }
                if (replyItem != null && replyItem.isRunning) {
                    replyItem.isRunning = false;
                    updated.add(replyItem);
                }
            }
        } else {
            if (thinkItem != null && thinkItem.isRunning) {
                thinkItem.isRunning = false;
                updated.add(thinkItem);
            }
            if (replyItem != null && replyItem.isRunning) {
                replyItem.isRunning = false;
                updated.add(replyItem);
            }
        }
        for (int i = 0; i < updated.size(); i++) {
            notifyItemUpdated(updated.get(i));
        }
    }

    /**
     * Returns true only when {@code baseUrl} parses to a loopback or local literal host
     * (rejecting substring lookalikes such as {@code https://evil.com/?localhost}).
     */
    public static boolean isLoopbackBaseUrl(String baseUrl) {
        if (baseUrl == null) return false;
        String trimmed = baseUrl.trim();
        if (trimmed.isEmpty()) return false;
        try {
            URI uri = URI.create(trimmed.contains("://") ? trimmed : ("http://" + trimmed));
            return HttpControlServer.isLoopbackOrLocalLiteralHost(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Starts a new user task turn or resumes after human takeover.
     */
    public void submitUserTask(final String userPrompt) {
        if (mRunning) {
            addErrorItem("Agent 正在运行中，请先点击停止按钮中断当前任务。");
            return;
        }

        final Thread prevWorker = mWorkerThread;

        AcidModelStore.ConfiguredProvider activeProvider = mModelStore.getActiveProvider();
        AcidModelStore.ModelEntry activeModel = mModelStore.getActiveModel();
        if (activeProvider == null || activeModel == null) {
            addErrorItem("尚未装载任何可用模型！请点击浏览器右下角 [功能 -> 模型配置] 添加供应商与模型。");
            return;
        }
        String apiKey = getApiKey();
        String baseUrl = getBaseUrl();
        if (apiKey.isEmpty() && !isLoopbackBaseUrl(baseUrl)) {
            addErrorItem("当前供应商 [" + activeProvider.displayName + "] 尚未配置 API Key！请点击浏览器右下角 [功能 -> 模型配置] 填入 API Key，或点击面板右上角切换其他已配置模型。");
            return;
        }

        final AcidSessionStore.AgentSession session = mSessionStore.getActiveSession();
        if (session == null) {
            addErrorItem("当前会话不可用，请重试。");
            return;
        }
        final BrowserController.TabSession activeTab = mController.getActiveTab();
        final int bindTabId = activeTab != null ? activeTab.id : -1;

        mStopRequested = false;
        mPausedForTakeover = false;
        mRunning = true;
        final int runGen = mRunGeneration.incrementAndGet();

        final Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                if (prevWorker != null && prevWorker.isAlive() && Thread.currentThread() != prevWorker) {
                    disconnectQuietly(mActiveConnection);
                    try {
                        prevWorker.interrupt();
                    } catch (Exception ignored) {}
                    try {
                        prevWorker.join(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (prevWorker.isAlive()) {
                        if (mWorkerThread == Thread.currentThread()) {
                            mWorkerThread = prevWorker;
                        }
                        if (!isRunCancelled(runGen)) {
                            mRunning = false;
                            addErrorItem(session, "上一轮请求仍在清理连接，请稍后重试。");
                            final boolean paused = mPausedForTakeover;
                            mMainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (mRunGeneration.get() == runGen) {
                                        if (!paused) {
                                            mController.setAgentBoundTabId(-1);
                                            if (mController.getControlMode() == AgentOverlayView.MODE_AGENT) {
                                                mController.setControlMode(AgentOverlayView.MODE_HUMAN, "");
                                            }
                                        }
                                        if (mListener != null) {
                                            mListener.onLoopStateChanged(false, paused, 0);
                                        }
                                    }
                                }
                            });
                        }
                        return;
                    }
                }
                if (isRunCancelled(runGen)) {
                    return;
                }

                if (bindTabId > 0) {
                    mController.setAgentBoundTabId(bindTabId);
                }

                // Create and display User UI bubble after previous worker has unwound cleanly
                AcidSessionStore.UiMessageItem userItem = new AcidSessionStore.UiMessageItem();
                userItem.type = AcidSessionStore.TYPE_USER;
                userItem.text = userPrompt;
                userItem.timestamp = System.currentTimeMillis();
                synchronized (mSessionStore) {
                    if (isRunCancelled(runGen)) {
                        return;
                    }
                    if (mSessionStore.getActiveSession() == session) {
                        mSessionStore.updateTitleIfDefault(userPrompt);
                    }
                    session.uiItems.add(userItem);
                    mSessionStore.saveSession(session);
                }
                notifyItemAdded(userItem);

                // Run history alignment after previous worker unwinds so turns never overlap on apiMessages.
                sanitizeAndAlignApiMessages(session, mSessionStore);
                if (isRunCancelled(runGen)) {
                    return;
                }
                runAgentLoopInternal(session, userPrompt, runGen);
            }
        }, "AcidAgentLoopThread");
        mWorkerThread = worker;
        worker.start();
    }

    public void resumeFromTakeover() {
        if (!mPausedForTakeover || mRunning) return;
        if (mController.getAgentBoundTabId() <= 0) {
            BrowserController.TabSession activeTab = mController.getActiveTab();
            if (activeTab != null) {
                mController.setAgentBoundTabId(activeTab.id);
            }
        }
        submitUserTask(AcidLocale.getString(
            R.string.takeover_resume_prompt,
            "我已完成手动操作，请观察当前最新页面并继续完成任务。",
            "I have completed the manual action. Please observe the current page and continue the task."
        ));
    }

    private String buildSystemPrompt() {
        String wsPath = mSandboxTools.getWorkspaceDir().getAbsolutePath();
        String skillsCatalog = "";
        if (mController != null && mController.getSkillManager() != null) {
            skillsCatalog = mController.getSkillManager().buildSystemPromptCatalog();
        }
        if (skillsCatalog.isEmpty()) {
            skillsCatalog = "## On-Demand Skills\n" +
                "- When asked to develop, create, or tweak a userscript or browser feature, FIRST call `read(file_path='skills/userscript_dev.md')`.\n" +
                "- When asked to design, customize, or style the browser homepage, FIRST call `read(file_path='skills/homepage_dev.md')`.\n" +
                "- When asked to download files from cloud drives (Baidu Netdisk, UC Drive, Quark, 123Pan, 115, Xunlei, Lanzou) without an app, FIRST call `read(file_path='skills/cloud_drive_download.md')`.\n\n";
        }
        return "You are Acid Agent, an autonomous AI agent living natively inside the Acid Android Browser.\n" +
            "You have full access to 5 core tools inside the app:\n" +
            "1. `browser`: Inspect and control the live WebView (`observe`, `screenshot`, `navigate`, `click`, `fill`, `select`, `scroll`, `wait`, `eval`, `upload`, `download`, `set_mode`, `tabs`, `back`, `reload`, `request_takeover`).\n" +
            "2. `read`: Read files in your private sandbox workspace (`" + wsPath + "`).\n" +
            "3. `write`: Create or overwrite files in the sandbox (including HTML reports, scraped data, or `userscripts/<name>.user.js` persistent browser features).\n" +
            "4. `edit`: Perform exact string replacements in sandbox files.\n" +
            "5. `bash`: Execute shell commands (`/system/bin/sh`) inside the sandbox workspace.\n\n" +
            skillsCatalog +
            "## Operational Rules\n" +
            "- **Closed-Loop Web Navigation**: Every `browser` action (`navigate`, `click`, `fill`, `select`, `scroll`, `wait`, `upload`, `set_mode`) automatically returns the updated page observation (`[e1..eN]` interactive element handles and text excerpt). Always inspect the returned `[eN]` handles before issuing your next click or fill.\n" +
            "- **Visual Screenshot Observation**: Call `browser(action='screenshot', som=true/false, save_path='...')` whenever you need to see the rendered page visually (charts, canvas, layout checks, image/CAPTCHA inspection, visual icons). Pass `som=true` to draw `[e1..eN]` labels strictly onto the returned screenshot image (without modifying the webpage). Pass optional `save_path` (`.png`/`.jpg`/`.jpeg` inside `acid_workspace/`) to save the exact same-resolution screenshot image to disk (for post-processing via `bash` and uploading via `browser(action='upload')`). After a screenshot, `browser(action='click', x=..., y=...)` automatically accepts pixel coordinates directly in the screenshot's dimensions (`coord_space='auto'`/`'screenshot'`) in both Mobile and Desktop modes.\n" +
            "- **Uploading Workspace Files**: To upload attachments onto a webpage, NEVER call `fill` on `input(file)`. Call `browser(action='upload', ref='e...', file_path='report.pdf')` (or `file_paths=['a.png', 'b.png']` for multi-file upload) targeting the `input(file)`, `label(file-trigger)`, or upload button. Only regular non-protected files inside `acid_workspace/` may be uploaded (protected config/state/homepage/userscripts/adblock/skills entries are denied).\n" +
            "- **Deterministic Waiting**: When interacting with dynamic SPAs, waiting for AJAX requests, or waiting for a confirmation message/selector, use `browser(action='wait', condition='stable'|'selector'|'text'|'gone'|'ms', ...)` instead of busy looping.\n" +
            "- **Desktop vs Mobile Identity**: If a mobile webpage blocks content, hides buttons, or forces app download banners, immediately call `browser(action='set_mode', mode='desktop')` to switch to 1280px PC mode.\n" +
            "- **Sandbox & Local Reports**: You can save scraped data, write local HTML pages into the workspace via `write`, and display them directly in the browser via `browser(action='navigate', url='workspace://report.html')`.\n" +
            "- **Custom Homepage Authoring & Styling**: The active browser homepage is stored at `homepage/index.html` in your workspace (`workspace/homepage/index.html`). When asked to design, customize, or modify the homepage, you MUST FIRST call `read(file_path='skills/homepage_dev.md')` to load the interactive discovery SOP (strictly ask clarifying preference questions before coding!) and recommended zero-key APIs.\n" +
            "- **Persistent Features & Userscripts**: When the user requests a reusable feature (e.g. reading mode, layout tweaks, automation), use `write(file_path='userscripts/<name>.user.js', content='...')` rather than one-off `eval`. Consult `skills/userscript_dev.md` for header metadata.\n" +
            "- **Ad & Nuisance Blocking**: When the user asks you to block ads, popups, or nuisance overlays on the current site, inspect the element via `browser(action='observe')`, derive the standard ABP syntax rule (e.g. `domain.com##.ad-class` or `||ad-domain.com^`), and append it to `adblock/custom_rules.txt` using `edit` or `write` for permanent hot-reloaded blocking. You can also run `browser(action='eval', code='document.querySelector(\"...\").remove()')` to make it disappear instantly.\n" +
            "- **Browser Settings Configuration & Hot-Reload**: The full browser settings are stored in `settings.json` in your workspace (`" + wsPath + "/settings.json`). You can read it via `read(file_path='settings.json')` and modify settings (" + AcidSettings.describeJsonSchemaForPrompt() + ") via `write` or `edit`. Any change to `settings.json` automatically live-reloads into the active browser instance without restarting.\n" +
            "- **Bookmarks Management & Hot-Reload**: User bookmarks are stored in `bookmarks.json` in your workspace (`" + wsPath + "/bookmarks.json`). You can read them (`read`), add new bookmarks, or organize them via `write` or `edit`. Any change to `bookmarks.json` automatically live-reloads into the browser.\n" +
            "- **Browsing History & Audit**: Full browsing history is stored in `history.json` in your workspace (`" + wsPath + "/history.json`) with timestamps. You can read it (`read`) to help users search, recall, or organize past visits. Any modification automatically live-reloads.\n" +
            "- **Human-in-the-Loop Takeover**: When encountering login passwords, SMS codes, CAPTCHAs, or payment confirmations, call `browser(action='request_takeover', reason='...')` and stop so the user can take over.\n" +
            "- **Response Language**: " + (AcidLocale.isEn()
                ? "Respond to the user in English unless the user explicitly asks in another language.\n"
                : "Respond to the user in Simplified Chinese (简体中文) unless the user explicitly asks in another language.\n") +
            "- Format your final explanations clearly using Markdown (headings, bold, lists, code blocks).";
    }

    private void runAgentLoopInternal(AcidSessionStore.AgentSession session, String userPrompt, final int runGen) {
        try {
            if (isRunCancelled(runGen)) return;
            JSONObject initObs = mController.getAutomationDriver().observeSync(mController.isSomEnabled(), true, false);
            String initSnapshot = initObs != null ? initObs.optString("compactText", "") : "";

            synchronized (mSessionStore) {
                if (isRunCancelled(runGen)) return;
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", userPrompt +
                    (initSnapshot.isEmpty() ? "" : "\n\n[Current Browser State]\n" + initSnapshot));
                session.apiMessages.add(userMsg);
                mSessionStore.saveActiveSession();
            }
            notifyContextUsage(session);

            // Guarded ReAct autonomous loop: continues until task completed, stopped by user, takeover requested, or max steps reached
            for (int step = 1; ; step++) {
                if (isRunCancelled(runGen)) break;
                if (step > mMaxLoopSteps) {
                    addErrorItem(session, "已达到单次任务最大自主执行步数上限 (" + mMaxLoopSteps + " 步)，智能体已暂停以防止死循环。如需继续，请发送新指令。");
                    break;
                }
                notifyState(true, false, step);

                JSONArray payloadMessages = buildPrunedMessagesForRequest(session);
                JSONObject responseMsg = callChatCompletionsStream(session, payloadMessages, step, runGen);

                if (isRunCancelled(runGen) || responseMsg == null) break;

                String reasoning = responseMsg.optString("reasoning_content", "");
                String content = responseMsg.isNull("content") ? "" : responseMsg.optString("content", "");
                JSONArray toolCalls = responseMsg.optJSONArray("tool_calls");

                // Save assistant API message (guarded inside session lock so stop/takeover cannot interleave)
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
                    if (isRunCancelled(runGen)) break;
                    session.apiMessages.add(assistantEntry);
                    mSessionStore.saveSession(session);
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
                            if (mRunGeneration.get() == runGen && mRunning && !mStopRequested
                                && mController.getControlMode() != AgentOverlayView.MODE_AGENT) {
                                mController.setControlMode(AgentOverlayView.MODE_AGENT, "");
                            }
                        }
                    });
                }

                boolean takeoverTriggered = false;
                int executedToolCount = 0;
                List<JSONObject> turnImages = new ArrayList<JSONObject>();
                try {
                    for (int i = 0; i < toolCalls.length(); i++) {
                        if (mStopRequested || mRunGeneration.get() != runGen) break;
                        JSONObject tc = toolCalls.getJSONObject(i);
                        String callId = tc.optString("id", "call_" + step + "_" + i);
                        JSONObject fn = tc.optJSONObject("function");
                        String toolName = fn != null ? fn.optString("name", "") : "";
                        String rawArgs = (fn != null && !fn.isNull("arguments")) ? fn.optString("arguments", "{}") : "{}";

                        JSONObject parsedArgs = null;
                        String prettyArgs;
                        String argsParseError = null;
                        try {
                            parsedArgs = parseToolArgumentsStrict(rawArgs);
                            prettyArgs = parsedArgs.toString(2);
                        } catch (Exception e) {
                            argsParseError = e.getMessage() != null ? e.getMessage() : "Malformed JSON";
                            prettyArgs = rawArgs;
                        }

                        // Create Tool Call UI bubble in running state
                        AcidSessionStore.UiMessageItem toolUiItem = new AcidSessionStore.UiMessageItem();
                        toolUiItem.type = AcidSessionStore.TYPE_TOOL;
                        toolUiItem.toolCallId = callId;
                        toolUiItem.toolName = toolName;
                        toolUiItem.toolSummary = parsedArgs != null
                            ? summarizeToolArgs(toolName, parsedArgs)
                            : (toolName + " · [invalid JSON args]");
                        toolUiItem.toolArgsJson = prettyArgs;
                        toolUiItem.toolResult = "正在执行...";
                        toolUiItem.isRunning = true;
                        toolUiItem.timestamp = System.currentTimeMillis();
                        synchronized (mSessionStore) {
                            session.uiItems.add(toolUiItem);
                        }
                        notifyItemAdded(toolUiItem);

                        // Execute tool (or reject immediately on malformed JSON arguments instead of running with {})
                        AcidSandboxTools.ToolExecResult execRes = (parsedArgs != null)
                            ? mSandboxTools.executeTool(toolName, parsedArgs)
                            : new AcidSandboxTools.ToolExecResult(
                                "Error: Invalid JSON arguments for tool '" + toolName + "' (" + argsParseError
                                    + "). Please re-issue the tool call with valid JSON.",
                                false,
                                null
                            );

                        if (mStopRequested || mRunGeneration.get() != runGen) {
                            break;
                        }

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
                            if (mStopRequested || mRunGeneration.get() != runGen) {
                                break;
                            }
                            session.apiMessages.add(toolMsg);
                            mSessionStore.saveSession(session);
                        }
                        if (execRes.imageBase64 != null && !execRes.imageBase64.isEmpty()) {
                            JSONObject imgMeta = new JSONObject();
                            imgMeta.put("base64", execRes.imageBase64);
                            imgMeta.put("mimeType", execRes.imageMimeType != null ? execRes.imageMimeType : ScreenshotCaptureHelper.DEFAULT_MIME_TYPE);
                            imgMeta.put("width", execRes.imageWidth);
                            imgMeta.put("height", execRes.imageHeight);
                            turnImages.add(imgMeta);
                        }
                        executedToolCount = i + 1;

                        if (execRes.requestedTakeover) {
                            takeoverTriggered = true;
                            mPausedForTakeover = true;
                            break;
                        }
                    }
                } finally {
                    if (executedToolCount < toolCalls.length()) {
                        if (mRunGeneration.get() == runGen) {
                            String skipReason = takeoverTriggered
                                ? "[Execution paused: previous tool requested user takeover]"
                                : (mStopRequested ? "[Execution stopped: task stopped by user]" : "[Execution skipped]");
                            ensureToolCallsAlignment(session, toolCalls, executedToolCount, skipReason, mSessionStore);
                        } else {
                            // stopLoop()/pauseForTakeover() may have run concurrently before/after assistantEntry
                            sanitizeAndAlignApiMessages(session, mSessionStore);
                        }
                    }
                }

                if (!takeoverTriggered && !mStopRequested && mRunGeneration.get() == runGen
                    && executedToolCount == toolCalls.length() && !turnImages.isEmpty()) {
                    appendTurnScreenshotMessages(session, turnImages, mSessionStore);
                }

                if (takeoverTriggered) {
                    break;
                }
            }
        } catch (final Exception e) {
            if (!mStopRequested && mRunGeneration.get() == runGen) {
                addErrorItem(session, "请求异常: " + e.getMessage());
            }
        } finally {
            if (mRunGeneration.get() == runGen) {
                mRunning = false;
                closePendingRunningUiItems(session, mStopRequested ? "[任务已由用户手动停止]" : null);
                final boolean paused = mPausedForTakeover;
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mRunGeneration.get() != runGen) {
                            return;
                        }
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
    }

    private void addErrorItem(String errorMsg) {
        addErrorItem(null, errorMsg);
    }

    private void addErrorItem(AcidSessionStore.AgentSession targetSession, String errorMsg) {
        AcidSessionStore.AgentSession session = targetSession != null ? targetSession : mSessionStore.getActiveSession();
        if (session == null) return;
        AcidSessionStore.UiMessageItem errItem = new AcidSessionStore.UiMessageItem();
        errItem.type = AcidSessionStore.TYPE_ERROR;
        errItem.text = errorMsg;
        errItem.isError = true;
        errItem.timestamp = System.currentTimeMillis();
        synchronized (mSessionStore) {
            session.uiItems.add(errItem);
            mSessionStore.saveSession(session);
        }
        notifyItemAdded(errItem);
    }

    /**
     * Enforces strict OpenAI/ReAct protocol alignment: every tool call declared in an assistant message
     * MUST be paired with a corresponding role="tool" response before the next turn, even when execution
     * is prematurely paused by user takeover, stopped by user, or aborted due to an error.
     */
    public static void ensureToolCallsAlignment(
        AcidSessionStore.AgentSession session,
        JSONArray toolCalls,
        int executedCount,
        String skipReason,
        AcidSessionStore sessionStore
    ) {
        if (session == null || toolCalls == null || executedCount >= toolCalls.length()) return;
        for (int j = executedCount; j < toolCalls.length(); j++) {
            JSONObject tc = toolCalls.optJSONObject(j);
            if (tc == null) continue;
            String remCallId = tc.optString("id", "call_" + j);
            JSONObject remFn = tc.optJSONObject("function");
            String remName = remFn != null ? remFn.optString("name", "") : "tool";
            JSONObject placeholderMsg = new JSONObject();
            try {
                placeholderMsg.put("role", "tool");
                placeholderMsg.put("tool_call_id", remCallId);
                placeholderMsg.put("name", remName);
                placeholderMsg.put("content", skipReason != null ? skipReason : "[Tool execution skipped]");
            } catch (Exception ignored) {}
            if (sessionStore != null) {
                synchronized (sessionStore) {
                    session.apiMessages.add(placeholderMsg);
                }
            } else {
                session.apiMessages.add(placeholderMsg);
            }
        }
        if (sessionStore != null) {
            synchronized (sessionStore) {
                sessionStore.saveSession(session);
            }
        }
    }

    /**
     * Enforces strict OpenAI/ReAct protocol alignment across the entire conversation history:
     * - Every 'assistant' message that declares 'tool_calls' MUST be followed by corresponding
     *   role='tool' responses for each declared tool call before any subsequent user or assistant message.
     * - Orphaned or out-of-order 'tool' responses (not preceded by assistant with matching tool_call_id)
     *   are pruned to avoid 400 rejection.
     * - Any dangling or unclosed 'tool_calls' interrupted by user stoppage or task abort are automatically
     *   healed and closed with synthetic tool messages before the next user prompt is dispatched.
     */
    public static void sanitizeAndAlignApiMessages(
        AcidSessionStore.AgentSession session,
        AcidSessionStore sessionStore
    ) {
        if (session == null) return;
        Object lock = sessionStore != null ? sessionStore : session;
        synchronized (lock) {
            if (session.apiMessages == null || session.apiMessages.isEmpty()) return;
            List<JSONObject> cleaned = new ArrayList<JSONObject>();
            boolean dirty = false;
            int i = 0;
            int n = session.apiMessages.size();
            while (i < n) {
                JSONObject msg = session.apiMessages.get(i);
                if (msg == null) {
                    dirty = true;
                    i++;
                    continue;
                }
                String role = msg.optString("role", "");
                if ("assistant".equals(role) && msg.has("tool_calls") && !msg.isNull("tool_calls")) {
                    JSONArray toolCalls = msg.optJSONArray("tool_calls");
                    cleaned.add(msg);
                    i++;
                    if (toolCalls != null && toolCalls.length() > 0) {
                        Map<String, JSONObject> expectedCalls = new LinkedHashMap<String, JSONObject>();
                        for (int tcIdx = 0; tcIdx < toolCalls.length(); tcIdx++) {
                            JSONObject tc = toolCalls.optJSONObject(tcIdx);
                            if (tc != null) {
                                String callId = tc.optString("id", "call_" + tcIdx);
                                expectedCalls.put(callId, tc);
                            }
                        }

                        Map<String, JSONObject> receivedResponses = new LinkedHashMap<String, JSONObject>();
                        while (i < n) {
                            JSONObject nextMsg = session.apiMessages.get(i);
                            if (nextMsg == null) {
                                dirty = true;
                                i++;
                                continue;
                            }
                            if ("tool".equals(nextMsg.optString("role", ""))) {
                                String tcId = nextMsg.optString("tool_call_id", "");
                                if (expectedCalls.containsKey(tcId)) {
                                    receivedResponses.put(tcId, nextMsg);
                                    i++;
                                } else {
                                    // Orphaned or unknown tool response; discard
                                    dirty = true;
                                    i++;
                                }
                            } else {
                                // Hit a non-tool message (user, assistant, system) before all tool_calls fulfilled!
                                break;
                            }
                        }

                        for (Map.Entry<String, JSONObject> entry : expectedCalls.entrySet()) {
                            String tcId = entry.getKey();
                            JSONObject tc = entry.getValue();
                            JSONObject existingResp = receivedResponses.get(tcId);
                            if (existingResp != null) {
                                cleaned.add(existingResp);
                            } else {
                                dirty = true;
                                JSONObject fn = tc != null ? tc.optJSONObject("function") : null;
                                String fnName = fn != null ? fn.optString("name", "tool") : "tool";
                                JSONObject placeholder = new JSONObject();
                                try {
                                    placeholder.put("role", "tool");
                                    placeholder.put("tool_call_id", tcId);
                                    placeholder.put("name", fnName);
                                    placeholder.put("content", "[任务已中断或停止]");
                                } catch (Exception ignored) {}
                                cleaned.add(placeholder);
                            }
                        }
                    }
                } else if ("tool".equals(role)) {
                    // Stray tool message without preceding assistant tool_calls; discard
                    dirty = true;
                    i++;
                } else {
                    cleaned.add(msg);
                    i++;
                }
            }

            if (dirty) {
                session.apiMessages.clear();
                session.apiMessages.addAll(cleaned);
                if (sessionStore != null) {
                    sessionStore.saveSession(session);
                }
            }
        }
    }

    public static void appendTurnScreenshotMessages(
        AcidSessionStore.AgentSession session,
        List<JSONObject> turnImages,
        AcidSessionStore sessionStore
    ) {
        if (session == null || turnImages == null || turnImages.isEmpty()) return;
        JSONObject imgMsg = buildMultimodalImageMessage(turnImages);
        if (imgMsg == null) return;

        Object lock = sessionStore != null ? sessionStore : session;
        synchronized (lock) {
            // Compact any previously stored screenshot messages so session JSON stays compact on disk
            for (int i = 0; i < session.apiMessages.size(); i++) {
                JSONObject existing = session.apiMessages.get(i);
                if (isMultimodalImageMessage(existing)) {
                    try {
                        existing.put("content", "[Earlier browser screenshot omitted]");
                    } catch (Exception ignored) {}
                }
            }
            session.apiMessages.add(imgMsg);
            if (sessionStore != null) {
                sessionStore.saveSession(session);
            }
        }
    }

    public static JSONObject buildMultimodalImageMessage(List<JSONObject> images) {
        if (images == null || images.isEmpty()) return null;
        try {
            JSONArray parts = new JSONArray();
            JSONObject first = images.get(0);
            int w = first != null ? first.optInt("width", 0) : 0;
            int h = first != null ? first.optInt("height", 0) : 0;
            JSONObject intro = new JSONObject();
            intro.put("type", "text");
            intro.put("text", w > 0 && h > 0
                ? "[Attached Screenshot] Image resolution is " + w + "x" + h
                    + " px. Use ref='eN' when SoM marks are drawn, or pass click(x, y) in this image's [0.."
                    + w + ", 0.." + h + "] pixel coordinates."
                : "[Attached Screenshot] Inspect this viewport image for visual context.");
            parts.put(intro);

            for (JSONObject img : images) {
                if (img == null) continue;
                String b64 = img.optString("base64", "");
                if (b64.isEmpty()) continue;
                String mime = img.optString("mimeType", ScreenshotCaptureHelper.DEFAULT_MIME_TYPE);
                JSONObject urlObj = new JSONObject();
                urlObj.put("url", "data:" + mime + ";base64," + b64);
                urlObj.put("detail", "high");
                JSONObject imgPart = new JSONObject();
                imgPart.put("type", "image_url");
                imgPart.put("image_url", urlObj);
                parts.put(imgPart);
            }
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", parts);
            return msg;
        } catch (Exception e) {
            Log.w(TAG, "Failed to build multimodal screenshot message", e);
            return null;
        }
    }

    public static boolean isMultimodalImageMessage(JSONObject msg) {
        if (msg == null) return false;
        Object raw = msg.opt("content");
        if (!(raw instanceof JSONArray)) return false;
        JSONArray arr = (JSONArray) raw;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject part = arr.optJSONObject(i);
            if (part != null && "image_url".equals(part.optString("type"))) {
                return true;
            }
        }
        return false;
    }

    private static String extractMessageText(JSONObject msg) {
        if (msg == null) return "";
        Object raw = msg.opt("content");
        if (raw instanceof String) {
            return (String) raw;
        }
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.optString("text", ""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    public static List<JSONObject> pruneHistoryForRequest(List<JSONObject> apiMessages) {
        if (apiMessages == null || apiMessages.isEmpty()) {
            return new ArrayList<JSONObject>();
        }

        // 1. Identify DOM observations (with Interactive Elements), multimodal screenshots, and oversized (>8192 chars) non-DOM tool outputs.
        List<Integer> domObsIndices = new ArrayList<Integer>();
        List<Integer> nonDomGiantIndices = new ArrayList<Integer>();
        List<Integer> imageMsgIndices = new ArrayList<Integer>();
        for (int i = 0; i < apiMessages.size(); i++) {
            JSONObject m = apiMessages.get(i);
            if (m == null) continue;
            String role = m.optString("role");
            if (isMultimodalImageMessage(m)) {
                imageMsgIndices.add(i);
                continue;
            }
            String content = extractMessageText(m);
            if ("tool".equals(role)) {
                if (content.contains("Interactive Elements (")) {
                    domObsIndices.add(i);
                } else if (content.length() > 8192) {
                    nonDomGiantIndices.add(i);
                }
            } else if ("user".equals(role) && content.contains("[Current Browser State]") && content.contains("Interactive Elements (")) {
                domObsIndices.add(i);
            }
        }

        // 2. Ironclad Rules:
        // - The latest DOM observation (last in domObsIndices) MUST NEVER be pruned under any circumstances!
        // - Keep at least the last 3 DOM observations intact so pagination slices (offset=0, offset=180, offset=360) co-exist.
        // - Keep at least the last 2 oversized non-DOM tool outputs intact; compact older >8192 non-DOM outputs via DSH head/tail trimming.
        // - Keep ONLY the latest 1 multimodal screenshot intact in the API payload; older screenshots downgrade to lightweight text placeholders.
        int domKeepThreshold = domObsIndices.size() > 3
            ? domObsIndices.get(domObsIndices.size() - 3)
            : -1;
        int nonDomKeepThreshold = nonDomGiantIndices.size() > 2
            ? nonDomGiantIndices.get(nonDomGiantIndices.size() - 2)
            : -1;
        int imageKeepThreshold = imageMsgIndices.size() > 1
            ? imageMsgIndices.get(imageMsgIndices.size() - 1)
            : -1;

        Set<Integer> domIndicesSet = new HashSet<Integer>(domObsIndices);
        Set<Integer> nonDomGiantSet = new HashSet<Integer>(nonDomGiantIndices);
        Set<Integer> imageIndicesSet = new HashSet<Integer>(imageMsgIndices);
        int latestDomIndex = !domObsIndices.isEmpty() ? domObsIndices.get(domObsIndices.size() - 1) : -1;

        List<JSONObject> result = new ArrayList<JSONObject>(apiMessages.size());
        for (int i = 0; i < apiMessages.size(); i++) {
            JSONObject orig = apiMessages.get(i);
            if (orig == null) continue;
            if (imageKeepThreshold >= 0 && i < imageKeepThreshold && imageIndicesSet.contains(i)) {
                try {
                    JSONObject prunedImg = new JSONObject(orig.toString());
                    prunedImg.put("content", "[Earlier browser screenshot omitted to preserve context]");
                    result.add(prunedImg);
                } catch (Exception e) {
                    result.add(orig);
                }
                continue;
            }
            boolean shouldCompactDom = domKeepThreshold >= 0 && i < domKeepThreshold && domIndicesSet.contains(i) && i != latestDomIndex;
            boolean shouldCompactGiantNonDom = nonDomKeepThreshold >= 0 && i < nonDomKeepThreshold && nonDomGiantSet.contains(i);
            if (shouldCompactDom || shouldCompactGiantNonDom) {
                try {
                    JSONObject pruned = new JSONObject(orig.toString());
                    String full = extractMessageText(orig);
                    if ("user".equals(orig.optString("role"))) {
                        int marker = full.indexOf("[Current Browser State]");
                        if (marker >= 0) {
                            String prefix = full.substring(0, marker);
                            String compactedSnapshot = compactOldObservation(full.substring(marker));
                            pruned.put("content", prefix + compactedSnapshot);
                        }
                    } else {
                        pruned.put("content", compactOldObservation(full));
                    }
                    result.add(pruned);
                } catch (Exception e) {
                    result.add(orig);
                }
            } else {
                result.add(orig);
            }
        }
        return result;
    }

    private JSONArray buildPrunedMessagesForRequest(AcidSessionStore.AgentSession session) throws Exception {
        sanitizeAndAlignApiMessages(session, mSessionStore);

        JSONArray arr = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", buildSystemPrompt());
        arr.put(sys);

        synchronized (mSessionStore) {
            List<JSONObject> prunedList = pruneHistoryForRequest(session.apiMessages);
            for (JSONObject m : prunedList) {
                arr.put(m);
            }
        }
        return arr;
    }

    public static String compactOldObservation(String full) {
        if (full == null || full.length() < 100) return full;

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
                        String rawInner = line.substring(p1 + 1, p2).trim();
                        int pipeIdx = rawInner.indexOf('|');
                        if (pipeIdx >= 0) {
                            rawInner = rawInner.substring(0, pipeIdx).trim();
                        }
                        elementCount = Integer.parseInt(rawInner);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not parse expired element count from history line", e);
                }
                sb.append("Interactive Elements: [").append(elementCount > 0 ? elementCount : "historical")
                  .append(" elements superseded by later step; see latest observation below]\n");
                continue;
            }
            if (insideElements) {
                if (line.startsWith("  [e") || line.startsWith("  [#")) {
                    continue; // Skip individual stale element lines
                } else {
                    insideElements = false;
                }
            }

            if (line.startsWith("Page Text Excerpt")) {
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

    public static class StreamingToolCall {
        public int index;
        public String id = "";
        public String type = "function";
        public String name = "";
        public final StringBuilder arguments = new StringBuilder();
    }

    /**
     * Stateful streaming parser for inline {@code <think>...</think>} blocks that safely
     * handles tags split across arbitrary SSE token/chunk boundaries.
     */
    public static class ThinkStreamParser {
        private static final String OPEN_TAG = "<think>";
        private static final String CLOSE_TAG = "</think>";

        public final StringBuilder reasoningSb = new StringBuilder();
        public final StringBuilder contentSb = new StringBuilder();
        private final StringBuilder mPending = new StringBuilder();
        public boolean insideThinkTag = false;
        public boolean thinkUpdated = false;
        public boolean thinkClosed = false;
        public boolean contentUpdated = false;

        public void feedReasoningDelta(String rChunk) {
            if (rChunk == null || rChunk.isEmpty()) return;
            reasoningSb.append(rChunk);
            thinkUpdated = true;
        }

        public void feedContentDelta(String cChunk) {
            if (cChunk == null || cChunk.isEmpty()) return;
            mPending.append(cChunk);
            drainPending(false);
        }

        public void finish() {
            drainPending(true);
        }

        private void drainPending(boolean flushAll) {
            while (mPending.length() > 0) {
                if (!insideThinkTag) {
                    int openIdx = mPending.indexOf(OPEN_TAG);
                    if (openIdx >= 0) {
                        if (openIdx > 0) {
                            contentSb.append(mPending, 0, openIdx);
                            contentUpdated = true;
                        }
                        mPending.delete(0, openIdx + OPEN_TAG.length());
                        insideThinkTag = true;
                        continue;
                    }
                    int hold = flushAll ? 0 : partialTagSuffixLength(mPending, OPEN_TAG);
                    int flushLen = mPending.length() - hold;
                    if (flushLen > 0) {
                        contentSb.append(mPending, 0, flushLen);
                        mPending.delete(0, flushLen);
                        contentUpdated = true;
                    }
                    break;
                } else {
                    int closeIdx = mPending.indexOf(CLOSE_TAG);
                    if (closeIdx >= 0) {
                        if (closeIdx > 0) {
                            reasoningSb.append(mPending, 0, closeIdx);
                            thinkUpdated = true;
                        }
                        mPending.delete(0, closeIdx + CLOSE_TAG.length());
                        insideThinkTag = false;
                        thinkClosed = true;
                        continue;
                    }
                    int hold = flushAll ? 0 : partialTagSuffixLength(mPending, CLOSE_TAG);
                    int flushLen = mPending.length() - hold;
                    if (flushLen > 0) {
                        reasoningSb.append(mPending, 0, flushLen);
                        mPending.delete(0, flushLen);
                        thinkUpdated = true;
                    }
                    break;
                }
            }
        }

        private static int partialTagSuffixLength(StringBuilder sb, String tag) {
            int maxCheck = Math.min(sb.length(), tag.length() - 1);
            for (int len = maxCheck; len >= 1; len--) {
                boolean match = true;
                int start = sb.length() - len;
                for (int i = 0; i < len; i++) {
                    if (sb.charAt(start + i) != tag.charAt(i)) {
                        match = false;
                        break;
                    }
                }
                if (match) return len;
            }
            return 0;
        }
    }

    public static void accumulateToolCallsDelta(
        Map<Integer, StreamingToolCall> toolCallsMap,
        JSONArray toolCallsDelta
    ) {
        if (toolCallsMap == null || toolCallsDelta == null) return;
        for (int i = 0; i < toolCallsDelta.length(); i++) {
            JSONObject tc = toolCallsDelta.optJSONObject(i);
            if (tc == null) continue;
            int idx = (!tc.isNull("index") && tc.has("index")) ? tc.optInt("index", 0) : 0;
            StreamingToolCall call = toolCallsMap.get(idx);
            if (call == null) {
                call = new StreamingToolCall();
                call.index = idx;
                toolCallsMap.put(idx, call);
            }
            if (tc.has("id") && !tc.isNull("id")) {
                String tid = tc.optString("id", "");
                if (!tid.isEmpty() && !"null".equals(tid)) call.id = tid;
            }
            if (tc.has("type") && !tc.isNull("type")) {
                String tType = tc.optString("type", "");
                if (!tType.isEmpty() && !"null".equals(tType)) call.type = tType;
            }
            JSONObject fn = tc.optJSONObject("function");
            if (fn != null) {
                if (fn.has("name") && !fn.isNull("name")) {
                    String name = fn.optString("name", "");
                    if (!name.isEmpty() && !"null".equals(name)) {
                        if (call.name == null || call.name.isEmpty()) {
                            call.name = name;
                        } else if (!call.name.equals(name)) {
                            call.name += name;
                        }
                    }
                }
                if (fn.has("arguments") && !fn.isNull("arguments")) {
                    String args = fn.optString("arguments", "");
                    if (!args.isEmpty()) call.arguments.append(args);
                }
            }
        }
    }

    /**
     * Parses OpenAI tool call arguments strictly: empty/whitespace strings represent zero-arg calls
     * ({@code {}}), whereas non-empty malformed JSON throws instead of silently becoming {@code {}}.
     */
    public static JSONObject parseToolArgumentsStrict(String rawArgs) throws Exception {
        if (rawArgs == null || rawArgs.trim().isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(rawArgs);
    }

    /**
     * Extracts a human-readable error message from a streaming SSE JSON chunk when an
     * OpenAI-compatible gateway emits an HTTP 200 inline {@code {"error": ...}} event.
     */
    public static String extractSseErrorMessage(JSONObject chunk) {
        if (chunk == null || !chunk.has("error") || chunk.isNull("error")) {
            return null;
        }
        Object errVal = chunk.opt("error");
        if (errVal instanceof JSONObject) {
            JSONObject eo = (JSONObject) errVal;
            String msg = eo.optString("message", "").trim();
            if (msg.isEmpty()) msg = eo.optString("detail", "").trim();
            if (msg.isEmpty()) msg = eo.optString("error", "").trim();
            String code = eo.isNull("code") ? "" : eo.optString("code", "").trim();
            String type = eo.isNull("type") ? "" : eo.optString("type", "").trim();
            if (msg.isEmpty()) msg = eo.toString();
            if (!code.isEmpty() && !"null".equals(code)) {
                return msg + " (code: " + code + ")";
            }
            if (!type.isEmpty() && !"null".equals(type)) {
                return msg + " (" + type + ")";
            }
            return msg;
        }
        String raw = String.valueOf(errVal).trim();
        return raw.isEmpty() || "null".equals(raw) ? "Unknown stream error" : raw;
    }

    private JSONObject callChatCompletionsStream(
        AcidSessionStore.AgentSession session,
        JSONArray messages,
        int step,
        int runGen
    ) throws Exception {
        if (mStopRequested || mPausedForTakeover || mRunGeneration.get() != runGen) {
            return null;
        }
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

        URL endpointUrl = new URL(endpoint);
        AcidHttp.createDownloadValidator(endpoint).validate(endpointUrl);
        HttpURLConnection conn = (HttpURLConnection) endpointUrl.openConnection();
        conn.setInstanceFollowRedirects(false);
        mActiveConnection = conn;

        ThinkStreamParser streamParser = new ThinkStreamParser();
        StringBuilder reasoningSb = streamParser.reasoningSb;
        StringBuilder contentSb = streamParser.contentSb;
        Map<Integer, StreamingToolCall> toolCallsMap = new TreeMap<Integer, StreamingToolCall>();

        AcidSessionStore.UiMessageItem currentThinkItem = null;
        AcidSessionStore.UiMessageItem currentReplyItem = null;

        long lastUiUpdate = 0;
        final long THROTTLE_MS = 60;

        OutputStream os = null;
        InputStream is = null;
        BufferedReader reader = null;
        try {
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

            os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();
            os = null;

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String errStr = AcidIo.readStreamUtf8(conn.getErrorStream());
                throw new Exception("HTTP " + code + ": " + (errStr.length() > 300 ? errStr.substring(0, 300) : errStr));
            }

            is = conn.getInputStream();
            reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                if (isRunCancelled(runGen)) {
                    disconnectQuietly(conn);
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

                String streamErr = extractSseErrorMessage(chunk);
                if (streamErr != null) {
                    disconnectQuietly(conn);
                    throw new IOException(streamErr);
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
                        synchronized (mSessionStore) {
                            session.lastPromptTokens = pt;
                            session.lastCompletionTokens = ct;
                            session.lastTotalTokens = tt;
                            session.lastCachedTokens = cached;
                        }
                        notifyContextUsage(session);
                    }
                }

                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject choice0 = choices.optJSONObject(0);
                if (choice0 == null) continue;
                JSONObject delta = choice0.optJSONObject("delta");
                if (delta == null) continue;

                streamParser.thinkUpdated = false;
                streamParser.thinkClosed = false;
                streamParser.contentUpdated = false;

                // 1. Process reasoning_content (DeepSeek R1 / Reasoner native)
                String rChunk = delta.isNull("reasoning_content") ? "" : delta.optString("reasoning_content", "");
                streamParser.feedReasoningDelta(rChunk);

                // 2. Process content (including cross-chunk <think>...</think> state machine)
                String cChunk = delta.isNull("content") ? "" : delta.optString("content", "");
                streamParser.feedContentDelta(cChunk);

                if (streamParser.thinkUpdated) {
                    String currentReasoning = reasoningSb.toString();
                    if (!currentReasoning.trim().isEmpty()) {
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
                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate >= THROTTLE_MS) {
                            lastUiUpdate = now;
                            currentThinkItem.text = currentReasoning;
                            notifyItemUpdated(currentThinkItem);
                        }
                    }
                }

                if (streamParser.thinkClosed && currentThinkItem != null && currentThinkItem.isRunning) {
                    currentThinkItem.isRunning = false;
                    currentThinkItem.text = reasoningSb.toString().trim();
                    notifyItemUpdated(currentThinkItem);
                }

                if (streamParser.contentUpdated && contentSb.length() > 0) {
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
                    long now = System.currentTimeMillis();
                    if (now - lastUiUpdate >= THROTTLE_MS) {
                        lastUiUpdate = now;
                        currentReplyItem.text = contentSb.toString();
                        notifyItemUpdated(currentReplyItem);
                    }
                }

                // 3. Process tool_calls delta chunks
                JSONArray toolCallsDelta = delta.optJSONArray("tool_calls");
                if (toolCallsDelta != null) {
                    accumulateToolCallsDelta(toolCallsMap, toolCallsDelta);
                }
            }
            streamParser.finish();
        } catch (Exception e) {
            closeAbortedStreamingItems(session, currentThinkItem, currentReplyItem);
            throw e;
        } finally {
            if (mActiveConnection == conn) {
                mActiveConnection = null;
            }
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {}
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {}
            } else if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {}
            }
            try {
                conn.disconnect();
            } catch (Exception ignored) {}
        }

        if (isRunCancelled(runGen)) {
            closeAbortedStreamingItems(session, currentThinkItem, currentReplyItem);
            return null;
        }

        // Finalize thinking item
        if (currentThinkItem != null) {
            String finR = reasoningSb.toString().trim();
            if (finR.isEmpty()) {
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
        if (currentReplyItem == null && contentSb.length() > 0) {
            currentReplyItem = new AcidSessionStore.UiMessageItem();
            currentReplyItem.type = AcidSessionStore.TYPE_ASSISTANT;
            currentReplyItem.isRunning = false;
            currentReplyItem.text = contentSb.toString();
            currentReplyItem.timestamp = System.currentTimeMillis();
            synchronized (mSessionStore) {
                session.uiItems.add(currentReplyItem);
            }
            notifyItemAdded(currentReplyItem);
        } else if (currentReplyItem != null) {
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

    public static String summarizeToolArgs(String name, JSONObject args) {
        if ("browser".equals(name)) {
            String act = args.optString("action", "observe");
            if ("screenshot".equals(act)) {
                String sp = args.optString("save_path", "");
                if (!sp.isEmpty()) return "screenshot · " + sp;
                return args.optBoolean("som", false) ? "screenshot · som=true" : "screenshot";
            }
            if ("upload".equals(act)) return "upload · " + args.optString("file_path", args.has("file_paths") ? "multi" : "");
            if ("navigate".equals(act)) return "navigate · " + args.optString("url");
            if ("click".equals(act)) return "click · [" + args.optString("ref", args.optString("x") + "," + args.optString("y")) + "]";
            if ("fill".equals(act)) return "fill · [" + args.optString("ref") + "] \"" + args.optString("text") + "\"";
            if ("select".equals(act)) return "select · [" + args.optString("ref") + "] \"" + args.optString("text") + "\"";
            if ("scroll".equals(act)) return "scroll · " + args.optString("direction", "down");
            if ("wait".equals(act)) return "wait · " + args.optString("condition", "stable") + (args.has("selector") ? " [" + args.optString("selector") + "]" : (args.has("text") ? " \"" + args.optString("text") + "\"" : ""));
            if ("set_mode".equals(act)) return "set_mode · " + args.optString("mode");
            if ("tabs".equals(act)) return "tabs · " + args.optString("tab_action", "list");
            if ("eval".equals(act)) return "eval · JS";
            if ("download".equals(act)) return "download · " + args.optString("file_name", args.optString("url"));
            return act;
        }
        if ("read".equals(name) || "write".equals(name) || "edit".equals(name)) {
            String fp = args.optString("file_path", "");
            if ("read".equals(name) && (fp.startsWith("skills/") || fp.contains("/skills/"))) {
                return "加载技能 · " + fp;
            }
            return fp;
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
        return estimateTokens(session);
    }

    public static int estimateTokens(AcidSessionStore.AgentSession session) {
        if (session == null) return 0;
        if (session.lastTotalTokens > 0) return session.lastTotalTokens;
        int chars = 0;
        for (JSONObject m : session.apiMessages) {
            if (m != null) {
                Object rawContent = m.opt("content");
                if (rawContent instanceof JSONArray) {
                    JSONArray parts = (JSONArray) rawContent;
                    for (int p = 0; p < parts.length(); p++) {
                        JSONObject part = parts.optJSONObject(p);
                        if (part == null) continue;
                        if ("text".equals(part.optString("type"))) {
                            chars += part.optString("text", "").length();
                        } else if ("image_url".equals(part.optString("type"))) {
                            chars += 2000; // Approximate ~1000 tokens per attached screenshot instead of raw Base64 char count
                        }
                    }
                } else {
                    chars += extractMessageText(m).length();
                }
                chars += m.optString("reasoning_content", "").length();
                JSONArray tc = m.optJSONArray("tool_calls");
                if (tc != null) chars += tc.toString().length();
            }
        }
        return Math.max(1, (int) Math.round(chars / 2.0));
    }
}
