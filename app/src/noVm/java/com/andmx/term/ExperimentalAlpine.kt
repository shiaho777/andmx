package com.andmx.term

import android.content.Context

internal object ExperimentalAlpine {
    fun isAvailable(context: Context): Boolean = false

    fun open(context: Context, allowNetwork: Boolean, progress: (String) -> Unit): AlpineConsole =
        error("Experimental Alpine is unavailable in this build")
}
