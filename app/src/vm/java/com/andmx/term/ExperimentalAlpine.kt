package com.andmx.term

import android.content.Context
import org.andmx.runtime.LinuxRuntime

internal object ExperimentalAlpine {
    fun isAvailable(context: Context): Boolean = LinuxRuntime(context).isAvailable

    fun open(context: Context, allowNetwork: Boolean, progress: (String) -> Unit): AlpineConsole {
        val runtime = LinuxRuntime(context)
        runtime.prepare(progress)
        val console = runtime.openConsole(allowNetwork)
        return object : AlpineConsole {
            override val input get() = console.input()
            override val output get() = console.output()
            override fun stop() = console.stop()
            override fun waitFor() = console.waitFor()
            override fun close() = console.close()
        }
    }
}
