package android.os;

public class Looper {
    private static final Looper sMainLooper = new Looper();

    public static Looper getMainLooper() {
        return sMainLooper;
    }

    public static Looper myLooper() {
        return sMainLooper;
    }
}
