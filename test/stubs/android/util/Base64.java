package android.util;

public class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;

    public static String encodeToString(byte[] input, int flags) {
        if (input == null) return "";
        return java.util.Base64.getEncoder().encodeToString(input);
    }

    public static byte[] decode(String str, int flags) {
        if (str == null) return new byte[0];
        return java.util.Base64.getDecoder().decode(str);
    }
}
