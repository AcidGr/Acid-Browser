package com.acid.browser;

import android.util.Log;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves, downloads, and caches external {@code @require} scripts and {@code @resource} assets
 * declared in userscript headers, ensuring offline availability and fast local injection.
 */
public class UserscriptDependencyResolver {

    private static final String TAG = "AcidUserscriptDep";
    private final File mDepsDir;

    public interface FetchCallback {
        void onComplete(int successCount, int failureCount);
    }

    public UserscriptDependencyResolver(File userscriptsDir) {
        mDepsDir = new File(userscriptsDir, "deps");
        if (!mDepsDir.exists()) {
            mDepsDir.mkdirs();
        }
    }

    public File getDepsDir() {
        return mDepsDir;
    }

    /**
     * Resolves local cached file for a dependency URL. If not cached, fetches it synchronously.
     */
    public String resolveDependency(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        String safeUrl = url.trim();
        File localFile = getLocalFileForUrl(safeUrl);
        if (localFile.exists() && localFile.length() > 0) {
            return AcidIo.readFileUtf8OrEmpty(localFile, 15 * 1024 * 1024);
        }

        try {
            String content = AcidHttp.fetchText(safeUrl, 8000, 15000, 15 * 1024 * 1024);
            if (content != null && !content.isEmpty()) {
                AcidIo.writeFileUtf8Safe(localFile, content);
                return content;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch userscript dependency: " + safeUrl, e);
        }
        return "";
    }

    /**
     * Asynchronously pre-fetches all dependencies of a parsed userscript upon installation.
     */
    public void prefetchDependenciesAsync(final UserscriptMeta meta, final FetchCallback callback) {
        if (meta == null || (meta.requires.isEmpty() && meta.resources.isEmpty())) {
            if (callback != null) callback.onComplete(0, 0);
            return;
        }

        final List<String> urlsToFetch = new ArrayList<String>();
        final List<String> resourceUrls = new ArrayList<String>();
        urlsToFetch.addAll(meta.requires);
        for (String resLine : meta.resources) {
            String url = extractResourceUrl(resLine);
            if (!url.isEmpty()) {
                urlsToFetch.add(url);
                resourceUrls.add(url);
            }
        }

        AcidAsync.run(new AcidAsync.Task<int[]>() {
            @Override
            public int[] execute() throws Exception {
                int success = 0;
                int failure = 0;
                for (String u : urlsToFetch) {
                    try {
                        boolean ok = resourceUrls.contains(u)
                            ? resolveResourceBytes(u).length > 0
                            : !resolveDependency(u).isEmpty();
                        if (ok) {
                            success++;
                        } else {
                            failure++;
                        }
                    } catch (Exception e) {
                        failure++;
                    }
                }
                return new int[]{success, failure};
            }
        }, new AcidAsync.Callback<int[]>() {
            @Override
            public void onSuccess(int[] counts) {
                if (callback != null) callback.onComplete(counts[0], counts[1]);
            }

            @Override
            public void onError(Exception error) {
                if (callback != null) callback.onComplete(0, urlsToFetch.size());
            }
        });
    }

    /**
     * Reads all cached requires for this script. If any require is not cached, attempts synchronous resolution.
     */
    public List<String> loadRequiresForScript(UserscriptMeta meta) {
        List<String> list = new ArrayList<String>();
        if (meta == null) return list;

        for (String reqUrl : meta.requires) {
            String code = resolveDependency(reqUrl);
            if (!code.isEmpty()) {
                list.add(code);
            }
        }
        return list;
    }


    /**
     * Cache-only variant of {@link #loadRequiresForScript}: never touches the network, so it is
     * safe on the UI thread. Returns null when at least one {@code @require} is not cached yet.
     */
    public List<String> loadRequiresCachedOnly(UserscriptMeta meta) {
        List<String> list = new ArrayList<String>();
        if (meta == null) return list;
        for (String reqUrl : meta.requires) {
            if (reqUrl == null || reqUrl.trim().isEmpty()) continue;
            File f = getLocalFileForUrl(reqUrl.trim());
            if (!f.exists() || f.length() == 0) return null;
            String code = AcidIo.readFileUtf8OrEmpty(f, 15 * 1024 * 1024);
            if (code.isEmpty()) return null;
            list.add(code);
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // @resource: raw bytes, so images/fonts survive (text-only caching corrupts them)
    // -------------------------------------------------------------------------

    private static final int MAX_RESOURCE_BYTES = 15 * 1024 * 1024;

    /** Cached bytes of a resource, downloading on first use. Blocking; call off the UI thread. */
    public byte[] resolveResourceBytes(String url) {
        if (url == null || url.trim().isEmpty()) return new byte[0];
        String safeUrl = url.trim();
        File f = getResourceFile(safeUrl);
        try {
            if (f.exists() && f.length() > 0) return readAll(f);
            if (!safeUrl.startsWith("http://") && !safeUrl.startsWith("https://")) return new byte[0];
            AcidHttp.downloadToFile(safeUrl, f, 8000, 15000, MAX_RESOURCE_BYTES);
            return readAll(f);
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch userscript resource: " + safeUrl, e);
            return new byte[0];
        }
    }

    /** Resource decoded as UTF-8 text, or null when unavailable. */
    public String resolveResourceText(String url) {
        byte[] data = resolveResourceBytes(url);
        if (data.length == 0) return null;
        try {
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Resource as a {@code data:} URL, or null when unavailable. */
    public String resolveResourceDataUrl(String url) {
        byte[] data = resolveResourceBytes(url);
        if (data.length == 0) return null;
        return "data:" + guessMime(url) + ";base64,"
            + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    public static String guessMime(String url) {
        String u = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        if (u.endsWith(".css")) return "text/css";
        if (u.endsWith(".js")) return "application/javascript";
        if (u.endsWith(".json")) return "application/json";
        if (u.endsWith(".html") || u.endsWith(".htm")) return "text/html";
        if (u.endsWith(".svg")) return "image/svg+xml";
        if (u.endsWith(".png")) return "image/png";
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
        if (u.endsWith(".gif")) return "image/gif";
        if (u.endsWith(".webp")) return "image/webp";
        if (u.endsWith(".ico")) return "image/x-icon";
        if (u.endsWith(".woff2")) return "font/woff2";
        if (u.endsWith(".woff")) return "font/woff";
        if (u.endsWith(".ttf")) return "font/ttf";
        if (u.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private File getResourceFile(String url) {
        return new File(mDepsDir, "res_" + hashUrl(url));
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    public static String extractResourceUrl(String resourceDeclaration) {
        if (resourceDeclaration == null) return "";
        String trimmed = resourceDeclaration.trim();
        int space = trimmed.indexOf(' ');
        if (space < 0) {
            return trimmed.startsWith("http://") || trimmed.startsWith("https://") ? trimmed : "";
        }
        String secondPart = trimmed.substring(space + 1).trim();
        return secondPart.startsWith("http://") || secondPart.startsWith("https://") ? secondPart : "";
    }

    private File getLocalFileForUrl(String url) {
        String hash = hashUrl(url);
        String namePart = "";
        try {
            int lastSlash = url.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < url.length() - 1) {
                String sub = url.substring(lastSlash + 1);
                int q = sub.indexOf('?');
                if (q >= 0) sub = sub.substring(0, q);
                namePart = "_" + sub.replaceAll("[^a-zA-Z0-9._-]", "_");
            }
        } catch (Exception ignored) {}

        if (namePart.length() > 30) namePart = namePart.substring(0, 30);
        return new File(mDepsDir, "dep_" + hash + namePart + ".js");
    }

    private static String hashUrl(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString().substring(0, 12);
        } catch (Exception e) {
            return String.valueOf(Math.abs(url.hashCode()));
        }
    }
}
