package com.acid.browser;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Parsed `==UserScript==` metadata block plus URL scoping rules.
 *
 * <p>The engine previously ignored the metadata block entirely: every script was injected
 * into every page regardless of what its {@code @match} said, and {@code @run-at} had no
 * effect. This class makes the header authoritative.
 *
 * <p>Parsing is deliberately forgiving. A malformed header must never stop the script from
 * running, so a file with no usable metadata falls back to "inject everywhere, run at idle"
 * — the behaviour every existing script already relied on.
 */
public class UserscriptMeta {

    private static final String TAG = "AcidUserscriptMeta";

    public static final String RUN_AT_START = "document-start";
    public static final String RUN_AT_END = "document-end";
    public static final String RUN_AT_IDLE = "document-idle";

    /** Compiled URL patterns, keyed by their glob source. Glob matching runs per page load. */
    private static final ConcurrentHashMap<String, Pattern> REGEX_CACHE =
        new ConcurrentHashMap<String, Pattern>();

    public String fileName = "";
    public String name = "";
    public String namespace = "";
    public String version = "";
    public String description = "";
    public final List<String> matches = new ArrayList<String>();
    public final List<String> includes = new ArrayList<String>();
    public final List<String> excludes = new ArrayList<String>();
    public String runAt = RUN_AT_IDLE;

    /** Acid extension: 24x24 SVG path drawn by the agent, shown as the menu glyph. */
    public String iconPath = "";
    /** Acid extension: expose this script as a button in the browser menu. */
    public boolean showInMenu = false;
    /** Acid extension: ordering hint among menu entries. */
    public int menuOrder = 0;
    /** Whether this script is active. Pending scripts created by agent are disabled by default. */
    public boolean enabled = true;

    /** Script source with the metadata block stripped. */
    public String body = "";
    /** False when the file had no parseable header; defaults were applied instead. */
    public boolean hasMetadata = false;

    private UserscriptMeta() {}

    /**
     * Parses a userscript file. Never throws: on any problem the returned object still
     * carries a usable body and permissive defaults.
     */
    public static UserscriptMeta parse(String fileName, String content) {
        UserscriptMeta meta = new UserscriptMeta();
        meta.fileName = fileName != null ? fileName : "";
        String src = content != null ? content : "";

        try {
            int blockStart = src.indexOf("==UserScript==");
            int blockEnd = src.indexOf("==/UserScript==");

            if (blockStart >= 0 && blockEnd > blockStart) {
                meta.hasMetadata = true;
                String header = src.substring(blockStart + "==UserScript==".length(), blockEnd);
                meta.parseHeaderLines(header);
                // Body is everything after the closing marker.
                meta.body = src.substring(blockEnd + "==/UserScript==".length());
            } else {
                meta.body = src;
            }
        } catch (Exception e) {
            Log.w(TAG, "Malformed userscript header in " + fileName + "; falling back to defaults", e);
            meta.hasMetadata = false;
            meta.body = src;
        }

        if (meta.name.isEmpty()) {
            meta.name = stripExtension(meta.fileName);
        }
        if (meta.runAt == null || meta.runAt.isEmpty()) {
            meta.runAt = RUN_AT_IDLE;
        }
        meta.body = meta.body.trim();
        return meta;
    }

