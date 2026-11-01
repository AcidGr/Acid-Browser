package com.acid.browser;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages on-demand Agent Skills stored under {@code acid_workspace/skills/}.
 *
 * <p>Supports both flat single-file skills ({@code skills/<name>.md}) and standard
 * multi-file Agent Skill packages ({@code skills/<name>/SKILL.md} with bundled
 * {@code references/}, {@code scripts/}, and {@code assets/} subdirectories).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Synchronizes builtin skills from APK assets ({@code userscript_dev.md}, {@code homepage_dev.md}, {@code cloud_drive_download.md}).</li>
 *   <li>Parses skill metadata from standard YAML frontmatter ({@code --- name / description ---}) as well as Markdown headers.</li>
 *   <li>Supports per-skill enable/disable state persisted in {@code skills/.disabled_skills.json}.</li>
 *   <li>Builds dynamic Skill Catalog for Acid Agent's System Prompt progressive disclosure.</li>
 *   <li>Imports skills from external URLs (GitHub tree/folder links, GitHub blob/raw links, ZIP archives, and direct Markdown URLs).</li>
 * </ul>
 */
public class SkillManager {

    private static final String TAG = "AcidSkillManager";

    public static final String SKILL_USERSCRIPT = "userscript_dev.md";
    public static final String SKILL_HOMEPAGE = "homepage_dev.md";
    public static final String SKILL_CLOUD_DRIVE = "cloud_drive_download.md";

    /** Records the app version that last wrote the official skills. */
    private static final String SYNC_STATE_FILE = "skills_sync.json";

    private static final String DISABLED_STATE_FILE = ".disabled_skills.json";
    private static final int MAX_IMPORT_FILES = 50;
    private static final long MAX_IMPORT_TOTAL_BYTES = 8L * 1024L * 1024L; // 8 MB safety cap

    private static final Set<String> BUILTIN_SKILLS = new HashSet<String>();
    static {
        BUILTIN_SKILLS.add(SKILL_USERSCRIPT);
        BUILTIN_SKILLS.add(SKILL_HOMEPAGE);
        BUILTIN_SKILLS.add(SKILL_CLOUD_DRIVE);
    }

    public interface OnSkillsChangedListener {
        void onSkillsChanged();
    }

    public static class SkillMeta {
        /** Relative path to the entry Markdown file under {@code skills/} (e.g. {@code "userscript_dev.md"} or {@code "my-skill/SKILL.md"}). */
        public final String fileName;
        /** Canonical skill identifier / slug (e.g. {@code "userscript_dev"} or {@code "my-skill"}). */
        public final String name;
        /** Human-readable display title. */
        public final String title;
        /** Concise trigger description injected into System Prompt catalog. */
        public final String description;
        public final boolean isBuiltin;
        /** True when backed by a subdirectory {@code skills/<packageDirName>/} rather than a flat file. */
        public final boolean isDirectoryPackage;
        /** Subdirectory name under {@code skills/} if {@link #isDirectoryPackage} is true, else empty. */
        public final String packageDirName;
        /** Relative paths inside {@code skills/<packageDirName>/} of bundled companion files (e.g. {@code "references/api.md"}, {@code "scripts/run.sh"}). */
        public final List<String> companionFiles;
        /** Whether this skill is enabled and active in the System Prompt catalog. */
        public boolean enabled;
        public final long lastModified;
        public final long fileSize;

        public SkillMeta(
            String fileName,
            String name,
            String title,
            String description,
            boolean isBuiltin,
            long lastModified,
            long fileSize
        ) {
            this(
                fileName, name, title, description, isBuiltin,
                false, "", Collections.<String>emptyList(), true,
                lastModified, fileSize
            );
        }

        public SkillMeta(
            String fileName,
            String name,
            String title,
            String description,
            boolean isBuiltin,
            boolean isDirectoryPackage,
            String packageDirName,
            List<String> companionFiles,
            boolean enabled,
            long lastModified,
            long fileSize
        ) {
            this.fileName = fileName;
            this.name = name;
            this.title = title;
            this.description = description;
            this.isBuiltin = isBuiltin;
            this.isDirectoryPackage = isDirectoryPackage;
            this.packageDirName = packageDirName != null ? packageDirName : "";
            this.companionFiles = companionFiles != null
                ? new ArrayList<String>(companionFiles)
                : new ArrayList<String>();
            this.enabled = enabled;
            this.lastModified = lastModified;
            this.fileSize = fileSize;
        }
    }

    public static class ImportResult {
        public final boolean success;
        public final String skillFileName;
        public final String title;
        public final int fileCount;
        public final String errorMessage;

        public static ImportResult ok(String skillFileName, String title, int fileCount) {
            return new ImportResult(true, skillFileName, title, fileCount, null);
        }

        public static ImportResult error(String errorMessage) {
            return new ImportResult(false, "", "", 0, errorMessage);
        }

        private ImportResult(boolean success, String skillFileName, String title, int fileCount, String errorMessage) {
            this.success = success;
            this.skillFileName = skillFileName != null ? skillFileName : "";
            this.title = title != null ? title : "";
            this.fileCount = fileCount;
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }
    }

    private final Context mContext;
    private final File mWorkspaceDir;
    private final File mSkillsDir;
    private final Set<String> mDisabledSkills = new HashSet<String>();
    private final List<OnSkillsChangedListener> mListeners = new ArrayList<OnSkillsChangedListener>();

    public SkillManager(Context context, File workspaceDir) {
        mContext = context != null ? context.getApplicationContext() : null;
        mWorkspaceDir = workspaceDir;
        mSkillsDir = new File(workspaceDir, "skills");
        if (!mSkillsDir.exists()) {
            mSkillsDir.mkdirs();
        }
        loadDisabledState();
        syncDefaultSkills();
    }

    public File getSkillsDir() {
        return mSkillsDir;
    }

    public void addListener(OnSkillsChangedListener listener) {
        synchronized (mListeners) {
            if (listener != null && !mListeners.contains(listener)) {
                mListeners.add(listener);
            }
        }
    }

