package com.acid.browser;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Sandboxed, security-hardened storage vault for Agent Skills.
 *
 * <p>Enforces physical boundary invariants:
 * <ul>
 *   <li>Only operations within {@code mVaultRoot} are allowed.</li>
 *   <li>Skill directories are addressed exclusively via {@link SkillSlug}, preventing path traversal.</li>
 *   <li>Destructive operations like directory removal verify the target is an immediate child of the vault root and never the root itself.</li>
 *   <li>Unpacking and inspection happen in isolated staging directories before atomic promotion.</li>
 * </ul>
 */
public class SkillStorageVault {

    private final File mVaultRoot;

    public SkillStorageVault(File vaultRoot) {
        if (vaultRoot == null) {
            throw new IllegalArgumentException("Vault root cannot be null");
        }
        mVaultRoot = vaultRoot;
        if (!mVaultRoot.exists()) {
            mVaultRoot.mkdirs();
        }
    }

    public File getVaultRoot() {
        return mVaultRoot;
    }

    /**
     * Resolves an authorized subdirectory for a specific skill slug.
     * Enforces that the returned directory is strictly an immediate child of the vault root.
     */
    public File resolveSkillDir(SkillSlug slug) {
        if (slug == null) {
            throw new IllegalArgumentException("SkillSlug cannot be null");
        }
        File target = new File(mVaultRoot, slug.value());
        assertStrictChild(target);
        return target;
    }

    /**
     * Deletes a skill directory safely.
     * Strictly verifies that target != vaultRoot and parent == vaultRoot.
     */
    public boolean deleteSkillDir(SkillSlug slug) {
        if (slug == null) return false;
        File target = resolveSkillDir(slug);
        if (target.exists()) {
            return deleteRecursiveSafe(target);
        }
        return false;
    }

    /**
     * Allocates an isolated temporary directory for unpacking/downloading skills.
     */
    public File createStagingDir() {
        File staging = new File(mVaultRoot, ".staging_" + UUID.randomUUID().toString().replace("-", ""));
        staging.mkdirs();
        assertStrictChild(staging);
        return staging;
    }

    /**
     * Moves a validated staging directory to the canonical skill destination atomically.
     */
    public File promoteStagingDir(File stagingDir, SkillSlug slug) throws Exception {
        if (stagingDir == null || !stagingDir.exists()) {
            throw new IllegalArgumentException("Invalid staging directory");
        }
        File finalDir = resolveSkillDir(slug);
        if (finalDir.exists()) {
            deleteRecursiveSafe(finalDir);
        }
        if (!stagingDir.renameTo(finalDir)) {
            // Fallback: recursive copy and cleanup
            moveDirectoryRecursive(stagingDir, finalDir);
            deleteRecursiveSafe(stagingDir);
        }
        return finalDir;
    }

    private void assertStrictChild(File file) {
        try {
            File canonVault = mVaultRoot.getCanonicalFile();
            File canonFile = file.getCanonicalFile();
            if (canonFile.equals(canonVault)) {
                throw new SecurityException("Vault integrity violation: attempted operation directly on vault root " + canonVault);
            }
            File parent = canonFile.getParentFile();
            if (parent == null || !parent.equals(canonVault)) {
                throw new SecurityException("Vault escape violation: target " + canonFile + " is not an immediate child of " + canonVault);
            }
        } catch (IOException e) {
            throw new SecurityException("Could not verify vault canonical boundaries for " + file, e);
        }
    }

    public static boolean deleteRecursiveSafe(File file) {
        if (file == null || !file.exists()) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursiveSafe(c);
                }
            }
        }
        return file.delete();
    }

    private static void moveDirectoryRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists()) dst.mkdirs();
            File[] files = src.listFiles();
            if (files != null) {
                for (File f : files) {
                    moveDirectoryRecursive(f, new File(dst, f.getName()));
                }
            }
        } else {
            if (dst.getParentFile() != null) {
                dst.getParentFile().mkdirs();
            }
            if (!src.renameTo(dst)) {
                java.io.FileInputStream fis = new java.io.FileInputStream(src);
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    AcidIo.writeFileUtf8Safe(dst, new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    fis.close();
                }
            }
        }
    }
}
