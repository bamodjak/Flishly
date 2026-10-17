package com.flishly.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PathGuardTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `root itself is inside`() {
        assertTrue(PathGuard.isInside(root, root))
    }

    @Test
    fun `nested path is inside`() {
        assertTrue(PathGuard.isInside(root, "$root/Documents/file.txt"))
    }

    @Test
    fun `traversal to parent is rejected`() {
        assertFalse(PathGuard.isInside(root, "/storage/emulated/0../evil"))
    }

    @Test
    fun `sibling directory with similar prefix is rejected`() {
        assertFalse(PathGuard.isInside(root, "/storage/emulated/0_backup/x"))
    }

    @Test
    fun `completely unrelated path is rejected`() {
        assertFalse(PathGuard.isInside(root, "/data/data/com.flishly.server/databases"))
    }

    @Test
    fun `isRoot matches only the root itself`() {
        assertTrue(PathGuard.isRoot(root, root))
        assertFalse(PathGuard.isRoot(root, "$root/sub"))
    }

    @Test
    fun `safeFileName rejects dot and dotdot`() {
        assertNull(PathGuard.safeFileName(".."))
        assertNull(PathGuard.safeFileName("."))
    }

    @Test
    fun `safeFileName strips directory components`() {
        assertEquals("evil.txt", PathGuard.safeFileName("../../etc/evil.txt"))
        assertEquals("evil.txt", PathGuard.safeFileName("/abs/path/evil.txt"))
    }

    @Test
    fun `safeFileName rejects blank and null bytes`() {
        assertNull(PathGuard.safeFileName("   "))
        assertNull(PathGuard.safeFileName("bad\u0000name"))
    }

    @Test
    fun `safeFileName keeps normal names`() {
        assertEquals("photo (1).jpg", PathGuard.safeFileName("photo (1).jpg"))
        assertEquals(".hidden", PathGuard.safeFileName(".hidden"))
    }

    @Test
    fun `file resolution stays within root for traversal attempts`() {
        val rootDir = File("/data/local/tmp/flishly-test-root").apply { mkdirs() }
        val target = File(rootDir, "../outside.txt")
        val canonicalRoot = rootDir.canonicalPath
        assertFalse(PathGuard.isInside(canonicalRoot, target.canonicalPath))
    }
}
