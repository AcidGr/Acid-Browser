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
    private final SkillStorageVault mVault;
    private final Set<String> mDisabledSkills = new HashSet<String>();
    private final List<OnSkillsChangedListener> mListeners = new ArrayList<OnSkillsChangedListener>();

    public SkillManager(Context context, File workspaceDir) {
        mContext = context != null ? context.getApplicationContext() : null;
        mWorkspaceDir = workspaceDir;
        mSkillsDir = new File(workspaceDir, "skills");
        if (!mSkillsDir.exists()) {
            mSkillsDir.mkdirs();
        }
        mVault = new SkillStorageVault(mSkillsDir);
        loadDisabledState();
        syncDefaultSkills();
    }

    public SkillStorageVault getVault() {
        return mVault;
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

    static File findDirectorySkillEntry(File dir) {
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

        if (clean.isEmpty()) return false;

        // Check if it refers to a directory package (e.g. "my-skill/SKILL.md" or "my-skill")
        int slash = clean.indexOf('/');
        if (slash > 0) {
            String topDirName = clean.substring(0, slash);
            SkillSlug slug = SkillSlug.from(topDirName, "");
            File topDir = mVault.resolveSkillDir(slug);
            File entry = topDir.exists() && topDir.isDirectory() ? findDirectorySkillEntry(topDir) : null;
            // If deleting the entry file of the skill package, delete the entire package folder
            if (entry != null && clean.equalsIgnoreCase(topDirName + "/" + entry.getName())) {
                boolean ok = mVault.deleteSkillDir(slug);
                if (ok) {
                    mDisabledSkills.remove(normalizeSkillKey(topDirName));
                    saveDisabledState();
                    notifyChanged();
                }
                return ok;
            }
        }

        File target = new File(mSkillsDir, clean);
        if (target.exists() && !target.equals(mSkillsDir)) {
            boolean ok = target.isDirectory() ? SkillStorageVault.deleteRecursiveSafe(target) : target.delete();
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

    static boolean deleteRecursive(File fileOrDir) {
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

    static String stripBom(String s) {
        if (s != null && s.startsWith("\uFEFF")) {
            return s.substring(1);
        }
        return s != null ? s : "";
    }

    static String unquoteYaml(String s) {
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
    // =========================================================================
    // External Importer Facade (delegates to SkillPackageImporter)
    // =========================================================================

    public static class GitHubSkillCoordinates extends SkillPackageImporter.GitHubSkillCoordinates {
        public GitHubSkillCoordinates(String owner, String repo, String branch, String path, boolean isTreeOrFolder) {
            super(owner, repo, branch, path, isTreeOrFolder);
        }
    }

    public static GitHubSkillCoordinates parseGitHubUrl(String rawUrl) {
        SkillPackageImporter.GitHubSkillCoordinates c = SkillPackageImporter.parseGitHubUrl(rawUrl);
        if (c == null) return null;
        return new GitHubSkillCoordinates(c.owner, c.repo, c.branch, c.path, c.isTreeOrFolder);
    }

    public ImportResult importSkillFromUrl(String rawUrl) {
        return new SkillPackageImporter(this).importSkillFromUrl(rawUrl);
    }

    public synchronized ImportResult importFromZipBytes(byte[] zipBytes, String fallbackSlug) {
        return new SkillPackageImporter(this).importFromZipBytes(zipBytes, fallbackSlug);
    }
}
