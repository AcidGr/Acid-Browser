package android.graphics;

public final class Color {
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int TRANSPARENT = 0x00000000;

    private Color() {}

    public static int alpha(int color) { return color >>> 24; }
    public static int red(int color) { return (color >> 16) & 0xFF; }
    public static int green(int color) { return (color >> 8) & 0xFF; }
    public static int blue(int color) { return color & 0xFF; }

    public static int argb(int alpha, int red, int green, int blue) {
        return ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    public static int rgb(int red, int green, int blue) {
        return argb(255, red, green, blue);
    }

    public static int parseColor(String colorString) {
        if (colorString == null || colorString.isEmpty() || colorString.charAt(0) != '#') {
            throw new IllegalArgumentException("Unknown color: " + colorString);
        }
        long color = Long.parseLong(colorString.substring(1), 16);
        if (colorString.length() == 7) {
            color |= 0x00000000ff000000L;
        } else if (colorString.length() != 9) {
            throw new IllegalArgumentException("Unknown color: " + colorString);
        }
        return (int) color;
    }
}
