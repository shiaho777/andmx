package com.andmx

import android.app.Application
import com.andmx.agent.ModelCallTrace
import com.andmx.agent.hooks.HookTrustStore
import com.andmx.automation.AutomationWake
import com.andmx.ui2.chat.ChatController
import com.andmx.workspace.ChangeTracker
import com.andmx.workspace.ProjectManager
import com.andmx.workspace.WorkspaceIndex
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AndmxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val pm = ProjectManager(this)
        pm.restoreBinding()
        ChangeTracker.install(this)
        HookTrustStore.install(this)
        ModelCallTrace.install(this)
        WorkspaceIndex.get(this).onProjectSelected(pm.hostPath.value)
        AutomationWake.onDue = { ChatController.get(this).runDueAutomationsNow() }
    }
}
