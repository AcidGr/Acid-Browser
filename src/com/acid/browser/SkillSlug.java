package com.acid.browser;

import java.io.Serializable;

/**
 * Immutable, validated domain value object representing a canonical Agent Skill slug.
 *
 * <p>Enforces strict safety invariants:
 * <ul>
 *   <li>Guaranteed non-empty and non-blank.</li>
 *   <li>Cannot contain path traversal characters ('/', '\\', '..', or '.').</li>
 *   <li>Normalized to safe alphanumeric, underscore, hyphen, and unicode Chinese characters.</li>
 *   <li>Fallback guaranteed: if raw name sanitizes to an empty string, falls back to a safe identifier.</li>
 * </ul>
 */
public final class SkillSlug implements Serializable, Comparable<SkillSlug> {

    public static final String DEFAULT_FALLBACK = "imported_skill";

    private final String mValue;

    private SkillSlug(String value) {
        if (value == null || value.trim().isEmpty() || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("Invalid skill slug: " + value);
        }
        mValue = value;
    }

    /**
     * Parses and normalizes an untrusted string into a guaranteed-safe {@link SkillSlug}.
     *
     * @param raw      untrusted user/file/URL string
     * @param fallback fallback identifier if raw cleans to empty; if fallback also cleans to empty, {@link #DEFAULT_FALLBACK} is used
     */
    public static SkillSlug from(String raw, String fallback) {
        String clean = sanitize(raw);
        if (clean.isEmpty()) {
            clean = sanitize(fallback);
        }
        if (clean.isEmpty()) {
            clean = DEFAULT_FALLBACK;
        }
        return new SkillSlug(clean);
    }

    public static SkillSlug from(String raw) {
        return from(raw, DEFAULT_FALLBACK);
    }

    public String value() {
        return mValue;
    }

    public static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_");
        while (s.startsWith("_") || s.startsWith("-")) {
            s = s.substring(1);
        }
        while (s.endsWith("_") || s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        if (".".equals(s) || "..".equals(s)) {
            return "";
        }
        return s;
    }

    @Override
    public String toString() {
        return mValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SkillSlug skillSlug = (SkillSlug) o;
        return mValue.equals(skillSlug.mValue);
    }

    @Override
    public int hashCode() {
        return mValue.hashCode();
    }

    @Override
    public int compareTo(SkillSlug other) {
        return mValue.compareTo(other.mValue);
    }
}
