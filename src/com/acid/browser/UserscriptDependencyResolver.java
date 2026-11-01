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
        urlsToFetch.addAll(meta.requires);
        for (String resLine : meta.resources) {
            String url = extractResourceUrl(resLine);
            if (!url.isEmpty()) urlsToFetch.add(url);
        }

        AcidAsync.run(new AcidAsync.Task<int[]>() {
            @Override
            public int[] execute() throws Exception {
                int success = 0;
                int failure = 0;
                for (String u : urlsToFetch) {
                    try {
                        String code = resolveDependency(u);
                        if (!code.isEmpty()) {
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
