package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Multi-Session Persistence Store for Acid Agent.
 * Stores each conversation session as a compact JSON file under /files/sessions/.
 * Preserves both:
 *  - `uiItems`: Structured UI bubbles (user, thinking, tool_call with full input/output details, assistant, error)
 *  - `apiMessages`: Pruned OpenAI-compatible conversation history for seamless multi-turn continuation
 */
public class AcidSessionStore {

    private static final String TAG = "AcidSessionStore";

    private static final String PREF_SESSION = "acid_session_prefs";
    private static final String KEY_ACTIVE_ID = "active_session_id";

    public static final String TYPE_USER = "user";
    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_TOOL = "tool";
    public static final String TYPE_ASSISTANT = "assistant";
    public static final String TYPE_ERROR = "error";

    public static class UiMessageItem {
        public String id = "ui_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 100000);
        public String type;
        public String text;          // For user, thinking, assistant, error
        public String toolCallId;    // For tool
        public String toolName;      // For tool (browser, read, write, edit, bash)
        public String toolSummary;   // Short one-line summary shown on the collapsed pill
        public String toolArgsJson;  // Pretty JSON input parameters
        public String toolResult;    // Full tool execution output
        public boolean isRunning;    // True while tool is executing or while streaming
        public boolean isError;      // True if tool returned an error
        public long timestamp;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                if (id != null) o.put("id", id);
                o.put("type", type != null ? type : TYPE_ASSISTANT);
                if (text != null) o.put("text", text);
                if (toolCallId != null) o.put("toolCallId", toolCallId);
                if (toolName != null) o.put("toolName", toolName);
                if (toolSummary != null) o.put("toolSummary", toolSummary);
                if (toolArgsJson != null) o.put("toolArgsJson", toolArgsJson);
                if (toolResult != null) o.put("toolResult", toolResult);
                o.put("isRunning", isRunning);
                o.put("isError", isError);
                o.put("timestamp", timestamp);
            } catch (Exception e) {
                // org.json declares a checked exception on put(); not thrown in practice,
                // but log rather than silently producing a truncated bubble.
                Log.w(TAG, "Failed to encode UI item of type " + type, e);
            }
            return o;
        }

        public static UiMessageItem fromJson(JSONObject o) {
            UiMessageItem it = new UiMessageItem();
            it.id = o.optString("id", "ui_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 100000));
            it.type = o.optString("type", TYPE_ASSISTANT);
            it.text = o.optString("text", "");
            it.toolCallId = o.optString("toolCallId", "");
            it.toolName = o.optString("toolName", "");
            it.toolSummary = o.optString("toolSummary", "");
            it.toolArgsJson = o.optString("toolArgsJson", "");
            it.toolResult = o.optString("toolResult", "");
            it.isRunning = false; // Persisted items are no longer actively running
            it.isError = o.optBoolean("isError", false);
            it.timestamp = o.optLong("timestamp", System.currentTimeMillis());
            return it;
        }
    }

    public static class SessionMeta {
        public final String id;
        public final String title;
        public final long updatedAt;
        public final int messageCount;

        public SessionMeta(String id, String title, long updatedAt, int messageCount) {
            this.id = id;
            this.title = title;
            this.updatedAt = updatedAt;
            this.messageCount = messageCount;
        }

        public String getFormattedTime() {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(updatedAt));
        }
    }

    public static class AgentSession {
        public String id;
        public String title;
        public long createdAt;
        public long updatedAt;
        public int lastPromptTokens;
        public int lastCompletionTokens;
        public int lastTotalTokens;
        public int lastCachedTokens;
        public final List<UiMessageItem> uiItems = new ArrayList<UiMessageItem>();
        public final List<JSONObject> apiMessages = new ArrayList<JSONObject>();
    }

    private final Context mContext;
    private final File mSessionsDir;
    private AgentSession mActiveSession;

    public AcidSessionStore(Context context) {
        mContext = context;
        mSessionsDir = new File(context.getFilesDir(), "sessions");
        if (!mSessionsDir.exists()) {
            mSessionsDir.mkdirs();
        }
        loadOrCreateInitialSession();
    }

    private void loadOrCreateInitialSession() {
        SharedPreferences sp = mContext.getSharedPreferences(PREF_SESSION, Context.MODE_PRIVATE);
        String savedId = sp.getString(KEY_ACTIVE_ID, null);
        if (savedId != null) {
            AgentSession loaded = readSessionFromDisk(savedId);
            if (loaded != null) {
                mActiveSession = loaded;
                return;
            }
        }
        List<SessionMeta> all = listSessions();
        if (!all.isEmpty()) {
            AgentSession first = readSessionFromDisk(all.get(0).id);
            if (first != null) {
                mActiveSession = first;
                setActiveSessionIdPref(first.id);
                return;
            }
        }
        createNewSession();
    }

    private void setActiveSessionIdPref(String id) {
        mContext.getSharedPreferences(PREF_SESSION, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_ID, id)
            .apply();
    }

    public synchronized AgentSession getActiveSession() {
        if (mActiveSession == null) {
            createNewSession();
        }
        return mActiveSession;
    }

    public synchronized AgentSession createNewSession() {
        // If current active session is completely empty, reuse it instead of spawning blank duplicates
        if (mActiveSession != null && mActiveSession.uiItems.isEmpty() && mActiveSession.apiMessages.isEmpty()) {
            return mActiveSession;
        }
        AgentSession s = new AgentSession();
        long now = System.currentTimeMillis();
        s.id = "s_" + now;
        s.title = "新对话";
        s.createdAt = now;
        s.updatedAt = now;
        mActiveSession = s;
        saveSessionToDisk(s);
        setActiveSessionIdPref(s.id);
        return s;
    }

    public synchronized AgentSession switchToSession(String sessionId) {
        if (mActiveSession != null && mActiveSession.id.equals(sessionId)) {
            return mActiveSession;
        }
        AgentSession target = readSessionFromDisk(sessionId);
        if (target != null) {
            mActiveSession = target;
            setActiveSessionIdPref(target.id);
        }
        return mActiveSession;
    }

    public synchronized boolean deleteSession(String sessionId) {
        File f = new File(mSessionsDir, sessionId + ".json");
        boolean deleted = f.exists() && f.delete();
        if (mActiveSession != null && mActiveSession.id.equals(sessionId)) {
            mActiveSession = null;
            List<SessionMeta> remaining = listSessions();
            if (!remaining.isEmpty()) {
                switchToSession(remaining.get(0).id);
            } else {
                createNewSession();
            }
        }
        return deleted;
    }

    public synchronized List<SessionMeta> listSessions() {
        List<SessionMeta> list = new ArrayList<SessionMeta>();
        File[] files = mSessionsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".json")) {
                    try {
                        String raw = AcidIo.readFileUtf8(f);
                        JSONObject o = new JSONObject(raw);
                        String id = o.optString("id", f.getName().replace(".json", ""));
                        String title = o.optString("title", "新对话");
                        long updated = o.optLong("updatedAt", f.lastModified());
                        JSONArray uiArr = o.optJSONArray("uiItems");
                        int count = uiArr != null ? uiArr.length() : 0;
                        list.add(new SessionMeta(id, title, updated, count));
                    } catch (Exception e) {
                        Log.w(TAG, "Skipping unreadable session file: " + f.getName(), e);
                    }
                }
            }
        }
        Collections.sort(list, new Comparator<SessionMeta>() {
            @Override
            public int compare(SessionMeta a, SessionMeta b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return list;
    }

    public synchronized void saveActiveSession() {
        if (mActiveSession != null) {
            mActiveSession.updatedAt = System.currentTimeMillis();
            saveSessionToDisk(mActiveSession);
        }
    }

    public synchronized void updateTitleIfDefault(String firstUserPrompt) {
        if (mActiveSession == null || firstUserPrompt == null) return;
        String clean = firstUserPrompt.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return;
        if ("新对话".equals(mActiveSession.title) || mActiveSession.title == null || mActiveSession.title.isEmpty()) {
            mActiveSession.title = clean.length() > 22 ? clean.substring(0, 22) + "..." : clean;
            saveActiveSession();
        }
    }

    private void saveSessionToDisk(AgentSession s) {
        try {
            JSONObject root = new JSONObject();
            root.put("id", s.id);
            root.put("title", s.title != null ? s.title : "新对话");
            root.put("createdAt", s.createdAt);
            root.put("updatedAt", s.updatedAt);
            root.put("lastPromptTokens", s.lastPromptTokens);
            root.put("lastCompletionTokens", s.lastCompletionTokens);
            root.put("lastTotalTokens", s.lastTotalTokens);
            root.put("lastCachedTokens", s.lastCachedTokens);

            JSONArray uiArr = new JSONArray();
            for (UiMessageItem item : s.uiItems) {
                uiArr.put(item.toJson());
            }
            root.put("uiItems", uiArr);

            // Save apiMessages to disk preserving full content
            JSONArray apiArr = new JSONArray();
            int n = s.apiMessages.size();
            for (int i = 0; i < n; i++) {
                JSONObject msg = s.apiMessages.get(i);
                // Safety bound: only guard against runaway massive single payloads (>64KB)
                String c = msg.optString("content", "");
                if (c.length() > 65536) {
                    JSONObject copy = new JSONObject(msg.toString());
                    copy.put("content", c.substring(0, 32768) + "\n...[Content truncated at 32KB on disk]...\n" + c.substring(c.length() - 8192));
                    apiArr.put(copy);
                } else {
                    apiArr.put(msg);
                }
            }
            root.put("apiMessages", apiArr);

            File f = new File(mSessionsDir, s.id + ".json");
            AcidIo.writeFileUtf8(f, root.toString());
        } catch (Exception e) {
            // Losing a session silently is unrecoverable for the user, so always log it.
            Log.e(TAG, "Failed to persist session " + (s != null ? s.id : "?"), e);
        }
    }

    private AgentSession readSessionFromDisk(String id) {
        File f = new File(mSessionsDir, id + ".json");
        if (!f.exists() || !f.isFile()) return null;
        try {
            String raw = AcidIo.readFileUtf8(f);
            JSONObject root = new JSONObject(raw);
            AgentSession s = new AgentSession();
            s.id = root.optString("id", id);
            s.title = root.optString("title", "新对话");
            s.createdAt = root.optLong("createdAt", f.lastModified());
            s.updatedAt = root.optLong("updatedAt", f.lastModified());
            s.lastPromptTokens = root.optInt("lastPromptTokens", 0);
            s.lastCompletionTokens = root.optInt("lastCompletionTokens", 0);
            s.lastTotalTokens = root.optInt("lastTotalTokens", 0);
            s.lastCachedTokens = root.optInt("lastCachedTokens", 0);

            JSONArray uiArr = root.optJSONArray("uiItems");
            if (uiArr != null) {
                for (int i = 0; i < uiArr.length(); i++) {
                    JSONObject o = uiArr.optJSONObject(i);
                    if (o != null) {
                        s.uiItems.add(UiMessageItem.fromJson(o));
                    }
                }
            }

            JSONArray apiArr = root.optJSONArray("apiMessages");
            if (apiArr != null) {
                for (int i = 0; i < apiArr.length(); i++) {
                    JSONObject o = apiArr.optJSONObject(i);
                    if (o != null) {
                        s.apiMessages.add(o);
                    }
                }
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }
}