    public void removeListener(OnSkillsChangedListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    public void notifyExternalMutation() {
        notifyChanged();
    }

    private void notifyChanged() {
        List<OnSkillsChangedListener> copy;
        synchronized (mListeners) {
            copy = new ArrayList<OnSkillsChangedListener>(mListeners);
        }
        for (OnSkillsChangedListener l : copy) {
            try {
                l.onSkillsChanged();
            } catch (Exception e) {
                Log.w(TAG, "Listener error", e);
            }
        }
    }

    // =========================================================================
    // Enable / Disable State Persistence
    // =========================================================================

    private static String normalizeSkillKey(String key) {
        if (key == null) return "";
        String clean = key.trim().replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.startsWith("skills/")) clean = clean.substring(7);
        int slash = clean.indexOf('/');
        if (slash > 0) {
            clean = clean.substring(0, slash);
        } else if (clean.toLowerCase(Locale.US).endsWith(".md")) {
            clean = clean.substring(0, clean.length() - 3);
        }
        return clean.trim().toLowerCase(Locale.US);
    }

    private synchronized void loadDisabledState() {
        mDisabledSkills.clear();
        File f = new File(mSkillsDir, DISABLED_STATE_FILE);
        if (!f.exists() || !f.isFile()) return;
        try {
            String raw = AcidIo.readFileUtf8OrEmpty(f, 64 * 1024);
            if (raw.trim().isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String k = normalizeSkillKey(arr.optString(i, ""));
                if (!k.isEmpty()) mDisabledSkills.add(k);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read disabled skills state", e);
        }
    }

    private synchronized void saveDisabledState() {
        File f = new File(mSkillsDir, DISABLED_STATE_FILE);
        try {
            JSONArray arr = new JSONArray();
            List<String> sorted = new ArrayList<String>(mDisabledSkills);
            Collections.sort(sorted);
            for (String k : sorted) {
                arr.put(k);
            }
            AcidIo.writeFileUtf8Safe(f, arr.toString());
        } catch (Exception e) {
            Log.w(TAG, "Failed to save disabled skills state", e);
        }
    }

    public synchronized boolean isSkillEnabled(String fileNameOrName) {
        String key = normalizeSkillKey(fileNameOrName);
        return key.isEmpty() || !mDisabledSkills.contains(key);
    }

    public synchronized void setSkillEnabled(String fileNameOrName, boolean enabled) {
        String key = normalizeSkillKey(fileNameOrName);
        if (key.isEmpty()) return;
        boolean changed;
        if (enabled) {
            changed = mDisabledSkills.remove(key);
        } else {
            changed = mDisabledSkills.add(key);
        }
        if (changed) {
            saveDisabledState();
            notifyChanged();
        }
    }

    // =========================================================================
    // Builtin Synchronization & Reset
    // =========================================================================

    /**
     * Installs or upgrades the three official skills bundled in the APK.
     *
     * <p>Official skills follow app updates: when {@code VERSION_CODE} rises above the version
     * that last wrote them, the bundled copy overwrites whatever is on disk. Nothing else in
     * {@code skills/} is touched, so user-added skills are never affected.
     *
     * <p>Within a single app version the files are left alone, so edits made between launches
     * survive until the next upgrade.
     */
    public synchronized void syncDefaultSkills() {
        JSONObject state = loadSyncState();
        int versionCode = BuildInfo.VERSION_CODE;
        boolean stateChanged = false;

        for (String skillFile : BUILTIN_SKILLS) {
            String assetContent = readBuiltinContent(skillFile);
            if (assetContent.isEmpty()) continue;

            File target = new File(mSkillsDir, skillFile);
            int recordedVersion = state.optJSONObject(skillFile) != null
                ? state.optJSONObject(skillFile).optInt("versionCode", 0)
                : 0;

            boolean missing = !target.exists() || target.length() == 0;
            // Upgrade only on a version bump; a same-version reinstall keeps local edits.
            boolean versionBumped = versionCode > recordedVersion;

            if (!missing && !versionBumped) continue;

            if (missing || versionBumped) {
                if (AcidIo.writeFileUtf8Safe(target, assetContent)) {
                    recordSyncState(state, skillFile, versionCode);
                    stateChanged = true;
                    Log.i(TAG, "Synced official skill " + skillFile + " to v" + versionCode);
                }
            }
        }

        if (stateChanged) saveSyncState(state);
    }

    private JSONObject loadSyncState() {
        File f = new File(mWorkspaceDir, SYNC_STATE_FILE);
        try {
            if (f.exists() && f.length() > 0) {
                String raw = AcidIo.readFileUtf8OrEmpty(f, 256 * 1024);
                if (!raw.isEmpty()) return new JSONObject(raw);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read skills sync state", e);
        }
        return new JSONObject();
    }

    private void saveSyncState(JSONObject state) {
        try {
            AcidIo.writeFileUtf8Safe(new File(mWorkspaceDir, SYNC_STATE_FILE), state.toString(2));
        } catch (Exception e) {
            Log.w(TAG, "Could not write skills sync state", e);
        }
    }

    private void recordSyncState(JSONObject state, String skillFile, int versionCode) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("versionCode", versionCode);
            state.put(skillFile, entry);
        } catch (Exception e) {
            Log.w(TAG, "Could not record sync state for " + skillFile, e);
        }
    }

    private String readBuiltinContent(String fileName) {
        if (mContext != null) {
            String assetContent = AcidIo.readAssetUtf8OrEmpty(mContext, "skills/" + fileName);
            if (!assetContent.isEmpty()) return assetContent;
        }
        // Fallback for headless JVM unit tests running from repo root
        File localAsset = new File("assets/skills/" + fileName);
        if (localAsset.exists() && localAsset.isFile()) {
            return AcidIo.readFileUtf8OrEmpty(localAsset, 2 * 1024 * 1024);
        }
        return "";
    }

