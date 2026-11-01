package com.acid.browser;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Handles network downloads, GitHub repository/directory inspection, and ZIP extraction
 * for Agent Skills packages.
 *
 * <p>Extracted from {@link SkillManager} to decouple archive/network transport from local
 * skill catalog lifecycle.
 */
public class SkillPackageImporter {

    private static final String TAG = "AcidSkillImporter";
    public static final int MAX_IMPORT_FILES = 50;
    public static final long MAX_IMPORT_TOTAL_BYTES = 8L * 1024L * 1024L; // 8 MB safety cap

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

    private final SkillManager mManager;
    private final File mSkillsDir;
    private final SkillStorageVault mVault;

    public SkillPackageImporter(SkillManager manager) {
        mManager = manager;
        mSkillsDir = manager != null ? manager.getSkillsDir() : null;
        mVault = mSkillsDir != null ? new SkillStorageVault(mSkillsDir) : null;
    }

    /**
     * Parses GitHub URLs into repository and branch/path coordinates.
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
     * ZIP archive URL, or direct Markdown URL. Runs network I/O synchronously.
     */
    public SkillManager.ImportResult importSkillFromUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return SkillManager.ImportResult.error("链接不能为空");
        }
        String urlStr = rawUrl.trim();
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            urlStr = "https://" + urlStr;
        }

        try {
            GitHubSkillCoordinates gh = parseGitHubUrl(urlStr);
            if (gh != null) {
                if (gh.isTreeOrFolder) {
                    SkillManager.ImportResult folderRes = importFromGitHubDirectory(gh);
                    if (folderRes.success) {
                        return folderRes;
                    }
                    if (!urlStr.contains("/blob/")) {
                        return folderRes;
                    }
                }
                if (urlStr.contains("github.com/") && urlStr.contains("/blob/")) {
                    urlStr = urlStr.replaceFirst("https?://(www\\.)?github\\.com/", "https://raw.githubusercontent.com/")
                                   .replaceFirst("/blob/", "/");
                }
            }

            String cleanPath = new URL(urlStr).getPath().toLowerCase(Locale.US);
            if (cleanPath.endsWith(".zip") || urlStr.contains("/archive/refs/")) {
                return importFromZipUrl(urlStr);
            }

            byte[] data = downloadBytes(urlStr, MAX_IMPORT_TOTAL_BYTES);
            if (data == null || data.length == 0) {
                return SkillManager.ImportResult.error("下载内容为空");
            }
            if (data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4) {
                return importFromZipBytes(data, inferSkillSlugFromUrl(urlStr));
            }

            String content = new String(data, StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                return SkillManager.ImportResult.error("下载的文本内容为空");
            }

            String slug = inferSkillSlugFromUrlAndContent(urlStr, content);
            String targetFileName = slug.endsWith(".md") ? slug : (slug + ".md");
            if (SkillManager.isBuiltin(targetFileName)) {
                targetFileName = "custom_" + targetFileName;
            }
            if (mManager != null) {
                boolean saved = mManager.saveSkill(targetFileName, content);
                if (!saved) {
                    return SkillManager.ImportResult.error("写入本地技能文件失败");
                }
                SkillManager.SkillMeta meta = mManager.findSkillMetaForFile(new File(mSkillsDir, targetFileName));
                return SkillManager.ImportResult.ok(targetFileName, meta != null ? meta.title : targetFileName, 1);
            }
            return SkillManager.ImportResult.error("SkillManager 未就绪");

        } catch (Exception e) {
            Log.w(TAG, "importSkillFromUrl failed: " + urlStr, e);
            return SkillManager.ImportResult.error("导入失败: " + e.getMessage());
        }
    }

    public SkillManager.ImportResult importFromGitHubDirectory(GitHubSkillCoordinates gh) {
        if (mSkillsDir == null || mVault == null) return SkillManager.ImportResult.error("技能目录未就绪");
        try {
            String rawSlug = gh.path.isEmpty()
                ? gh.repo
                : gh.path.substring(gh.path.lastIndexOf('/') + 1);
            SkillSlug slug = SkillSlug.from(rawSlug, "imported_skill");
            String dirSlug = slug.value();

            File targetDir = mVault.resolveSkillDir(slug);
            int[] fileCounter = new int[] { 0 };
            long[] byteCounter = new long[] { 0L };

            fetchGitHubDirectoryRecursive(gh.owner, gh.repo, gh.branch, gh.path, targetDir, fileCounter, byteCounter);

            if (fileCounter[0] == 0) {
                return SkillManager.ImportResult.error("未在该 GitHub 目录下找到可下载的技能文件");
            }
            File entry = SkillManager.findDirectorySkillEntry(targetDir);
            if (entry == null) {
                mVault.deleteSkillDir(slug);
                return SkillManager.ImportResult.error("该目录下未包含 SKILL.md 或任何 .md 技能主文档");
            }

            if (mManager != null) mManager.notifyExternalMutation();
            SkillManager.SkillMeta meta = mManager != null ? mManager.findSkillMetaForFile(entry) : null;
            String relEntry = dirSlug + "/" + entry.getName();
            return SkillManager.ImportResult.ok(relEntry, meta != null ? meta.title : dirSlug, fileCounter[0]);
        } catch (Exception e) {
            return SkillManager.ImportResult.error("GitHub 目录拉取失败: " + e.getMessage());
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
            JSONObject obj = new JSONObject(jsonStr);
            if ("file".equals(obj.optString("type"))) {
                String dlUrl = obj.optString("download_url", "");
                String name = obj.optString("name", "SKILL.md");
                if (!dlUrl.isEmpty()) {
                    byte[] content = downloadBytes(dlUrl, MAX_IMPORT_TOTAL_BYTES - byteCounter[0]);
                    byteCounter[0] += content.length;
                    File outFile = new File(destDir, name);
                    writeBytesSafe(outFile, content);
                    fileCounter[0]++;
                }
            }
            return;
        }

        if (jsonStr.startsWith("[")) {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                if (fileCounter[0] >= MAX_IMPORT_FILES || byteCounter[0] >= MAX_IMPORT_TOTAL_BYTES) break;
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String type = item.optString("type", "");
                String name = item.optString("name", "");
                if (name.startsWith(".") || name.startsWith("__MACOSX")) continue;

                if ("file".equals(type)) {
                    String dlUrl = item.optString("download_url", "");
                    if (!dlUrl.isEmpty()) {
                        byte[] content = downloadBytes(dlUrl, MAX_IMPORT_TOTAL_BYTES - byteCounter[0]);
                        byteCounter[0] += content.length;
                        File outFile = new File(destDir, name);
                        writeBytesSafe(outFile, content);
                        fileCounter[0]++;
                    }
                } else if ("dir".equals(type)) {
                    String subPath = item.optString("path", "");
                    File subDest = new File(destDir, name);
                    fetchGitHubDirectoryRecursive(owner, repo, branch, subPath, subDest, fileCounter, byteCounter);
                }
            }
        }
    }

    public SkillManager.ImportResult importFromZipUrl(String urlStr) throws Exception {
        byte[] zipBytes = downloadBytes(urlStr, MAX_IMPORT_TOTAL_BYTES);
        return importFromZipBytes(zipBytes, inferSkillSlugFromUrl(urlStr));
    }

    public synchronized SkillManager.ImportResult importFromZipBytes(byte[] zipBytes, String fallbackSlug) {
        if (zipBytes == null || zipBytes.length == 0) {
            return SkillManager.ImportResult.error("ZIP 压缩包为空");
        }
        if (mSkillsDir == null || mVault == null) {
            return SkillManager.ImportResult.error("技能目录未就绪");
        }
        SkillSlug slug = SkillSlug.from(fallbackSlug, "imported_skill");

        File tempExtractDir = mVault.createStagingDir();

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
                String outCanon = out.getCanonicalPath();
                if (!outCanon.equals(tempCanon) && !outCanon.startsWith(tempCanon + File.separator)) {
                    zis.closeEntry();
                    continue; // Strict Zip-slip protection
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
                SkillStorageVault.deleteRecursiveSafe(tempExtractDir);
                return SkillManager.ImportResult.error("ZIP 包中未找到有效文件");
            }

            File sourceRoot = tempExtractDir;
            File[] topChildren = tempExtractDir.listFiles();
            if (topChildren != null && topChildren.length == 1 && topChildren[0].isDirectory()) {
                sourceRoot = topChildren[0];
                if ("imported_skill".equals(slug.value()) || slug.value().endsWith(".zip")) {
                    slug = SkillSlug.from(sourceRoot.getName(), slug.value());
                }
            }

            File entryMd = SkillManager.findDirectorySkillEntry(sourceRoot);
            if (entryMd == null) {
                SkillStorageVault.deleteRecursiveSafe(tempExtractDir);
                return SkillManager.ImportResult.error("ZIP 包中未找到 SKILL.md 或任何 .md 技能文档");
            }

            File finalDir = mVault.promoteStagingDir(sourceRoot, slug);
            if (!sourceRoot.equals(tempExtractDir)) {
                SkillStorageVault.deleteRecursiveSafe(tempExtractDir);
            }

            if (mManager != null) mManager.notifyExternalMutation();
            File finalEntry = SkillManager.findDirectorySkillEntry(finalDir);
            String relEntry = slug.value() + "/" + (finalEntry != null ? finalEntry.getName() : "SKILL.md");
            SkillManager.SkillMeta meta = (finalEntry != null && mManager != null)
                ? mManager.findSkillMetaForFile(finalEntry)
                : null;
            return SkillManager.ImportResult.ok(relEntry, meta != null ? meta.title : slug.value(), fileCount);
        } catch (Exception e) {
            SkillStorageVault.deleteRecursiveSafe(tempExtractDir);
            return SkillManager.ImportResult.error("解压技能包失败: " + e.getMessage());
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
                java.io.FileInputStream fis = new java.io.FileInputStream(src);
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    writeBytesSafe(dst, bos.toByteArray());
                } finally {
                    fis.close();
                }
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

            InputStream is = null;
            try {
                is = new BufferedInputStream(conn.getInputStream());
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) {
                        throw new Exception("文件超过最大大小限制 (" + (maxBytes / 1024 / 1024) + "MB)");
                    }
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Exception ignored) {}
                }
                conn.disconnect();
            }
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

    public static String inferSkillSlugFromUrl(String urlStr) {
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

    public static String inferSkillSlugFromUrlAndContent(String urlStr, String content) {
        if (content != null) {
            String trimmed = SkillManager.stripBom(content).trim();
            if (trimmed.startsWith("---")) {
                String[] lines = trimmed.split("\n");
                for (int i = 1; i < Math.min(lines.length, 20); i++) {
                    String l = lines[i].trim();
                    if ("---".equals(l) || "...".equals(l)) break;
                    if (l.toLowerCase(Locale.US).startsWith("name:")) {
                        String val = SkillManager.unquoteYaml(l.substring(5).trim());
                        String slug = sanitizeSlug(val);
                        if (!slug.isEmpty()) return slug;
                    }
                }
            }
        }
        return inferSkillSlugFromUrl(urlStr);
    }

    public static String sanitizeSlug(String raw) {
        return SkillSlug.sanitize(raw);
    }
}
