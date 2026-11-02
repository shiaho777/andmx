package com.andmx.agent.hooks

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.exec.ExecutionEnvironment
import com.andmx.exec.ProcessResult
import com.andmx.exec.ProcessSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HookSystemTest {

    private class RecordingEnvironment(private val stdout: String = "{}") : ExecutionEnvironment {
        var last: ProcessSpec? = null
        override val id: String = "recording"
        override val displayName: String = "Recording"
        override suspend fun isAvailable(): Boolean = true
        override suspend fun execute(spec: ProcessSpec): ProcessResult {
            last = spec
            return ProcessResult(exitCode = 0, stdout = stdout, stderr = "", durationMs = 1)
        }
    }

    @Test
    fun projectHookRunsInWorkspaceDirectory() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = RecordingEnvironment()
        val system = HookSystem(context)
        system.executor = environment
        system.replaceHooks(
            listOf(
                HookSystem.HookConfig(
                    event = HookSystem.HookEvent.PRE_TOOL_USE,
                    command = "./guard.sh",
                    cwd = "/root/project",
                    source = HookSystem.HookSource.PROJECT,
                ),
            ),
        )

        system.runEvent(HookSystem.HookEvent.PRE_TOOL_USE, HookSystem.HookContext(toolName = "Bash"))

        assertEquals("/root/project", environment.last?.workingDir)
        assertEquals(listOf("/bin/sh", "-c", "./guard.sh"), environment.last?.argv)
    }

    @Test
    fun blockDecisionFromHookStopsTheEvent() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val system = HookSystem(context)
        system.executor = RecordingEnvironment("""{"decision":"block","message":"denied"}""")
        system.replaceHooks(
            listOf(
                HookSystem.HookConfig(
                    event = HookSystem.HookEvent.USER_PROMPT_SUBMIT,
                    command = "exit 1",
                ),
            ),
        )

        val result = system.runEvent(HookSystem.HookEvent.USER_PROMPT_SUBMIT)

        assertEquals(HookSystem.HookDecision.BLOCK, result.decision)
        assertEquals("denied", result.message)
    }

    @Test
    fun matcherFiltersByToolName() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = RecordingEnvironment()
        val system = HookSystem(context)
        system.executor = environment
        system.replaceHooks(
            listOf(
                HookSystem.HookConfig(
                    event = HookSystem.HookEvent.PRE_TOOL_USE,
                    command = "echo matched",
                    matcher = "Write|Edit",
                ),
            ),
        )

        system.runEvent(HookSystem.HookEvent.PRE_TOOL_USE, HookSystem.HookContext(toolName = "Bash"))
        assertTrue(environment.last == null)

        system.runEvent(HookSystem.HookEvent.PRE_TOOL_USE, HookSystem.HookContext(toolName = "Write"))
        assertEquals(listOf("/bin/sh", "-c", "echo matched"), environment.last?.argv)
    }
}
