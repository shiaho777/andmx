package com.andmx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andmx.agent.ShellTool
import com.andmx.exec.ProcessSpec
import com.andmx.exec.alpine.AlpineVm
import com.andmx.workspace.ProjectManager
import com.andmx.workspace.WorkspaceAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AlpineRuntimeTest {
    @Test
    fun embeddedAlpineStartsOfflineAndSharesTheProjectWithShellAndTerminal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = AlpineVm.get(context)
        assumeTrue(vm.isAvailable)
        val workspaceParent = InstrumentationRegistry.getArguments().getString("alpineWorkspaceParent")
            ?.let(::File) ?: context.cacheDir
        val workspace = Files.createTempDirectory(workspaceParent.toPath(), "alpine-project-").toFile()
        val projects = ProjectManager(context)
        val access = WorkspaceAccess(context)
        projects.selectProject(workspace.path)
        try {
            access.writeText("from-android.txt", "android")
            vm.start(
                ProcessSpec(listOf("/bin/sh", "-c",
                    "test ! -d /sys/class/net/eth0 && test -f /etc/alpine-release && cat from-android.txt && printf alpine > from-alpine.txt"),
                    workingDir = access.guestCwd()),
                workspace,
                network = false,
            ).use { process ->
                process.outputStream.close()
                assertTrue(process.waitFor(25, TimeUnit.SECONDS))
                assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
                assertEquals("android", process.inputStream.bufferedReader().readText())
            }
            assertEquals("alpine", access.readText("from-alpine.txt"))
            assertEquals("alpine", File(workspace, "from-alpine.txt").readText())
            assertTrue(access.readText("/etc/alpine-release").startsWith("3.24."))
            access.writeText("/tmp/andmx-stdin.txt", "中文\"\u0001")
            assertEquals("中文\"\u0001", access.readText("/tmp/andmx-stdin.txt"))
            assertTrue(access.deleteFile("/tmp/andmx-stdin.txt"))
            assertFalse(access.exists("/tmp/andmx-stdin.txt"))

            val result = ShellTool(context).execute(buildJsonObject { put("command", "printf 'agent'; exit 7") })
            assertTrue(result.isError)
            assertTrue(result.output, result.output.contains("agent\n[exit=7]"))

            val terminal = vm.start(
                ProcessSpec(listOf("/bin/sh", "-i"), workingDir = access.guestCwd()),
                workspace,
                rows = 24,
                cols = 80,
            )
            terminal.use {
                terminal.outputStream.write("test -t 0 && printf 'tty-%s' ok; exit 0\n".toByteArray())
                terminal.outputStream.flush()
                assertTrue(terminal.waitFor(25, TimeUnit.SECONDS))
                assertEquals(0, terminal.exitValue())
                assertTrue(terminal.inputStream.bufferedReader().readText().contains("tty-ok"))
            }
            vm.start(ProcessSpec(listOf("/bin/sh", "-c", "sleep 30")), workspace).use { cancelled ->
                cancelled.destroy()
                assertTrue(cancelled.waitFor(5, TimeUnit.SECONDS))
                assertFalse(cancelled.isAlive)
                assertEquals(137, cancelled.exitValue())
            }
        } finally {
            projects.clearProject()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun installedPackageRemainsAvailableAfterRestartingAlpineOffline() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("alpinePackageInstall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = AlpineVm.get(context)
        assumeTrue(vm.isAvailable)
        val workspace = Files.createTempDirectory(context.cacheDir.toPath(), "alpine-git-").toFile()
        val cwd = "/root/project"
        val proxy = InstrumentationRegistry.getArguments().getString("alpineHttpsProxy")
        val env = if (proxy == null) emptyMap() else mapOf("https_proxy" to proxy, "http_proxy" to proxy)
        try {
            vm.start(
                ProcessSpec(
                    listOf("/bin/sh", "-c", "timeout 45 apk add --no-cache git && git init -q && git status --porcelain"),
                    workingDir = cwd,
                    env = env,
                    redirectErrorStream = true,
                ),
                workspace,
                network = true,
            ).use { process ->
                process.outputStream.close()
                assertTrue("Alpine package install timed out", process.waitFor(80, TimeUnit.SECONDS))
                assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
            }
            assertTrue(File(workspace, ".git").isDirectory)
            vm.start(
                ProcessSpec(listOf("/bin/sh", "-c", "test ! -d /sys/class/net/eth0 && git rev-parse --is-inside-work-tree"),
                    workingDir = cwd, redirectErrorStream = true),
                workspace,
                network = false,
            ).use { process ->
                process.outputStream.close()
                assertTrue(process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                assertEquals("true", process.inputStream.bufferedReader().readText().trim())
            }
        } finally {
            workspace.deleteRecursively()
        }
    }
}
