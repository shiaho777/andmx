package com.andmx

import android.app.Application
import com.andmx.workspace.ProjectManager
import com.andmx.workspace.WorkspaceIndex
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AndmxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val pm = ProjectManager(this)
        pm.restoreBinding()
        WorkspaceIndex.get(this).onProjectSelected(pm.hostPath.value)
    }
}
