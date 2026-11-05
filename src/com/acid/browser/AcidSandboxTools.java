package com.acid.browser;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * In-Process Tool Catalog & Sandbox Executor for Acid Agent Loop.
 * Provides the 5 core tools modeled after DSH standard/minimal + mobile-use:
 *  1. browser (unified closed-loop web perception & interaction tool)
 *  2. read    (line-numbered UTF-8 file reader inside sandbox)
 *  3. write   (create or overwrite file inside sandbox / userscripts)
 *  4. edit    (exact string replacement inside sandbox files)
 *  5. bash    (execute shell commands with working directory in sandbox)
 */
public class AcidSandboxTools {

    private static final String TAG = "AcidSandboxTools";

    private final BrowserController mController;
    private final File mWorkspaceDir;
    private final Handler mMainHandler;

    public AcidSandboxTools(BrowserController controller) {
        this(controller, controller != null ? controller.getWorkspaceDir() : null);
    }

    public AcidSandboxTools(BrowserController controller, File workspaceDir) {
        mController = controller;
        File ws = workspaceDir != null ? workspaceDir : (controller != null ? controller.getWorkspaceDir() : null);
        mWorkspaceDir = ws != null ? ws : new File(".");
        if (!mWorkspaceDir.exists()) {
            mWorkspaceDir.mkdirs();
        }
        Handler h = null;
        try {
            if (Looper.getMainLooper() != null) {
                h = new Handler(Looper.getMainLooper());
            }
        } catch (Throwable ignored) {}
        mMainHandler = h;
    }

    private void runOnMain(Runnable r) {
        if (mMainHandler != null) {
            mMainHandler.post(r);
        } else {
            r.run();
        }
    }

    private void log(String msg) {
        if (mController != null) {
            mController.emitLog(msg);
        } else {
            Log.i(TAG, msg);
        }
    }

    public File getWorkspaceDir() {
        return mWorkspaceDir;
    }

    // =========================================================================
    // Declarative AgentTool Registry (Single Source of Truth for Schema & Dispatch)
    // =========================================================================

    public interface AgentTool {
        String name();
        JSONObject buildOpenAiSchema() throws Exception;
        ToolExecResult execute(AcidSandboxTools host, JSONObject args, ToolSource source) throws Exception;
    }

    private static final List<AgentTool> TOOL_LIST = new ArrayList<AgentTool>();
    private static final Map<String, AgentTool> TOOL_MAP = new LinkedHashMap<String, AgentTool>();

    public static synchronized void registerTool(AgentTool tool) {
        if (tool == null || tool.name() == null) return;
        TOOL_MAP.put(tool.name(), tool);
        TOOL_LIST.clear();
        TOOL_LIST.addAll(TOOL_MAP.values());
    }

    public static List<String> getRegisteredToolNames() {
        return Collections.unmodifiableList(new ArrayList<String>(TOOL_MAP.keySet()));
    }

