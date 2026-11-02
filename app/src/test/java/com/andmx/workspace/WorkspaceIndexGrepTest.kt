package com.andmx.workspace

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceIndexGrepTest {

    private lateinit var context: Context
    private lateinit var project: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        project = Files.createTempDirectory("andmx-index-test").toFile()
        File(project, "app").mkdirs()
        File(project, "app/Main.kt").writeText("fun main() = needle\n")
        File(project, "app/notes.md").writeText("needle in markdown\n")
        File(project, "app/other.txt").writeText("unrelated\n")
        SettingsStore(context).update(
            ProviderSettings(
                indexNewFolders = true,
                indexNewFoldersUserConfigured = true,
                instantGrep = true,
            ),
        )
        ProjectManager(context).selectProject(project.absolutePath)
        awaitReady()
    }

    @After
    fun tearDown() = runBlocking {
        WorkspaceIndex.get(context).clearCurrent()
        ProjectManager(context).clearProject()
        project.deleteRecursively()
        SettingsStore(context).update(
            ProviderSettings(indexNewFolders = false, indexNewFoldersUserConfigured = false, instantGrep = false),
        )
    }

    private suspend fun awaitReady() {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            if (WorkspaceIndex.get(context).status.value.phase == WorkspaceIndexPhase.READY) return
            delay(50)
        }
        error("index did not become ready: ${WorkspaceIndex.get(context).status.value}")
    }

    @Test
    fun indexedGrepAndGlobAnswerFromTheProjectIndex() = runBlocking {
        val index = WorkspaceIndex.get(context)

        val hits = index.grepIndexed(project.absolutePath, "needle")
        assertNotNull(hits)
        assertTrue(hits.toString(), hits!!.any { it.startsWith("app/Main.kt:") })
        assertTrue(hits.toString(), hits.any { it.startsWith("app/notes.md:") })

        val globbed = index.globIndexed(project.absolutePath, "**/*.kt", 100)
        assertEquals(listOf("app/Main.kt"), globbed)
    }

    @Test
    fun invalidatedIndexFallsBackUntilRebuilt() = runBlocking {
        val index = WorkspaceIndex.get(context)
        assertNotNull(index.grepIndexed(project.absolutePath, "needle"))

        val target = File(project, "app/Main.kt").canonicalPath
        val rootCanonical = project.canonicalPath
        assertTrue("target=$target root=$rootCanonical", target.startsWith("$rootCanonical/"))
        index.invalidatePath(File(project, "app/Main.kt").absolutePath, project.absolutePath)
        assertTrue(
            "dirty roots did not include ${project.absolutePath}; host=${ProjectManager(context).hostPath.value}",
            index.isDirtyForTests(project.absolutePath),
        )
        assertNull(index.grepIndexed(project.absolutePath, "needle"))

        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            val hits = index.grepIndexed(project.absolutePath, "needle")
            if (hits != null) return@runBlocking
            delay(100)
        }
        error("index did not rebuild after invalidation")
    }
}
