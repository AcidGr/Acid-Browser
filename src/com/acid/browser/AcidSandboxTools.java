package com.acid.browser;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
                    "- 'screenshot': Capture visual screenshot of the visible browser viewport (coordinates auto-aligned in both desktop and mobile modes). Set optional 'som'=true to draw [e1..eN] boxes strictly onto the screenshot image without altering webpage DOM, and optional 'save_path' ('.png'/'.jpg'/'.jpeg' inside acid_workspace/) to save the exact screenshot image file for bash cropping or later upload.\n" +
                    "- 'navigate': Load URL in current tab (supports http/https or file:/// inside workspace).\n" +
                    "- 'click': Tap element by 'ref' (e.g. 'e3'), visible text ('text', e.g. '消息'/'发送'), CSS selector ('selector'), or coordinates ('x', 'y' with optional 'coord_space'). Includes auto-healing if the DOM re-rendered.\n" +
                    "- 'fill': Fill text into input/textarea/contenteditable ('ref' or 'selector'). React/Vue safe. Optional 'submit'=true.\n" +
                    "- 'select': Choose option in <select> dropdown 'ref' by value or visible label.\n" +
                    "- 'scroll': Scroll viewport ('direction': 'down'|'up'|'top'|'bottom', 'amount': 0.8).\n" +
                    "- 'wait': Wait for a specific condition ('condition': 'networkidle'|'selector'|'stable'|'text'|'gone'|'ms', optional 'selector', 'text', 'quiet_ms', 'ms', 'timeout_ms'). Returns a receipt {satisfied, timed_out, elapsed_ms, error_code?} followed by the latest page observation. Always check 'satisfied' before proceeding.\n" +
                    "- 'eval': Evaluate JavaScript ('code') on current page and return {ok, result, result_type}. Accepts an expression, multiple statements (value of the last statement is returned automatically), or a function body with explicit 'return'. Supports async/await; Promises are awaited. Syntax errors return SYNTAX_ERROR without executing.\n" +
                    "- 'upload': Upload one or more files from acid_workspace/ to a webpage file input/upload button by clicking the trigger ('ref', 'text', 'selector', or 'x'/'y') and providing 'file_path' or 'file_paths' (supports multiple when webpage allows; protected workspace config/state files are blocked).\n" +
                    "- 'download': Open the browser's built-in multi-threaded download confirmation sheet for a direct file URL ('url', optional 'file_name', 'user_agent'). Automatically injects cloud-drive client User-Agent & Cookies (Baidu PCS, Quark, UC, 115) so WebView never hits HTTP 403.\n" +
                    "- 'set_mode': Switch browser identity ('mode': 'desktop' for 1280px PC view or 'mobile'), or toggle 'som' (true/false).\n" +
                    "- 'tabs': Manage tabs ('tab_action': 'list'|'new'|'switch'|'close', optional 'tab_id', 'url').\n" +
                    "- 'back': Navigate back in current tab history.\n" +
                    "- 'reload': Reload current webpage.\n" +
                    "- 'request_takeover': Pause automation loop and ask human user to manually handle login/CAPTCHA ('reason').",
                    new String[]{
                        "observe", "screenshot", "navigate", "click", "fill", "select",
                        "scroll", "wait", "eval", "upload", "download", "set_mode", "tabs", "back", "reload", "request_takeover"
                    }
                ));
                browserProps.put("url", prop("string", "Target URL for 'navigate', 'download', or 'tabs' (new)."));
                browserProps.put("save_path", prop("string", "Optional workspace-relative path for 'screenshot' (inside acid_workspace/, ending with .png, .jpg, or .jpeg) to save the captured image file at the exact returned resolution. Protected workspace files are rejected."));
                browserProps.put("file_path", prop("string", "Workspace-relative file path for 'upload' (inside acid_workspace/, e.g. 'report.pdf'). Protected workspace files are rejected."));
                browserProps.put("file_paths", propStringArray("List of workspace-relative file paths for multi-file 'upload' (inside acid_workspace/)."));
                browserProps.put("file_name", prop("string", "Optional save filename override for 'download' (e.g. 'app.apk')."));
                browserProps.put("user_agent", prop("string", "Optional custom User-Agent header for 'download' (auto-populated for Baidu/Quark/UC/115 if omitted)."));
                browserProps.put("ref", prop("string", "Target element handle from observe snapshot (e.g. 'e1', 'e7') for 'click', 'upload', 'fill', 'select', or container 'scroll'. Supports auto-healing if the DOM refreshed."));
                browserProps.put("text", prop("string", "Text value for 'fill', option value/label for 'select', visible element text for semantic 'click'/'upload' (e.g. '上传'/'选择文件'), or expected text for 'wait'."));
                browserProps.put("selector", prop("string", "CSS selector for 'click', 'upload', 'fill', 'wait', or container scope for 'observe' (e.g. '[role=\"dialog\"]', 'table')."));
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
                browserProps.put("som", prop("boolean", "Optional for 'screenshot' (draw [e1..eN] element boxes strictly onto the screenshot image without altering the webpage DOM) or 'set_mode'."));
                browserProps.put("tab_action", prop("string", "Sub-action for 'tabs': 'list', 'new', 'switch', 'close'."));
                browserProps.put("tab_id", prop("integer", "Target tab ID for 'tabs' switch/close."));
                browserProps.put("x", prop("number", "X coordinate for 'click'/'upload' in the selected 'coord_space' (default: screenshot pixel coordinate after screenshot, else WebView pixel coordinate)."));
                browserProps.put("y", prop("number", "Y coordinate for 'click'/'upload' in the selected 'coord_space' (default: screenshot pixel coordinate after screenshot, else WebView pixel coordinate)."));
                browserProps.put("coord_space", propEnum(
                    "string",
                    "Coordinate space for (x, y) in 'click'/'upload': 'auto' (uses latest screenshot pixels on this tab if valid, else view pixels), 'screenshot' (pixel coords of last screenshot), 'normalized' (0..1000 range), or 'view' (raw Android WebView pixels).",
                    new String[]{"auto", "screenshot", "normalized", "view"}
                ));
                browserProps.put("reason", prop("string", "Explanation shown to the human user when calling 'request_takeover'."));
                browserProps.put("offset", prop("integer", "0-based element offset for pagination in 'observe' (default 0)."));
                browserProps.put("limit", prop("integer", "Maximum number of interactive elements to return in 'observe' (default 180)."));
                browserProps.put("text_offset", prop("integer", "0-based character offset for reading page body text excerpt in 'observe' (default 0)."));
                browserProps.put("text_limit", prop("integer", "Maximum character count for body text excerpt in 'observe' (default 1200)."));
                browserProps.put("viewport_only", prop("boolean", "When true in 'observe', return only interactive elements currently inside the viewport (default false)."));
                browserProps.put("scope", prop("string", "Optional semantic scope hint for 'observe'."));

                return makeTool(
                    "browser",
                    "Control the built-in Acid WebView browser. Every state-changing action (navigate, click, fill, select, scroll, upload, set_mode, back, reload) automatically returns the updated page observation snapshot so you can immediately decide the next step.",
                    browserProps,
                    new String[]{"action"}
                );
            }

            @Override
            public ToolExecResult execute(AcidSandboxTools host, JSONObject args, ToolSource source) throws Exception {
                return host.executeBrowserTool(args, source);
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

    private static JSONObject propStringArray(String description) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "array");
        JSONObject item = new JSONObject();
        item.put("type", "string");
        o.put("items", item);
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
        public final String imageBase64;
        public final String imageMimeType;
        public final int imageWidth;
        public final int imageHeight;

        public ToolExecResult(String outputText, boolean requestedTakeover, String takeoverReason) {
            this(outputText, requestedTakeover, takeoverReason, null, null, 0, 0);
        }

        public ToolExecResult(
            String outputText,
            boolean requestedTakeover,
            String takeoverReason,
            String imageBase64,
            String imageMimeType,
            int imageWidth,
            int imageHeight
        ) {
            this.outputText = outputText;
            this.requestedTakeover = requestedTakeover;
            this.takeoverReason = takeoverReason;
            this.imageBase64 = imageBase64;
            this.imageMimeType = imageMimeType;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }

        public static ToolExecResult withImage(
            String outputText,
            String imageBase64,
            String imageMimeType,
            int imageWidth,
            int imageHeight
        ) {
            return new ToolExecResult(outputText, false, null, imageBase64, imageMimeType, imageWidth, imageHeight);
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
            return new ToolExecResult("Error: Tool execution exception (" + name + "): " + e.getMessage(), false, null);
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

    private ToolExecResult executeBrowserTool(final JSONObject args, ToolSource source) throws Exception {
        String action = args.optString("action", "observe").toLowerCase().trim();
        if (source != ToolSource.AGENT_UI && mController != null && mController.getAgentBoundTabId() > 0) {
            boolean mutatesActiveTab = "navigate".equals(action) || "click".equals(action)
                || "fill".equals(action) || "select".equals(action)
                || "scroll".equals(action) || "eval".equals(action)
                || "upload".equals(action)
                || "back".equals(action) || "reload".equals(action)
                || "set_mode".equals(action);
            if (mutatesActiveTab) {
                return new ToolExecResult(
                    "Error: browser(action='" + action + "') via MCP is blocked while an in-app Agent session owns a bound tab.",
                    false,
                    null
                );
            }
        }
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

        if ("screenshot".equals(action)) {
            boolean hasSavePath = args.has("save_path") && !args.optString("save_path", "").trim().isEmpty();
            ScreenshotCaptureHelper.SaveTargetResolution saveTarget = null;
            if (hasSavePath) {
                saveTarget = ScreenshotCaptureHelper.resolveSaveTarget(mWorkspaceDir, args.optString("save_path"));
                if (!saveTarget.ok) {
                    return new ToolExecResult("Error: " + saveTarget.errorMessage, false, null);
                }
            }
            if (driver == null || mController == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
            boolean includeSom = args.optBoolean("som", false);
            boolean saveAsPng = saveTarget != null && saveTarget.format == ScreenshotCaptureHelper.SaveFormat.PNG;
            ScreenshotCaptureHelper.CaptureResult cap = driver.captureScreenshotSync(
                includeSom,
                ScreenshotCaptureHelper.DEFAULT_JPEG_QUALITY,
                saveAsPng
            );
            if (cap == null || !cap.ok || cap.base64 == null) {
                String errCode = cap != null && cap.errorCode != null ? cap.errorCode : "SCREENSHOT_FAILED";
                String errMsg = cap != null && cap.errorMessage != null ? cap.errorMessage : "Screenshot capture failed.";
                return new ToolExecResult("Error: browser(action='screenshot') failed (" + errCode + "): " + errMsg, false, null);
            }

            if (saveTarget != null) {
                if (cap.savedBytes == null || cap.savedBytes.length == 0) {
                    return new ToolExecResult("Error: browser(action='screenshot') produced empty image bytes for save_path.", false, null);
                }
                try {
                    AcidIo.writeBytes(saveTarget.file, cap.savedBytes);
                    log("[SCREENSHOT] Saved " + saveTarget.relativePath + " (" + cap.savedBytes.length + " bytes, " + saveTarget.format + ")");
                } catch (Exception writeErr) {
                    return new ToolExecResult(
                        "Error: Failed to save screenshot to '" + saveTarget.relativePath + "': " + writeErr.getMessage(),
                        false,
                        null
                    );
                }
            }

            ScreenshotCaptureHelper.ScreenshotFrame f = cap.frame;
            StringBuilder sb = new StringBuilder();
            sb.append("=== [BROWSER ACTION: SCREENSHOT] ===\n");
            if (cap.title != null && !cap.title.isEmpty()) {
                sb.append("Page: ").append(cap.title).append("\n");
            }
            if (cap.url != null && !cap.url.isEmpty()) {
                sb.append("URL: ").append(cap.url).append("\n");
            }
            sb.append("Mode: ").append(f.desktopMode ? "Desktop" : "Mobile")
              .append(" | ImageSize: ").append(f.imageWidth).append("x").append(f.imageHeight)
              .append(" px | ViewSize: ").append(f.viewWidth).append("x").append(f.viewHeight)
              .append(" px");
            if (f.cssViewportWidth > 0 && f.cssViewportHeight > 0) {
                sb.append(" | CssViewport: ")
                  .append(Math.round(f.cssViewportWidth)).append("x").append(Math.round(f.cssViewportHeight));
            }
            sb.append("\n");
            if (saveTarget != null) {
                sb.append("SavedFile: ").append(saveTarget.relativePath)
                  .append(" (").append(cap.savedBytes.length).append(" bytes, ")
                  .append(saveTarget.format).append(", ")
                  .append(f.imageWidth).append("x").append(f.imageHeight).append(" px)\n");
            }
            sb.append("Coordinate Guide: When calling browser(action='click', x=..., y=...), pass pixel coordinates within this screenshot [0..")
              .append(f.imageWidth).append(", 0..").append(f.imageHeight)
              .append("] (coord_space='auto' or 'screenshot' maps directly to real device view pixels in both Mobile and Desktop modes).\n");

            if (includeSom) {
                sb.append("Overlay SoM Drawn on Image Only: ").append(cap.somMarks.size()).append(" interactive elements\n");
                if (!cap.somMarks.isEmpty()) {
                    sb.append("Marked Elements (prefer ref='eN' or click center @(x,y) on screenshot):\n");
                    for (ScreenshotCaptureHelper.SomMarkRect m : cap.somMarks) {
                        sb.append("  ").append(m.summaryLine).append("\n");
                    }
                } else {
                    sb.append("Marked Elements: none visible in viewport.\n");
                }
            } else {
                sb.append("Overlay SoM: off (pass som=true if you want [eN] boxes drawn on the screenshot).\n");
            }
            return ToolExecResult.withImage(sb.toString(), cap.base64, cap.mimeType, f.imageWidth, f.imageHeight);
        }

        if ("navigate".equals(action)) {
            String rawUrl = args.optString("url", "");
            String url = resolveAndValidateNavigationUrl(rawUrl);
            if (url == null) {
                return new ToolExecResult(formatBlockedNavigationError(rawUrl), false, null);
            }
            if (source != ToolSource.AGENT_UI && isUserscriptDownloadTarget(url, null)) {
                return new ToolExecResult("Error: navigating to or installing .user.js URLs is only available from the in-app agent chat, not through the MCP gateway.", false, null);
            }
            if (driver == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
            JSONObject obs = driver.navigateSync(url, 3800);
            String dlNotice = awaitAndFormatDownloadNotice(dlBefore, 400);
            return new ToolExecResult(dlNotice + formatObservationWithActionHeader("NAVIGATE -> " + url, null, obs), false, null);
        }

        if ("click".equals(action)) {
            if (driver == null || mController == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
            if (!hasClickTarget(args)) {
                return new ToolExecResult("Error: browser click requires 'ref' (e.g. 'e1'), semantic 'text', 'selector', or ('x', 'y') coordinates.", false, null);
            }
            JSONObject clickRes = dispatchClickTarget(driver, args);
            if (clickRes != null && !clickRes.optBoolean("ok", false)) {
                String errCode = clickRes.optString("error", "");
                if ("MISSING_SCREENSHOT".equals(errCode) || "STALE_SCREENSHOT".equals(errCode)
                    || "INVALID_COORDINATE".equals(errCode) || "INVALID_COORD_SPACE".equals(errCode)) {
                    JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
                    return new ToolExecResult(
                        "Error: browser(action='click') coordinate error (" + errCode + "): "
                            + clickRes.optString("message", "Invalid coordinates")
                            + "\n\n" + formatObservationWithActionHeader("CLICK", clickRes, obs),
                        false,
                        null
                    );
                }
            }
            Thread.sleep(380); // Let page animations/DOM settle after click
            String dlNotice = awaitAndFormatDownloadNotice(dlBefore, 500);
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(dlNotice + formatObservationWithActionHeader("CLICK", clickRes, obs), false, null);
        }

        if ("upload".equals(action)) {
            return executeUploadAction(driver, args);
        }

        if ("fill".equals(action)) {
            if (driver == null || mController == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
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
            if (driver == null || mController == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
            String ref = args.optString("ref", "").trim();
            String text = args.optString("text", "");
            JSONObject selRes = driver.selectOptionSync(ref, text);
            Thread.sleep(200);
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("SELECT", selRes, obs), false, null);
        }

        if ("scroll".equals(action)) {
            if (driver == null || mController == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
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
            if (driver == null || mController == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
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
            if (driver == null || mController == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
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
            if (source != ToolSource.AGENT_UI && isUserscriptDownloadTarget(url, customFileName)) {
                return new ToolExecResult("Error: downloading userscripts is not permitted through the MCP gateway.", false, null);
            }
            if (source != ToolSource.AGENT_UI) {
                try {
                    AcidHttp.REMOTE_FETCH_VALIDATOR.validate(new URL(url));
                } catch (Exception e) {
                    return new ToolExecResult("Error: blocked download URL through MCP gateway: " + e.getMessage(), false, null);
                }
            }
            if (mController == null) {
                return new ToolExecResult("Error: Browser controller unavailable", false, null);
            }
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
            if (driver == null || mController == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
            final CountDownLatch latch = new CountDownLatch(1);
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (args.has("mode")) {
                            String m = args.optString("mode", "mobile").toLowerCase();
                            boolean desktop = "desktop".equals(m) || "pc".equals(m);
                            mController.setDesktopMode(desktop, true);
                        }
                        if (args.has("som")) {
                            mController.setSomEnabled(args.optBoolean("som", false));
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
            latch.await(2000, TimeUnit.MILLISECONDS);
            Thread.sleep(600);
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("SET_MODE", null, obs), false, null);
        }

        if ("tabs".equals(action)) {
            final String sub = args.optString("tab_action", "list").toLowerCase(Locale.US);
            final String validatedNewTabUrl;
            if ("new".equals(sub)) {
                String rawNewUrl = args.optString("url", BrowserController.HOME_URL);
                if (rawNewUrl == null || rawNewUrl.trim().isEmpty() || BrowserController.HOME_URL.equalsIgnoreCase(rawNewUrl.trim())) {
                    validatedNewTabUrl = BrowserController.HOME_URL;
                } else {
                    validatedNewTabUrl = resolveAndValidateNavigationUrl(rawNewUrl);
                    if (validatedNewTabUrl == null) {
                        return new ToolExecResult(formatBlockedNavigationError(rawNewUrl), false, null);
                    }
                    if (source != ToolSource.AGENT_UI && isUserscriptDownloadTarget(validatedNewTabUrl, null)) {
                        return new ToolExecResult("Error: opening or installing .user.js URLs is only available from the in-app agent chat, not through the MCP gateway.", false, null);
                    }
                }
            } else {
                validatedNewTabUrl = BrowserController.HOME_URL;
            }
            final int explicitTabId = ("switch".equals(sub) || "close".equals(sub))
                ? args.optInt("tab_id", -1)
                : -1;
            if (("switch".equals(sub) || "close".equals(sub)) && explicitTabId <= 0) {
                return new ToolExecResult("Error: browser(action='tabs', tab_action='" + sub + "') requires an explicit positive 'tab_id'.", false, null);
            }
            if (driver == null || mController == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
            final boolean isAgentUi = (source == ToolSource.AGENT_UI);
            if (!isAgentUi
                && ("new".equals(sub) || "switch".equals(sub) || "close".equals(sub))
                && mController.getAgentBoundTabId() > 0) {
                return new ToolExecResult("Error: modifying tabs via MCP is blocked while an in-app Agent session owns a bound tab.", false, null);
            }
            final CountDownLatch latch = new CountDownLatch(1);
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean isWatching = isAgentUi
                            && mController.getActiveTab() != null
                            && mController.getActiveTab().id == mController.getAgentBoundTabId();

                        if ("new".equals(sub)) {
                            BrowserController.TabSession created = mController.createNewTab(
                                validatedNewTabUrl,
                                !isAgentUi || isWatching
                            );
                            if (isAgentUi && created != null) {
                                mController.setAgentBoundTabId(created.id);
                            }
                        } else if ("switch".equals(sub)) {
                            if (!isAgentUi || isWatching) {
                                if (mController.switchToTab(explicitTabId) && isAgentUi) {
                                    mController.setAgentBoundTabId(explicitTabId);
                                }
                            } else if (mController.getTabById(explicitTabId) != null) {
                                mController.setAgentBoundTabId(explicitTabId);
                            }
                        } else if ("close".equals(sub)) {
                            mController.closeTab(explicitTabId);
                        }
                    } finally {
                        latch.countDown();
                    }
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
            if (driver == null || mController == null) {
                return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
            }
            final boolean isBack = "back".equals(action);
            final CountDownLatch latch = new CountDownLatch(1);
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    try {
                        BrowserController.TabSession tab = mController.getTargetTab();
                        if (tab != null && tab.webView != null) {
                            if (isBack && tab.webView.canGoBack()) {
                                tab.webView.goBack();
                            } else if (!isBack) {
                                tab.webView.reload();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
            latch.await(2000, TimeUnit.MILLISECONDS);
            Thread.sleep(500);
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader(action.toUpperCase(), null, obs), false, null);
        }

        if ("request_takeover".equals(action)) {
            if (source != ToolSource.AGENT_UI) {
                return new ToolExecResult("Error: request_takeover is only permitted from the in-app agent chat, not through the MCP gateway.", false, null);
            }
            if (mController == null) {
                return new ToolExecResult("Error: Browser controller unavailable", false, null);
            }
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

        return new ToolExecResult("Error: Unknown browser action: " + action, false, null);
    }

    private static boolean hasClickTarget(JSONObject args) {
        if (args == null) return false;
        return (args.has("x") && args.has("y"))
            || args.has("ref")
            || args.has("text")
            || args.has("selector");
    }

    private JSONObject dispatchClickTarget(WebAutomationDriver driver, JSONObject args) throws Exception {
        if (args.has("x") && args.has("y")) {
            double rawX = args.optDouble("x", Double.NaN);
            double rawY = args.optDouble("y", Double.NaN);
            String coordSpace = args.optString("coord_space", "auto");
            ScreenshotCaptureHelper.ResolvedPoint resolved = driver.resolveClickCoordinates(rawX, rawY, coordSpace);
            if (!resolved.ok) {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("error", resolved.errorCode);
                err.put("message", resolved.errorMessage);
                return err;
            }
            JSONObject res = driver.clickCoordinateSync(resolved.viewX, resolved.viewY);
            if (res != null) {
                res.put("coordSpace", resolved.coordSpaceUsed);
                res.put("inputX", rawX);
                res.put("inputY", rawY);
            }
            return res;
        }
        JSONObject targetOpts = new JSONObject();
        if (args.has("ref")) targetOpts.put("ref", args.optString("ref").trim());
        if (args.has("text")) targetOpts.put("text", args.optString("text").trim());
        if (args.has("selector")) targetOpts.put("selector", args.optString("selector").trim());
        if (args.has("exact")) targetOpts.put("exact", args.optBoolean("exact", false));
        return driver.clickTargetSync(targetOpts, true);
    }

    private static List<String> collectUploadPaths(JSONObject args) {
        List<String> paths = new ArrayList<String>();
        if (args == null) return paths;
        JSONArray arr = args.optJSONArray("file_paths");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                paths.add(arr.optString(i, ""));
            }
        }
        if (args.has("file_path")) {
            String single = args.optString("file_path", "");
            if (!single.trim().isEmpty() || paths.isEmpty()) {
                paths.add(single);
            }
        }
        return paths;
    }

    private ToolExecResult executeUploadAction(WebAutomationDriver driver, JSONObject args) throws Exception {
        if (!hasClickTarget(args)) {
            return new ToolExecResult(
                "Error: browser(action='upload') requires 'ref' (e.g. 'e1'), semantic 'text', 'selector', or ('x', 'y') coordinates to click the upload button/input.",
                false,
                null
            );
        }
        List<String> rawPaths = collectUploadPaths(args);
        AgentUploadManager.UploadResolution resolution = AgentUploadManager.resolveUploadFiles(mWorkspaceDir, rawPaths);
        if (!resolution.ok) {
            return new ToolExecResult("Error: " + resolution.errorMessage, false, null);
        }

        if (driver == null || mController == null) {
            return new ToolExecResult("Error: Browser controller or automation driver unavailable", false, null);
        }
        BrowserController.TabSession targetTab = mController.getTargetTab();
        if (targetTab == null || targetTab.id <= 0 || targetTab.webView == null) {
            return new ToolExecResult("Error: NO_TARGET_TAB: No active or bound browser tab available for upload.", false, null);
        }
        AgentUploadManager uploadManager = mController.getUploadManager();
        if (uploadManager == null) {
            return new ToolExecResult("Error: Upload manager unavailable", false, null);
        }

        AgentUploadManager.ArmedUpload slot = uploadManager.arm(targetTab.id, resolution.files);
        try {
            JSONObject clickRes = dispatchClickTarget(driver, args);
            if (clickRes == null || !clickRes.optBoolean("ok", false)) {
                String clickErr = clickRes != null ? clickRes.optString("error", "Click target failed") : "Click target failed";
                JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
                return new ToolExecResult(
                    "Error: browser(action='upload') failed to click upload target: " + clickErr
                        + "\n\n" + formatObservationWithActionHeader("UPLOAD_FAILED", clickRes, obs),
                    false,
                    null
                );
            }

            AgentUploadManager.ConsumeResult consumeRes = uploadManager.awaitCompletion(slot, 2500L);
            if (!consumeRes.triggered) {
                JSONObject failReceipt = new JSONObject();
                failReceipt.put("ok", false);
                failReceipt.put("error", consumeRes.errorCode);
                failReceipt.put("message", consumeRes.errorMessage);
                failReceipt.put("targetTabActive", mController.isTargetTabActive());
                JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
                String tabHint = !mController.isTargetTabActive()
                    ? " Note: target tab is not in foreground, so WebView may block synthetic clicks without a user gesture."
                    : "";
                return new ToolExecResult(
                    "Error: browser(action='upload') did not trigger a webpage file chooser ("
                        + consumeRes.errorCode + "): " + consumeRes.errorMessage + tabHint
                        + "\n\n" + formatObservationWithActionHeader("UPLOAD_FAILED", failReceipt, obs),
                    false,
                    null
                );
            }
            if (!consumeRes.accepted) {
                JSONObject failReceipt = new JSONObject();
                failReceipt.put("ok", false);
                failReceipt.put("error", consumeRes.errorCode);
                failReceipt.put("message", consumeRes.errorMessage);
                JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
                return new ToolExecResult(
                    "Error: browser(action='upload') rejected by webpage file input ("
                        + consumeRes.errorCode + "): " + consumeRes.errorMessage
                        + "\n\n" + formatObservationWithActionHeader("UPLOAD_FAILED", failReceipt, obs),
                    false,
                    null
                );
            }

            Thread.sleep(450); // Allow page change handler / multipart upload initiation to settle
            JSONObject uploadReceipt = new JSONObject();
            uploadReceipt.put("ok", true);
            uploadReceipt.put("uploaded", true);
            uploadReceipt.put("count", consumeRes.fileNames.size());
            JSONArray uploadedArr = new JSONArray();
            for (String fn : consumeRes.fileNames) {
                uploadedArr.put(fn);
            }
            uploadReceipt.put("files", uploadedArr);
            uploadReceipt.put("multipleAllowed", consumeRes.multipleAllowed);
            JSONObject obs = driver.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("UPLOAD", uploadReceipt, obs), false, null);
        } finally {
            uploadManager.disarm(slot);
        }
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

    /**
     * Validates and normalizes a navigation/new-tab URL against the workspace sandbox.
     * Returns the normalized URL string, or {@code null} if the URL attempts to escape
     * the workspace via {@code file://}, {@code workspace://}, or {@code content://}.
     */
    public String resolveAndValidateNavigationUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String url = rawUrl.trim();
        if (url.isEmpty()) return "";
        String lower = url.toLowerCase(Locale.US);

        if (lower.startsWith("workspace://")) {
            File f = resolveSandboxFile(url.substring("workspace://".length()));
            if (f == null) return null;
            return "file://" + f.getAbsolutePath();
        }
        if (lower.startsWith("content:") || lower.startsWith("intent:")
            || lower.startsWith("javascript:") || lower.startsWith("data:")
            || lower.startsWith("vbscript:")) {
            return null;
        }
        if (lower.startsWith("file:")) {
            String localPath = lower.startsWith("file://")
                ? url.substring("file://".length())
                : url.substring("file:".length());
            int qIdx = localPath.indexOf('?');
            if (qIdx >= 0) localPath = localPath.substring(0, qIdx);
            int hIdx = localPath.indexOf('#');
            if (hIdx >= 0) localPath = localPath.substring(0, hIdx);
            try {
                localPath = java.net.URLDecoder.decode(localPath, "UTF-8");
            } catch (Exception ignored) {}
            if (localPath.indexOf('\0') >= 0 || localPath.indexOf('\\') >= 0) {
                return null;
            }
            if (localPath.startsWith("/android_asset/") || localPath.startsWith("/android_res/")) {
                String[] segs = localPath.split("/");
                for (String seg : segs) {
                    if ("..".equals(seg) || ".".equals(seg)) {
                        return null;
                    }
                }
                return "file://" + localPath;
            }
            File f = resolveSandboxFile(localPath);
            if (f == null) return null;
            return "file://" + f.getAbsolutePath();
        }
        if (!url.contains("://") && !lower.startsWith("about:") && !lower.startsWith("javascript:")) {
            File possibleLocal = resolveSandboxFile(url);
            if (possibleLocal != null && possibleLocal.exists() && possibleLocal.isFile()) {
                return "file://" + possibleLocal.getAbsolutePath();
            }
        }
        return url;
    }

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
            return "Error: reading file: " + e.getMessage();
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
        String mcpPermErr = checkMcpWritePermission(f, source, "writing");
        if (mcpPermErr != null) {
            return mcpPermErr;
        }

        // Writing into userscripts/ is an install, not a plain write: it must go through
        // validation so a script without @match cannot silently end up running everywhere.
        if (isInsideUserscripts(f)) {
            return installUserscriptFromTool(f, content, false);
        }

        try {
            AcidIo.writeFileUtf8(f, content);
            log("[WRITE] Saved " + f.getName() + " (" + content.length() + " chars)");
            notifyWorkspaceFileMutated(f);
            return "Successfully wrote " + content.length() + " characters to " + f.getAbsolutePath() +
                " (Accessible in browser via: file://" + f.getAbsolutePath() + ")";
        } catch (Exception e) {
            return "Error: writing file: " + e.getMessage();
        }
    }

    private String installUserscriptFromTool(File requestedFile, String content, boolean isEdit) {
        File userscriptsDir = (mController != null && mController.getUserscriptManager() != null)
            ? mController.getUserscriptManager().getUserscriptsDir()
            : new File(mWorkspaceDir, "userscripts");
        String safeName = UserscriptManager.safeFileName(requestedFile.getName());
        File actualFile = new File(userscriptsDir, safeName);

        if (mController != null && mController.getUserscriptManager() != null) {
            String error = mController.installUserscript(requestedFile.getName(), content);
            if (error != null) {
                return "Error: " + error;
            }
        } else {
            if (content == null || content.trim().isEmpty()) {
                return "Error: 脚本内容为空";
            }
            if (content.length() > UserscriptManager.MAX_SCRIPT_BYTES) {
                return "Error: 脚本超过 " + (UserscriptManager.MAX_SCRIPT_BYTES / (1024 * 1024)) + " MB 上限";
            }
            UserscriptMeta checkMeta = UserscriptMeta.parse(requestedFile.getName(), content);
            if (!checkMeta.hasMetadata) {
                return "Error: 缺少 ==UserScript== 元数据块，无法确定生效范围";
            }
            if (checkMeta.matches.isEmpty() && checkMeta.includes.isEmpty()) {
                return "Error: 缺少 @match 或 @include，无法确定生效范围";
            }
            if (!AcidIo.writeFileUtf8Safe(actualFile, content)) {
                return "Error: 写入文件失败: " + actualFile.getAbsolutePath();
            }
        }

        UserscriptMeta meta = UserscriptMeta.parse(safeName, content);
        if (isEdit) {
            log("[EDIT] Updated userscript " + safeName);
            return "Successfully edited userscript \"" + meta.name + "\" at " + actualFile.getAbsolutePath();
        }
        return "Installed userscript \"" + meta.name + "\"\n"
            + "Scope: " + meta.describeScope() + "\n"
            + "Run at: " + meta.runAt + "\n"
            + "File: " + actualFile.getAbsolutePath() + "\n\n"
            + "The script is now managed in the browser menu (功能 -> 脚本管理). "
            + (meta.enabled ? "It is enabled and will run on matching pages." : "It is marked as pending/disabled until the user enables it.");
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
            final boolean hasListener = store.hasSettingsChangedListener();
            final boolean reloaded = store.reloadFromJsonFile(true);
            if (reloaded) {
                if (!hasListener) {
                    runOnMain(new Runnable() {
                        @Override
                        public void run() {
                            mController.applySettingsHotReload();
                        }
                    });
                }
                log("[SETTINGS] Live hot-reloaded browser settings from settings.json");
            }
        } else if ("bookmarks.json".equals(name)) {
            if (store.reloadBookmarksFromJsonFile(true)) {
                log("[BOOKMARKS] Live hot-reloaded bookmarks from bookmarks.json");
            }
        } else if ("history.json".equals(name)) {
            if (store.reloadHistoryFromJsonFile(true)) {
                log("[HISTORY] Live hot-reloaded history from history.json");
            }
        }
    }

    private static String formatBlockedNavigationError(String rawUrl) {
        String lower = rawUrl != null ? rawUrl.trim().toLowerCase(Locale.US) : "";
        if (lower.startsWith("workspace://")) {
            return "Error: Access denied: path escapes sandbox workspace (" + rawUrl + ")";
        }
        if (lower.startsWith("file:")) {
            return "Error: Access denied: file:// URL escapes sandbox workspace (" + rawUrl + ")";
        }
        return "Error: Access denied: unsafe or disallowed URL scheme (" + rawUrl + ")";
    }

    private static String decodeUrlSafely(String s) {
        if (s == null) return "";
        String cur = s;
        for (int i = 0; i < 2; i++) {
            try {
                String decoded = URLDecoder.decode(cur.replace("+", "%2B"), "UTF-8");
                if (decoded.equals(cur)) break;
                cur = decoded;
            } catch (Exception ignored) {
                break;
            }
        }
        return cur;
    }

    private static boolean hasUserscriptSuffix(String rawName) {
        if (rawName == null) return false;
        String clean = decodeUrlSafely(rawName).trim();
        if (clean.isEmpty()) return false;
        String sanitized = com.acid.browser.download.DownloadCoordinator.sanitizeDownloadFileName(clean);
        return clean.toLowerCase(Locale.US).endsWith(".user.js")
            || sanitized.toLowerCase(Locale.US).endsWith(".user.js");
    }

    private static boolean isUserscriptDownloadTarget(String url, String customFileName) {
        if (hasUserscriptSuffix(customFileName)) {
            return true;
        }
        if (url != null) {
            if (BrowserController.isUserscriptUrl(url)) {
                return true;
            }
            String guessed = com.acid.browser.download.DownloadCoordinator.smartGuessFileName(url, null, null);
            if (hasUserscriptSuffix(guessed)) {
                return true;
            }
            String decodedLower = decodeUrlSafely(url).toLowerCase(Locale.US);
            String pathPart = decodedLower;
            int q = pathPart.indexOf('?');
            String queryPart = "";
            if (q >= 0) {
                queryPart = pathPart.substring(q + 1);
                pathPart = pathPart.substring(0, q);
            }
            int h = pathPart.indexOf('#');
            if (h >= 0) pathPart = pathPart.substring(0, h);
            int semi = pathPart.indexOf(';');
            if (semi >= 0) pathPart = pathPart.substring(0, semi);
            if (pathPart.trim().endsWith(".user.js")) {
                return true;
            }
            if (!queryPart.isEmpty()) {
                int qHash = queryPart.indexOf('#');
                if (qHash >= 0) queryPart = queryPart.substring(0, qHash);
                String[] pairs = queryPart.split("&");
                for (String pair : pairs) {
                    int eq = pair.indexOf('=');
                    String val = (eq >= 0) ? pair.substring(eq + 1) : pair;
                    if (hasUserscriptSuffix(val)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isShadowingBuiltinSkillPackage(File f) {
        if (f == null || !isInsideSkills(f)) return false;
        try {
            File skillsDir = new File(mWorkspaceDir, "skills").getCanonicalFile();
            File target = f.getCanonicalFile();
            String skillsPath = skillsDir.getPath();
            String targetPath = target.getPath();
            if (!targetPath.startsWith(skillsPath + File.separator)) return false;
            String rel = targetPath.substring(skillsPath.length() + 1).replace(File.separatorChar, '/');
            int slash = rel.indexOf('/');
            if (slash > 0) {
                String topDir = rel.substring(0, slash);
                if (SkillManager.isBuiltin(topDir) || SkillManager.isBuiltin(topDir + ".md")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String checkMcpWritePermission(File f, ToolSource source, String actionVerb) {
        if (f != null && isShadowingBuiltinSkillPackage(f)) {
            return "Error: cannot modify or shadow builtin skill package directory under skills/.";
        }
        if (source == ToolSource.AGENT_UI || f == null) return null;
        if (isInsideUserscripts(f)) {
            String msg = "installing".equals(actionVerb) || "writing".equals(actionVerb)
                ? "installing userscripts" : "editing userscripts";
            return "Error: " + msg + " is only available from the in-app agent chat, not through the MCP gateway.";
        }
        if (isInsideSkills(f)) {
            return "Error: modifying skills/ is only available from the in-app agent chat, not through the MCP gateway.";
        }
        if (isInsideAdBlock(f)) {
            return "Error: modifying adblock/ is only available from the in-app agent chat, not through the MCP gateway.";
        }
        if (isInsideHomepage(f)) {
            return "Error: modifying homepage/ is only available from the in-app agent chat, not through the MCP gateway.";
        }
        if (isProtectedRootStateFile(f)) {
            return "Error: modifying " + f.getName() + " is only available from the in-app agent chat, not through the MCP gateway.";
        }
        return null;
    }

    private boolean isInsideDir(File f, File dir) {
        if (f == null || dir == null) return false;
        try {
            String dirCanon = dir.getCanonicalPath();
            String targetCanon = f.getCanonicalPath();
            return targetCanon.equals(dirCanon) || targetCanon.startsWith(dirCanon + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    /** True when {@code f} lives inside the userscripts directory (including subdirectories). */
    private boolean isInsideUserscripts(File f) {
        File dir = (mController != null && mController.getUserscriptManager() != null)
            ? mController.getUserscriptManager().getUserscriptsDir()
            : new File(mWorkspaceDir, "userscripts");
        return isInsideDir(f, dir);
    }

    /** True when {@code f} lives inside the adblock directory (including subdirectories). */
    private boolean isInsideAdBlock(File f) {
        return isInsideDir(f, new File(mWorkspaceDir, "adblock"));
    }

    /** True when {@code f} lives inside the skills directory (including subdirectories). */
    private boolean isInsideSkills(File f) {
        return isInsideDir(f, new File(mWorkspaceDir, "skills"));
    }

    /** True when {@code f} lives inside the homepage directory. */
    private boolean isInsideHomepage(File f) {
        return isInsideDir(f, new File(mWorkspaceDir, "homepage"));
    }

    /** True when {@code f} is a protected workspace state JSON file (settings, bookmarks, history, userscript storage, skill sync). */
    private boolean isProtectedRootStateFile(File f) {
        if (f == null) return false;
        try {
            File target = f.getCanonicalFile();
            return target.equals(new File(mWorkspaceDir, "settings.json").getCanonicalFile())
                || target.equals(new File(mWorkspaceDir, "bookmarks.json").getCanonicalFile())
                || target.equals(new File(mWorkspaceDir, "history.json").getCanonicalFile())
                || target.equals(new File(mWorkspaceDir, "userscript_storage.json").getCanonicalFile())
                || target.equals(new File(mWorkspaceDir, "skills_sync.json").getCanonicalFile());
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
        String mcpPermErr = checkMcpWritePermission(f, source, "editing");
        if (mcpPermErr != null) {
            return mcpPermErr;
        }
        if (!f.exists() || !f.isFile()) {
            return "Error: File not found: " + f.getAbsolutePath();
        }
        if (oldStr.isEmpty()) {
            return "Error: old_string cannot be empty.";
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
                return installUserscriptFromTool(f, updated, true);
            }

            AcidIo.writeFileUtf8(f, updated);
            log("[EDIT] Updated " + f.getName());
            notifyWorkspaceFileMutated(f);
            return "Successfully edited " + f.getAbsolutePath();
        } catch (Exception e) {
            return "Error: editing file: " + e.getMessage();
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
        InputStream stdoutStream = null;
        Thread readerThread = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.directory(mWorkspaceDir);
            pb.redirectErrorStream(true);
            proc = pb.start();
            try {
                proc.getOutputStream().close();
            } catch (Exception ignored) {}

            final InputStream is = proc.getInputStream();
            stdoutStream = is;
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            final Object baosLock = new Object();
            readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            synchronized (baosLock) {
                                int remaining = 64 * 1024 - baos.size();
                                if (remaining > 0) {
                                    baos.write(buf, 0, Math.min(n, remaining));
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed while reading process output", e);
                    }
                }
            }, "AcidBashReader");
            readerThread.start();

            boolean finished = waitForProcess(proc, timeoutMs);
            if (!finished) {
                try {
                    proc.destroy();
                } catch (Exception ignored) {}
                AcidIo.closeQuietly(stdoutStream);
                try {
                    readerThread.join(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                String partialOut;
                synchronized (baosLock) {
                    partialOut = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                }
                return "Error: [timeout after " + timeoutMs + "ms]\n" + partialOut;
            }
            readerThread.join(1000);
            int code = proc.exitValue();
            String out;
            synchronized (baosLock) {
                out = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }
            if (out.length() > 16000) {
                out = out.substring(0, 16000) + "\n...[output truncated]...";
            }
            return out + "\n[exit code: " + code + "]";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "Error: executing bash command: interrupted\n[exit code: -1]";
        } catch (Exception e) {
            return "Error: executing bash command: " + e.getMessage() + "\n[exit code: -1]";
        } finally {
            AcidIo.closeQuietly(stdoutStream);
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
