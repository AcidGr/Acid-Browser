package com.acid.browser;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages on-demand Markdown skills stored under {@code acid_workspace/skills/}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Synchronizes builtin skills from APK assets ({@code userscript_dev.md}, {@code homepage_dev.md}).</li>
 *   <li>Parses skill metadata (title, summary) from Markdown headers for UI listing.</li>
 *   <li>Builds dynamic Skill Catalog for Acid Agent's System Prompt on-demand progressive disclosure.</li>
 *   <li>Provides CRUD operations for third-party user skills.</li>
 * </ul>
 */
public class SkillManager {

    private static final String TAG = "AcidSkillManager";

    public static final String SKILL_USERSCRIPT = "userscript_dev.md";
    public static final String SKILL_HOMEPAGE = "homepage_dev.md";

    private static final Set<String> BUILTIN_SKILLS = new HashSet<String>();
    static {
        BUILTIN_SKILLS.add(SKILL_USERSCRIPT);
        BUILTIN_SKILLS.add(SKILL_HOMEPAGE);
    }

    public interface OnSkillsChangedListener {
        void onSkillsChanged();
    }

    public static class SkillMeta {
        public final String fileName;
        public final String name;
        public final String title;
        public final String description;
        public final boolean isBuiltin;
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
            this.fileName = fileName;
            this.name = name;
            this.title = title;
            this.description = description;
            this.isBuiltin = isBuiltin;
            this.lastModified = lastModified;
            this.fileSize = fileSize;
        }
    }

    private final Context mContext;
    private final File mWorkspaceDir;
    private final File mSkillsDir;
    private final List<OnSkillsChangedListener> mListeners = new ArrayList<OnSkillsChangedListener>();

    public SkillManager(Context context, File workspaceDir) {
        mContext = context.getApplicationContext();
        mWorkspaceDir = workspaceDir;
        mSkillsDir = new File(workspaceDir, "skills");
        if (!mSkillsDir.exists()) {
            mSkillsDir.mkdirs();
        }
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

    /**
     * Synchronizes bundled default skills from assets if missing or empty.
     */
    public synchronized void syncDefaultSkills() {
        for (String skillFile : BUILTIN_SKILLS) {
            File target = new File(mSkillsDir, skillFile);
            if (!target.exists() || target.length() == 0) {
                String assetContent = readAssetText("skills/" + skillFile);
                if (!assetContent.isEmpty()) {
                    writeFileText(target, assetContent);
                }
            }
        }
    }

    /**
     * Resets a builtin skill to its initial asset version.
     */
    public synchronized boolean resetBuiltinSkill(String fileName) {
        if (!isBuiltin(fileName)) return false;
        String assetContent = readAssetText("skills/" + fileName);
        if (assetContent.isEmpty()) return false;
        File target = new File(mSkillsDir, fileName);
        boolean ok = writeFileText(target, assetContent);
        if (ok) notifyChanged();
        return ok;
    }

    public static boolean isBuiltin(String fileName) {
        return fileName != null && BUILTIN_SKILLS.contains(fileName.trim().toLowerCase());
    }

    /**
     * Lists all Markdown skills under {@code skills/} sorted with builtins first.
     */
    public synchronized List<SkillMeta> getSkills() {
        List<SkillMeta> result = new ArrayList<SkillMeta>();
        File[] files = mSkillsDir.listFiles();
        if (files == null) return result;

        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".md")) {
                result.add(parseSkillMeta(f));
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

    /**
     * Reads the full text of a skill file.
     */
    public synchronized String readSkill(String fileName) {
        File file = new File(mSkillsDir, fileName);
        if (!file.exists() || !file.isFile()) return "";
        return readFileText(file);
    }

    /**
     * Creates or updates a skill Markdown file.
     */
    public synchronized boolean saveSkill(String fileName, String content) {
        if (fileName == null || fileName.trim().isEmpty()) return false;
        String cleanName = fileName.trim();
        if (!cleanName.endsWith(".md")) {
            cleanName += ".md";
        }
        File target = new File(mSkillsDir, cleanName);
        boolean ok = writeFileText(target, content != null ? content : "");
        if (ok) {
            notifyChanged();
        }
        return ok;
    }

    /**
     * Deletes a third-party skill. Builtin skills cannot be deleted.
     */
    public synchronized boolean deleteSkill(String fileName) {
        if (isBuiltin(fileName)) {
            Log.w(TAG, "Cannot delete builtin skill: " + fileName);
            return false;
        }
        File target = new File(mSkillsDir, fileName);
        if (target.exists() && target.isFile()) {
            boolean ok = target.delete();
            if (ok) notifyChanged();
            return ok;
        }
        return false;
    }

    /**
     * Builds dynamic Markdown catalog for the Agent's system prompt.
     */
    public synchronized String buildSystemPromptCatalog() {
        List<SkillMeta> skills = getSkills();
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## On-Demand Skills\n");
        sb.append("When handling tasks matching the following specialized domains, you MUST FIRST call `read(file_path='skills/<file>')` to load the complete skill rules and guidelines before generating output or code:\n");
        for (SkillMeta m : skills) {
            sb.append("- `skills/").append(m.fileName).append("`: **")
              .append(m.title).append("** — ")
              .append(m.description).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private SkillMeta parseSkillMeta(File file) {
        String fileName = file.getName();
        String name = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        boolean builtin = isBuiltin(fileName);

        String title = name;
        String description = builtin ? "内置核心技能" : "自定义扩展技能";

        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            int count = 0;
            boolean foundTitle = false;
            while ((line = br.readLine()) != null && count < 15) {
                count++;
                line = line.trim();
                if (line.isEmpty()) continue;

                if (!foundTitle && line.startsWith("#")) {
                    // Extract title from "# Skill: Custom Homepage..." or "# Custom Homepage..."
                    String rawTitle = line.replaceFirst("^#+\\s*", "");
                    if (rawTitle.toLowerCase().startsWith("skill:")) {
                        rawTitle = rawTitle.substring(6).trim();
                    }
                    if (!rawTitle.isEmpty()) {
                        title = rawTitle;
                        foundTitle = true;
                        continue;
                    }
                }

                if (foundTitle && (line.startsWith(">") || (!line.startsWith("#") && !line.startsWith("-")))) {
                    String desc = line.replaceFirst("^>\\s*", "");
                    if (desc.length() > 120) {
                        desc = desc.substring(0, 117) + "...";
                    }
                    if (!desc.isEmpty()) {
                        description = desc;
                        break;
                    }
                }
            }
            br.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse skill meta: " + fileName, e);
        }

        return new SkillMeta(fileName, name, title, description, builtin, file.lastModified(), file.length());
    }

    private String readAssetText(String assetPath) {
        try {
            InputStream is = mContext.getAssets().open(assetPath);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "Failed to read asset: " + assetPath, e);
            return "";
        }
    }

    private String readFileText(File file) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "Failed to read file: " + file.getAbsolutePath(), e);
            return "";
        }
    }

    private boolean writeFileText(File file, String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            osw.write(text);
            osw.flush();
            osw.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write file: " + file.getAbsolutePath(), e);
            return false;
        }
    }
}
