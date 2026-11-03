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
class WorkflowActorStoreTest {

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
    fun saveLoadAndDeleteByRun() = runBlocking {
        val store = WorkflowActorStore(context)
        store.save("session:dwf_1:actor#1@1", "dwf_1", """[{"role":"user","content":"hi"}]""")
        store.save("session:dwf_2:actor#1@1", "dwf_2", """[{"role":"user","content":"bye"}]""")

        assertEquals(
            """[{"role":"user","content":"hi"}]""",
            store.load("session:dwf_1:actor#1@1"),
        )

        store.deleteRun("dwf_1")
        assertNull(store.load("session:dwf_1:actor#1@1"))
        assertEquals(
            """[{"role":"user","content":"bye"}]""",
            store.load("session:dwf_2:actor#1@1"),
        )
    }
}
