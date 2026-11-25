package com.acid.browser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.acid.browser.download.DownloadCoordinator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Coordinates WebDAV cloud synchronization and local full-environment {@code .zip} backup /
 * migration for Acid Browser.
 *
 * <p>Backed-up workspace modules:
 * <ul>
 *   <li>Bookmarks as standard Netscape {@code bookmarks.html} (interoperable with desktop Floccus)</li>
 *   <li>Sanitized browser preferences ({@code settings.json}, excluding secrets/tokens)</li>
 *   <li>Userscripts ({@code userscripts/**} &amp; {@code userscript_storage.json})</li>
 *   <li>AdBlock custom rules, cached subscriptions &amp; subscription URL ({@code adblock/**} &amp; {@code adblock_meta.json})</li>
 *   <li>Custom Homepage &amp; Agent Skills ({@code homepage/**}, {@code skills/**}, {@code skills_sync.json})</li>
 * </ul>
 *
 * <p>Explicitly excludes browsing history, cookies, cache, WebDAV credentials, LLM API keys, and
 * MCP auth tokens.
 */
public final class BrowserBackupManager {

    private static final String TAG = "AcidBackup";

    public static final int BACKUP_FORMAT_VERSION = 1;
    public static final String REMOTE_BOOKMARKS_FILE = "bookmarks.html";
    public static final String REMOTE_ARCHIVE_FILE = "acid_backup.zip";

    public static final int MAX_ZIP_BYTES = 32 * 1024 * 1024;
    public static final int MAX_SINGLE_ENTRY_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ZIP_ENTRIES = 500;

    private static final String ENTRY_MANIFEST = "manifest.json";
    private static final String ENTRY_BOOKMARKS = "bookmarks.html";
    private static final String ENTRY_SETTINGS = "settings.json";
    private static final String ENTRY_USERSCRIPT_STORAGE = "userscript_storage.json";
    private static final String ENTRY_ADBLOCK_META = "adblock_meta.json";
    private static final String ENTRY_SKILLS_SYNC = "skills_sync.json";
    private static final String ENTRY_ADBLOCK_CUSTOM = "adblock/custom_rules.txt";
    private static final String ENTRY_ADBLOCK_SUB = "adblock/subscriptions.txt";

    private BrowserBackupManager() {}

    public static final class WorkspaceTarget {
        public final File workspaceDir;
        public final BrowserDataStore dataStore;
        public final UserscriptManager userscriptManager;
        public final AdBlockManager adBlockManager;
        public final SkillManager skillManager;

        public WorkspaceTarget(
            File workspaceDir,
            BrowserDataStore dataStore,
            UserscriptManager userscriptManager,
            AdBlockManager adBlockManager,
            SkillManager skillManager
        ) {
            this.workspaceDir = workspaceDir;
            this.dataStore = dataStore;
            this.userscriptManager = userscriptManager;
            this.adBlockManager = adBlockManager;
            this.skillManager = skillManager;
        }

        public static WorkspaceTarget from(
            Activity activity,
            BrowserController controller,
            BrowserDataStore dataStore
        ) {
            File wsDir = controller != null ? controller.getWorkspaceDir()
                : (dataStore != null ? dataStore.getWorkspaceDir() : null);
            UserscriptManager um = controller != null ? controller.getUserscriptManager() : null;
            SkillManager sm = controller != null ? controller.getSkillManager() : null;
            AdBlockManager ab = activity != null ? AdBlockManager.getInstance(activity) : null;
            return new WorkspaceTarget(wsDir, dataStore, um, ab, sm);
        }
    }

    public static final class SyncSummary {
        public final boolean isUpload;
        public final boolean overwriteMode;
        public final boolean restoredBookmarks;
        public final boolean restoredSettings;
        public final boolean restoredUserscripts;
        public final boolean restoredAdBlock;
        public final boolean restoredSkillsHomepage;
        public final int bookmarkCount;
        public final int bookmarkSkippedDupCount;
        public final long archiveSizeBytes;
        public final boolean missingRemoteArchiveNotice;

        SyncSummary(
            boolean isUpload,
            boolean overwriteMode,
            boolean restoredBookmarks,
            boolean restoredSettings,
            boolean restoredUserscripts,
            boolean restoredAdBlock,
            boolean restoredSkillsHomepage,
            int bookmarkCount,
            int bookmarkSkippedDupCount,
            long archiveSizeBytes,
            boolean missingRemoteArchiveNotice
        ) {
            this.isUpload = isUpload;
            this.overwriteMode = overwriteMode;
            this.restoredBookmarks = restoredBookmarks;
            this.restoredSettings = restoredSettings;
            this.restoredUserscripts = restoredUserscripts;
            this.restoredAdBlock = restoredAdBlock;
            this.restoredSkillsHomepage = restoredSkillsHomepage;
            this.bookmarkCount = bookmarkCount;
            this.bookmarkSkippedDupCount = bookmarkSkippedDupCount;
            this.archiveSizeBytes = archiveSizeBytes;
            this.missingRemoteArchiveNotice = missingRemoteArchiveNotice;
        }

        public int getRestoredModuleCount() {
            int n = 0;
            if (restoredBookmarks) n++;
            if (restoredSettings) n++;
            if (restoredUserscripts) n++;
            if (restoredAdBlock) n++;
            if (restoredSkillsHomepage) n++;
            return n;
        }

        public String formatStatusLabel() {
            return isUpload ? "云端同步上传成功" : "从云端恢复完成";
        }
    }

    public interface BackupResultCallback {
        void onResult(boolean ok, SyncSummary summary, String message);
    }

    // =========================================================================
    // ZIP Creation
    // =========================================================================

    public static byte[] buildBackupZipBytes(
        WorkspaceTarget target,
        WebDavClient.SyncSelection sel
    ) throws IOException {
        if (target == null || target.dataStore == null || target.workspaceDir == null) {
            throw new IOException("浏览器工作区未就绪");
        }
        if (sel == null || !sel.hasAny()) {
            throw new IOException("请至少勾选一项要备份的内容");
        }

        if (sel.userscripts && target.userscriptManager != null && target.userscriptManager.getStorage() != null) {
            target.userscriptManager.getStorage().flushSync();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
        ZipOutputStream zos = new ZipOutputStream(baos);
        int[] stats = new int[2]; // [0] entryCount, [1] rawBytes

        try {
            JSONObject manifest = new JSONObject();
            JSONObject mods = new JSONObject();
            mods.put("bookmarks", sel.bookmarks);
            mods.put("settings", sel.settings);
            mods.put("userscripts", sel.userscripts);
            mods.put("adblock", sel.adblock);
            mods.put("skillsAndHomepage", sel.skillsAndHomepage);
            manifest.put("formatVersion", BACKUP_FORMAT_VERSION);
            manifest.put("appVersionCode", BuildInfo.VERSION_CODE);
            manifest.put("appVersionName", BuildInfo.VERSION_NAME);
            manifest.put("exportedAtMs", System.currentTimeMillis());
            manifest.put("modules", mods);
            writeZipBytesEntry(zos, ENTRY_MANIFEST, manifest.toString(2).getBytes(StandardCharsets.UTF_8), stats);

            if (sel.bookmarks) {
                String html = NetscapeBookmarkCodec.exportToHtml(target.dataStore.loadBookmarks());
                writeZipBytesEntry(zos, ENTRY_BOOKMARKS, html.getBytes(StandardCharsets.UTF_8), stats);
            }

            if (sel.settings) {
                JSONObject exported = target.dataStore.exportToJson();
                writeZipBytesEntry(zos, ENTRY_SETTINGS, exported.toString(2).getBytes(StandardCharsets.UTF_8), stats);
            }

            if (sel.userscripts) {
                File scriptsDir = new File(target.workspaceDir, "userscripts");
                writeDirectoryToZip(zos, scriptsDir, "userscripts", stats);
                File storageFile = new File(target.workspaceDir, "userscript_storage.json");
                if (storageFile.isFile()) {
                    writeZipFileEntry(zos, ENTRY_USERSCRIPT_STORAGE, storageFile, stats);
                }
            }

            if (sel.adblock) {
                JSONObject abMeta = new JSONObject();
                abMeta.put("adblock_subscription_url", target.dataStore.getAdBlockSubscriptionUrl());
                writeZipBytesEntry(zos, ENTRY_ADBLOCK_META, abMeta.toString(2).getBytes(StandardCharsets.UTF_8), stats);

                File customRules = new File(target.workspaceDir, "adblock/custom_rules.txt");
                if (customRules.isFile()) {
                    writeZipFileEntry(zos, ENTRY_ADBLOCK_CUSTOM, customRules, stats);
                }
                File subs = new File(target.workspaceDir, "adblock/subscriptions.txt");
                if (subs.isFile()) {
                    writeZipFileEntry(zos, ENTRY_ADBLOCK_SUB, subs, stats);
                }
            }

            if (sel.skillsAndHomepage) {
                File skillsDir = new File(target.workspaceDir, "skills");
                writeDirectoryToZip(zos, skillsDir, "skills", stats);
                File skillsSync = new File(target.workspaceDir, "skills_sync.json");
                if (skillsSync.isFile()) {
                    writeZipFileEntry(zos, ENTRY_SKILLS_SYNC, skillsSync, stats);
                }
                File homeDir = new File(target.workspaceDir, "homepage");
                writeDirectoryToZip(zos, homeDir, "homepage", stats);
            }
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException("生成备份包失败: " + e.getMessage(), e);
        } finally {
            AcidIo.closeQuietly(zos);
        }

        byte[] result = baos.toByteArray();
        if (result.length > MAX_ZIP_BYTES) {
            throw new IOException("备份包体积超过 32 MB 安全上限");
        }
        return result;
    }

    private static void writeDirectoryToZip(
        ZipOutputStream zos,
        File dir,
        String zipPrefix,
        int[] stats
    ) throws IOException {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String name = f.getName();
            if (name.contains(".tmp_") || ".DS_Store".equals(name)) continue;
            String entryPath = zipPrefix + "/" + name;
            if (f.isDirectory()) {
                writeDirectoryToZip(zos, f, entryPath, stats);
            } else if (f.isFile()) {
                writeZipFileEntry(zos, entryPath, f, stats);
            }
        }
    }

    private static void writeZipFileEntry(
        ZipOutputStream zos,
        String entryPath,
        File file,
        int[] stats
    ) throws IOException {
        long len = file.length();
        if (len > MAX_SINGLE_ENTRY_BYTES) {
            throw new IOException("单文件体积超过 8 MB 备份限制: " + entryPath);
        }
        byte[] data = readFileBytesCapped(file, MAX_SINGLE_ENTRY_BYTES);
        writeZipBytesEntry(zos, entryPath, data, stats);
    }

    private static void writeZipBytesEntry(
        ZipOutputStream zos,
        String entryPath,
        byte[] data,
        int[] stats
    ) throws IOException {
        stats[0]++;
        if (stats[0] > MAX_ZIP_ENTRIES) {
            throw new IOException("备份文件数量超过上限（最多 500 个）");
        }
        if (data.length > MAX_SINGLE_ENTRY_BYTES || (long) stats[1] + data.length > MAX_ZIP_BYTES) {
            throw new IOException("备份数据总体积超过 32 MB 限制");
        }
        stats[1] += data.length;
        ZipEntry entry = new ZipEntry(entryPath);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    // =========================================================================
    // ZIP Restore & Validation (Staging + Allowlist + Zip-Slip Protection)
    // =========================================================================

    public static SyncSummary restoreFromZipBytes(
        WorkspaceTarget target,
        byte[] zipBytes,
        WebDavClient.SyncSelection sel,
        boolean overwriteMode
    ) throws IOException {
        return restoreFromArchiveAndOptionalBookmarks(
            target, zipBytes, null, sel, overwriteMode, false
        );
    }

    private static SyncSummary restoreFromArchiveAndOptionalBookmarks(
        WorkspaceTarget target,
        byte[] zipBytes,
        byte[] standaloneBookmarksHtml,
        WebDavClient.SyncSelection sel,
        boolean overwriteMode,
        boolean missingRemoteArchiveNotice
    ) throws IOException {
        if (target == null || target.dataStore == null || target.workspaceDir == null) {
            throw new IOException("浏览器工作区未就绪");
        }
        if (sel == null || !sel.hasAny()) {
            throw new IOException("请至少勾选一项要恢复的内容");
        }
        if (!target.workspaceDir.exists() && !target.workspaceDir.mkdirs()) {
            throw new IOException("无法创建工作区目录");
        }

        File stagingDir = new File(
            target.workspaceDir,
            ".backup_staging_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000)
        );
        try {
            JSONObject manifestModules = null;
            if (zipBytes != null) {
                extractZipToStaging(zipBytes, stagingDir);
                manifestModules = validateStagingManifest(stagingDir);
            }

            // Pre-validate standalone / archive bookmarks & JSON files before mutating workspace
            List<BrowserDataStore.EntryItem> parsedBookmarks = null;
            boolean shouldRestoreBookmarks = false;
            if (sel.bookmarks) {
                if (standaloneBookmarksHtml != null) {
                    parsedBookmarks = parseBookmarksHtmlForRestore(
                        new String(standaloneBookmarksHtml, StandardCharsets.UTF_8), overwriteMode
                    );
                    shouldRestoreBookmarks = true;
                } else if (zipBytes != null && hasModuleInArchive(manifestModules, stagingDir, "bookmarks", ENTRY_BOOKMARKS)) {
                    File bmFile = new File(stagingDir, ENTRY_BOOKMARKS);
                    if (bmFile.isFile()) {
                        parsedBookmarks = parseBookmarksHtmlForRestore(AcidIo.readFileUtf8(bmFile), overwriteMode);
                        shouldRestoreBookmarks = true;
                    } else if (overwriteMode) {
                        parsedBookmarks = Collections.emptyList();
                        shouldRestoreBookmarks = true;
                    }
                }
            }

            JSONObject stagedSettingsJson = null;
            boolean shouldRestoreSettings = false;
            if (sel.settings && zipBytes != null && hasModuleInArchive(manifestModules, stagingDir, "settings", ENTRY_SETTINGS)) {
                File sf = new File(stagingDir, ENTRY_SETTINGS);
                if (sf.isFile()) {
                    try {
                        stagedSettingsJson = new JSONObject(AcidIo.readFileUtf8(sf));
                        shouldRestoreSettings = true;
                    } catch (Exception e) {
                        throw new IOException("备份中的 settings.json 格式无效", e);
                    }
                }
            }

            JSONObject stagedAdBlockMeta = null;
            boolean shouldRestoreAdBlock = sel.adblock && zipBytes != null
                && hasModuleInArchive(manifestModules, stagingDir, "adblock",
                    ENTRY_ADBLOCK_CUSTOM, ENTRY_ADBLOCK_SUB, ENTRY_ADBLOCK_META);
            if (shouldRestoreAdBlock) {
                File abMetaFile = new File(stagingDir, ENTRY_ADBLOCK_META);
                if (abMetaFile.isFile()) {
                    try {
                        stagedAdBlockMeta = new JSONObject(AcidIo.readFileUtf8(abMetaFile));
                    } catch (Exception e) {
                        throw new IOException("备份中的 adblock_meta.json 格式无效", e);
                    }
                }
            }

            // Mutate workspace cleanly module by module
            int bookmarkCount = 0;
            int bookmarkDupSkipped = 0;
            if (shouldRestoreBookmarks && parsedBookmarks != null) {
                if (overwriteMode) {
                    target.dataStore.saveBookmarks(parsedBookmarks);
                    bookmarkCount = parsedBookmarks.size();
                } else {
                    BookmarkRepository.ImportSummary bmRes = target.dataStore.importBookmarks(parsedBookmarks);
                    bookmarkCount = bmRes.addedCount;
                    bookmarkDupSkipped = bmRes.skippedDuplicateCount;
                }
            }

            if (shouldRestoreSettings && stagedSettingsJson != null) {
                target.dataStore.applyRestoredSettingsJson(stagedSettingsJson, overwriteMode);
            }

            boolean restoredUserscripts = false;
            if (sel.userscripts && zipBytes != null
                && hasModuleInArchive(manifestModules, stagingDir, "userscripts",
                    "userscripts", ENTRY_USERSCRIPT_STORAGE)) {
                applyRestoredUserscripts(target, stagingDir, overwriteMode);
                restoredUserscripts = true;
            }

            if (shouldRestoreAdBlock) {
                applyRestoredAdBlock(target, stagingDir, stagedAdBlockMeta, overwriteMode);
            }

            boolean restoredSkillsHomepage = false;
            if (sel.skillsAndHomepage && zipBytes != null
                && hasModuleInArchive(manifestModules, stagingDir, "skillsAndHomepage",
                    "skills", ENTRY_SKILLS_SYNC, "homepage")) {
                applyRestoredSkillsAndHomepage(target, stagingDir, overwriteMode);
                restoredSkillsHomepage = true;
            }

            return new SyncSummary(
                false,
                overwriteMode,
                shouldRestoreBookmarks,
                shouldRestoreSettings,
                restoredUserscripts,
                shouldRestoreAdBlock,
                restoredSkillsHomepage,
                bookmarkCount,
                bookmarkDupSkipped,
                zipBytes != null ? zipBytes.length : 0L,
                missingRemoteArchiveNotice
            );
        } finally {
            SkillStorageVault.deleteRecursiveSafe(stagingDir);
        }
    }

    private static void extractZipToStaging(byte[] zipBytes, File stagingDir) throws IOException {
        if (zipBytes.length == 0) {
            throw new IOException("备份包内容为空");
        }
        if (zipBytes.length > MAX_ZIP_BYTES) {
            throw new IOException("备份包体积超过 32 MB 安全限制");
        }
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw new IOException("无法创建临时解压目录");
        }
        String stagingRootCanon = stagingDir.getCanonicalPath();
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        byte[] buf = new byte[8192];
        int entryCount = 0;
        long totalUncompressed = 0L;

        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new IOException("备份包条目数量超过安全上限（最多 500 个）");
                }
                String name = validateAndNormalizeZipEntryName(entry.getName());
                if (name == null) {
                    // Skip benign OS metadata entries such as __MACOSX or .DS_Store
                    zis.closeEntry();
                    continue;
                }
                if (!isAllowedArchivePath(name)) {
                    throw new IOException("备份包包含未授权的文件路径：" + name);
                }

                File destFile = new File(stagingDir, name);
                String destCanon = destFile.getCanonicalPath();
                if (!destCanon.equals(stagingRootCanon)
                    && !destCanon.startsWith(stagingRootCanon + File.separator)) {
                    throw new IOException("检测到越权路径：" + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!destFile.exists() && !destFile.mkdirs()) {
                        throw new IOException("无法创建目录：" + name);
                    }
                    zis.closeEntry();
                    continue;
                }

                ByteArrayOutputStream entryBuf = new ByteArrayOutputStream();
                int entryBytes = 0;
                int n;
                while ((n = zis.read(buf)) != -1) {
                    entryBytes += n;
                    totalUncompressed += n;
                    if (entryBytes > MAX_SINGLE_ENTRY_BYTES) {
                        throw new IOException("单个备份文件超过 8 MB 限制：" + name);
                    }
                    if (totalUncompressed > MAX_ZIP_BYTES) {
                        throw new IOException("备份包解压总大小超过 32 MB 限制");
                    }
                    entryBuf.write(buf, 0, n);
                }
                AcidIo.writeBytes(destFile, entryBuf.toByteArray());
                zis.closeEntry();
            }
        } finally {
            AcidIo.closeQuietly(zis);
        }
    }

    private static String validateAndNormalizeZipEntryName(String rawName) throws IOException {
        if (rawName == null) {
            throw new IOException("备份包包含空文件名");
        }
        if (rawName.indexOf('\0') >= 0) {
            throw new IOException("备份包包含非法字符");
        }
        String clean = rawName.replace('\\', '/').trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isEmpty()) {
            throw new IOException("备份包包含非法根目录路径");
        }
        if (clean.startsWith("/") || (clean.length() >= 2 && clean.charAt(1) == ':')) {
            throw new IOException("检测到绝对路径越界：" + rawName);
        }
        String[] segs = clean.split("/", -1);
        for (int i = 0; i < segs.length; i++) {
            String s = segs[i];
            if (s.isEmpty() || ".".equals(s) || "..".equals(s)) {
                throw new IOException("检测到目录穿越路径：" + rawName);
            }
        }
        if (clean.startsWith("__MACOSX/") || clean.endsWith("/.DS_Store") || ".DS_Store".equals(clean)) {
            return null;
        }
        return clean;
    }

    private static boolean isAllowedArchivePath(String cleanName) {
        if (ENTRY_MANIFEST.equals(cleanName)
            || ENTRY_BOOKMARKS.equals(cleanName)
            || ENTRY_SETTINGS.equals(cleanName)
            || ENTRY_USERSCRIPT_STORAGE.equals(cleanName)
            || ENTRY_ADBLOCK_META.equals(cleanName)
            || ENTRY_SKILLS_SYNC.equals(cleanName)
            || ENTRY_ADBLOCK_CUSTOM.equals(cleanName)
            || ENTRY_ADBLOCK_SUB.equals(cleanName)) {
            return true;
        }
        return cleanName.startsWith("userscripts/")
            || "userscripts".equals(cleanName)
            || cleanName.startsWith("homepage/")
            || "homepage".equals(cleanName)
            || cleanName.startsWith("skills/")
            || "skills".equals(cleanName)
            || "adblock".equals(cleanName);
    }

    private static JSONObject validateStagingManifest(File stagingDir) throws IOException {
        File manifestFile = new File(stagingDir, ENTRY_MANIFEST);
        if (!manifestFile.isFile()) {
            throw new IOException("无效的 Acid 备份包：缺少 manifest.json");
        }
        try {
            JSONObject manifest = new JSONObject(AcidIo.readFileUtf8(manifestFile));
            int fmt = manifest.optInt("formatVersion", 0);
            if (fmt < 1 || fmt > BACKUP_FORMAT_VERSION) {
                throw new IOException("不支持的备份格式版本：v" + fmt);
            }
            return manifest.optJSONObject("modules");
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException("备份元数据 manifest.json 已损坏", e);
        }
    }

    private static boolean hasModuleInArchive(
        JSONObject modulesObj,
        File stagingDir,
        String moduleKey,
        String... relativePaths
    ) {
        if (modulesObj != null && modulesObj.optBoolean(moduleKey, false)) {
            return true;
        }
        for (int i = 0; i < relativePaths.length; i++) {
            if (new File(stagingDir, relativePaths[i]).exists()) {
                return true;
            }
        }
        return false;
    }

    private static List<BrowserDataStore.EntryItem> parseBookmarksHtmlForRestore(
        String htmlContent,
        boolean overwriteMode
    ) throws IOException {
        NetscapeBookmarkCodec.ImportParseResult parsed =
            NetscapeBookmarkCodec.parseImportContent(htmlContent);
        if (parsed.ok) {
            return parsed.items;
        }
        // An empty Netscape HTML export on overwrite mode is valid and yields an empty list
        if (overwriteMode && htmlContent != null) {
            String upper = htmlContent.toUpperCase(Locale.US);
            if (upper.contains("NETSCAPE-BOOKMARK-FILE-1")
                && "未在文件中识别到有效的书签记录".equals(parsed.errorMessage)) {
                return Collections.emptyList();
            }
        }
        throw new IOException(parsed.errorMessage != null ? parsed.errorMessage : "书签文件解析失败");
    }

    private static void applyRestoredUserscripts(
        WorkspaceTarget target,
        File stagingDir,
        boolean overwriteMode
    ) throws IOException {
        if (target.userscriptManager != null && target.userscriptManager.getStorage() != null) {
            target.userscriptManager.getStorage().flushSync();
        }
        File stagedScripts = new File(stagingDir, "userscripts");
        File localScripts = new File(target.workspaceDir, "userscripts");
        if (overwriteMode) {
            SkillStorageVault.deleteRecursiveSafe(localScripts);
            if (!localScripts.exists() && !localScripts.mkdirs()) {
                throw new IOException("无法重建 userscripts 目录");
            }
            if (stagedScripts.isDirectory()) {
                copyDirectoryOverlay(stagedScripts, localScripts, null);
            }
        } else if (stagedScripts.isDirectory()) {
            if (!localScripts.exists() && !localScripts.mkdirs()) {
                throw new IOException("无法创建 userscripts 目录");
            }
            copyDirectoryOverlay(stagedScripts, localScripts, null);
        }

        File stagedStorage = new File(stagingDir, ENTRY_USERSCRIPT_STORAGE);
        File localStorage = new File(target.workspaceDir, "userscript_storage.json");
        if (overwriteMode) {
            if (stagedStorage.isFile()) {
                AcidIo.copyFile(stagedStorage, localStorage);
            } else {
                AcidIo.writeFileUtf8(localStorage, "{}");
            }
        } else if (stagedStorage.isFile()) {
            mergeJsonObjectFile(stagedStorage, localStorage);
        }

        if (target.userscriptManager != null) {
            target.userscriptManager.reloadFromDisk();
        }
    }

    private static void applyRestoredAdBlock(
        WorkspaceTarget target,
        File stagingDir,
        JSONObject abMeta,
        boolean overwriteMode
    ) throws IOException {
        if (abMeta != null && abMeta.has("adblock_subscription_url")) {
            String subUrl = abMeta.optString("adblock_subscription_url", "");
            if (overwriteMode || !subUrl.trim().isEmpty()) {
                target.dataStore.setAdBlockSubscriptionUrl(subUrl);
            }
        }

        File localAdDir = new File(target.workspaceDir, "adblock");
        if (!localAdDir.exists() && !localAdDir.mkdirs()) {
            throw new IOException("无法创建 adblock 目录");
        }
        File stagedCustom = new File(stagingDir, ENTRY_ADBLOCK_CUSTOM);
        File localCustom = new File(localAdDir, "custom_rules.txt");
        if (overwriteMode) {
            if (stagedCustom.isFile()) {
                AcidIo.copyFile(stagedCustom, localCustom);
            } else {
                AcidIo.writeFileUtf8(localCustom, "");
            }
        } else if (stagedCustom.isFile()) {
            mergeAdBlockRulesFile(stagedCustom, localCustom);
        }

        File stagedSub = new File(stagingDir, ENTRY_ADBLOCK_SUB);
        File localSub = new File(localAdDir, "subscriptions.txt");
        if (overwriteMode) {
            if (stagedSub.isFile()) {
                AcidIo.copyFile(stagedSub, localSub);
            } else if (localSub.exists()) {
                localSub.delete();
            }
        } else if (stagedSub.isFile()) {
            AcidIo.copyFile(stagedSub, localSub);
        }

        if (target.adBlockManager != null) {
            target.adBlockManager.syncFromDataStore();
            target.adBlockManager.loadAllRulesSync();
        }
    }

    private static void applyRestoredSkillsAndHomepage(
        WorkspaceTarget target,
        File stagingDir,
        boolean overwriteMode
    ) throws IOException {
        // 1. Homepage
        File stagedHome = new File(stagingDir, "homepage");
        File localHome = new File(target.workspaceDir, "homepage");
        if (overwriteMode) {
            SkillStorageVault.deleteRecursiveSafe(localHome);
            if (!localHome.exists() && !localHome.mkdirs()) {
                throw new IOException("无法重建 homepage 目录");
            }
            if (stagedHome.isDirectory()) {
                copyDirectoryOverlay(stagedHome, localHome, null);
            }
            File homeIndex = new File(localHome, "index.html");
            if (!homeIndex.isFile() || homeIndex.length() == 0) {
                target.dataStore.syncDefaultHomepage();
            }
        } else if (stagedHome.isDirectory()) {
            if (!localHome.exists() && !localHome.mkdirs()) {
                throw new IOException("无法创建 homepage 目录");
            }
            copyDirectoryOverlay(stagedHome, localHome, null);
        }

        // 2. Skills & Disabled state & Sync metadata
        File stagedSkills = new File(stagingDir, "skills");
        File localSkills = new File(target.workspaceDir, "skills");
        if (overwriteMode) {
            SkillStorageVault.deleteRecursiveSafe(localSkills);
            if (!localSkills.exists() && !localSkills.mkdirs()) {
                throw new IOException("无法重建 skills 目录");
            }
            if (stagedSkills.isDirectory()) {
                copyDirectoryOverlay(stagedSkills, localSkills, null);
            }
        } else if (stagedSkills.isDirectory()) {
            if (!localSkills.exists() && !localSkills.mkdirs()) {
                throw new IOException("无法创建 skills 目录");
            }
            copyDirectoryOverlay(stagedSkills, localSkills, ".disabled_skills.json");
            File stagedDisabled = new File(stagedSkills, ".disabled_skills.json");
            File localDisabled = new File(localSkills, ".disabled_skills.json");
            if (stagedDisabled.isFile()) {
                mergeJsonArrayUnionFile(stagedDisabled, localDisabled);
            }
        }

        File stagedSync = new File(stagingDir, ENTRY_SKILLS_SYNC);
        File localSync = new File(target.workspaceDir, "skills_sync.json");
        if (overwriteMode) {
            if (stagedSync.isFile()) {
                AcidIo.copyFile(stagedSync, localSync);
            } else if (localSync.exists()) {
                localSync.delete();
            }
        } else if (stagedSync.isFile()) {
            mergeJsonObjectFile(stagedSync, localSync);
        }

        if (target.skillManager != null) {
            target.skillManager.reloadFromDisk();
        }
    }

    // =========================================================================
    // File Merge Helpers
    // =========================================================================

    private static void copyDirectoryOverlay(
        File srcDir,
        File dstDir,
        String skipTopLevelName
    ) throws IOException {
        File[] children = srcDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (skipTopLevelName != null && skipTopLevelName.equals(name)) {
                continue;
            }
            File target = new File(dstDir, name);
            if (child.isDirectory()) {
                if (!target.exists() && !target.mkdirs()) {
                    throw new IOException("无法创建目录: " + target.getName());
                }
                copyDirectoryOverlay(child, target, null);
            } else if (child.isFile()) {
                AcidIo.copyFile(child, target);
            }
        }
    }

    private static void mergeJsonObjectFile(File backupFile, File localFile) throws IOException {
        try {
            JSONObject merged = localFile.isFile()
                ? new JSONObject(AcidIo.readFileUtf8(localFile))
                : new JSONObject();
            JSONObject incoming = new JSONObject(AcidIo.readFileUtf8(backupFile));
            Iterator<String> keys = incoming.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                merged.put(k, incoming.get(k));
            }
            AcidIo.writeFileUtf8(localFile, merged.toString(2));
        } catch (Exception e) {
            throw new IOException("合并 JSON 文件失败 (" + localFile.getName() + "): " + e.getMessage(), e);
        }
    }

    private static void mergeJsonArrayUnionFile(File backupFile, File localFile) throws IOException {
        try {
            Set<String> items = new LinkedHashSet<String>();
            if (localFile.isFile()) {
                JSONArray a = new JSONArray(AcidIo.readFileUtf8(localFile));
                for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i, "").trim();
                    if (!s.isEmpty()) items.add(s);
                }
            }
            JSONArray b = new JSONArray(AcidIo.readFileUtf8(backupFile));
            for (int i = 0; i < b.length(); i++) {
                String s = b.optString(i, "").trim();
                if (!s.isEmpty()) items.add(s);
            }
            JSONArray out = new JSONArray();
            for (String s : items) {
                out.put(s);
            }
            AcidIo.writeFileUtf8(localFile, out.toString());
        } catch (Exception e) {
            throw new IOException("合并列表状态失败: " + e.getMessage(), e);
        }
    }

    private static void mergeAdBlockRulesFile(File backupFile, File localFile) throws IOException {
        List<String> lines = new ArrayList<String>();
        Set<String> seenRules = new LinkedHashSet<String>();
        if (localFile.isFile()) {
            collectRuleLines(AcidIo.readFileUtf8(localFile), lines, seenRules, true);
        }
        collectRuleLines(AcidIo.readFileUtf8(backupFile), lines, seenRules, !localFile.isFile());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        AcidIo.writeFileUtf8(localFile, sb.toString());
    }

    private static void collectRuleLines(
        String raw,
        List<String> outLines,
        Set<String> seenRules,
        boolean keepComments
    ) {
        if (raw == null || raw.isEmpty()) return;
        String[] split = raw.split("\\r?\\n");
        for (int i = 0; i < split.length; i++) {
            String line = split[i].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("!") || line.startsWith("#")) {
                if (keepComments) {
                    outLines.add(line);
                }
                continue;
            }
            if (seenRules.add(line)) {
                outLines.add(line);
            }
        }
    }

    // =========================================================================
    // WebDAV Cloud Sync (Synchronous for Tests & Async for UI)
    // =========================================================================

    public static SyncSummary uploadToWebDav(
        WorkspaceTarget target,
        WebDavClient.WebDavConfig cfg,
        WebDavClient.SyncSelection sel
    ) throws IOException {
        if (target == null || target.dataStore == null) {
            throw new IOException("浏览器数据尚未初始化");
        }
        if (sel == null || !sel.hasAny()) {
            throw new IOException("请至少勾选一项要同步的内容");
        }
        WebDavClient.validateConfigOrThrow(cfg);
        WebDavClient.ensureRemoteDirectory(cfg);

        int bmCount = 0;
        if (sel.bookmarks) {
            List<BrowserDataStore.EntryItem> list = target.dataStore.loadBookmarks();
            bmCount = list.size();
            String html = NetscapeBookmarkCodec.exportToHtml(list);
            WebDavClient.putBytes(
                cfg, REMOTE_BOOKMARKS_FILE, "text/html; charset=utf-8",
                html.getBytes(StandardCharsets.UTF_8)
            );
        }

        long zipBytes = 0L;
        if (sel.hasArchiveModules()) {
            byte[] archive = buildBackupZipBytes(target, sel);
            zipBytes = archive.length;
            WebDavClient.putBytes(cfg, REMOTE_ARCHIVE_FILE, "application/zip", archive);
        }

        return new SyncSummary(
            true,
            false,
            sel.bookmarks,
            sel.settings,
            sel.userscripts,
            sel.adblock,
            sel.skillsAndHomepage,
            bmCount,
            0,
            zipBytes,
            false
        );
    }

    public static SyncSummary restoreFromWebDav(
        WorkspaceTarget target,
        WebDavClient.WebDavConfig cfg,
        WebDavClient.SyncSelection sel,
        boolean overwriteMode
    ) throws IOException {
        if (target == null || target.dataStore == null) {
            throw new IOException("浏览器数据尚未初始化");
        }
        if (sel == null || !sel.hasAny()) {
            throw new IOException("请至少勾选一项要恢复的内容");
        }
        WebDavClient.validateConfigOrThrow(cfg);

        byte[] standaloneBookmarks = null;
        if (sel.bookmarks) {
            try {
                standaloneBookmarks = WebDavClient.getBytes(
                    cfg, REMOTE_BOOKMARKS_FILE, NetscapeBookmarkCodec.MAX_IMPORT_BYTES
                );
            } catch (WebDavClient.WebDavException wde) {
                if (wde.statusCode != 404) {
                    throw wde;
                }
            }
        }

        byte[] zipBytes = null;
        boolean needZip = sel.hasArchiveModules() || (sel.bookmarks && standaloneBookmarks == null);
        boolean missingArchiveNotice = false;
        if (needZip) {
            try {
                zipBytes = WebDavClient.getBytes(cfg, REMOTE_ARCHIVE_FILE, MAX_ZIP_BYTES);
            } catch (WebDavClient.WebDavException wde) {
                if (wde.statusCode == 404) {
                    if (standaloneBookmarks == null) {
                        throw new WebDavClient.WebDavException(
                            404, "云端目录中未找到备份文件（acid_backup.zip 或 bookmarks.html）", wde
                        );
                    }
                    // Remote folder only has bookmarks.html (e.g. Floccus-only setup)
                    missingArchiveNotice = sel.hasArchiveModules();
                } else {
                    throw wde;
                }
            }
        }

        return restoreFromArchiveAndOptionalBookmarks(
            target, zipBytes, standaloneBookmarks, sel, overwriteMode, missingArchiveNotice
        );
    }

    // =========================================================================
    // UI-Integrated Async Helpers (Local .zip Export/Import & Cloud Sync)
    // =========================================================================

    public static void testConnectionAsync(
        final Activity activity,
        final WebDavClient.WebDavConfig cfg,
        final BackupResultCallback callback
    ) {
        AcidAsync.run(new Runnable() {
            @Override
            public void run() {
                try {
                    final String msg = WebDavClient.testConnection(cfg);
                    deliverCallback(activity, callback, true, null, msg);
                } catch (final Exception e) {
                    Log.w(TAG, "WebDAV connection test failed", e);
                    deliverCallback(activity, callback, false, null, friendlyError(e));
                }
            }
        });
    }

    public static void uploadToWebDavAsync(
        final Activity activity,
        final WorkspaceTarget target,
        final WebDavClient.WebDavConfig cfg,
        final WebDavClient.SyncSelection sel,
        final BackupResultCallback callback
    ) {
        AcidAsync.run(new Runnable() {
            @Override
            public void run() {
                try {
                    final SyncSummary summary = uploadToWebDav(target, cfg, sel);
                    if (activity != null) {
                        WebDavClient.saveLastSyncStatus(activity, summary.formatStatusLabel());
                    }
                    deliverCallback(activity, callback, true, summary, formatSyncSummaryMessage(summary));
                } catch (final Exception e) {
                    Log.w(TAG, "WebDAV upload failed", e);
                    deliverCallback(activity, callback, false, null, friendlyError(e));
                }
            }
        });
    }

    public static void restoreFromWebDavAsync(
        final Activity activity,
        final WorkspaceTarget target,
        final WebDavClient.WebDavConfig cfg,
        final WebDavClient.SyncSelection sel,
        final boolean overwriteMode,
        final Runnable onRestoredUiRefresh,
        final BackupResultCallback callback
    ) {
        AcidAsync.run(new Runnable() {
            @Override
            public void run() {
                try {
                    final SyncSummary summary = restoreFromWebDav(target, cfg, sel, overwriteMode);
                    if (activity != null) {
                        WebDavClient.saveLastSyncStatus(activity, summary.formatStatusLabel());
                    }
                    runPostRestoreAndDeliver(
                        activity, onRestoredUiRefresh, callback, summary, formatSyncSummaryMessage(summary)
                    );
                } catch (final Exception e) {
                    Log.w(TAG, "WebDAV restore failed", e);
                    deliverCallback(activity, callback, false, null, friendlyError(e));
                }
            }
        });
    }

    public static void exportLocalZipAndShare(
        final Activity activity,
        final BrowserController controller,
        final WorkspaceTarget target,
        final WebDavClient.SyncSelection sel,
        final boolean isDarkMode,
        final BackupResultCallback callback
    ) {
        if (activity == null) return;
        AcidAsync.run(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] zipBytes = buildBackupZipBytes(target, sel);
                    File destDir = BookmarkTransferHelper.resolveExportDir(activity, controller);
                    if (destDir == null) {
                        throw new IOException("无法定位本地下载目录");
                    }
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    String fileName = DownloadCoordinator.resolveUniqueFileName(
                        destDir, "acid_backup_" + stamp + ".zip"
                    );
                    final File outFile = new File(destDir, fileName);
                    AcidIo.writeBytes(outFile, zipBytes);
                    WebDavClient.saveLastSyncStatus(activity, "本地备份包导出成功");

                    final long kb = Math.max(1L, zipBytes.length / 1024L);
                    BookmarkTransferHelper.runIfAlive(activity, new Runnable() {
                        @Override
                        public void run() {
                            String body = AcidLocale.tr("已导出整套环境备份包（")
                                + kb + " KB"
                                + AcidLocale.tr("）至：")
                                + "\n" + outFile.getAbsolutePath();
                            BookmarkTransferHelper.promptExportShare(
                                activity, isDarkMode,
                                "本地备份导出成功", body,
                                outFile, "application/zip", "分享备份压缩包"
                            );
                            if (callback != null) {
                                callback.onResult(true, null, "已导出至 Download/" + outFile.getName());
                            }
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "Local ZIP export failed", e);
                    deliverCallback(activity, callback, false, null, friendlyError(e));
                }
            }
        });
    }

    public static void startZipImportPicker(Activity activity, int requestCode) {
        BookmarkTransferHelper.startDocumentPicker(activity, requestCode, "选择完整备份压缩包 (.zip)");
    }

    public static void handleZipImportResult(
        final Activity activity,
        final WorkspaceTarget target,
        final WebDavClient.SyncSelection sel,
        final boolean overwriteMode,
        int resultCode,
        Intent data,
        final Runnable onRestoredUiRefresh,
        final BackupResultCallback callback
    ) {
        final Uri targetUri = BookmarkTransferHelper.validatePickedUri(activity, resultCode, data);
        if (targetUri == null) return;

        AcidAsync.run(new Runnable() {
            @Override
            public void run() {
                InputStream is = null;
                try {
                    is = activity.getContentResolver().openInputStream(targetUri);
                    if (is == null) {
                        throw new IOException("无法读取选中的备份包");
                    }
                    byte[] zipBytes = AcidIo.readBytesCapped(is, MAX_ZIP_BYTES);
                    SyncSummary summary = restoreFromZipBytes(target, zipBytes, sel, overwriteMode);
                    WebDavClient.saveLastSyncStatus(activity, "本地备份包恢复完成");
                    runPostRestoreAndDeliver(
                        activity, onRestoredUiRefresh, callback, summary, formatSyncSummaryMessage(summary)
                    );
                } catch (final Exception e) {
                    Log.w(TAG, "Local ZIP import failed", e);
                    deliverCallback(activity, callback, false, null, friendlyError(e));
                } finally {
                    AcidIo.closeQuietly(is);
                }
            }
        });
    }

    private static void runPostRestoreAndDeliver(
        final Activity activity,
        final Runnable onRestoredUiRefresh,
        final BackupResultCallback callback,
        final SyncSummary summary,
        final String message
    ) {
        if (activity == null) {
            if (onRestoredUiRefresh != null) onRestoredUiRefresh.run();
            if (callback != null) callback.onResult(true, summary, message);
            return;
        }
        BookmarkTransferHelper.runIfAlive(activity, new Runnable() {
            @Override
            public void run() {
                if (onRestoredUiRefresh != null) {
                    onRestoredUiRefresh.run();
                }
                if (callback != null) {
                    callback.onResult(true, summary, message);
                }
            }
        });
    }

    private static void deliverCallback(
        Activity activity,
        final BackupResultCallback callback,
        final boolean ok,
        final SyncSummary summary,
        final String message
    ) {
        if (callback == null) return;
        if (activity == null) {
            callback.onResult(ok, summary, message);
            return;
        }
        BookmarkTransferHelper.runIfAlive(activity, new Runnable() {
            @Override
            public void run() {
                callback.onResult(ok, summary, message);
            }
        });
    }

    static String formatSyncSummaryMessage(SyncSummary s) {
        if (s == null) return "";
        if (s.isUpload) {
            return s.restoredBookmarks
                ? ("云端上传完成（" + s.bookmarkCount + " 条书签）")
                : "云端上传完成";
        }
        int modCount = s.getRestoredModuleCount();
        if (modCount == 0) {
            return "备份包中未包含所选模块数据";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已恢复 ").append(modCount).append(" 项模块数据");
        if (s.restoredBookmarks) {
            sb.append(s.overwriteMode ? " · 书签 " : " · 新增书签 ").append(s.bookmarkCount);
        }
        if (s.missingRemoteArchiveNotice) {
            sb.append("（云端暂无完整包，仅恢复书签）");
        }
        return sb.toString();
    }

    private static String friendlyError(Exception e) {
        String msg = e != null ? e.getMessage() : null;
        return (msg != null && !msg.trim().isEmpty()) ? msg : "操作失败，请稍后重试";
    }

    private static byte[] readFileBytesCapped(File file, int maxBytes) throws IOException {
        return AcidIo.readBytesCapped(new FileInputStream(file), maxBytes);
    }
}
