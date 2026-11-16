package com.acid.browser;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.acid.browser.download.DownloadCoordinator;
import com.acid.browser.download.DownloadItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages user-selected phone attachments for in-app Agent conversations:
 * - Enforces single-batch/pending attachment limit ({@link #MAX_ATTACHMENTS} = 5).
 * - Safely copies SAF {@code content://} URIs into {@code acid_workspace/uploads/} off UI thread.
 * - Downscales & Base64-encodes raster images for multimodal models without OOM or payload bloat.
 * - Classifies text vs binary files, previews small UTF-8 files, and guards {@code read} against binary noise.
 */
public final class AgentAttachmentHelper {

    private static final String TAG = "AgentAttachment";

    public static final int MAX_ATTACHMENTS = 5;
    public static final long MAX_SINGLE_FILE_BYTES = 50L * 1024L * 1024L; // 50 MB per file
    public static final int INLINE_TEXT_PREVIEW_BYTES = 12 * 1024;        // 12 KB inline preview
    public static final int MAX_IMAGE_LONG_EDGE = ScreenshotCaptureHelper.MAX_IMAGE_LONG_EDGE;
    public static final int IMAGE_JPEG_QUALITY = ScreenshotCaptureHelper.DEFAULT_JPEG_QUALITY;
    public static final String UPLOADS_DIR_NAME = "uploads";
    public static final String USER_ATTACHMENTS_MARKER = "[Attached User Files]";

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private static final Set<String> RASTER_IMAGE_EXT = new HashSet<String>(Arrays.asList(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif"
    ));

    private static final Set<String> TEXT_EXT = new HashSet<String>(Arrays.asList(
        ".txt", ".md", ".markdown", ".json", ".jsonl", ".csv", ".tsv", ".xml",
        ".html", ".htm", ".xhtml", ".svg", ".css", ".scss", ".less",
        ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".vue", ".svelte",
        ".py", ".java", ".kt", ".kts", ".c", ".cc", ".cpp", ".h", ".hpp",
        ".go", ".rs", ".rb", ".php", ".sh", ".bash", ".zsh", ".bat", ".ps1",
        ".yaml", ".yml", ".toml", ".ini", ".conf", ".cfg", ".env", ".properties",
        ".sql", ".log", ".gradle", ".gitignore", ".rtf", ".srt", ".vtt", ".lrc"
    ));

    private static final Set<String> KNOWN_BINARY_EXT = new HashSet<String>(Arrays.asList(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz", ".jar",
        ".apk", ".apks", ".xapk", ".apkm", ".dex", ".so", ".exe", ".dll",
        ".mp3", ".aac", ".flac", ".wav", ".ogg", ".m4a", ".opus",
        ".mp4", ".mkv", ".avi", ".mov", ".webm", ".flv", ".3gp",
        ".ttf", ".otf", ".woff", ".woff2", ".db", ".sqlite", ".bin",
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".ico"
    ));

    private AgentAttachmentHelper() {}

    public static final class AttachmentItem {
        public final String id;
        public final String displayName;
        public final String relativePath;
        public final String mimeType;
        public final long sizeBytes;
        public final boolean isImage;
        public final int imageWidth;
        public final int imageHeight;
        /** In-memory Base64 JPEG/PNG payload for LLM multimodal requests; NOT stored in uiItems JSON. */
        public final String imageBase64;

        public AttachmentItem(
            String id,
            String displayName,
            String relativePath,
            String mimeType,
            long sizeBytes,
            boolean isImage,
            int imageWidth,
            int imageHeight,
            String imageBase64
        ) {
            this.id = (id != null && !id.isEmpty()) ? id : nextAttachmentId();
            this.displayName = displayName != null ? displayName : "attachment";
            this.relativePath = relativePath != null ? relativePath : "";
            this.mimeType = mimeType != null ? mimeType : "application/octet-stream";
            this.sizeBytes = Math.max(0L, sizeBytes);
            this.isImage = isImage;
            this.imageWidth = Math.max(0, imageWidth);
            this.imageHeight = Math.max(0, imageHeight);
            this.imageBase64 = imageBase64 != null ? imageBase64 : "";
        }

        public JSONObject toUiJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("displayName", displayName);
                o.put("relativePath", relativePath);
                o.put("mimeType", mimeType);
                o.put("sizeBytes", sizeBytes);
                o.put("isImage", isImage);
                if (imageWidth > 0) o.put("imageWidth", imageWidth);
                if (imageHeight > 0) o.put("imageHeight", imageHeight);
            } catch (Exception e) {
                Log.w(TAG, "Failed to serialize attachment item: " + displayName, e);
            }
            return o;
        }

        public static AttachmentItem fromUiJson(JSONObject o) {
            if (o == null) return null;
            return new AttachmentItem(
                o.optString("id", nextAttachmentId()),
                o.optString("displayName", "attachment"),
                o.optString("relativePath", ""),
                o.optString("mimeType", "application/octet-stream"),
                o.optLong("sizeBytes", 0L),
                o.optBoolean("isImage", false),
                o.optInt("imageWidth", 0),
                o.optInt("imageHeight", 0),
                ""
            );
        }
    }

    public static final class LimitedSelection<T> {
        public final List<T> accepted;
        public final int trimmedCount;

        public LimitedSelection(List<T> accepted, int trimmedCount) {
            this.accepted = accepted != null ? accepted : Collections.<T>emptyList();
            this.trimmedCount = Math.max(0, trimmedCount);
        }
    }

    public static final class ImportBatchResult {
        public final List<AttachmentItem> imported;
        public final List<String> errors;

        public ImportBatchResult(List<AttachmentItem> imported, List<String> errors) {
            this.imported = imported != null ? imported : Collections.<AttachmentItem>emptyList();
            this.errors = errors != null ? errors : Collections.<String>emptyList();
        }
    }

    private static String nextAttachmentId() {
        return "att_" + System.currentTimeMillis() + "_" + NEXT_ID.getAndIncrement();
    }

    /** Builds an ACTION_OPEN_DOCUMENT multi-select Intent allowing any file type. */
    public static Intent buildAttachmentPickerIntent(boolean useOpenDocument) {
        Intent intent = new Intent(useOpenDocument ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (useOpenDocument) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return intent;
    }

    /** Caps candidate selection against remaining slots within {@link #MAX_ATTACHMENTS} (5). */
    public static <T> LimitedSelection<T> clampToMaxAttachments(List<T> candidates, int currentPendingCount) {
        if (candidates == null || candidates.isEmpty()) {
            return new LimitedSelection<T>(Collections.<T>emptyList(), 0);
        }
        int remaining = Math.max(0, MAX_ATTACHMENTS - Math.max(0, currentPendingCount));
        if (remaining == 0) {
            return new LimitedSelection<T>(Collections.<T>emptyList(), candidates.size());
        }
        if (candidates.size() <= remaining) {
            return new LimitedSelection<T>(new ArrayList<T>(candidates), 0);
        }
        return new LimitedSelection<T>(
            new ArrayList<T>(candidates.subList(0, remaining)),
            candidates.size() - remaining
        );
    }

    /** Extracts validated URIs from picker Intent, preserving multi-select order and capping to 5 total. */
    public static LimitedSelection<Uri> extractPickerUris(Context ctx, Intent data, int currentPendingCount) {
        if (data == null) {
            return new LimitedSelection<Uri>(Collections.<Uri>emptyList(), 0);
        }
        List<Uri> rawUris = new ArrayList<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null && AcidFileProvider.isSafeFileChooserUri(ctx, u)) {
                    rawUris.add(u);
                }
            }
        }
        if (rawUris.isEmpty() && data.getData() != null) {
            Uri u = data.getData();
            if (AcidFileProvider.isSafeFileChooserUri(ctx, u)) {
                rawUris.add(u);
            }
        }
        return clampToMaxAttachments(rawUris, currentPendingCount);
    }

    public static String sanitizeFileName(String rawName) {
        String cleaned = DownloadCoordinator.sanitizeDownloadFileName(rawName);
        if (cleaned == null || cleaned.trim().isEmpty()) {
            return "attachment.bin";
        }
        cleaned = cleaned.replace('/', '_').replace('\\', '_').trim();
        if (cleaned.length() > 120) {
            int dot = cleaned.lastIndexOf('.');
            String ext = (dot > 0 && cleaned.length() - dot <= 16) ? cleaned.substring(dot) : "";
            int keepStem = Math.max(16, 120 - ext.length());
            cleaned = cleaned.substring(0, Math.min(keepStem, cleaned.length())) + ext;
        }
        if (cleaned.replace(".", "").isEmpty()) {
            return "attachment.bin";
        }
        return cleaned;
    }

    /** Resolves a non-colliding canonical file path inside {@code uploadsDir}. */
    public static File resolveUniqueUploadFile(File uploadsDir, String rawName) throws IOException {
        if (uploadsDir == null) throw new IOException("Uploads directory is null");
        if (!uploadsDir.exists() && !uploadsDir.mkdirs()) {
            throw new IOException("Cannot create uploads directory");
        }
        File dirCanon = uploadsDir.getCanonicalFile();
        String baseName = sanitizeFileName(rawName);
        int dot = baseName.lastIndexOf('.');
        String stem = (dot > 0) ? baseName.substring(0, dot) : baseName;
        String ext = (dot > 0) ? baseName.substring(dot) : "";

        for (int idx = 0; idx < 10000; idx++) {
            String candidateName = (idx == 0) ? baseName : (stem + "_" + idx + ext);
            File candidate = new File(dirCanon, candidateName).getCanonicalFile();
            if (!candidate.getPath().startsWith(dirCanon.getPath() + File.separator)) {
                throw new IOException("Path escapes uploads directory");
            }
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dirCanon, System.currentTimeMillis() + "_" + baseName);
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes == 0) return "0 B";
        return DownloadItem.formatSize(bytes);
    }

    public static int resolveIconForAttachment(AttachmentItem item) {
        if (item == null) return AcidIcons.ICON_FILE_OTHER;
        if (item.isImage) return AcidIcons.ICON_FILE_IMAGE;
        DownloadItem d = new DownloadItem(0, item.displayName, "", item.mimeType, item.sizeBytes, item.sizeBytes, 0, 0);
        switch (d.getCategory()) {
            case DownloadItem.CAT_APK: return AcidIcons.ICON_FILE_APK;
            case DownloadItem.CAT_ARCHIVE: return AcidIcons.ICON_FILE_ARCHIVE;
            case DownloadItem.CAT_DOC: return AcidIcons.ICON_FILE_DOC;
            case DownloadItem.CAT_IMAGE: return AcidIcons.ICON_FILE_IMAGE;
            case DownloadItem.CAT_VIDEO: return AcidIcons.ICON_FILE_VIDEO;
            case DownloadItem.CAT_AUDIO: return AcidIcons.ICON_FILE_AUDIO;
            default: return AcidIcons.ICON_FILE_OTHER;
        }
    }

    public static String getExtensionLower(String fileName) {
        if (fileName == null) return "";
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot == base.length() - 1) return "";
        return base.substring(dot).toLowerCase(Locale.US);
    }

    /** True for raster image files suitable for multimodal {@code image_url} vision parts (excludes SVG XML). */
    public static boolean isRasterImageFile(String mimeType, String fileName) {
        String mime = mimeType != null ? mimeType.toLowerCase(Locale.US).trim() : "";
        String ext = getExtensionLower(fileName);
        if (".svg".equals(ext) || "image/svg+xml".equals(mime)) {
            return false;
        }
        if (mime.startsWith("image/")) {
            return true;
        }
        return RASTER_IMAGE_EXT.contains(ext);
    }

    /**
     * Checks raw leading file bytes for NUL or excessive non-printable control bytes so
     * binary attachments (.pdf, .zip, .png, .mp4) are never dumped as UTF-8 mojibake.
     */
    public static boolean isBinarySample(byte[] sample, int len) {
        if (sample == null || len <= 0) return false;
        int n = Math.min(len, sample.length);
        int controlCount = 0;
        for (int i = 0; i < n; i++) {
            int b = sample[i] & 0xFF;
            if (b == 0x00) {
                return true;
            }
            if (b < 0x20 && b != '\t' && b != '\n' && b != '\r' && b != '\f') {
                controlCount++;
            }
        }
        return controlCount > Math.max(4, n / 12);
    }

    public static boolean isBinaryFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) return false;
        String ext = getExtensionLower(file.getName());
        if (KNOWN_BINARY_EXT.contains(ext)) {
            return true;
        }
        if (file.length() == 0) return false;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] buf = new byte[Math.min(4096, (int) Math.min(file.length(), 4096L))];
            int read = fis.read(buf);
            return isBinarySample(buf, read);
        } catch (Exception e) {
            return false;
        } finally {
            AcidIo.closeQuietly(fis);
        }
    }

    public static boolean isTextReadableFile(String mimeType, String fileName, File file) {
        String mime = mimeType != null ? mimeType.toLowerCase(Locale.US).trim() : "";
        String ext = getExtensionLower(fileName);
        if (KNOWN_BINARY_EXT.contains(ext) || isRasterImageFile(mime, fileName)) {
            return false;
        }
        if (mime.startsWith("text/")
            || "application/json".equals(mime)
            || "application/xml".equals(mime)
            || "application/javascript".equals(mime)
            || "application/x-javascript".equals(mime)
            || "application/yaml".equals(mime)
            || "image/svg+xml".equals(mime)
            || TEXT_EXT.contains(ext)) {
            return file == null || !isBinaryFile(file);
        }
        return file != null && file.length() > 0 && !isBinaryFile(file);
    }

    /** Imports up to remaining slots of selected URIs into {@code workspaceDir/uploads/}. Must run off UI thread. */
    public static ImportBatchResult importUrisToWorkspace(Context ctx, File workspaceDir, List<Uri> uris) {
        List<AttachmentItem> imported = new ArrayList<AttachmentItem>();
        List<String> errors = new ArrayList<String>();
        if (ctx == null || workspaceDir == null || uris == null || uris.isEmpty()) {
            return new ImportBatchResult(imported, errors);
        }
        File uploadsDir = new File(workspaceDir, UPLOADS_DIR_NAME);
        ContentResolver resolver = ctx.getContentResolver();

        for (Uri uri : uris) {
            if (uri == null) continue;
            if (Thread.currentThread().isInterrupted()) break;
            String rawName = queryDisplayName(resolver, uri);
            String cleanName = sanitizeFileName(rawName);
            String mime = resolveMimeType(resolver, uri, cleanName);
            cleanName = ensureExtensionFromMime(cleanName, mime);

            File destFile = null;
            try {
                destFile = resolveUniqueUploadFile(uploadsDir, cleanName);
                long bytesCopied = copyUriWithSizeCap(resolver, uri, destFile, MAX_SINGLE_FILE_BYTES);
                String relPath = UPLOADS_DIR_NAME + "/" + destFile.getName();

                boolean isImg = isRasterImageFile(mime, destFile.getName());
                String b64 = "";
                int imgW = 0;
                int imgH = 0;
                if (isImg) {
                    EncodedImage preview = encodeScaledImageForModel(destFile);
                    if (preview != null) {
                        b64 = preview.base64;
                        imgW = preview.width;
                        imgH = preview.height;
                    } else {
                        // File header could not be decoded as a raster image; treat as ordinary binary file
                        isImg = false;
                    }
                }

                imported.add(new AttachmentItem(
                    nextAttachmentId(),
                    destFile.getName(),
                    relPath,
                    mime,
                    bytesCopied,
                    isImg,
                    imgW,
                    imgH,
                    b64
                ));
            } catch (Exception e) {
                if (destFile != null && destFile.exists()) {
                    destFile.delete();
                }
                errors.add(cleanName + ": " + (e.getMessage() != null ? e.getMessage() : "导入失败"));
            }
        }
        return new ImportBatchResult(imported, errors);
    }

    /** Safely loads a small UI thumbnail bitmap (~96px square) for a workspace image file. */
    public static Bitmap decodeThumbnailBitmap(File file, int targetPx) {
        if (file == null || !file.exists() || !file.isFile() || targetPx <= 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetPx, targetPx);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = inSampleSize;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp == null) return null;
            return applyExifOrientationIfNeeded(file.getAbsolutePath(), bmp);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Builds structured prompt context describing user-attached files stored in {@code uploads/}. */
    public static String buildAttachmentPromptContext(File workspaceDir, List<AttachmentItem> attachments) {
        if (attachments == null || attachments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n").append(USER_ATTACHMENTS_MARKER).append(" (").append(attachments.size()).append(" file(s) copied into workspace):\n");
        for (int i = 0; i < attachments.size(); i++) {
            AttachmentItem att = attachments.get(i);
            if (att == null) continue;
            sb.append(i + 1).append(". `").append(att.relativePath).append("`")
              .append(" (name=").append(att.displayName)
              .append(", size=").append(formatFileSize(att.sizeBytes))
              .append(", mime=").append(att.mimeType);
            if (att.isImage && att.imageWidth > 0 && att.imageHeight > 0) {
                sb.append(", preview=").append(att.imageWidth).append("x").append(att.imageHeight).append("px attached visually");
            }
            sb.append(")\n");

            if (!att.isImage && workspaceDir != null && !att.relativePath.isEmpty()) {
                File f = new File(workspaceDir, att.relativePath);
                if (f.exists() && f.isFile()) {
                    if (isTextReadableFile(att.mimeType, att.displayName, f)) {
                        if (f.length() <= INLINE_TEXT_PREVIEW_BYTES) {
                            String content = AcidIo.readFileUtf8OrEmpty(f, INLINE_TEXT_PREVIEW_BYTES);
                            if (!content.isEmpty()) {
                                sb.append("--- Content of `").append(att.relativePath).append("` ---\n");
                                sb.append(content);
                                if (!content.endsWith("\n")) sb.append("\n");
                                sb.append("--- End of `").append(att.relativePath).append("` ---\n");
                            }
                        } else {
                            sb.append("   -> Text file exceeds inline cap; browse via `read(file_path='")
                              .append(att.relativePath).append("', offset=1, limit=200)` or `bash`.\n");
                        }
                    } else {
                        sb.append("   -> Binary/non-text file stored in workspace; inspect metadata via `bash` or upload onto a webpage via `browser(action='upload', file_path='")
                          .append(att.relativePath).append("', ...)`.\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Deletes a workspace-relative upload file if still inside {@code uploads/} and unused (e.g. removed before send). */
    public static void deletePendingUploadIfSafe(File workspaceDir, AttachmentItem item) {
        if (workspaceDir == null || item == null || item.relativePath == null || item.relativePath.isEmpty()) {
            return;
        }
        try {
            File uploadsCanon = new File(workspaceDir, UPLOADS_DIR_NAME).getCanonicalFile();
            File targetCanon = new File(workspaceDir, item.relativePath).getCanonicalFile();
            if (targetCanon.getPath().startsWith(uploadsCanon.getPath() + File.separator)
                && targetCanon.exists() && targetCanon.isFile()) {
                targetCanon.delete();
            }
        } catch (Exception ignored) {}
    }

    private static final class EncodedImage {
        final String base64;
        final int width;
        final int height;

        EncodedImage(String base64, int width, int height) {
            this.base64 = base64;
            this.width = width;
            this.height = height;
        }
    }

    private static EncodedImage encodeScaledImageForModel(File imageFile) {
        if (imageFile == null || !imageFile.exists() || !imageFile.isFile()) return null;
        Bitmap decoded = null;
        Bitmap oriented = null;
        Bitmap scaled = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_LONG_EDGE, MAX_IMAGE_LONG_EDGE);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decoded = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);
            if (decoded == null) return null;

            oriented = applyExifOrientationIfNeeded(imageFile.getAbsolutePath(), decoded);
            if (oriented != decoded) {
                decoded.recycle();
                decoded = null;
            }

            ScreenshotCaptureHelper.ImageDimensions targetDim = ScreenshotCaptureHelper.computeOutputDimensions(
                oriented.getWidth(),
                oriented.getHeight(),
                MAX_IMAGE_LONG_EDGE
            );
            if (targetDim.width != oriented.getWidth() || targetDim.height != oriented.getHeight()) {
                scaled = Bitmap.createScaledBitmap(oriented, targetDim.width, targetDim.height, true);
                if (scaled != oriented) {
                    oriented.recycle();
                    oriented = null;
                }
            } else {
                scaled = oriented;
                oriented = null;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, baos);
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            return new EncodedImage(b64, scaled.getWidth(), scaled.getHeight());
        } catch (Throwable t) {
            Log.w(TAG, "Failed to encode attached image: " + imageFile.getName(), t);
            return null;
        } finally {
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            if (oriented != null && !oriented.isRecycled()) oriented.recycle();
            if (scaled != null && !scaled.isRecycled()) scaled.recycle();
        }
    }

    private static int computeSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private static Bitmap applyExifOrientationIfNeeded(String path, Bitmap src) {
        if (path == null || src == null) return src;
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix m = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    m.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    m.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    m.postRotate(270);
                    break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    m.preScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    m.preScale(1f, -1f);
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    m.postRotate(90);
                    m.preScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    m.postRotate(270);
                    m.preScale(-1f, 1f);
                    break;
                default:
                    return src;
            }
            Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            return rotated != null ? rotated : src;
        } catch (Throwable ignored) {
            return src;
        }
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        String name = null;
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) {}
            }
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.trim().isEmpty()) {
            name = "attachment";
        }
        return name;
    }

    private static String resolveMimeType(ContentResolver resolver, Uri uri, String fileName) {
        String mime = null;
        try {
            mime = resolver.getType(uri);
        } catch (Throwable ignored) {}
        if (mime == null || mime.trim().isEmpty() || "application/octet-stream".equalsIgnoreCase(mime)) {
            String ext = getExtensionLower(fileName);
            if (!ext.isEmpty()) {
                String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.substring(1));
                if (guessed != null && !guessed.trim().isEmpty()) {
                    mime = guessed;
                }
            }
        }
        return (mime != null && !mime.trim().isEmpty()) ? mime : "application/octet-stream";
    }

    public static String ensureExtensionFromMime(String cleanName, String mime) {
        if (cleanName == null || cleanName.isEmpty()) cleanName = "attachment";
        if (!getExtensionLower(cleanName).isEmpty() || mime == null || mime.trim().isEmpty()) {
            return cleanName;
        }
        String m = mime.toLowerCase(Locale.US).trim();
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0, semi).trim();
        String ext = null;
        if ("image/jpeg".equals(m)) ext = "jpg";
        else if ("image/png".equals(m)) ext = "png";
        else if ("image/webp".equals(m)) ext = "webp";
        else if ("text/plain".equals(m)) ext = "txt";
        else if ("application/pdf".equals(m)) ext = "pdf";
        else if ("application/json".equals(m)) ext = "json";
        else {
            try {
                ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(m);
            } catch (Throwable ignored) {}
        }
        if (ext != null && !ext.trim().isEmpty()) {
            return cleanName + "." + ext.trim().toLowerCase(Locale.US);
        }
        return cleanName;
    }

    private static long copyUriWithSizeCap(
        ContentResolver resolver,
        Uri uri,
        File destFile,
        long maxBytes
    ) throws IOException {
        InputStream in = null;
        FileOutputStream out = null;
        boolean ok = false;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) {
                throw new IOException("无法读取所选文件流");
            }
            out = new FileOutputStream(destFile);
            byte[] buf = new byte[16384];
            long total = 0L;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("文件过大（超过 " + formatFileSize(maxBytes) + " 限制）");
                }
                out.write(buf, 0, n);
            }
            out.flush();
            ok = true;
            return total;
        } finally {
            AcidIo.closeQuietly(in);
            AcidIo.closeQuietly(out);
            if (!ok && destFile.exists()) {
                destFile.delete();
            }
        }
    }
}
