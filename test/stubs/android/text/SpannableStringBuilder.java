package android.text;

public class SpannableStringBuilder implements CharSequence, Appendable {

    private final StringBuilder mSb;

    public SpannableStringBuilder() {
        mSb = new StringBuilder();
    }

    public SpannableStringBuilder(CharSequence text) {
        mSb = new StringBuilder(text != null ? text : "");
    }

    @Override
    public int length() {
        return mSb.length();
    }

    @Override
    public char charAt(int index) {
        return mSb.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return mSb.subSequence(start, end);
    }

    @Override
    public SpannableStringBuilder append(CharSequence csq) {
        mSb.append(csq);
        return this;
    }

    @Override
    public SpannableStringBuilder append(CharSequence csq, int start, int end) {
        mSb.append(csq, start, end);
        return this;
    }

    @Override
    public SpannableStringBuilder append(char c) {
        mSb.append(c);
        return this;
    }

    public SpannableStringBuilder replace(int start, int end, CharSequence tb) {
        mSb.replace(start, end, tb.toString());
        return this;
    }

    public void setSpan(Object what, int start, int end, int flags) {
        // No-op in JVM unit testing
    }

    @Override
    public String toString() {
        return mSb.toString();
    }
}
