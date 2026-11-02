package com.andmx.ui2.terminal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.andmx.term.TerminalSession

/**
 * 进程级终端会话表。TerminalScreen 是浮层，关掉它不应该杀掉 PTY；
 * 会话活到进程结束，用户重新打开终端还能接上。
 */
object TerminalSessionRegistry {
    private val _sessions = mutableStateListOf<TerminalSession>()
    val sessions: List<TerminalSession> get() = _sessions

    var activeIndex by mutableIntStateOf(0)

    fun ensureSession(context: Context) {
        if (_sessions.isEmpty()) newSession(context)
    }

    fun newSession(context: Context): TerminalSession {
        val session = TerminalSession(context.applicationContext)
        _sessions.add(session)
        activeIndex = _sessions.lastIndex
        runCatching { session.start() }
        return session
    }

    fun close(index: Int) {
        if (index !in _sessions.indices) return
        val wasActive = index == activeIndex
        _sessions[index].destroy()
        _sessions.removeAt(index)
        activeIndex = when {
            _sessions.isEmpty() -> 0
            index < activeIndex -> (activeIndex - 1).coerceAtLeast(0)
            wasActive -> activeIndex.coerceAtMost(_sessions.lastIndex)
            else -> activeIndex.coerceAtMost(_sessions.lastIndex)
        }
    }

    fun destroyAll() {
        _sessions.forEach { it.destroy() }
        _sessions.clear()
        activeIndex = 0
    }
}
