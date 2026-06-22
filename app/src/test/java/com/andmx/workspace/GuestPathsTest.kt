package com.andmx.workspace

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestPathsTest {

    @Test
    fun relativePathsResolveUnderRootHome() {
        assertEquals("/root/app/src/Main.kt", GuestPaths.normalize("app/src/Main.kt"))
        assertEquals("/root/Main.kt", GuestPaths.normalize("./app/../Main.kt"))
    }

    @Test
    fun absolutePathsStayAtGuestRoot() {
        assertEquals("/etc/hosts", GuestPaths.normalize("/etc/hosts"))
        assertFalse(GuestPaths.same("/app.py", "app.py"))
    }

    @Test
    fun rootAbsoluteAndRelativeWorkspacePathsMatch() {
        assertTrue(GuestPaths.same("/root/app.py", "app.py"))
        assertTrue(GuestPaths.same("/root/src/../app.py", "./app.py"))
    }

    @Test
    fun buildsCodexStyleMentionReference() {
        assertEquals("@/root/app.py ", GuestPaths.reference("app.py"))
    }

    @Test
    fun mapsBetweenRootfsFilesAndGuestPaths() {
        val root = Files.createTempDirectory("andmx-rootfs").toFile()
        try {
            val file = File(root, "root/src/Main.kt")
            requireNotNull(file.parentFile).mkdirs()
            file.writeText("fun main() = Unit")

            assertEquals("/", GuestPaths.fromRootFile(root, root))
            assertEquals("/root/src/Main.kt", GuestPaths.fromRootFile(root, file))
            assertEquals(file.canonicalFile, GuestPaths.resolve(root, "src/Main.kt"))
            assertEquals(File(root, "etc/hosts").canonicalFile, GuestPaths.resolve(root, "/etc/hosts"))
        } finally {
            root.deleteRecursively()
        }
    }
}
