package android.net;

import java.net.URI;
import java.net.URLDecoder;

public abstract class Uri {

    public static Uri parse(String uriString) {
        return new StringUri(uriString);
    }

    public abstract String getScheme();
    public abstract String getHost();
    public abstract String getPath();
    public abstract int getPort();
    public abstract String getLastPathSegment();
    public abstract String toString();

    public static final class Builder {
        private String scheme;
        private String authority;
        private String path = "";

        public Builder scheme(String scheme) {
            this.scheme = scheme;
            return this;
        }

        public Builder authority(String authority) {
            this.authority = authority;
            return this;
        }

        public Builder appendPath(String segment) {
            if (segment != null) {
                if (this.path == null || this.path.isEmpty()) {
                    this.path = "/" + segment;
                } else if (this.path.endsWith("/")) {
                    this.path += segment;
                } else {
                    this.path += "/" + segment;
                }
            }
            return this;
        }

        public Uri build() {
            String uriStr = (scheme != null ? scheme + "://" : "")
                + (authority != null ? authority : "")
                + (path != null ? path : "");
            return new StringUri(uriStr);
        }
    }

    private static class StringUri extends Uri {
        private final String mRaw;
        private URI mParsed;

        StringUri(String raw) {
            mRaw = raw != null ? raw : "";
            try {
                mParsed = URI.create(mRaw);
            } catch (Exception ignored) {
                mParsed = null;
            }
        }

        @Override
        public String getScheme() {
            if (mParsed != null && mParsed.getScheme() != null) return mParsed.getScheme();
            int idx = mRaw.indexOf("://");
            return idx > 0 ? mRaw.substring(0, idx) : null;
        }

        @Override
        public String getHost() {
            if (mParsed != null && mParsed.getHost() != null) return mParsed.getHost();
            int schemeIdx = mRaw.indexOf("://");
            int start = schemeIdx >= 0 ? schemeIdx + 3 : 0;
            int slash = mRaw.indexOf('/', start);
            int colon = mRaw.indexOf(':', start);
            int end = mRaw.length();
            if (slash >= 0 && slash < end) end = slash;
            if (colon >= 0 && colon < end) end = colon;
            if (start < end) {
                String host = mRaw.substring(start, end).trim();
                return host.isEmpty() ? null : host;
            }
            return null;
        }

        @Override
        public String getPath() {
            if (mParsed != null && mParsed.getPath() != null) return mParsed.getPath();
            int schemeIdx = mRaw.indexOf("://");
            int start = schemeIdx >= 0 ? schemeIdx + 3 : 0;
            int slash = mRaw.indexOf('/', start);
            if (slash >= 0) {
                int q = mRaw.indexOf('?', slash);
                return q >= 0 ? mRaw.substring(slash, q) : mRaw.substring(slash);
            }
            return "";
        }

        @Override
        public int getPort() {
            if (mParsed != null) return mParsed.getPort();
            return -1;
        }

        @Override
        public String getLastPathSegment() {
            String path = getPath();
            if (path == null || path.isEmpty()) return null;
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }

        @Override
        public String toString() {
            return mRaw;
        }
    }
}
