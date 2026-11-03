package com.andmx.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkflowJournalStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndmxDatabase.resetForTests()
        context.deleteDatabase("andmx.db")
    }

    @After
    fun tearDown() {
        AndmxDatabase.resetForTests()
    }

    @Test
    fun saveLoadAndDeleteRoundTrips() = runBlocking {
        val store = WorkflowJournalStore(context)
        store.save("dwf_1", """{"runs":[{"runId":"dwf_1"}]}""")

        assertEquals("""{"runs":[{"runId":"dwf_1"}]}""", store.load("dwf_1"))

        store.save("dwf_1", """{"runs":[{"runId":"dwf_1","status":"running"}]}""")
        assertEquals(
            """{"runs":[{"runId":"dwf_1","status":"running"}]}""",
            store.load("dwf_1"),
        )

        store.delete("dwf_1")
        assertNull(store.load("dwf_1"))
    }
}
