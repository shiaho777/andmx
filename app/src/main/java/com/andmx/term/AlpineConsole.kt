package com.andmx.term

import java.io.InputStream
import java.io.OutputStream

internal interface AlpineConsole : AutoCloseable {
    val input: InputStream
    val output: OutputStream
    fun waitFor(): Int
    fun stop()
}