    static {
        // 1. browser (Unified Closed-Loop Web Automation Tool)
        registerTool(new AgentTool() {
            @Override
            public String name() {
                return "browser";
            }

            @Override
            public JSONObject buildOpenAiSchema() throws Exception {
                JSONObject browserProps = new JSONObject();
                browserProps.put("action", propEnum(
                    "string",
                    "Action to perform on the browser:\n" +
                    "- 'observe': Inspect current webpage semantic tree ([e1..eN] handles) and text excerpt. Supports optional 'selector' (container scope), 'offset'/'limit' (pagination), and 'text_offset' (body text paging).\n" +
                    "- 'navigate': Load URL in current tab (supports http/https or file:/// inside workspace).\n" +
                    "- 'click': Tap element by 'ref' (e.g. 'e3'), visible text ('text', e.g. '消息'/'发送'), CSS selector ('selector'), or pixel coordinates ('x', 'y'). Includes auto-healing if the DOM re-rendered.\n" +
                    "- 'fill': Fill text into input/textarea/contenteditable ('ref' or 'selector'). React/Vue safe. Optional 'submit'=true.\n" +
                    "- 'select': Choose option in <select> dropdown 'ref' by value or visible label.\n" +
                    "- 'scroll': Scroll viewport ('direction': 'down'|'up'|'top'|'bottom', 'amount': 0.8).\n" +
                    "- 'wait': Wait for a specific condition ('condition': 'networkidle'|'selector'|'stable'|'text'|'gone'|'ms', optional 'selector', 'text', 'quiet_ms', 'ms', 'timeout_ms'). Returns a receipt {satisfied, timed_out, elapsed_ms, error_code?} followed by the latest page observation. Always check 'satisfied' before proceeding.\n" +
                    "- 'eval': Evaluate JavaScript ('code') on current page and return {ok, result, result_type}. Accepts an expression, multiple statements (value of the last statement is returned automatically), or a function body with explicit 'return'. Supports async/await; Promises are awaited. Syntax errors return SYNTAX_ERROR without executing.\n" +
                    "- 'download': Open the browser's built-in multi-threaded download confirmation sheet for a direct file URL ('url', optional 'file_name', 'user_agent'). Automatically injects cloud-drive client User-Agent & Cookies (Baidu PCS, Quark, UC, 115) so WebView never hits HTTP 403.\n" +
                    "- 'set_mode': Switch browser identity ('mode': 'desktop' for 1280px PC view or 'mobile'), or toggle 'som' (true/false).\n" +
                    "- 'tabs': Manage tabs ('tab_action': 'list'|'new'|'switch'|'close', optional 'tab_id', 'url').\n" +
                    "- 'back': Navigate back in current tab history.\n" +
                    "- 'reload': Reload current webpage.\n" +
                    "- 'request_takeover': Pause automation loop and ask human user to manually handle login/CAPTCHA ('reason').",
                    new String[]{
                        "observe", "navigate", "click", "fill", "select",
                        "scroll", "wait", "eval", "download", "set_mode", "tabs", "back", "reload", "request_takeover"
                    }
                ));
                browserProps.put("url", prop("string", "Target URL for 'navigate', 'download', or 'tabs' (new)."));
                browserProps.put("file_name", prop("string", "Optional save filename override for 'download' (e.g. 'app.apk')."));
                browserProps.put("user_agent", prop("string", "Optional custom User-Agent header for 'download' (auto-populated for Baidu/Quark/UC/115 if omitted)."));
                browserProps.put("ref", prop("string", "Target element handle from observe snapshot (e.g. 'e1', 'e7') for 'click', 'fill', 'select', or container 'scroll'. Supports auto-healing if the DOM refreshed."));
                browserProps.put("text", prop("string", "Text value for 'fill', option value/label for 'select', visible element text for semantic 'click' (e.g. '消息'/'发送'), or expected text for 'wait'."));
                browserProps.put("selector", prop("string", "CSS selector for 'click', 'fill', 'wait', or container scope for 'observe' (e.g. '[role=\"dialog\"]', 'table')."));
                browserProps.put("exact", prop("boolean", "Whether semantic text matching requires exact full equality (default false)."));
                browserProps.put("condition", prop("string", "Wait condition for 'wait': 'stable' (DOM quiet), 'selector' (element exists), 'text' (text present), 'gone' (element/text disappeared), 'networkidle' (page loaded), 'ms' (fixed sleep)."));
                browserProps.put("quiet_ms", prop("integer", "Quiet threshold in ms without DOM mutations for 'stable' wait (default 300)."));
                browserProps.put("ms", prop("integer", "Sleep duration in milliseconds for 'wait' (condition='ms')."));
                browserProps.put("timeout_ms", prop("integer", "Maximum wait timeout in milliseconds for 'wait' (default 5000)."));
                browserProps.put("clear", prop("boolean", "Whether to clear existing input value before 'fill' (default true)."));
                browserProps.put("submit", prop("boolean", "Whether to press Enter / submit form after 'fill' (default false)."));
                browserProps.put("direction", prop("string", "Scroll direction for 'scroll': 'down', 'up', 'left', 'right', 'top', 'bottom'."));
                browserProps.put("amount", prop("number", "Scroll viewport multiplier for 'scroll' (default 0.75)."));
                browserProps.put("code", prop("string", "JavaScript code expression to evaluate for 'eval'. Async functions and Promises are automatically awaited and resolved."));
                browserProps.put("mode", prop("string", "Browser identity mode for 'set_mode': 'desktop' (1280px PC) or 'mobile'."));
                browserProps.put("som", prop("boolean", "Enable or disable Set-of-Marks visual boxes on webpage."));
                browserProps.put("tab_action", prop("string", "Sub-action for 'tabs': 'list', 'new', 'switch', 'close'."));
                browserProps.put("tab_id", prop("integer", "Target tab ID for 'tabs' switch/close."));
                browserProps.put("x", prop("number", "Raw X coordinate in view pixels for coordinate 'click'."));
                browserProps.put("y", prop("number", "Raw Y coordinate in view pixels for coordinate 'click'."));
                browserProps.put("reason", prop("string", "Explanation shown to the human user when calling 'request_takeover'."));
                browserProps.put("offset", prop("integer", "0-based element offset for pagination in 'observe' (default 0)."));
                browserProps.put("limit", prop("integer", "Maximum number of interactive elements to return in 'observe' (default 180)."));
                browserProps.put("text_offset", prop("integer", "0-based character offset for reading page body text excerpt in 'observe' (default 0)."));

                return makeTool(
                    "browser",
                    "Control the built-in Acid WebView browser. Every state-changing action (navigate, click, fill, select, scroll, set_mode, back, reload) automatically returns the updated page observation snapshot so you can immediately decide the next step.",
                    browserProps,
                    new String[]{"action"}
                );
            }

            @Override
            public ToolExecResult execute(AcidSandboxTools host, JSONObject args, ToolSource source) throws Exception {
                return host.executeBrowserTool(args);
            }
        });

        // 2. read
        registerTool(new AgentTool() {
            @Override
            public String name() {
                return "read";
            }

            @Override
            public JSONObject buildOpenAiSchema() throws Exception {
                JSONObject readProps = new JSONObject();
                readProps.put("file_path", prop("string", "Path to read (relative paths resolve inside the app sandbox workspace, e.g. 'notes.md' or 'userscripts/custom.user.js')."));
                readProps.put("offset", prop("integer", "1-based starting line number (default 1)."));
                readProps.put("limit", prop("integer", "Maximum number of lines to read (default 200)."));
                return makeTool(
                    "read",
                    "Read a UTF-8 text file inside the sandbox workspace and return line-numbered content.",
                    readProps,
                    new String[]{"file_path"}
                );
            }

            @Override
            public ToolExecResult execute(AcidSandboxTools host, JSONObject args, ToolSource source) {
                return new ToolExecResult(host.executeReadTool(args), false, null);
            }
        });

        // 3. write
        registerTool(new AgentTool() {
            @Override
            public String name() {
                return "write";
            }

            @Override
            public JSONObject buildOpenAiSchema() throws Exception {
                JSONObject writeProps = new JSONObject();
                writeProps.put("file_path", prop("string",
                    "Path to write (relative to sandbox workspace). Writing to 'userscripts/<name>.user.js' "
                    + "installs a browser userscript; the file MUST contain a complete ==UserScript== header "
                    + "with at least @name and @match, otherwise the install is rejected."));
                writeProps.put("content", prop("string", "Complete UTF-8 text content to write."));
                return makeTool(
                    "write",
                    "Create or overwrite a UTF-8 text file in the sandbox workspace (e.g. HTML reports, JSON data, scripts). "
                    + "Files written under 'userscripts/' are validated and installed as browser plugins that run on the "
                    + "sites named by their @match header.",
                    writeProps,
                    new String[]{"file_path", "content"}
                );
            }

            @Override
            public ToolExecResult execute(AcidSandboxTools host, JSONObject args, ToolSource source) {
                return new ToolExecResult(host.executeWriteTool(args, source), false, null);
            }
        });

        // 4. edit
        registerTool(new AgentTool() {
            @Override
            public String name() {
                return "edit";
            }

            @Override
            public JSONObject buildOpenAiSchema() throws Exception {
                JSONObject editProps = new JSONObject();
                editProps.put("file_path", prop("string", "Path to the existing text file inside the sandbox workspace."));
                editProps.put("old_string", prop("string", "Exact literal text to search for and replace."));
                editProps.put("new_string", prop("string", "Replacement text."));
                editProps.put("replace_all", prop("boolean", "Whether to replace all occurrences (default false)."));
                return makeTool(
                    "edit",
                    "Edit an existing UTF-8 text file by replacing literal text.",
                    editProps,
                    new String[]{"file_path", "old_string", "new_string"}
                );
            }

            @Override
            public ToolExecResult execute(AcidSandboxTools host, JSONObject args, ToolSource source) {
                return new ToolExecResult(host.executeEditTool(args, source), false, null);
            }
        });

        // 5. bash
        registerTool(new AgentTool() {
            @Override
            public String name() {
                return "bash";
            }

            @Override
            public JSONObject buildOpenAiSchema() throws Exception {
                JSONObject bashProps = new JSONObject();
                bashProps.put("command", prop("string", "Shell command to execute via /system/bin/sh inside the sandbox workspace directory."));
                bashProps.put("timeout_ms", prop("integer", "Timeout in milliseconds (default 20000)."));
                return makeTool(
                    "bash",
                    "Execute a shell command (`sh -c`) with working directory set to the Acid sandbox workspace (`" +
                    "/data/user/0/com.acid.browser/files/acid_workspace`). Returns stdout, stderr, and exit code.",
                    bashProps,
                    new String[]{"command"}
                );
            }

            @Override
            public ToolExecResult execute(AcidSandboxTools host, JSONObject args, ToolSource source) {
                if (source != ToolSource.AGENT_UI) {
                    return new ToolExecResult("Error: shell execution via bash is restricted to in-app agent chat for security.", false, null);
                }
                return new ToolExecResult(host.executeBashTool(args), false, null);
            }
        });
    }

