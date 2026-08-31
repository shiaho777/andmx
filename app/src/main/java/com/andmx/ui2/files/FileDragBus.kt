package com.andmx.ui2.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 文件树长按拖拽 → 对话框引用的会话总线（ZCode 对齐）。
 *
 * ZCode 用 HTML5 drag + 自定义 MIME `application/x-zcode-workspace-file` 传
 * `{type, workspacePath, workspaceIdentity, path, relativePath, name}`；
 * Android 无跨 view 拖放协议，源（侧边栏抽屉）与目标（对话 Composer）同在
 * 一个 window 内，故以纯 Compose 坐标流 + 本单例承载同一份数据。
 */
object FileDragBus {

    enum class Kind { FILE, DIRECTORY }

    data class Payload(
        val kind: Kind,
        val name: String,
        val path: String,
        val relativePath: String,
    )

    data class State(
        val payload: Payload? = null,
        val position: androidx.compose.ui.geometry.Offset? = null,
        val overComposer: Boolean = false,
    ) {
        val dragging: Boolean get() = payload != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(payload: Payload, position: androidx.compose.ui.geometry.Offset) {
        _state.value = State(payload = payload, position = position)
    }

    fun update(position: androidx.compose.ui.geometry.Offset, overComposer: Boolean) {
        val s = _state.value
        if (s.payload == null) return
        _state.value = s.copy(position = position, overComposer = overComposer)
    }

    fun finish(): Payload? {
        val s = _state.value
        _state.value = State()
        return s.payload?.takeIf { s.overComposer }
    }

    fun cancel() {
        _state.value = State()
    }
}
