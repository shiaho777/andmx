package com.andmx.ui2.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatScrollMemoryTest {

    @Test
    fun positionIsScopedByConversation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("andmx_chat_scroll", Context.MODE_PRIVATE).edit().clear().commit()
        val memory = ChatScrollMemory(context)

        memory.save(1L, 12, 34)
        memory.save(2L, 5, 6)

        assertEquals(ChatScrollMemory.Position(12, 34), memory.load(1L))
        assertEquals(ChatScrollMemory.Position(5, 6), memory.load(2L))
        assertNull(memory.load(3L))
    }

    @Test
    fun clearRemovesOnlyTargetConversation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("andmx_chat_scroll", Context.MODE_PRIVATE).edit().clear().commit()
        val memory = ChatScrollMemory(context)

        memory.save(7L, 1, 2)
        memory.save(8L, 3, 4)
        memory.clear(7L)

        assertNull(memory.load(7L))
        assertEquals(ChatScrollMemory.Position(3, 4), memory.load(8L))
    }
}