    /**
     * Resets a builtin skill to its initial asset version.
     */
    public synchronized boolean resetBuiltinSkill(String fileName) {
        if (!isBuiltin(fileName)) return false;
        String assetContent = readBuiltinContent(fileName);
        if (assetContent.isEmpty()) return false;
        File target = new File(mSkillsDir, fileName);
        boolean ok = AcidIo.writeFileUtf8Safe(target, assetContent);
        if (ok) notifyChanged();
        return ok;
    }

    public static boolean isBuiltin(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.trim().toLowerCase(Locale.US);
        return BUILTIN_SKILLS.contains(lower);
    }

    // =========================================================================
    // Discovery: Flat Single-File Skills + Directory Skill Packages
    // =========================================================================

    /**
     * Lists all skills under {@code skills/} (both flat {@code .md} files and
     * subdirectory skill packages {@code <dir>/SKILL.md}), sorted with builtins first.
     */
    public synchronized List<SkillMeta> getSkills() {
        List<SkillMeta> result = new ArrayList<SkillMeta>();
        File[] files = mSkillsDir.listFiles();
        if (files == null) return result;

        Set<String> seenKeys = new HashSet<String>();

        // 1. Scan directory skill packages first (or flat files)
        for (File f : files) {
            String fname = f.getName();
            if (fname.startsWith(".")) continue;

            if (f.isFile() && fname.toLowerCase(Locale.US).endsWith(".md")) {
                SkillMeta meta = parseSkillMeta(f, null, fname, Collections.<String>emptyList(), f.length(), f.lastModified());
                result.add(meta);
                seenKeys.add(normalizeSkillKey(fname));
            } else if (f.isDirectory()) {
                File entryMd = findDirectorySkillEntry(f);
                if (entryMd != null) {
                    List<String> companions = new ArrayList<String>();
                    long[] sizeAndMod = new long[] { 0L, entryMd.lastModified() };
                    collectDirectoryFiles(f, f, entryMd, companions, sizeAndMod);
                    Collections.sort(companions);
                    String relEntry = fname + "/" + entryMd.getName();
                    SkillMeta meta = parseSkillMeta(
                        entryMd, fname, relEntry, companions,
                        Math.max(entryMd.length(), sizeAndMod[0]),
                        sizeAndMod[1]
                    );
                    result.add(meta);
                    seenKeys.add(normalizeSkillKey(fname));
                }
            }
        }

        Collections.sort(result, new Comparator<SkillMeta>() {
            @Override
            public int compare(SkillMeta a, SkillMeta b) {
                if (a.isBuiltin && !b.isBuiltin) return -1;
                if (!a.isBuiltin && b.isBuiltin) return 1;
                return a.fileName.compareToIgnoreCase(b.fileName);
            }
        });

        return result;
    }

    private static File findDirectorySkillEntry(File dir) {
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) return null;

