package com.andmx.agent.memory

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.exec.files.GuestFs
import com.andmx.exec.proot.ProotRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MemoryDreamTest {

    private lateinit var system: MemorySystem

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fs = GuestFs(ProotRuntime(context))
        system = MemorySystem(fs, MemoryConfig(maxRawMemoriesForConsolidation = 100))
        system.clear()
    }

    private fun raw(content: String, category: MemorySystem.MemoryCategory) =
        MemorySystem.RawMemory(
            content = content,
            category = category,
            sessionId = "7",
            timestamp = "2026-02-0${(content.hashCode() and 0x7fffffff) % 9 + 1}T00:00:00Z",
        )

    @Test
    fun dreamDeduplicatesAndRebuildsConsolidatedMemory() = runBlocking {
        system.appendRaw(raw("用户偏好: 用 pnpm 而不是 npm", MemorySystem.MemoryCategory.USER_PREFERENCE))
        system.appendRaw(raw("用户偏好: 用 pnpm 而不是 npm", MemorySystem.MemoryCategory.USER_PREFERENCE))
        system.appendRaw(raw("仓库入口: app/build.gradle", MemorySystem.MemoryCategory.REPO_MAP))

        val result = system.dream()

        assertEquals(3, result.before)
        assertEquals(2, result.after)
        assertEquals(1, result.removed)
        assertTrue(result.consolidated)
        assertEquals(2, system.loadRawMemories().size)
        val snapshot = system.load()
        assertTrue(snapshot.memoryContent.contains("pnpm"))
        assertTrue(snapshot.memoryContent.contains("build.gradle"))
        assertTrue(snapshot.summary.isNotBlank())
    }

    @Test
    fun dreamCapsRawMemoriesToTheConfiguredMaximum() = runBlocking {
        repeat(205) { i ->
            system.appendRaw(
                raw("故障: unique failure $i", MemorySystem.MemoryCategory.FAILURE_SHIELD),
            )
        }

        val result = system.dream()

        assertEquals(205, result.before)
        assertEquals(200, result.after)
        assertEquals(200, system.loadRawMemories().size)
    }

    @Test
    fun recallFragmentIsEmptyWithoutHits() = runBlocking {
        system.appendRaw(raw("用户偏好: 使用 pnpm", MemorySystem.MemoryCategory.USER_PREFERENCE))

        assertTrue(system.recallFragment("kubernetes").isEmpty())
        assertTrue(system.recallFragment("pnpm 安装").contains("pnpm"))
    }
}
