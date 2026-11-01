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

    public static Uri getUriForFile(File file) {
        if (file == null) return null;
        return new Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(file.getName())
            .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File targetFile = resolveSafeFile(uri);
        return ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * Resolves a Content URI to a local File while strictly verifying that the resolved canonical
     * path resides exclusively inside an authorized Download directory, preventing path traversal.
     */
    private File resolveSafeFile(Uri uri) throws FileNotFoundException {
        if (uri == null) throw new FileNotFoundException("Null URI");
        String fileName = uri.getLastPathSegment();
        if (fileName == null || fileName.trim().isEmpty() || ".".equals(fileName) || "..".equals(fileName)) {
            throw new FileNotFoundException("Invalid filename in URI: " + uri);
        }

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File targetFile = resolveAndVerifyBoundary(downloadDir, fileName);

        if (targetFile == null || !targetFile.exists()) {
            Context ctx = getContext();
            if (ctx != null) {
                File alt = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (alt != null) {
                    targetFile = resolveAndVerifyBoundary(alt, fileName);
                }
            }
        }

        if (targetFile == null || !targetFile.exists()) {
            throw new FileNotFoundException("File not found or access denied: " + fileName);
        }
        return targetFile;
    }

    private static File resolveAndVerifyBoundary(File baseDir, String relativeName) {
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
                String ext = fileName.substring(dot + 1).toLowerCase();
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
        } catch (Exception e) {
            targetFile = null;
        }

        String[] cols = projection != null ? projection : new String[]{
            OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
        };
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(fileName);
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(targetFile.exists() ? targetFile.length() : 0L);
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
}
