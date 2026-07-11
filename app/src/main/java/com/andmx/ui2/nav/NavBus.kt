package com.andmx.ui2.nav

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 进程级导航总线：从任意子页面请求切换到某个 Tab。 */
object NavBus {
    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val requests: SharedFlow<String> = _requests.asSharedFlow()

    fun navigateTo(route: String) { _requests.tryEmit(route) }
}
