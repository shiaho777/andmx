package com.andmx.ui2.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 进程级总线：其它页面（如文件页）把内容注入到对话输入框。 */
object ChatComposerBus {
    private val _inserts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val inserts: SharedFlow<String> = _inserts.asSharedFlow()

    fun insert(text: String) {
        _inserts.tryEmit(text)
    }
}