    /**
     * Builds the OpenAI-compatible {@code tools} JSON array from all registered {@link AgentTool}s.
     */
    public static JSONArray buildToolsSchema() {
        JSONArray tools = new JSONArray();
        for (int i = 0; i < TOOL_LIST.size(); i++) {
            try {
                tools.put(TOOL_LIST.get(i).buildOpenAiSchema());
            } catch (Exception e) {
                Log.e(TAG, "Failed to build tool schema for " + TOOL_LIST.get(i).name(), e);
            }
        }
        return tools;
    }

    /**
     * Builds the standard MCP (Model Context Protocol) tools array.
     * Each tool has {name, description, inputSchema}.
     */
    public static JSONArray buildMcpToolsList() {
        JSONArray openaiTools = buildToolsSchema();
        JSONArray mcpTools = new JSONArray();
        for (int i = 0; i < openaiTools.length(); i++) {
            JSONObject t = openaiTools.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) continue;
            try {
                JSONObject mcpTool = new JSONObject();
                mcpTool.put("name", fn.optString("name"));
                mcpTool.put("description", fn.optString("description"));
                mcpTool.put("inputSchema", fn.optJSONObject("parameters"));
                mcpTools.put(mcpTool);
            } catch (Exception e) {
                Log.w(TAG, "Failed to encode MCP tool descriptor", e);
            }
        }
        return mcpTools;
    }

    private static JSONObject prop(String type, String description) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("description", description);
        return o;
    }

    private static JSONObject propEnum(String type, String description, String[] values) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("description", description);
        JSONArray arr = new JSONArray();
        for (String v : values) arr.put(v);
        o.put("enum", arr);
        return o;
    }

    private static JSONObject makeTool(String name, String description, JSONObject properties, String[] required) throws Exception {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);

        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", properties);
        if (required != null && required.length > 0) {
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            params.put("required", req);
        }
        fn.put("parameters", params);

        JSONObject wrapper = new JSONObject();
        wrapper.put("type", "function");
        wrapper.put("function", fn);
        return wrapper;
    }

    // =========================================================================
    // Unified Tool Dispatcher
    // =========================================================================

    public static class ToolExecResult {
        public final String outputText;
        public final boolean requestedTakeover;
        public final String takeoverReason;

        public ToolExecResult(String outputText, boolean requestedTakeover, String takeoverReason) {
            this.outputText = outputText;
            this.requestedTakeover = requestedTakeover;
            this.takeoverReason = takeoverReason;
        }
    }

    /** Where a tool call originated. Gates the self-extending tools. */
    public enum ToolSource {
        /** The in-app agent chat, driven by the user typing a request. */
        AGENT_UI,
        /** The local MCP gateway, reachable by anything on the device and by web pages. */
        MCP
    }

    /** Backwards-compatible entry point; treats the call as coming from the agent UI. */
    public ToolExecResult executeTool(String name, JSONObject args) {
        return executeTool(name, args, ToolSource.AGENT_UI);
    }

    public ToolExecResult executeTool(String name, JSONObject args, ToolSource source) {
        if (args == null) args = new JSONObject();
        AgentTool tool = name != null ? TOOL_MAP.get(name) : null;
        if (tool == null) {
            return new ToolExecResult("Error: Unknown tool '" + name + "'", false, null);
        }
        try {
            return tool.execute(this, args, source);
        } catch (Exception e) {
            return new ToolExecResult("Tool execution exception (" + name + "): " + e.getMessage(), false, null);
        }
    }

    // =========================================================================
    // 1. Browser Tool Implementation (Closed-Loop Observation)
    // =========================================================================

    private String awaitAndFormatDownloadNotice(int dlCountBefore, long maxWaitMs) {
        if (mController == null) return "";
        long waited = 0;
        while (mController.getTriggeredDownloadsCount() <= dlCountBefore && waited < maxWaitMs) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            waited += 100;
        }
        if (mController.getTriggeredDownloadsCount() > dlCountBefore) {
            String info = mController.getLastTriggeredDownloadInfo();
            return "[DOWNLOAD SHEET OPENED] File: " + info
                + " — Native download confirmation dialog is now open on screen for the user to tap '立即下载' (Download Now) or '先收起' (Save for Later). Do NOT re-trigger this download.\n\n";
        }
        return "";
    }

    private ToolExecResult executeBrowserTool(final JSONObject args) throws Exception {
        String action = args.optString("action", "observe").toLowerCase().trim();
        final WebAutomationDriver driver = mController != null ? mController.getAutomationDriver() : null;
        final int dlBefore = mController != null ? mController.getTriggeredDownloadsCount() : 0;

        if ("observe".equals(action)) {
            if (driver == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
            boolean som = args.has("som") ? args.optBoolean("som", mController.isSomEnabled()) : mController.isSomEnabled();
            JSONObject extraOpts = new JSONObject();
            if (args.has("selector")) extraOpts.put("selector", args.optString("selector").trim());
            if (args.has("scope")) extraOpts.put("scope", args.optString("scope").trim());
            int offsetVal = args.optInt("offset", 0);
            if (args.has("offset")) extraOpts.put("offset", offsetVal);
            if (args.has("limit")) extraOpts.put("limit", args.optInt("limit", 180));
            if (args.has("text_offset")) extraOpts.put("textOffset", args.optInt("text_offset", 0));
            if (args.has("text_limit")) extraOpts.put("textLimit", args.optInt("text_limit", 1200));

            boolean viewportOnly = args.optBoolean("viewport_only", false);
            extraOpts.put("viewportOnly", viewportOnly);

            JSONObject obs = driver.observeSync(som, viewportOnly, false, extraOpts);
            return new ToolExecResult(formatObservationWithActionHeader("OBSERVE", null, obs), false, null);
        }

        if ("navigate".equals(action)) {
            String url = args.optString("url", "");
            if (url.startsWith("workspace://")) {
                File f = resolveSandboxFile(url.substring("workspace://".length()));
                if (f == null) {
                    return new ToolExecResult("Error: Access denied: path escapes sandbox workspace (" + url + ")", false, null);
                }
                url = "file://" + f.getAbsolutePath();
            } else if (url.startsWith("file://")) {
                if (!url.startsWith("file:///android_asset/")) {
                    String localPath = url.substring("file://".length());
                    int qIdx = localPath.indexOf('?');
                    if (qIdx >= 0) localPath = localPath.substring(0, qIdx);
                    int hIdx = localPath.indexOf('#');
                    if (hIdx >= 0) localPath = localPath.substring(0, hIdx);
                    try {
                        localPath = java.net.URLDecoder.decode(localPath, "UTF-8");
                    } catch (Exception ignored) {}
                    File f = resolveSandboxFile(localPath);
                    if (f == null) {
                        return new ToolExecResult("Error: Access denied: file:// URL escapes sandbox workspace (" + url + ")", false, null);
                    }
                    url = "file://" + f.getAbsolutePath();
                }
            } else if (!url.contains("://") && !url.startsWith("about:") && !url.startsWith("javascript:")) {
                // Check if it refers to a local HTML file inside workspace
                File possibleLocal = resolveSandboxFile(url);
                if (possibleLocal != null && possibleLocal.exists() && possibleLocal.isFile()) {
                    url = "file://" + possibleLocal.getAbsolutePath();
                }
            }
            if (driver == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
            JSONObject obs = driver.navigateSync(url, 3800);
            String dlNotice = awaitAndFormatDownloadNotice(dlBefore, 400);
            return new ToolExecResult(dlNotice + formatObservationWithActionHeader("NAVIGATE -> " + url, null, obs), false, null);
        }

        if ("click".equals(action)) {
            JSONObject clickRes;
            if (args.has("x") && args.has("y")) {
                clickRes = driver.clickCoordinateSync((float) args.optDouble("x", 0), (float) args.optDouble("y", 0));
            } else if (args.has("ref") || args.has("text") || args.has("selector")) {
                JSONObject targetOpts = new JSONObject();
                if (args.has("ref")) targetOpts.put("ref", args.optString("ref").trim());
                if (args.has("text")) targetOpts.put("text", args.optString("text").trim());
                if (args.has("selector")) targetOpts.put("selector", args.optString("selector").trim());
                if (args.has("exact")) targetOpts.put("exact", args.optBoolean("exact", false));
                clickRes = driver.clickTargetSync(targetOpts, true);
            } else {
                return new ToolExecResult("Error: browser click requires 'ref' (e.g. 'e1'), semantic 'text', 'selector', or ('x', 'y') coordinates.", false, null);
            }
            Thread.sleep(380); // Let page animations/DOM settle after click
            String dlNotice = awaitAndFormatDownloadNotice(dlBefore, 500);
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(dlNotice + formatObservationWithActionHeader("CLICK", clickRes, obs), false, null);
        }

        if ("fill".equals(action)) {
            String text = args.optString("text", "");
            boolean clear = args.optBoolean("clear", true);
            boolean submit = args.optBoolean("submit", false);
            JSONObject targetOpts = new JSONObject();
            if (args.has("ref")) targetOpts.put("ref", args.optString("ref").trim());
            if (args.has("selector")) targetOpts.put("selector", args.optString("selector").trim());
            JSONObject fillRes = driver.fillSync(targetOpts, text, clear, submit);
            if (submit) {
                Thread.sleep(650);
            } else {
                Thread.sleep(150);
            }
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("FILL", fillRes, obs), false, null);
        }

        if ("select".equals(action)) {
            String ref = args.optString("ref", "").trim();
            String text = args.optString("text", "");
            JSONObject selRes = driver.selectOptionSync(ref, text);
            Thread.sleep(200);
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("SELECT", selRes, obs), false, null);
        }

        if ("scroll".equals(action)) {
            String dir = args.optString("direction", "down");
            double amount = args.optDouble("amount", 0.75);
            String ref = args.optString("ref", null);
            if (ref != null && ref.isEmpty()) ref = null;
            JSONObject scrRes = driver.scrollSync(dir, amount, ref);
            Thread.sleep(250);
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("SCROLL " + dir, scrRes, obs), false, null);
        }

        if ("wait".equals(action)) {
            String cond = args.optString("condition", "stable");
            String target = args.has("selector") ? args.optString("selector") : (args.has("text") ? args.optString("text") : null);
            long quietMs = args.optLong("quiet_ms", 300);
            long timeoutMs = args.optLong("timeout_ms", 5000);
            if ("ms".equalsIgnoreCase(cond) && args.has("ms")) {
                quietMs = args.optLong("ms", 1000);
            }
            JSONObject waitRes = driver.waitConditionSync(cond, target, quietMs, timeoutMs);
            boolean sat = waitRes.optBoolean("satisfied", false);
            String status = sat ? "✓ SATISFIED" : ("✗ " + waitRes.optString("error_code", "NOT_SATISFIED"));
            String title = "WAIT " + waitRes.optString("condition", cond).toUpperCase()
                + (target != null ? " (\"" + target + "\")" : "")
                + " " + status + " [" + waitRes.optLong("elapsed_ms", 0) + "ms]";
            JSONObject snapshot = waitRes.optJSONObject("snapshot");
            JSONObject receipt = new JSONObject(waitRes.toString());
            receipt.remove("snapshot");
            return new ToolExecResult(formatObservationWithActionHeader(title, receipt, snapshot), false, null);
        }

        if ("eval".equals(action)) {
            String code = args.optString("code", "");
            long timeout = args.optLong("timeout_ms", 10000);
            String raw = driver.evaluateJsAsync(code, timeout);
            long pollWait = (code.contains("location") || code.contains(".click(") || code.contains("download")) ? 1800 : 0;
            String dlNotice = awaitAndFormatDownloadNotice(dlBefore, pollWait);
            return new ToolExecResult(dlNotice + "=== [BROWSER ACTION: EVAL] ===\n" + raw, false, null);
        }

        if ("download".equals(action)) {
            String url = args.optString("url", "").trim();
            if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                return new ToolExecResult("Error: browser(action='download') requires a valid http/https 'url'.", false, null);
            }
            String customFileName = args.optString("file_name", "").trim();
            if (customFileName.isEmpty()) customFileName = null;
            String customUa = args.optString("user_agent", "").trim();
            if (customUa.isEmpty()) customUa = null;
            long contentLength = args.optLong("size", -1);

            String info = mController.requestDownload(url, customFileName, customUa, null, null, contentLength);
            return new ToolExecResult(
                "[DOWNLOAD SHEET OPENED] File: " + info
                + " — Native download confirmation dialog is now open on screen for the user to tap '立即下载' (Download Now) or '先收起' (Save for Later). Do NOT re-trigger this download.",
                false,
                null
            );
        }

        if ("set_mode".equals(action)) {
            final CountDownLatch latch = new CountDownLatch(1);
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    if (args.has("mode")) {
                        String m = args.optString("mode", "mobile").toLowerCase();
                        boolean desktop = "desktop".equals(m) || "pc".equals(m);
                        mController.setDesktopMode(desktop, true);
                    }
                    if (args.has("som")) {
                        mController.setSomEnabled(args.optBoolean("som", false));
                    }
                    latch.countDown();
                }
            });
            latch.await(2000, TimeUnit.MILLISECONDS);
            Thread.sleep(600);
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("SET_MODE", null, obs), false, null);
        }

        if ("tabs".equals(action)) {
            final String sub = args.optString("tab_action", "list").toLowerCase();
            final CountDownLatch latch = new CountDownLatch(1);
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    boolean isWatching = (mController != null
                        && mController.getActiveTab() != null
                        && mController.getActiveTab().id == mController.getAgentBoundTabId());

                    if ("new".equals(sub)) {
                        BrowserController.TabSession created = mController.createNewTab(
                            args.optString("url", BrowserController.HOME_URL),
                            isWatching
                        );
                        if (created != null) {
                            mController.setAgentBoundTabId(created.id);
                        }
                    } else if ("switch".equals(sub)) {
                        int targetId = args.optInt("tab_id", 1);
                        if (isWatching) {
                            if (mController.switchToTab(targetId)) {
                                mController.setAgentBoundTabId(targetId);
                            }
                        } else {
                            if (mController.getTabById(targetId) != null) {
                                mController.setAgentBoundTabId(targetId);
                            }
                        }
                    } else if ("close".equals(sub)) {
                        mController.closeTab(args.optInt("tab_id", 1));
                    }
                    latch.countDown();
                }
            });
            latch.await(2000, TimeUnit.MILLISECONDS);
            Thread.sleep(400);

            StringBuilder sb = new StringBuilder("=== [BROWSER ACTION: TABS (" + sub.toUpperCase() + ")] ===\n");
            List<BrowserController.TabSession> tabs = mController.getTabs();
            BrowserController.TabSession active = mController.getActiveTab();
            for (BrowserController.TabSession t : tabs) {
                sb.append(active != null && active.id == t.id ? "* " : "  ")
                  .append("[tab_id=").append(t.id).append("] ")
                  .append(t.title).append(" (").append(t.url).append(")\n");
            }
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            sb.append("\n").append(obs.optString("compactText", ""));
            return new ToolExecResult(sb.toString(), false, null);
        }

        if ("back".equals(action) || "reload".equals(action)) {
            final boolean isBack = "back".equals(action);
            final CountDownLatch latch = new CountDownLatch(1);
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    BrowserController.TabSession tab = mController.getTargetTab();
                    if (tab != null) {
                        if (isBack && tab.webView.canGoBack()) {
                            tab.webView.goBack();
                        } else if (!isBack) {
                            tab.webView.reload();
                        }
                    }
                    latch.countDown();
                }
            });
            latch.await(2000, TimeUnit.MILLISECONDS);
            Thread.sleep(500);
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader(action.toUpperCase(), null, obs), false, null);
        }

        if ("request_takeover".equals(action)) {
            final String reason = args.optString("reason", "需要人工协助完成当前页面操作（如登录或验证码）");
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    mController.setControlMode(AgentOverlayView.MODE_TAKEOVER_REQUESTED, reason);
                }
            });
            return new ToolExecResult(
                "[HUMAN TAKEOVER REQUESTED] Automation paused. Reason: " + reason,
                true,
                reason
            );
        }

        return new ToolExecResult("Unknown browser action: " + action, false, null);
    }

    private JSONObject cleanReceipt(JSONObject r) {
        if (r == null) return null;
        try {
            JSONObject c = new JSONObject(r.toString());
            c.remove("strategy");
            c.remove("viewX");
            c.remove("viewY");
            c.remove("revisionBefore");
            c.remove("revisionAfter");
            c.remove("wait_satisfied");
            c.remove("checks");
            c.remove("target");
            if (c.optBoolean("satisfied", false)) {
                c.remove("last_check");
                c.remove("quiet_ms");
                c.remove("timeout_ms");
            }
            if (c.has("value")) {
                String v = c.optString("value", "");
                if (v.length() > 80) {
                    c.put("value", v.substring(0, 60) + "... (" + v.length() + " chars)");
                }
            }
            if (c.has("hit") && c.optJSONObject("hit") != null) {
                JSONObject hit = c.optJSONObject("hit");
                hit.remove("rect");
            }
            return c;
        } catch (Exception e) {
            return r;
        }
    }

    private String formatObservationWithActionHeader(String actionTitle, JSONObject receipt, JSONObject obs) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== [BROWSER ACTION: ").append(actionTitle).append("] ===\n");
        if (receipt != null) {
            JSONObject cleaned = cleanReceipt(receipt);
            sb.append("Receipt: ").append(cleaned.toString()).append("\n");
        }
        if (obs != null) {
            String compact = obs.optString("compactText", "");
            if (!compact.isEmpty()) {
                sb.append(compact);
            } else {
                sb.append(obs.toString());
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // 2. Sandbox File Tools: read, write, edit
    // =========================================================================

    public File resolveSandboxFile(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return mWorkspaceDir;
        }
        String p = rawPath.trim();
        File f = p.startsWith("/") ? new File(p) : new File(mWorkspaceDir, p);
        try {
            File targetCanon = f.getCanonicalFile();
            File wsCanon = mWorkspaceDir.getCanonicalFile();
            String wsPath = wsCanon.getPath();
            String targetPath = targetCanon.getPath();
            if (targetPath.equals(wsPath) || targetPath.startsWith(wsPath + File.separator)) {
                return targetCanon;
            }
            return null; // Path traversal detected
        } catch (Exception e) {
            return null;
        }
    }

    private String executeReadTool(JSONObject args) {
        String pathStr = args.optString("file_path", "");
        int offset = Math.max(1, args.optInt("offset", 1));
        int limit = Math.max(1, Math.min(2000, args.optInt("limit", 200)));

        File f = resolveSandboxFile(pathStr);
        if (f == null) {
            return "Error: Access denied: path escapes sandbox workspace (" + pathStr + ")";
        }
        if (f.exists() && f.isDirectory() && isInsideSkills(f) && mController != null && mController.getSkillManager() != null) {
            File resolved = mController.getSkillManager().resolveSkillFile(pathStr);
            if (resolved != null && resolved.exists() && resolved.isFile()) {
                f = resolved;
            }
        }
        if (!f.exists() || !f.isFile()) {
            return "Error: File not found: " + f.getAbsolutePath();
        }
        try {
            String content = AcidIo.readFileUtf8(f);
            String[] lines = content.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            sb.append("File: ").append(f.getAbsolutePath())
              .append(" (Total lines: ").append(lines.length).append(")\n");
            int startIdx = Math.min(offset - 1, lines.length);
            int endIdx = Math.min(startIdx + limit, lines.length);
            for (int i = startIdx; i < endIdx; i++) {
                sb.append(i + 1).append(": ").append(lines[i]).append("\n");
            }
            if (endIdx < lines.length) {
                sb.append("\n(Showing lines ").append(startIdx + 1).append("-").append(endIdx)
                  .append(" of ").append(lines.length).append(". Use offset=").append(endIdx + 1)
                  .append(" to continue.)\n");
            } else if (isInsideSkills(f) && mController != null && mController.getSkillManager() != null) {
                SkillManager.SkillMeta meta = mController.getSkillManager().findSkillMetaForFile(f);
                if (meta != null && meta.isDirectoryPackage && !meta.companionFiles.isEmpty()) {
                    File entryFile = mController.getSkillManager().resolveSkillFile(meta.fileName);
                    if (entryFile != null && entryFile.getAbsolutePath().equals(f.getAbsolutePath())) {
                        sb.append("\n[Skill Package Companion Files in skills/").append(meta.packageDirName).append("/]\n");
                        for (String comp : meta.companionFiles) {
                            sb.append("- skills/").append(meta.packageDirName).append("/").append(comp).append("\n");
                        }
                        sb.append("(Use `read` to inspect companion reference docs or `bash` to run bundled scripts.)\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    private String executeWriteTool(JSONObject args, ToolSource source) {
        String pathStr = args.optString("file_path", "");
        String content = args.optString("content", "");
        if (pathStr.trim().isEmpty()) {
            return "Error: file_path is required.";
        }
        File f = resolveSandboxFile(pathStr);
        if (f == null) {
            return "Error: Access denied: path escapes sandbox workspace (" + pathStr + ")";
        }

        // Writing into userscripts/ is an install, not a plain write: it must go through
        // validation so a script without @match cannot silently end up running everywhere.
        if (isInsideUserscripts(f)) {
            if (source != ToolSource.AGENT_UI) {
                return "Error: installing userscripts is only available from the in-app agent chat, not through the MCP gateway.";
            }
            String error = mController.installUserscript(f.getName(), content);
            if (error != null) {
                return "Error: " + error;
            }
            UserscriptMeta meta = UserscriptMeta.parse(f.getName(), content);
            return "Installed userscript \"" + meta.name + "\"\n"
                + "Scope: " + meta.describeScope() + "\n"
                + "Run at: " + meta.runAt + "\n"
                + "File: " + f.getAbsolutePath() + "\n\n"
                + "The script is now managed in the browser menu (功能 -> 脚本管理). "
                + (meta.enabled ? "It is enabled and will run on matching pages." : "It is marked as pending/disabled until the user enables it.");
        }

        try {
            AcidIo.writeFileUtf8(f, content);
            log("[WRITE] Saved " + f.getName() + " (" + content.length() + " chars)");
            notifyWorkspaceFileMutated(f);
            return "Successfully wrote " + content.length() + " characters to " + f.getAbsolutePath() +
                " (Accessible in browser via: file://" + f.getAbsolutePath() + ")";
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    private void notifyWorkspaceFileMutated(final File f) {
        if (f == null) return;
        if (isInsideUserscripts(f)) {
            if (mController != null && mController.getUserscriptManager() != null) {
                mController.getUserscriptManager().invalidateCache();
            }
            log("[USERSCRIPTS] Invalidated userscript cache after updating " + f.getName());
            return;
        }
        if (isInsideAdBlock(f)) {
            if (mController != null && mController.getContext() != null) {
                AdBlockManager.getInstance(mController.getContext()).reloadCustomRules();
            }
            log("[ADBLOCK] Reloaded custom adblock rules from " + f.getName());
            return;
        }
        if (isInsideSkills(f)) {
            if (mController != null && mController.getSkillManager() != null) {
                mController.getSkillManager().notifyExternalMutation();
            }
            log("[SKILLS] Reloaded skills catalog after updating " + f.getName());
            return;
        }
        if (mController == null) return;
        final BrowserDataStore store = mController.getDataStore();
        if (store == null) return;
        String name = f.getName();
        if ("settings.json".equals(name)) {
            final boolean reloaded = store.reloadFromJsonFile();
            if (reloaded) {
                runOnMain(new Runnable() {
                    @Override
                    public void run() {
                        mController.applySettingsHotReload();
                        log("[SETTINGS] Live hot-reloaded browser settings from settings.json");
                    }
                });
            }
        } else if ("bookmarks.json".equals(name)) {
            if (store.reloadBookmarksFromJsonFile()) {
                log("[BOOKMARKS] Live hot-reloaded bookmarks from bookmarks.json");
            }
        } else if ("history.json".equals(name)) {
            if (store.reloadHistoryFromJsonFile()) {
                log("[HISTORY] Live hot-reloaded history from history.json");
            }
        }
    }

    /** True when {@code f} lives inside the userscripts directory. */
    private boolean isInsideUserscripts(File f) {
        if (f == null) return false;
        try {
            File dir = (mController != null && mController.getUserscriptManager() != null)
                ? mController.getUserscriptManager().getUserscriptsDir().getCanonicalFile()
                : new File(mWorkspaceDir, "userscripts").getCanonicalFile();
            File target = f.getCanonicalFile();
            return target.getParentFile() != null && target.getParentFile().equals(dir);
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve userscripts directory for " + f, e);
            return false;
        }
    }

    /** True when {@code f} lives inside the adblock directory. */
    private boolean isInsideAdBlock(File f) {
        if (f == null) return false;
        try {
            File dir = new File(mWorkspaceDir, "adblock").getCanonicalFile();
            File target = f.getCanonicalFile();
            return target.getParentFile() != null && target.getParentFile().equals(dir);
        } catch (Exception e) {
            return false;
        }
    }

    /** True when {@code f} lives inside the skills directory (including subdirectories). */
    private boolean isInsideSkills(File f) {
        if (f == null) return false;
        try {
            String skillsCanon = new File(mWorkspaceDir, "skills").getCanonicalPath();
            String targetCanon = f.getCanonicalPath();
            return targetCanon.equals(skillsCanon) || targetCanon.startsWith(skillsCanon + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private String executeEditTool(JSONObject args, ToolSource source) {
        String pathStr = args.optString("file_path", "");
        String oldStr = args.optString("old_string", "");
        String newStr = args.optString("new_string", "");
        boolean replaceAll = args.optBoolean("replace_all", false);

        File f = resolveSandboxFile(pathStr);
        if (f == null) {
            return "Error: Access denied: path escapes sandbox workspace (" + pathStr + ")";
        }
        if (!f.exists() || !f.isFile()) {
            return "Error: File not found: " + f.getAbsolutePath();
        }
        if (oldStr.isEmpty()) {
            return "Error: old_string cannot be empty.";
        }
        if (isInsideUserscripts(f)) {
            if (source != ToolSource.AGENT_UI) {
                return "Error: editing userscripts is only available from the in-app agent chat, not through the MCP gateway.";
            }
        }
        try {
            String content = AcidIo.readFileUtf8(f);
            int firstIdx = content.indexOf(oldStr);
            if (firstIdx < 0) {
                return "Error: old_string not found in " + f.getAbsolutePath();
            }
            String updated;
            if (replaceAll) {
                updated = content.replace(oldStr, newStr);
            } else {
                int secondIdx = content.indexOf(oldStr, firstIdx + 1);
                if (secondIdx >= 0) {
                    return "Error: old_string appears multiple times in " + f.getName() + "; set replace_all=true or provide more context.";
                }
                updated = content.substring(0, firstIdx) + newStr + content.substring(firstIdx + oldStr.length());
            }

            if (isInsideUserscripts(f)) {
                UserscriptMeta meta = UserscriptMeta.parse(f.getName(), updated);
                if (!meta.hasMetadata || (meta.matches.isEmpty() && meta.includes.isEmpty())) {
                    return "Error: edited userscript must contain a valid ==UserScript== header with @match or @include.";
                }
            }

            AcidIo.writeFileUtf8(f, updated);
            log("[EDIT] Updated " + f.getName());
            notifyWorkspaceFileMutated(f);
            return "Successfully edited " + f.getAbsolutePath();
        } catch (Exception e) {
            return "Error editing file: " + e.getMessage();
        }
    }

    // =========================================================================
    // 3. Sandbox Shell Tool: bash
    // =========================================================================

    private String executeBashTool(JSONObject args) {
        String cmd = args.optString("command", "");
        long timeoutMs = Math.max(1000, Math.min(120000, args.optLong("timeout_ms", 20000)));
        if (cmd.trim().isEmpty()) {
            return "Error: command is empty.";
        }
        log("[BASH] $ " + (cmd.length() > 60 ? cmd.substring(0, 60) + "..." : cmd));

        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.directory(mWorkspaceDir);
            pb.redirectErrorStream(true);
            proc = pb.start();
            try {
                proc.getOutputStream().close();
            } catch (Exception ignored) {}

            final InputStream is = proc.getInputStream();
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thread readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            if (baos.size() < 64 * 1024) {
                                baos.write(buf, 0, n);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed while reading process output", e);
                    }
                }
            });
            readerThread.start();

            boolean finished = waitForProcess(proc, timeoutMs);
            if (!finished) {
                proc.destroy();
                return "[timeout after " + timeoutMs + "ms]\n" + new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }
            readerThread.join(1000);
            int code = proc.exitValue();
            String out = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            if (out.length() > 16000) {
                out = out.substring(0, 16000) + "\n...[output truncated]...";
            }
            return out + "\n[exit code: " + code + "]";
        } catch (Exception e) {
            return "Error executing bash command: " + e.getMessage() + "\n[exit code: -1]";
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to destroy the bash process", e);
                }
            }
        }
    }

    private boolean waitForProcess(Process p, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                Thread.sleep(50);
            }
        }
        return false;
    }
}
