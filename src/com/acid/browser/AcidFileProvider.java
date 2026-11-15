package com.acid.browser;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lightweight ContentProvider that exposes files in the public Download directory
 * to external applications via safe content:// URIs with grantable read permissions.
 */
public class AcidFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.acid.browser.fileprovider";
    public static final String BUCKET_PUBLIC = "public";
    public static final String BUCKET_EXT_PRIVATE = "ext_private";
    public static final String BUCKET_INT_PRIVATE = "int_private";

    public static Uri getUriForFile(File file) {
        if (file == null) return null;
        String bucket = inferBucketForFile(file);
        return new Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(bucket)
            .appendPath(file.getName())
            .build();
    }

    static String inferBucketForFile(File file) {
        if (file == null) return BUCKET_PUBLIC;
        String abs = file.getAbsolutePath().replace('\\', '/');
        if (abs.contains("/Android/data/") && abs.contains("/files/Download/")) {
            return BUCKET_EXT_PRIVATE;
        }
        if ((abs.startsWith("/data/") || abs.contains("/files/Download/")) && !abs.contains("/Android/data/")) {
            try {
                File pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (pub != null) {
                    String pubPath = pub.getCanonicalPath().replace('\\', '/');
                    String fileCanon = file.getCanonicalPath().replace('\\', '/');
                    if (fileCanon.startsWith(pubPath + "/")) {
                        return BUCKET_PUBLIC;
                    }
                }
            } catch (Throwable ignored) {}
            return BUCKET_INT_PRIVATE;
        }
        return BUCKET_PUBLIC;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)) {
            throw new SecurityException("AcidFileProvider only supports read-only access (requested mode: " + mode + ")");
        }
        File targetFile = resolveSafeFile(uri);
        return ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * Resolves a Content URI to a local File while strictly verifying that the resolved canonical
     * path resides exclusively inside the target authorized Download directory, preventing both
     * path traversal and public-over-private file shadowing.
     */
    private File resolveSafeFile(Uri uri) throws FileNotFoundException {
        return resolveSafeUri(getContext(), uri);
    }

    static File resolveSafeUri(Context ctx, Uri uri) throws FileNotFoundException {
        if (uri == null) throw new FileNotFoundException("Null URI");
        String rawPath = uri.getPath();
        String fileName = uri.getLastPathSegment();
        String bucket = extractBucketFromPath(rawPath);
        if (bucket != null) {
            return resolveSafeFileInBucket(ctx, bucket, fileName);
        }
        return resolveSafeFile(ctx, fileName);
    }

    static String extractBucketFromPath(String path) {
        if (path == null || path.isEmpty()) return null;
        String clean = path;
        while (clean.startsWith("/")) clean = clean.substring(1);
        int slash = clean.indexOf('/');
        if (slash <= 0 || slash >= clean.length() - 1) return null;
        String first = clean.substring(0, slash);
        if (BUCKET_PUBLIC.equals(first) || BUCKET_EXT_PRIVATE.equals(first) || BUCKET_INT_PRIVATE.equals(first)) {
            return first;
        }
        return null;
    }

    static File resolveSafeFileInBucket(Context ctx, String bucket, String fileName) throws FileNotFoundException {
        if (fileName == null || fileName.trim().isEmpty() || ".".equals(fileName) || "..".equals(fileName)) {
            throw new FileNotFoundException("Invalid filename: " + fileName);
        }
        File baseDir = null;
        if (BUCKET_PUBLIC.equals(bucket)) {
            baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        } else if (BUCKET_EXT_PRIVATE.equals(bucket)) {
            if (ctx != null) {
                baseDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            }
        } else if (BUCKET_INT_PRIVATE.equals(bucket)) {
            if (ctx != null && ctx.getFilesDir() != null) {
                baseDir = new File(ctx.getFilesDir(), Environment.DIRECTORY_DOWNLOADS);
            }
        }
        File targetFile = resolveAndVerifyBoundary(baseDir, fileName);
        if (targetFile == null || !targetFile.exists()) {
            throw new FileNotFoundException("File not found or access denied in " + bucket + ": " + fileName);
        }
        return targetFile;
    }

    /**
     * Resolves a filename to an authorized File across public, external-private, and internal-private
     * Download directories with strict boundary confinement.
     */
    public static File resolveSafeFile(Context ctx, String fileName) throws FileNotFoundException {
        if (fileName == null || fileName.trim().isEmpty() || ".".equals(fileName) || "..".equals(fileName)) {
            throw new FileNotFoundException("Invalid filename: " + fileName);
        }

        // 1. Primary: Public Download directory
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File targetFile = resolveAndVerifyBoundary(downloadDir, fileName);

        // 2. Secondary fallback: External private files/Download directory
        if (targetFile == null || !targetFile.exists()) {
            if (ctx != null) {
                File alt = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (alt != null) {
                    targetFile = resolveAndVerifyBoundary(alt, fileName);
                }
            }
        }

        // 3. Tertiary fallback: Internal private files/Download directory (for restricted sandboxes / Scoped Storage fallback)
        if (targetFile == null || !targetFile.exists()) {
            if (ctx != null && ctx.getFilesDir() != null) {
                File internalDownloadDir = new File(ctx.getFilesDir(), Environment.DIRECTORY_DOWNLOADS);
                targetFile = resolveAndVerifyBoundary(internalDownloadDir, fileName);
            }
        }

        if (targetFile == null || !targetFile.exists()) {
            throw new FileNotFoundException("File not found or access denied: " + fileName);
        }
        return targetFile;
    }

    public static File resolveAndVerifyBoundary(File baseDir, String relativeName) {
        if (baseDir == null) return null;
        try {
            File baseCanon = baseDir.getCanonicalFile();
            File target = new File(baseCanon, relativeName).getCanonicalFile();
            if (!target.equals(baseCanon) && target.getPath().startsWith(baseCanon.getPath() + File.separator)) {
                return target;
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public String getType(Uri uri) {
        if (uri == null) return null;
        String fileName = uri.getLastPathSegment();
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String ext = fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                if ("apk".equals(ext)) {
                    return "application/vnd.android.package-archive";
                }
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null) return mime;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (uri == null) return null;
        String fileName = uri.getLastPathSegment();
        File targetFile;
        try {
            targetFile = resolveSafeFile(uri);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            targetFile = null;
        }

        String[] cols = projection != null ? projection : new String[]{
            OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
        };
        if (targetFile == null || !targetFile.exists()) {
            return new MatrixCursor(cols, 0);
        }
        String displayName = targetFile.getName() != null ? targetFile.getName() : fileName;
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(displayName);
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(targetFile.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    public interface ExtensionMimeResolver {
        String mimeForExtension(String ext);
    }

    /**
     * Validates an upload URI returned from the system file chooser against scheme spoofing,
     * internal {@link AcidFileProvider} re-exposure, and private app data directory disclosure.
     */
    public static boolean isSafeFileChooserUri(Context ctx, Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(java.util.Locale.ROOT).trim() : "";
        if ("content".equals(scheme)) {
            String raw = uri.toString() != null ? uri.toString() : "";
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(java.util.Locale.ROOT).trim() : "";
            String authority = host;
            if (authority.isEmpty() && lower.startsWith("content://")) {
                int start = "content://".length();
                int end = lower.length();
                int sIdx = lower.indexOf('/', start);
                int qIdx = lower.indexOf('?', start);
                int hIdx = lower.indexOf('#', start);
                if (sIdx >= 0 && sIdx < end) end = sIdx;
                if (qIdx >= 0 && qIdx < end) end = qIdx;
                if (hIdx >= 0 && hIdx < end) end = hIdx;
                authority = lower.substring(start, end);
            }
            int at = authority.lastIndexOf('@');
            if (at >= 0) authority = authority.substring(at + 1);
            if (authority.isEmpty()) return false;
            if (AUTHORITY.equalsIgnoreCase(authority)) return false;
            if (ctx != null && ctx.getPackageName() != null
                && (ctx.getPackageName() + ".fileprovider").equalsIgnoreCase(authority)) {
                return false;
            }
            return true;
        }
        if ("file".equals(scheme)) {
            String rawPath = uri.getPath();
            if (rawPath == null || rawPath.trim().isEmpty()) return false;
            String canonPath;
            try {
                canonPath = new File(rawPath).getCanonicalPath().replace('\\', '/');
            } catch (Exception e) {
                canonPath = rawPath.replace('\\', '/');
            }
            String normRaw = rawPath.replace('\\', '/');
            return isSafeExternalFilePath(canonPath, ctx) && isSafeExternalFilePath(normRaw, ctx);
        }
        return false;
    }

    private static boolean isSafeExternalFilePath(String path, Context ctx) {
        if (path == null || path.isEmpty()) return false;
        if (path.startsWith("/proc/self") || "/proc/self".equals(path)) return false;
        String pkg = (ctx != null && ctx.getPackageName() != null && !ctx.getPackageName().trim().isEmpty())
            ? ctx.getPackageName().trim() : "com.acid.browser";
        if (isPathUnderBase(path, "/data/data/" + pkg)) return false;
        if (isPathUnderAndroidUserPkg(path, "/data/user/", pkg)) return false;
        if (isPathUnderAndroidUserPkg(path, "/data/user_de/", pkg)) return false;
        if (ctx != null) {
            try {
                if (ctx.getApplicationInfo() != null && ctx.getApplicationInfo().dataDir != null) {
                    String dataCanon = new File(ctx.getApplicationInfo().dataDir).getCanonicalPath().replace('\\', '/');
                    if (isPathUnderBase(path, dataCanon)) return false;
                }
            } catch (Throwable ignored) {}
            try {
                File filesDir = ctx.getFilesDir();
                if (filesDir != null) {
                    if (isPathUnderBase(path, filesDir.getCanonicalPath().replace('\\', '/'))) return false;
                    File parent = filesDir.getParentFile();
                    if (parent != null && isPathUnderBase(path, parent.getCanonicalPath().replace('\\', '/'))) return false;
                }
            } catch (Throwable ignored) {}
            try {
                File cacheDir = ctx.getCacheDir();
                if (cacheDir != null && isPathUnderBase(path, cacheDir.getCanonicalPath().replace('\\', '/'))) return false;
            } catch (Throwable ignored) {}
        }
        return true;
    }

    private static boolean isPathUnderBase(String path, String base) {
        if (base == null || base.isEmpty()) return false;
        while (base.endsWith("/") && base.length() > 1) base = base.substring(0, base.length() - 1);
        return path.equals(base) || path.startsWith(base + "/");
    }

    private static boolean isPathUnderAndroidUserPkg(String path, String rootPrefix, String pkg) {
        if (!path.startsWith(rootPrefix)) return false;
        int restStart = rootPrefix.length();
        int slash = path.indexOf('/', restStart);
        if (slash <= restStart) return false;
        String sub = path.substring(slash + 1);
        return sub.equals(pkg) || sub.startsWith(pkg + "/");
    }

    /**
     * Normalizes HTML {@code accept} tokens (handling comma-separated items, {@code ;charset=...}
     * parameters, uppercase types, and extensions). Returns an empty list (meaning {@code *\/*})
     * whenever any token is {@code *\/*} or cannot be mapped to a concrete MIME type so valid
     * selections are never filtered out.
     */
    public static java.util.List<String> normalizeFileChooserAcceptTypes(
            String[] acceptTypes, ExtensionMimeResolver resolver) {
        java.util.ArrayList<String> mimes = new java.util.ArrayList<String>();
        if (acceptTypes == null || acceptTypes.length == 0) return mimes;
        for (int i = 0; i < acceptTypes.length; i++) {
            String rawEntry = acceptTypes[i];
            if (rawEntry == null) continue;
            String[] parts = rawEntry.split(",");
            for (int p = 0; p < parts.length; p++) {
                String token = parts[p] != null ? parts[p].trim() : "";
                if (token.isEmpty()) continue;
                int semi = token.indexOf(';');
                if (semi >= 0) token = token.substring(0, semi).trim();
                if (token.isEmpty()) continue;
                String lower = token.toLowerCase(java.util.Locale.ROOT);
                if ("*".equals(lower) || "*/*".equals(lower)) {
                    mimes.clear();
                    return mimes;
                }
                if (lower.indexOf('/') >= 0) {
                    int slash = lower.indexOf('/');
                    if (slash <= 0 || slash >= lower.length() - 1) {
                        mimes.clear();
                        return mimes;
                    }
                    if (!mimes.contains(lower)) mimes.add(lower);
                } else {
                    String ext = lower.startsWith(".") ? lower.substring(1) : lower;
                    if (ext.isEmpty()) continue;
                    String resolved = null;
                    if (resolver != null) {
                        resolved = resolver.mimeForExtension(ext);
                    } else {
                        try {
                            resolved = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                        } catch (Throwable ignored) {}
                    }
                    if (resolved == null || resolved.trim().isEmpty()) {
                        mimes.clear();
                        return mimes;
                    }
                    String normResolved = resolved.trim().toLowerCase(java.util.Locale.ROOT);
                    if (!mimes.contains(normResolved)) mimes.add(normResolved);
                }
            }
        }
        return mimes;
    }
}