        // Priority 1: SKILL.md (case-insensitive)
        for (File c : children) {
            if (c.isFile() && "skill.md".equalsIgnoreCase(c.getName())) {
                return c;
            }
        }
        // Priority 2: <dirName>.md
        String expected = dir.getName() + ".md";
        for (File c : children) {
            if (c.isFile() && expected.equalsIgnoreCase(c.getName())) {
                return c;
            }
        }
        // Priority 3: README.md or index.md
        for (File c : children) {
            if (c.isFile() && ("readme.md".equalsIgnoreCase(c.getName()) || "index.md".equalsIgnoreCase(c.getName()))) {
                return c;
            }
        }
        // Priority 4: Any .md file directly inside dir
        for (File c : children) {
            if (c.isFile() && c.getName().toLowerCase(Locale.US).endsWith(".md")) {
                return c;
            }
        }
        return null;
    }

    private static void collectDirectoryFiles(
        File rootDir,
        File currentDir,
        File entryMd,
        List<String> outCompanions,
        long[] sizeAndMod
    ) {
        File[] children = currentDir.listFiles();
        if (children == null) return;
        String rootPath = rootDir.getAbsolutePath();
        for (File c : children) {
            if (c.getName().startsWith(".")) continue;
            if (c.isDirectory()) {
                collectDirectoryFiles(rootDir, c, entryMd, outCompanions, sizeAndMod);
            } else if (c.isFile()) {
                sizeAndMod[0] += c.length();
                if (c.lastModified() > sizeAndMod[1]) {
                    sizeAndMod[1] = c.lastModified();
                }
                if (entryMd != null && c.getAbsolutePath().equals(entryMd.getAbsolutePath())) {
                    continue;
                }
                String abs = c.getAbsolutePath();
                if (abs.startsWith(rootPath)) {
                    String rel = abs.substring(rootPath.length()).replace('\\', '/');
                    while (rel.startsWith("/")) rel = rel.substring(1);
                    if (!rel.isEmpty()) {
                        outCompanions.add(rel);
                    }
                }
            }
        }
    }

    /**
     * Resolves a skill path or name (e.g. {@code "userscript_dev.md"}, {@code "my-skill/SKILL.md"},
     * {@code "my-skill"}, or {@code "my-skill/references/api.md"}) to a file inside {@code skills/}.
     */
    public synchronized File resolveSkillFile(String fileNameOrRelPath) {
        if (fileNameOrRelPath == null || fileNameOrRelPath.trim().isEmpty()) return null;
        String clean = fileNameOrRelPath.trim().replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.startsWith("skills/")) clean = clean.substring(7);

        File direct = new File(mSkillsDir, clean);
        try {
            String skillsCanon = mSkillsDir.getCanonicalPath();
            String targetCanon = direct.getCanonicalPath();
            if (!targetCanon.startsWith(skillsCanon)) {
                return null; // Prevent path traversal outside skills/
            }
        } catch (Exception e) {
            return null;
        }

        if (direct.exists() && direct.isDirectory()) {
            File entry = findDirectorySkillEntry(direct);
            if (entry != null) return entry;
            return new File(direct, "SKILL.md");
        }
        if (direct.exists() && direct.isFile()) {
            return direct;
        }
        // Try appending .md if not found
        if (!clean.contains("/") && !clean.toLowerCase(Locale.US).endsWith(".md")) {
            File withMd = new File(mSkillsDir, clean + ".md");
            if (withMd.exists() && withMd.isFile()) return withMd;
        }
        return direct;
    }

    /**
     * Finds the {@link SkillMeta} matching a given file inside {@code skills/}, or null.
     */
    public synchronized SkillMeta findSkillMetaForFile(File file) {
        if (file == null) return null;
        try {
            String skillsCanon = mSkillsDir.getCanonicalPath();
            String fileCanon = file.getCanonicalPath();
            if (!fileCanon.startsWith(skillsCanon)) return null;
            String rel = fileCanon.substring(skillsCanon.length()).replace('\\', '/');
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.isEmpty()) return null;

            List<SkillMeta> all = getSkills();
            for (SkillMeta m : all) {
                if (m.fileName.equalsIgnoreCase(rel)) return m;
                if (m.isDirectoryPackage && !m.packageDirName.isEmpty() && rel.startsWith(m.packageDirName + "/")) {
                    return m;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Reads the full text of a skill entry file or any companion file under {@code skills/}.
     */
    public synchronized String readSkill(String fileName) {
        File file = resolveSkillFile(fileName);
        if (file == null || !file.exists() || !file.isFile()) return "";
        return AcidIo.readFileUtf8OrEmpty(file, 2 * 1024 * 1024);
    }

    /**
     * Creates or updates a skill file (either a flat {@code .md} file, a {@code <dir>/SKILL.md}
     * entry file, or a companion file under {@code skills/<dir>/...}).
     */
    public synchronized boolean saveSkill(String fileName, String content) {
        if (fileName == null || fileName.trim().isEmpty()) return false;
        String cleanName = fileName.trim().replace('\\', '/');
        while (cleanName.startsWith("/")) cleanName = cleanName.substring(1);
        if (cleanName.startsWith("skills/")) cleanName = cleanName.substring(7);

        // If saving a top-level skill without extension and not an existing directory, append .md
        if (!cleanName.contains("/")) {
            File existingDir = new File(mSkillsDir, cleanName);
            if (existingDir.exists() && existingDir.isDirectory()) {
                File entry = findDirectorySkillEntry(existingDir);
                cleanName = cleanName + "/" + (entry != null ? entry.getName() : "SKILL.md");
            } else if (!cleanName.toLowerCase(Locale.US).endsWith(".md")) {
                cleanName += ".md";
            }
        }

        File target = resolveSkillFile(cleanName);
        if (target == null) return false;
        if (target.getParentFile() != null && !target.getParentFile().exists()) {
            target.getParentFile().mkdirs();
        }
        boolean ok = AcidIo.writeFileUtf8Safe(target, content != null ? content : "");
        if (ok) {
            notifyChanged();
        }
        return ok;
    }

    /**
     * Deletes a third-party skill (flat file or entire directory package). Builtin skills cannot be deleted.
     */
    public synchronized boolean deleteSkill(String fileName) {
        if (isBuiltin(fileName)) {
            Log.w(TAG, "Cannot delete builtin skill: " + fileName);
            return false;
        }
        if (fileName == null || fileName.trim().isEmpty()) return false;
        String clean = fileName.trim().replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.startsWith("skills/")) clean = clean.substring(7);

        // Check if it refers to a directory package (e.g. "my-skill/SKILL.md" or "my-skill")
        int slash = clean.indexOf('/');
        if (slash > 0) {
            String topDirName = clean.substring(0, slash);
            File topDir = new File(mSkillsDir, topDirName);
            File entry = topDir.exists() && topDir.isDirectory() ? findDirectorySkillEntry(topDir) : null;
            // If deleting the entry file of the skill package, delete the entire package folder
            if (entry != null && clean.equalsIgnoreCase(topDirName + "/" + entry.getName())) {
                boolean ok = deleteRecursive(topDir);
                if (ok) {
                    mDisabledSkills.remove(normalizeSkillKey(topDirName));
                    saveDisabledState();
                    notifyChanged();
                }
                return ok;
            }
        }

        File target = new File(mSkillsDir, clean);
        if (target.exists()) {
            boolean ok = target.isDirectory() ? deleteRecursive(target) : target.delete();
            if (ok) {
                if (slash <= 0) {
                    mDisabledSkills.remove(normalizeSkillKey(clean));
                    saveDisabledState();
                }
                notifyChanged();
            }
            return ok;
        }
        return false;
    }

    private static boolean deleteRecursive(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return false;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        return fileOrDir.delete();
    }

    // =========================================================================
    // System Prompt Progressive Disclosure Catalog
    // =========================================================================

    /**
     * Builds dynamic Markdown catalog of ENABLED skills for the Agent's system prompt.
     */
    public synchronized String buildSystemPromptCatalog() {
        List<SkillMeta> skills = getSkills();
        List<SkillMeta> enabledSkills = new ArrayList<SkillMeta>();
        for (SkillMeta m : skills) {
            if (m.enabled) {
                enabledSkills.add(m);
            }
        }
        if (enabledSkills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## On-Demand Skills\n");
        sb.append("When handling tasks matching the following specialized domains, you MUST FIRST call `read(file_path='skills/<file>')` to load the complete skill rules and guidelines before generating output or code:\n");
        for (SkillMeta m : enabledSkills) {
            sb.append("- `skills/").append(m.fileName).append("`: **")
              .append(m.title).append("** — ")
              .append(m.description);
            if (m.isDirectoryPackage && !m.companionFiles.isEmpty()) {
                sb.append(" *(Package `skills/").append(m.packageDirName).append("/` includes ")
                  .append(m.companionFiles.size()).append(" companion file")
                  .append(m.companionFiles.size() > 1 ? "s" : "").append(": ");
                int limit = Math.min(4, m.companionFiles.size());
                for (int i = 0; i < limit; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("`").append(m.companionFiles.get(i)).append("`");
                }
                if (m.companionFiles.size() > limit) {
                    sb.append(", ...");
                }
                sb.append(")*");
            }
            sb.append("\n");
        }
        sb.append("- **Skill Authoring & Evolution**: When the user asks you to save a workflow as a reusable Skill, create `skills/<skill-name>/SKILL.md` (with `---\\nname: <skill-name>\\ndescription: ...\\n---` YAML frontmatter, plus optional `scripts/` or `references/` files under `skills/<skill-name>/`) using `write`.\n\n");
        return sb.toString();
    }

    // =========================================================================
    // Metadata Parser: Standard YAML Frontmatter + Markdown Headers
    // =========================================================================

    private SkillMeta parseSkillMeta(
        File entryFile,
        String packageDirName,
        String relativeEntryPath,
        List<String> companionFiles,
        long totalSize,
        long lastModified
    ) {
        boolean isDirPkg = packageDirName != null && !packageDirName.isEmpty();
        String baseName;
        if (isDirPkg) {
            baseName = packageDirName;
        } else {
            String fn = entryFile.getName();
            baseName = fn.toLowerCase(Locale.US).endsWith(".md") ? fn.substring(0, fn.length() - 3) : fn;
        }

        boolean builtin = !isDirPkg && isBuiltin(entryFile.getName());
        boolean enabled = isSkillEnabled(isDirPkg ? packageDirName : entryFile.getName());

        String yamlName = "";
        String yamlTitle = "";
        String yamlDesc = "";
        String mdTitle = "";
        String mdDesc = "";

        try {
            BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(entryFile), StandardCharsets.UTF_8)
            );
            List<String> lines = new ArrayList<String>();
            String l;
            while ((l = br.readLine()) != null && lines.size() < 80) {
                lines.add(l);
            }
            br.close();

            int idx = 0;
            // Skip leading blank lines or BOM
            while (idx < lines.size()) {
                String trimmed = stripBom(lines.get(idx)).trim();
                if (!trimmed.isEmpty()) break;
                idx++;
            }

            // 1. Parse YAML Frontmatter if file starts with "---"
            if (idx < lines.size() && "---".equals(stripBom(lines.get(idx)).trim())) {
                idx++;
                String activeKey = "";
                StringBuilder multiVal = new StringBuilder();

                while (idx < lines.size()) {
                    String rawLine = lines.get(idx);
                    String trimmed = rawLine.trim();
                    idx++;

                    if ("---".equals(trimmed) || "...".equals(trimmed)) {
                        if ("description".equals(activeKey) && yamlDesc.isEmpty()) {
                            yamlDesc = multiVal.toString().trim();
                        }
                        break;
                    }

                    // Check if indented continuation line for multi-line YAML field
                    if ((rawLine.startsWith("  ") || rawLine.startsWith("\t")) && !activeKey.isEmpty()) {
                        if (!trimmed.isEmpty()) {
                            if (multiVal.length() > 0) multiVal.append(" ");
                            multiVal.append(unquoteYaml(trimmed));
                        }
                        continue;
                    }

                    // Flush previous multi-line key if any
                    if ("description".equals(activeKey) && yamlDesc.isEmpty() && multiVal.length() > 0) {
                        yamlDesc = multiVal.toString().trim();
                    }
                    activeKey = "";
                    multiVal.setLength(0);

                    int colon = trimmed.indexOf(':');
                    if (colon > 0) {
                        String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.US);
                        String val = trimmed.substring(colon + 1).trim();
                        if ("|".equals(val) || ">".equals(val) || "|-".equals(val) || ">-".equals(val) || val.isEmpty()) {
                            activeKey = key;
                        } else {
                            String cleanVal = unquoteYaml(val);
                            if ("name".equals(key)) {
                                yamlName = cleanVal;
                            } else if ("title".equals(key)) {
                                yamlTitle = cleanVal;
                            } else if ("description".equals(key)) {
                                yamlDesc = cleanVal;
                                activeKey = "description";
                                multiVal.append(cleanVal);
                            }
                        }
                    }
                }
            }

            // 2. Scan Markdown body for # Heading and > blockquote / summary paragraph
            int scannedBodyLines = 0;
            while (idx < lines.size() && scannedBodyLines < 25) {
                String line = stripBom(lines.get(idx)).trim();
                idx++;
                if (line.isEmpty() || "---".equals(line)) continue;
                scannedBodyLines++;

                if (mdTitle.isEmpty() && line.startsWith("#")) {
                    String rawTitle = line.replaceFirst("^#+\\s*", "").trim();
                    if (rawTitle.toLowerCase(Locale.US).startsWith("skill:")) {
                        rawTitle = rawTitle.substring(6).trim();
                    }
                    if (!rawTitle.isEmpty()) {
                        mdTitle = rawTitle;
                        continue;
                    }
                }

                if (mdDesc.isEmpty() && (!mdTitle.isEmpty() || !yamlName.isEmpty())) {
                    if (line.startsWith(">") || (!line.startsWith("#") && !line.startsWith("-") && !line.startsWith("```"))) {
                        String desc = line.replaceFirst("^>\\s*", "").trim();
                        if (!desc.isEmpty()) {
                            mdDesc = desc;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse skill meta: " + relativeEntryPath, e);
        }

        String finalName = !yamlName.isEmpty() ? yamlName : baseName;
        String finalTitle;
        if (!yamlTitle.isEmpty()) {
            finalTitle = yamlTitle;
        } else if (!mdTitle.isEmpty()) {
            finalTitle = mdTitle;
        } else if (!yamlName.isEmpty()) {
            finalTitle = yamlName;
        } else {
            finalTitle = baseName;
        }

        String finalDesc = !yamlDesc.isEmpty() ? yamlDesc : mdDesc;
        if (finalDesc.isEmpty()) {
            finalDesc = builtin ? "内置核心技能" : (isDirPkg ? "多文件扩展技能包" : "自定义扩展技能");
        }
        if (finalDesc.length() > 140) {
            finalDesc = finalDesc.substring(0, 137) + "...";
        }

        return new SkillMeta(
            relativeEntryPath,
            finalName,
            finalTitle,
            finalDesc,
            builtin,
            isDirPkg,
            isDirPkg ? packageDirName : "",
            companionFiles,
            enabled,
            lastModified,
            totalSize
        );
    }

    private static String stripBom(String s) {
        if (s != null && s.startsWith("\uFEFF")) {
            return s.substring(1);
        }
        return s != null ? s : "";
    }

    private static String unquoteYaml(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2) {
            if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
                return t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    // =========================================================================
    // External Importer: GitHub Folders (Tree), GitHub Files (Blob/Raw), ZIP & URLs
    // =========================================================================

    /**
     * Coordinates parsed from a GitHub repository URL.
     */
    public static class GitHubSkillCoordinates {
        public final String owner;
        public final String repo;
        public final String branch;
        public final String path;
        public final boolean isTreeOrFolder;

        public GitHubSkillCoordinates(String owner, String repo, String branch, String path, boolean isTreeOrFolder) {
            this.owner = owner;
            this.repo = repo;
            this.branch = branch;
            this.path = path;
            this.isTreeOrFolder = isTreeOrFolder;
        }
    }

    /**
     * Parses GitHub URLs such as:
     * <ul>
     *   <li>{@code https://github.com/anthropics/skills/tree/main/skills/frontend-design}</li>
     *   <li>{@code https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md}</li>
     *   <li>{@code https://github.com/owner/my-skill-repo}</li>
     * </ul>
     */
    public static GitHubSkillCoordinates parseGitHubUrl(String rawUrl) {
        if (rawUrl == null) return null;
        try {
            URL u = new URL(rawUrl.trim());
            String host = u.getHost() != null ? u.getHost().toLowerCase(Locale.US) : "";
            if (!"github.com".equals(host) && !"www.github.com".equals(host)) {
                return null;
            }
            String path = u.getPath();
            if (path == null) return null;
            while (path.startsWith("/")) path = path.substring(1);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.isEmpty()) return null;

            String[] parts = path.split("/");
            if (parts.length < 2) return null;
            String owner = parts[0];
            String repo = parts[1];
            if (repo.endsWith(".git")) repo = repo.substring(0, repo.length() - 4);

            if (parts.length == 2) {
                return new GitHubSkillCoordinates(owner, repo, "", "", true);
            }
            if (parts.length >= 4 && ("tree".equals(parts[2]) || "blob".equals(parts[2]))) {
                boolean isTree = "tree".equals(parts[2]);
                String branch = parts[3];
                StringBuilder subPath = new StringBuilder();
                for (int i = 4; i < parts.length; i++) {
                    if (subPath.length() > 0) subPath.append("/");
                    subPath.append(URLDecoder.decode(parts[i], "UTF-8"));
                }
                String relPath = subPath.toString();
                // If user pasted a blob URL to .../SKILL.md inside a folder, treat parent folder as package candidate!
                if (!isTree && (relPath.endsWith("/SKILL.md") || relPath.endsWith("/skill.md"))) {
                    String parentPath = relPath.substring(0, relPath.lastIndexOf('/'));
                    return new GitHubSkillCoordinates(owner, repo, branch, parentPath, true);
                }
                return new GitHubSkillCoordinates(owner, repo, branch, relPath, isTree);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Imports a skill or multi-file skill package from a GitHub folder link, GitHub blob/raw link,
     * ZIP archive URL, or direct Markdown URL. Runs network I/O synchronously (call from worker thread).
     */
    public ImportResult importSkillFromUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return ImportResult.error("链接不能为空");
        }
        String urlStr = rawUrl.trim();
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            urlStr = "https://" + urlStr;
        }

        try {
            // 1. Check if it's a GitHub URL
            GitHubSkillCoordinates gh = parseGitHubUrl(urlStr);
            if (gh != null) {
                if (gh.isTreeOrFolder) {
                    ImportResult folderRes = importFromGitHubDirectory(gh);
                    if (folderRes.success) {
                        return folderRes;
                    }
                    // If GitHub API failed and original URL was a blob/SKILL.md link, fall through to raw download
                    if (!urlStr.contains("/blob/")) {
                        return folderRes;
                    }
                }
                // Convert GitHub /blob/ URL to raw.githubusercontent.com
                if (urlStr.contains("github.com/") && urlStr.contains("/blob/")) {
                    urlStr = urlStr.replaceFirst("https?://(www\\.)?github\\.com/", "https://raw.githubusercontent.com/")
                                   .replaceFirst("/blob/", "/");
                }
            }

            // 2. Check if URL points to a ZIP archive
            String cleanPath = new URL(urlStr).getPath().toLowerCase(Locale.US);
            if (cleanPath.endsWith(".zip") || urlStr.contains("/archive/refs/")) {
                return importFromZipUrl(urlStr);
            }

            // 3. Direct HTTP(S) download (Markdown or ZIP detected by Content-Type / magic bytes)
            byte[] data = downloadBytes(urlStr, MAX_IMPORT_TOTAL_BYTES);
            if (data == null || data.length == 0) {
                return ImportResult.error("下载内容为空");
            }
            // Check ZIP magic header PK\x03\x04
            if (data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4) {
                return importFromZipBytes(data, inferSkillSlugFromUrl(urlStr));
            }

            String content = new String(data, StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                return ImportResult.error("下载的文本内容为空");
            }

            String slug = inferSkillSlugFromUrlAndContent(urlStr, content);
            String targetFileName = slug.endsWith(".md") ? slug : (slug + ".md");
            if (isBuiltin(targetFileName)) {
                targetFileName = "custom_" + targetFileName;
            }
            boolean saved = saveSkill(targetFileName, content);
            if (!saved) {
                return ImportResult.error("写入本地技能文件失败");
            }
            SkillMeta meta = findSkillMetaForFile(new File(mSkillsDir, targetFileName));
            return ImportResult.ok(targetFileName, meta != null ? meta.title : targetFileName, 1);

        } catch (Exception e) {
            Log.w(TAG, "importSkillFromUrl failed: " + urlStr, e);
            return ImportResult.error("导入失败: " + e.getMessage());
        }
    }

    private ImportResult importFromGitHubDirectory(GitHubSkillCoordinates gh) {
        try {
            String dirSlug = gh.path.isEmpty()
                ? gh.repo
                : gh.path.substring(gh.path.lastIndexOf('/') + 1);
            dirSlug = sanitizeSlug(dirSlug);
            if (dirSlug.isEmpty()) dirSlug = "imported_skill";

            File targetDir = new File(mSkillsDir, dirSlug);
            int[] fileCounter = new int[] { 0 };
            long[] byteCounter = new long[] { 0L };

            fetchGitHubDirectoryRecursive(gh.owner, gh.repo, gh.branch, gh.path, targetDir, fileCounter, byteCounter);

            if (fileCounter[0] == 0) {
                return ImportResult.error("未在该 GitHub 目录下找到可下载的技能文件");
            }
            File entry = findDirectorySkillEntry(targetDir);
            if (entry == null) {
                deleteRecursive(targetDir);
                return ImportResult.error("该目录下未包含 SKILL.md 或任何 .md 技能主文档");
            }

            notifyChanged();
            SkillMeta meta = findSkillMetaForFile(entry);
            String relEntry = dirSlug + "/" + entry.getName();
            return ImportResult.ok(relEntry, meta != null ? meta.title : dirSlug, fileCounter[0]);
        } catch (Exception e) {
            return ImportResult.error("GitHub 目录拉取失败: " + e.getMessage());
        }
    }

    private void fetchGitHubDirectoryRecursive(
        String owner,
        String repo,
        String branch,
        String apiPath,
        File destDir,
        int[] fileCounter,
        long[] byteCounter
    ) throws Exception {
        if (fileCounter[0] >= MAX_IMPORT_FILES || byteCounter[0] >= MAX_IMPORT_TOTAL_BYTES) {
            return;
        }
        StringBuilder apiUrl = new StringBuilder("https://api.github.com/repos/")
            .append(owner).append("/").append(repo).append("/contents");
        if (apiPath != null && !apiPath.isEmpty()) {
            String[] segs = apiPath.split("/");
            for (String s : segs) {
                if (!s.isEmpty()) {
                    apiUrl.append("/").append(java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"));
                }
            }
        }
        if (branch != null && !branch.isEmpty()) {
            apiUrl.append("?ref=").append(java.net.URLEncoder.encode(branch, "UTF-8"));
        }

        byte[] jsonBytes = downloadBytes(apiUrl.toString(), 2 * 1024 * 1024);
        String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8).trim();

        if (jsonStr.startsWith("{")) {
            // Single file response
            JSONObject obj = new JSONObject(jsonStr);
            if ("file".equals(obj.optString("type"))) {
                String dlUrl = obj.optString("download_url", "");
                String name = obj.optString("name", "SKILL.md");
                if (!dlUrl.isEmpty()) {
                    byte[] content = downloadBytes(dlUrl, MAX_IMPORT_TOTAL_BYTES - byteCounter[0]);
                    if (!destDir.exists()) destDir.mkdirs();
                    writeBytesSafe(new File(destDir, name), content);
                    fileCounter[0]++;
                    byteCounter[0] += content.length;
                }
            }
            return;
        }

        JSONArray items = new JSONArray(jsonStr);
        for (int i = 0; i < items.length(); i++) {
            if (fileCounter[0] >= MAX_IMPORT_FILES || byteCounter[0] >= MAX_IMPORT_TOTAL_BYTES) break;
            JSONObject item = items.getJSONObject(i);
            String type = item.optString("type", "");
            String name = item.optString("name", "");
            if (name.isEmpty() || name.startsWith(".")) continue;

            if ("file".equals(type)) {
                String dlUrl = item.optString("download_url", "");
                if (!dlUrl.isEmpty()) {
                    byte[] content = downloadBytes(dlUrl, MAX_IMPORT_TOTAL_BYTES - byteCounter[0]);
                    if (!destDir.exists()) destDir.mkdirs();
                    writeBytesSafe(new File(destDir, name), content);
                    fileCounter[0]++;
                    byteCounter[0] += content.length;
                }
            } else if ("dir".equals(type)) {
                String subPath = item.optString("path", "");
                File subDir = new File(destDir, name);
                fetchGitHubDirectoryRecursive(owner, repo, branch, subPath, subDir, fileCounter, byteCounter);
            }
        }
    }

    private ImportResult importFromZipUrl(String urlStr) throws Exception {
        byte[] zipBytes = downloadBytes(urlStr, MAX_IMPORT_TOTAL_BYTES);
        return importFromZipBytes(zipBytes, inferSkillSlugFromUrl(urlStr));
    }

    public synchronized ImportResult importFromZipBytes(byte[] zipBytes, String fallbackSlug) {
        if (zipBytes == null || zipBytes.length == 0) {
            return ImportResult.error("ZIP 压缩包为空");
        }
        String slug = sanitizeSlug(fallbackSlug);
        if (slug.isEmpty()) slug = "imported_skill";

        File tempExtractDir = new File(mSkillsDir, ".tmp_zip_" + System.currentTimeMillis());
        tempExtractDir.mkdirs();

        try {
            ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes));
            ZipEntry entry;
            int fileCount = 0;
            long totalBytes = 0;
            String tempCanon = tempExtractDir.getCanonicalPath();

            while ((entry = zis.getNextEntry()) != null) {
                if (fileCount >= MAX_IMPORT_FILES || totalBytes >= MAX_IMPORT_TOTAL_BYTES) break;
                String entryName = entry.getName().replace('\\', '/');
                while (entryName.startsWith("/")) entryName = entryName.substring(1);
                if (entryName.isEmpty() || entryName.contains("..") || entryName.startsWith("__MACOSX")) {
                    zis.closeEntry();
                    continue;
                }
                File out = new File(tempExtractDir, entryName);
                if (!out.getCanonicalPath().startsWith(tempCanon)) {
                    zis.closeEntry();
                    continue; // Zip-slip protection
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        totalBytes += n;
                        if (totalBytes > MAX_IMPORT_TOTAL_BYTES) break;
                        bos.write(buf, 0, n);
                    }
                    writeBytesSafe(out, bos.toByteArray());
                    fileCount++;
                }
                zis.closeEntry();
            }
            zis.close();

            if (fileCount == 0) {
                deleteRecursive(tempExtractDir);
                return ImportResult.error("ZIP 包中未找到有效文件");
            }

            // If the ZIP has a single top-level directory, unwrap it
            File sourceRoot = tempExtractDir;
            File[] topChildren = tempExtractDir.listFiles();
            if (topChildren != null && topChildren.length == 1 && topChildren[0].isDirectory()) {
                sourceRoot = topChildren[0];
                if ("imported_skill".equals(slug) || slug.endsWith(".zip")) {
                    slug = sanitizeSlug(sourceRoot.getName());
                }
            }

            File entryMd = findDirectorySkillEntry(sourceRoot);
            if (entryMd == null) {
                deleteRecursive(tempExtractDir);
                return ImportResult.error("ZIP 包中未找到 SKILL.md 或任何 .md 技能文档");
            }

            File finalDir = new File(mSkillsDir, slug);
            if (finalDir.exists()) {
                deleteRecursive(finalDir);
            }
            moveDirectoryRecursive(sourceRoot, finalDir);
            deleteRecursive(tempExtractDir);

            notifyChanged();
            File finalEntry = findDirectorySkillEntry(finalDir);
            String relEntry = slug + "/" + (finalEntry != null ? finalEntry.getName() : "SKILL.md");
            SkillMeta meta = finalEntry != null ? findSkillMetaForFile(finalEntry) : null;
            return ImportResult.ok(relEntry, meta != null ? meta.title : slug, fileCount);
        } catch (Exception e) {
            deleteRecursive(tempExtractDir);
            return ImportResult.error("解压技能包失败: " + e.getMessage());
        }
    }

    private static void moveDirectoryRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists()) dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    moveDirectoryRecursive(c, new File(dst, c.getName()));
                }
            }
        } else {
            if (dst.getParentFile() != null && !dst.getParentFile().exists()) {
                dst.getParentFile().mkdirs();
            }
            if (!src.renameTo(dst)) {
                byte[] bytes = AcidIo.readFileUtf8OrEmpty(src, (int) MAX_IMPORT_TOTAL_BYTES).getBytes(StandardCharsets.UTF_8);
                writeBytesSafe(dst, bytes);
            }
        }
    }

    private static byte[] downloadBytes(String urlStr, long maxBytes) throws Exception {
        HttpURLConnection conn = null;
        String currentUrl = urlStr;
        for (int redirects = 0; redirects < 5; redirects++) {
            conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "AcidBrowser/1.0 (Agent-Skill-Importer)");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json, text/markdown, text/plain, */*");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) break;
                currentUrl = new URL(new URL(currentUrl), loc).toString();
                continue;
            }
            if (code < 200 || code >= 300) {
                String err = AcidIo.readStreamUtf8(conn.getErrorStream());
                conn.disconnect();
                throw new Exception("HTTP " + code + (err != null && !err.isEmpty() ? ": " + err.substring(0, Math.min(120, err.length())) : ""));
            }

            InputStream is = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    is.close();
                    conn.disconnect();
                    throw new Exception("文件超过最大大小限制 (" + (maxBytes / 1024 / 1024) + "MB)");
                }
                bos.write(buf, 0, n);
            }
            is.close();
            conn.disconnect();
            return bos.toByteArray();
        }
        throw new Exception("重定向次数过多");
    }

    private static boolean writeBytesSafe(File target, byte[] data) {
        try {
            if (target.getParentFile() != null && !target.getParentFile().exists()) {
                target.getParentFile().mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(target);
            fos.write(data != null ? data : new byte[0]);
            fos.flush();
            fos.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String inferSkillSlugFromUrl(String urlStr) {
        try {
            String path = new URL(urlStr).getPath();
            if (path == null) return "imported_skill";
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (last.toLowerCase(Locale.US).endsWith(".zip")) {
                last = last.substring(0, last.length() - 4);
            } else if (last.toLowerCase(Locale.US).endsWith(".md")) {
                last = last.substring(0, last.length() - 3);
            }
            if ("skill".equalsIgnoreCase(last) || "main".equalsIgnoreCase(last) || "master".equalsIgnoreCase(last)) {
                String parent = path.substring(0, Math.max(0, path.lastIndexOf('/')));
                if (parent.contains("/")) {
                    String parentSeg = parent.substring(parent.lastIndexOf('/') + 1);
                    if (!parentSeg.isEmpty()) last = parentSeg;
                }
            }
            String slug = sanitizeSlug(last);
            return slug.isEmpty() ? "imported_skill" : slug;
        } catch (Exception e) {
            return "imported_skill";
        }
    }

    private static String inferSkillSlugFromUrlAndContent(String urlStr, String content) {
        // First check if YAML frontmatter defines `name:`
        if (content != null) {
            String trimmed = stripBom(content).trim();
            if (trimmed.startsWith("---")) {
                String[] lines = trimmed.split("\n");
                for (int i = 1; i < Math.min(lines.length, 20); i++) {
                    String l = lines[i].trim();
                    if ("---".equals(l) || "...".equals(l)) break;
                    if (l.toLowerCase(Locale.US).startsWith("name:")) {
                        String val = unquoteYaml(l.substring(5).trim());
                        String slug = sanitizeSlug(val);
                        if (!slug.isEmpty()) return slug;
                    }
                }
            }
        }
        return inferSkillSlugFromUrl(urlStr);
    }

    private static String sanitizeSlug(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_");
        while (s.startsWith("_") || s.startsWith("-")) s = s.substring(1);
        while (s.endsWith("_") || s.endsWith("-")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
