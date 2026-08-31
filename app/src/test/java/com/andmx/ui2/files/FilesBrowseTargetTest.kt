package com.andmx.ui2.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilesBrowseTargetTest {

    private fun tempTree(): File {
        val root = Files.createTempDirectory("andmx-ws").toFile()
        File(root, "app/src/Main.kt").apply { requireNotNull(parentFile).mkdirs() }.writeText("fun main() = Unit")
        File(root, "README.md").writeText("# demo")
        return root
    }

    @Test
    fun blankPathOpensWorkspaceRoot() {
        val root = tempTree()
        try {
            val target = resolveLocalBrowseTarget(null, root.absolutePath, root.absolutePath, "/root/project")
            assertEquals(root.absolutePath, target.dir)
            assertNull(target.file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun guestMountPathMapsToHostProject() {
        val root = tempTree()
        try {
            val target = resolveLocalBrowseTarget(
                "/root/project/app/src/Main.kt", root.absolutePath, root.absolutePath, "/root/project",
            )
            assertEquals(File(root, "app/src").absolutePath, target.dir)
            assertEquals(File(root, "app/src/Main.kt").absolutePath, target.file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tildeExpandsToGuestRootAndMapsIntoProject() {
        val root = tempTree()
        try {
            val target = resolveLocalBrowseTarget("~/app/src/Main.kt", root.absolutePath, root.absolutePath, "/root/project")
            assertEquals(File(root, "app/src").absolutePath, target.dir)
            assertEquals(File(root, "app/src/Main.kt").absolutePath, target.file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bareGuestRootOpensProjectDirNotFilesystemRoot() {
        val root = tempTree()
        try {
            for (path in listOf("/root", "~/", "~")) {
                val target = resolveLocalBrowseTarget(path, root.absolutePath, root.absolutePath, "/root/project")
                assertEquals("path=$path", root.absolutePath, target.dir)
                assertNull(target.file)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun guestRootfsInternalPathFallsBackToProjectNotRoot() {
        val root = tempTree()
        try {
            val target = resolveLocalBrowseTarget(
                "/root/project/README.md", root.absolutePath, root.absolutePath, "/root/project",
                rootfsRoot = "/data/data/com.andmx/files/rootfs",
            )
            assertEquals(root.absolutePath, target.dir)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun relativePathResolvesUnderProject() {
        val root = tempTree()
        try {
            val target = resolveLocalBrowseTarget(
                "app/src/Main.kt", root.absolutePath, root.absolutePath, "/root/project",
            )
            assertEquals(File(root, "app/src").absolutePath, target.dir)
            assertEquals(File(root, "app/src/Main.kt").absolutePath, target.file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingPathClimbsToNearestExistingDirectory() {
        val root = tempTree()
        try {
            val target = resolveLocalBrowseTarget(
                "app/build/outputs/NotYet.kt", root.absolutePath, root.absolutePath, "/root/project",
            )
            assertEquals(File(root, "app").absolutePath, target.dir)
            assertNull(target.file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileTargetOpensViewerDirectly() {
        val root = tempTree()
        try {
            val target = resolveLocalBrowseTarget(
                "README.md", root.absolutePath, root.absolutePath, "/root/project",
            )
            assertEquals(root.absolutePath, target.dir)
            assertEquals(File(root, "README.md").absolutePath, target.file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun remoteRelativePathResolvesUnderRemoteRoot() {
        assertEquals("/home/u/app/app/src", resolveRemoteBrowsePath("app/src", "/home/u/app"))
    }

    @Test
    fun remoteFilePathResolvesToItselfAndBrowserFallsBackToParent() {
        assertEquals("/home/u/app/Main.kt", resolveRemoteBrowsePath("/home/u/app/Main.kt", "/home/u/app"))
        assertEquals("/home/u/app", remoteParentDir("/home/u/app/Main.kt", "/home/u/app"))
    }

    @Test
    fun remoteHomePathExpandsToRemoteRoot() {
        assertEquals("/home/u/app/Main.kt", resolveRemoteBrowsePath("~/Main.kt", "/home/u/app"))
        assertEquals("/home/u/app", resolveRemoteBrowsePath("~", "/home/u/app"))
    }

    @Test
    fun remoteParentDirClimbsAndStopsAtRoot() {
        assertEquals("/home/u/app", remoteParentDir("/home/u/app/src", "/home/u/app"))
        assertEquals("/", remoteParentDir("/home", "/home/u/app"))
        assertEquals("/home/u/app", remoteParentDir("/", "/home/u/app"))
        assertEquals("/home/u", remoteParentDir("/home/u/app/", "/home/u/app"))
        assertEquals("/home/u", remoteParentDir("/home/u/app", "/"))
        assertEquals("/", remoteParentDir("/Main.kt", "/"))
    }
}