    private void parseHeaderLines(String header) {
        String[] lines = header.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            // Accept "// @key value", "/// @key value" and "@key value".
            while (line.startsWith("//")) {
                line = line.substring(2).trim();
            }
            if (!line.startsWith("@")) continue;

            int space = line.indexOf(' ');
            String key;
            String value;
            if (space < 0) {
                key = line.substring(1);
                value = "";
            } else {
                key = line.substring(1, space);
                value = line.substring(space + 1).trim();
            }
            applyKey(key, value);
        }
    }

    private void applyKey(String key, String value) {
        if (key == null || value == null) return;

        if ("name".equals(key)) {
            name = value;
        } else if ("namespace".equals(key)) {
            namespace = value;
        } else if ("version".equals(key)) {
            version = value;
        } else if ("description".equals(key)) {
            description = value;
        } else if ("match".equals(key)) {
            if (!value.isEmpty()) matches.add(value);
        } else if ("include".equals(key)) {
            if (!value.isEmpty()) includes.add(value);
        } else if ("exclude".equals(key)) {
            if (!value.isEmpty()) excludes.add(value);
        } else if ("run-at".equals(key)) {
            String v = value.toLowerCase();
            if (RUN_AT_START.equals(v) || RUN_AT_END.equals(v) || RUN_AT_IDLE.equals(v)) {
                runAt = v;
            }
        } else if ("acid-icon".equals(key)) {
            iconPath = value;
        } else if ("acid-menu".equals(key)) {
            showInMenu = "true".equalsIgnoreCase(value) || "1".equals(value);
        } else if ("acid-order".equals(key)) {
            try {
                menuOrder = Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                // Ordering is cosmetic; a bad value just keeps the default.
            }
        } else if ("acid-enabled".equals(key) || "enabled".equals(key)) {
            enabled = !"false".equalsIgnoreCase(value) && !"0".equals(value);
        }
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) return "";
        String n = fileName;
        if (n.endsWith(".user.js")) return n.substring(0, n.length() - ".user.js".length());
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /**
     * True when this script should run on {@code url}.
     *
     * <p>Precedence: any {@code @exclude} match wins, then any {@code @match}/{@code @include}
     * match. A script that declares neither is unrestricted, which keeps hand-written
     * scripts working exactly as before.
     */
    public boolean appliesTo(String url) {
        if (url == null || url.isEmpty()) return false;

        for (String p : excludes) {
            if (globMatches(p, url)) return false;
        }

        if (matches.isEmpty() && includes.isEmpty()) return true;

        for (String p : matches) {
            if (globMatches(p, url)) return true;
        }
        for (String p : includes) {
            if (globMatches(p, url)) return true;
        }
        return false;
    }

    /** Glob match where {@code *} spans anything, including path separators. */
    public static boolean globMatches(String glob, String url) {
        if (glob == null || url == null) return false;
        if ("*".equals(glob) || "*://*/*".equals(glob)) return true;

        Pattern p = REGEX_CACHE.get(glob);
        if (p == null) {
            try {
                p = Pattern.compile(globToRegex(glob), Pattern.CASE_INSENSITIVE);
            } catch (Exception e) {
                Log.w(TAG, "Bad URL pattern: " + glob, e);
                return false;
            }
            REGEX_CACHE.put(glob, p);
        }
        try {
            return p.matcher(url).matches();
        } catch (Exception e) {
            return false;
        }
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append('.');
                    break;
                case '.':
                case '+':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '^':
                case '$':
                case '|':
                case '\\':
                    sb.append('\\').append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }

    /** Human-readable scope, used by the management sheet and the install dialog. */
    public String describeScope() {
        if (matches.isEmpty() && includes.isEmpty()) return "所有网站";
        List<String> all = new ArrayList<String>();
        all.addAll(matches);
        all.addAll(includes);
        if (all.size() == 1 && "*://*/*".equals(all.get(0))) return "所有网站";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(all.get(i));
        }
        return sb.toString();
    }

    /** Serialized form for the management UI. */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("fileName", fileName);
            o.put("name", name);
            o.put("version", version);
            o.put("description", description);
            o.put("runAt", runAt);
            o.put("scope", describeScope());
            o.put("iconPath", iconPath);
            o.put("showInMenu", showInMenu);
            o.put("menuOrder", menuOrder);
            o.put("enabled", enabled);
            o.put("hasMetadata", hasMetadata);
            JSONArray m = new JSONArray();
            for (String s : matches) m.put(s);
            o.put("matches", m);
        } catch (Exception e) {
            Log.w(TAG, "Failed to serialize userscript metadata for " + fileName, e);
        }
        return o;
    }
}
