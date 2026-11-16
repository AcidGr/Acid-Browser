package com.acid.browser;

import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates Agent/MCP workspace file uploads cleanly without expanding WebView UI logic.
 *
 * <p>Owns two focused responsibilities:
 * <ul>
 *   <li>Single-source-of-truth workspace upload policy: restricts uploads strictly to regular
 *       files inside {@code acid_workspace/} while denying anything protected from workspace
 *       cleanup ({@link #isProtectedWorkspaceName(String)}).</li>
 *   <li>One-shot per-tab armed upload slots consumed by {@link BrowserController} when
 *       {@code onShowFileChooser} fires, supporting both single and {@code multiple} file inputs.</li>
 * </ul>
 */
public class AgentUploadManager {

    public static final long DEFAULT_ARM_TTL_MS = 10_000L;
    public static final int MAX_UPLOAD_FILES = 50;

    private static final Set<String> PROTECTED_WORKSPACE_NAMES;
    static {
        Set<String> s = new HashSet<String>();
        s.add("settings.json");
        s.add("bookmarks.json");
        s.add("history.json");
        s.add("homepage");
        s.add("userscripts");
        s.add("adblock");
        s.add("skills");
        s.add("skills_sync.json");
        s.add("userscript_storage.json");
        PROTECTED_WORKSPACE_NAMES = Collections.unmodifiableSet(s);
    }

    /**
     * Single source of truth for workspace entries preserved by workspace cleanup and blocked
     * from Agent/MCP file uploads.
     */
    public static boolean isProtectedWorkspaceName(String topLevelName) {
        return topLevelName != null && PROTECTED_WORKSPACE_NAMES.contains(topLevelName);
    }

    public static final class UploadResolution {
        public final boolean ok;
        public final List<File> files;
        public final String errorMessage;

        private UploadResolution(boolean ok, List<File> files, String errorMessage) {
            this.ok = ok;
            this.files = files != null ? files : Collections.<File>emptyList();
            this.errorMessage = errorMessage;
        }

        public static UploadResolution success(List<File> files) {
            return new UploadResolution(true, Collections.unmodifiableList(new ArrayList<File>(files)), null);
        }

        public static UploadResolution error(String message) {
            return new UploadResolution(false, null, message);
        }
    }

    public static final class WorkspaceFileResolution {
        public final boolean ok;
        public final File file;
        public final String relativePath;
        public final String errorMessage;

        private WorkspaceFileResolution(boolean ok, File file, String relativePath, String errorMessage) {
            this.ok = ok;
            this.file = file;
            this.relativePath = relativePath;
            this.errorMessage = errorMessage;
        }

        public static WorkspaceFileResolution success(File file, String relativePath) {
            return new WorkspaceFileResolution(true, file, relativePath, null);
        }

        public static WorkspaceFileResolution error(String message) {
            return new WorkspaceFileResolution(false, null, null, message);
        }
    }

    public static final class ConsumeResult {
        public final boolean triggered;
        public final boolean accepted;
        public final boolean multipleAllowed;
        public final String errorCode;
        public final String errorMessage;
        public final List<String> fileNames;

        private ConsumeResult(
            boolean triggered,
            boolean accepted,
            boolean multipleAllowed,
            String errorCode,
            String errorMessage,
            List<String> fileNames
        ) {
            this.triggered = triggered;
            this.accepted = accepted;
            this.multipleAllowed = multipleAllowed;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.fileNames = fileNames != null ? fileNames : Collections.<String>emptyList();
        }
    }

    public static final class ConsumeOutcome {
        public final boolean accepted;
        public final Uri[] uris;
        public final String summary;
        public final String errorMessage;

        private ConsumeOutcome(boolean accepted, Uri[] uris, String summary, String errorMessage) {
            this.accepted = accepted;
            this.uris = uris;
            this.summary = summary;
            this.errorMessage = errorMessage;
        }
    }

    public static final class ArmedUpload {
        public final int tabId;
        public final List<File> files;
        private final long expiresAtMs;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile ConsumeResult result = null;
        private volatile boolean finished = false;

        private ArmedUpload(int tabId, List<File> files, long expiresAtMs) {
            this.tabId = tabId;
            this.files = files;
            this.expiresAtMs = expiresAtMs;
        }

        private void complete(ConsumeResult r) {
            if (finished) return;
            finished = true;
            result = r;
            latch.countDown();
        }
    }

    private final Object mLock = new Object();
    private final Map<Integer, ArmedUpload> mArmedByTab = new HashMap<Integer, ArmedUpload>();

    /**
     * Resolves a single candidate file inside {@code workspaceDir}, rejecting null bytes, path
     * traversal/escapes outside {@code acid_workspace/}, the workspace root itself, and any entry
     * protected from workspace cleanup. Does not require the target file to already exist.
     */
    public static WorkspaceFileResolution resolveSafeUnprotectedWorkspaceFile(
        File workspaceDir,
        String rawPath,
        String actionVerb
    ) {
        if (workspaceDir == null) {
            return WorkspaceFileResolution.error("Workspace directory unavailable.");
        }
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return WorkspaceFileResolution.error("Workspace path is empty.");
        }
        if (rawPath.indexOf('\0') >= 0) {
            return WorkspaceFileResolution.error("Access denied: invalid null byte in path.");
        }
        String clean = rawPath.trim();
        if (clean.toLowerCase(Locale.US).startsWith("workspace://")) {
            clean = clean.substring("workspace://".length());
            while (clean.startsWith("/")) clean = clean.substring(1);
        }
        if (clean.isEmpty()) {
            return WorkspaceFileResolution.error("Workspace path is empty.");
        }

        final File wsCanon;
        final String wsPath;
        try {
            wsCanon = workspaceDir.getCanonicalFile();
            wsPath = wsCanon.getPath();
        } catch (Exception e) {
            return WorkspaceFileResolution.error("Failed to canonicalize workspace directory: " + e.getMessage());
        }

        File candidate = clean.startsWith("/") ? new File(clean) : new File(wsCanon, clean);
        final File targetCanon;
        try {
            targetCanon = candidate.getCanonicalFile();
        } catch (Exception e) {
            return WorkspaceFileResolution.error("Invalid workspace path (" + rawPath + "): " + e.getMessage());
        }

        String targetPath = targetCanon.getPath();
        String wsPrefix = wsPath.endsWith(File.separator) ? wsPath : (wsPath + File.separator);
        if (!targetPath.startsWith(wsPrefix) || targetPath.equals(wsPath)) {
            return WorkspaceFileResolution.error("Access denied: path escapes acid_workspace/ (" + rawPath + ")");
        }

        String rel = targetPath.substring(wsPrefix.length()).replace(File.separatorChar, '/');
        int sep = rel.indexOf('/');
        String topEntry = (sep >= 0) ? rel.substring(0, sep) : rel;
        if (isProtectedWorkspaceName(topEntry)) {
            String verb = (actionVerb != null && !actionVerb.isEmpty()) ? actionVerb : "accessed";
            return WorkspaceFileResolution.error(
                "Access denied: protected workspace item '" + topEntry + "' cannot be " + verb + " (" + rawPath + ")"
            );
        }

        return WorkspaceFileResolution.success(targetCanon, rel);
    }

    /**
     * Resolves and validates one or more workspace-relative paths for upload. Rejects any path
     * escaping {@code workspaceDir}, missing/directory targets, or entries protected from
     * workspace cleanup.
     */
    public static UploadResolution resolveUploadFiles(File workspaceDir, List<String> rawPaths) {
        if (workspaceDir == null) {
            return UploadResolution.error("Workspace directory unavailable.");
        }
        if (rawPaths == null || rawPaths.isEmpty()) {
            return UploadResolution.error("At least one workspace 'file_path' or 'file_paths' entry is required.");
        }
        if (rawPaths.size() > MAX_UPLOAD_FILES) {
            return UploadResolution.error("Too many upload files requested (max " + MAX_UPLOAD_FILES + ").");
        }

        List<File> resolved = new ArrayList<File>(rawPaths.size());
        for (int i = 0; i < rawPaths.size(); i++) {
            String raw = rawPaths.get(i);
            if (raw == null || raw.trim().isEmpty()) {
                return UploadResolution.error("Upload file path at index " + i + " is empty.");
            }
            WorkspaceFileResolution check = resolveSafeUnprotectedWorkspaceFile(workspaceDir, raw, "uploaded");
            if (!check.ok) {
                return UploadResolution.error(check.errorMessage);
            }
            File targetCanon = check.file;
            if (!targetCanon.exists()) {
                return UploadResolution.error("Upload file not found in workspace: " + raw);
            }
            if (!targetCanon.isFile()) {
                return UploadResolution.error("Upload path is not a regular file: " + raw);
            }
            if (!targetCanon.canRead()) {
                return UploadResolution.error("Upload file is not readable: " + raw);
            }
            resolved.add(targetCanon);
        }

        return UploadResolution.success(resolved);
    }

    public ArmedUpload arm(int tabId, List<File> files) {
        return arm(tabId, files, DEFAULT_ARM_TTL_MS);
    }

    public ArmedUpload arm(int tabId, List<File> files, long ttlMs) {
        if (tabId <= 0 || files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Valid tabId and non-empty file list are required");
        }
        long now = System.currentTimeMillis();
        long effectiveTtl = ttlMs > 0 ? ttlMs : DEFAULT_ARM_TTL_MS;
        List<File> copy = Collections.unmodifiableList(new ArrayList<File>(files));
        ArmedUpload slot = new ArmedUpload(tabId, copy, now + effectiveTtl);

        ArmedUpload previous;
        synchronized (mLock) {
            pruneExpiredLocked(now);
            previous = mArmedByTab.put(tabId, slot);
        }
        if (previous != null) {
            previous.complete(new ConsumeResult(
                false, false, false, "SUPERSEDED",
                "Upload request was superseded by another upload on tab " + tabId, null
            ));
        }
        return slot;
    }

    /**
     * Attempts to consume an armed upload for {@code tabId} when WebView triggers
     * {@code onShowFileChooser}. Returns {@code null} when no active armed upload exists
     * for this tab (so caller falls back to normal Agent-deny / human file chooser handling).
     */
    public ConsumeOutcome consumeForTab(int tabId, boolean allowMultiple) {
        if (tabId <= 0) return null;
        long now = System.currentTimeMillis();
        ArmedUpload slot;
        synchronized (mLock) {
            pruneExpiredLocked(now);
            slot = mArmedByTab.remove(tabId);
        }
        if (slot == null) {
            return null;
        }
        if (now > slot.expiresAtMs) {
            slot.complete(new ConsumeResult(
                false, false, allowMultiple, "EXPIRED",
                "Armed upload expired before WebView opened a file chooser.", null
            ));
            return null;
        }

        List<String> names = new ArrayList<String>(slot.files.size());
        for (File f : slot.files) {
            names.add(f.getName());
        }

        if (!allowMultiple && slot.files.size() > 1) {
            String err = "Webpage file input only accepts a single file (multiple=false), but "
                + slot.files.size() + " files were provided.";
            slot.complete(new ConsumeResult(
                true, false, false, "SINGLE_FILE_INPUT_ONLY", err, names
            ));
            return new ConsumeOutcome(false, null, "", err);
        }

        Uri[] uris = new Uri[slot.files.size()];
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < slot.files.size(); i++) {
            File f = slot.files.get(i);
            uris[i] = Uri.fromFile(f);
            if (i > 0) summary.append(", ");
            summary.append(f.getName());
        }

        slot.complete(new ConsumeResult(
            true, true, allowMultiple, null, null, Collections.unmodifiableList(names)
        ));
        return new ConsumeOutcome(true, uris, summary.toString(), null);
    }

    public ConsumeResult awaitCompletion(ArmedUpload slot, long timeoutMs) {
        if (slot == null) {
            return new ConsumeResult(false, false, false, "INVALID_SLOT", "No upload slot armed.", null);
        }
        long waitMs = timeoutMs > 0 ? timeoutMs : 1L;
        try {
            slot.latch.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ConsumeResult existing = slot.result;
            return existing != null ? existing : new ConsumeResult(
                false, false, false, "INTERRUPTED", "Interrupted while waiting for file chooser.", null
            );
        }
        ConsumeResult r = slot.result;
        return r != null ? r : new ConsumeResult(
            false, false, false, "CHOOSER_NOT_TRIGGERED",
            "Target element was clicked, but the webpage did not open a file chooser within " + waitMs + "ms.", null
        );
    }

    public void disarm(ArmedUpload slot) {
        if (slot == null) return;
        synchronized (mLock) {
            ArmedUpload cur = mArmedByTab.get(slot.tabId);
            if (cur == slot) {
                mArmedByTab.remove(slot.tabId);
            }
        }
        slot.complete(new ConsumeResult(
            false, false, false, "DISARMED", "Upload slot disarmed without triggering a file chooser.", null
        ));
    }

    public void cancelForTab(int tabId, String reason) {
        if (tabId <= 0) return;
        ArmedUpload slot;
        synchronized (mLock) {
            slot = mArmedByTab.remove(tabId);
        }
        if (slot != null) {
            slot.complete(new ConsumeResult(
                false, false, false, "CANCELLED",
                "Pending upload aborted (" + (reason != null ? reason : "cancelled") + ").", null
            ));
        }
    }

    public void cancelAll(String reason) {
        List<ArmedUpload> remaining;
        synchronized (mLock) {
            remaining = new ArrayList<ArmedUpload>(mArmedByTab.values());
            mArmedByTab.clear();
        }
        for (ArmedUpload slot : remaining) {
            slot.complete(new ConsumeResult(
                false, false, false, "CANCELLED",
                "Pending upload aborted (" + (reason != null ? reason : "cancelled") + ").", null
            ));
        }
    }

    private void pruneExpiredLocked(long nowMs) {
        if (mArmedByTab.isEmpty()) return;
        List<ArmedUpload> expired = null;
        java.util.Iterator<Map.Entry<Integer, ArmedUpload>> it = mArmedByTab.entrySet().iterator();
        while (it.hasNext()) {
            ArmedUpload slot = it.next().getValue();
            if (nowMs > slot.expiresAtMs) {
                it.remove();
                if (expired == null) expired = new ArrayList<ArmedUpload>();
                expired.add(slot);
            }
        }
        if (expired != null) {
            for (ArmedUpload slot : expired) {
                slot.complete(new ConsumeResult(
                    false, false, false, "EXPIRED",
                    "Armed upload expired before WebView opened a file chooser.", null
                ));
            }
        }
    }
}
