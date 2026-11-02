package com.andmx.ui2.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DraftStoreTest {

    @Test
    fun draftsAreScopedByConversation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("andmx_composer_drafts", Context.MODE_PRIVATE).edit().clear().commit()
        val store = DraftStore(context)

        store.save(1L, "first draft")
        store.save(2L, "second draft")
        assertEquals("first draft", store.load(1L))
        assertEquals("second draft", store.load(2L))

        store.save(1L, "updated")
        assertEquals("updated", store.load(1L))
        assertEquals("second draft", store.load(2L))
    }

    @Test
    fun blankSaveClearsDraft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("andmx_composer_drafts", Context.MODE_PRIVATE).edit().clear().commit()
        val store = DraftStore(context)

        store.save(7L, "hello")
        store.save(7L, "   ")
        assertEquals("", store.load(7L))
    }

    @Test
    fun clearRemovesOnlyTargetConversation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("andmx_composer_drafts", Context.MODE_PRIVATE).edit().clear().commit()
        val store = DraftStore(context)

        store.save(3L, "keep")
        store.save(4L, "remove")
        store.clear(4L)
        assertEquals("keep", store.load(3L))
        assertEquals("", store.load(4L))
    }
}
