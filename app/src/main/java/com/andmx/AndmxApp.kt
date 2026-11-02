package com.andmx

import android.app.Application
import com.andmx.agent.hooks.HookTrustStore
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
        WorkspaceIndex.get(this).onProjectSelected(pm.hostPath.value)
    }
}
