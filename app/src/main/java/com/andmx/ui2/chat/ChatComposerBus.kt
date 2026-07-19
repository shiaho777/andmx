package com.andmx.ui2.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ChatComposerBus {
    sealed class Insert {
        data class Text(val text: String) : Insert()
        data class File(val path: String) : Insert()
        data class Skill(val name: String, val path: String = "") : Insert()
        data class Command(val name: String, val payload: String = "") : Insert()
    }

    private val _inserts = MutableSharedFlow<Insert>(extraBufferCapacity = 8)
    val inserts: SharedFlow<Insert> = _inserts.asSharedFlow()

    fun insert(text: String) {
        val t = text.trim()
        when {
            t.startsWith("@") && !t.contains('\n') && t.length < 512 -> {
                val path = t.removePrefix("@").trim()
                if (path.isNotBlank()) insertFile(path) else _inserts.tryEmit(Insert.Text(text))
            }
            else -> _inserts.tryEmit(Insert.Text(text))
        }
    }

    fun insertFile(path: String) {
        if (path.isNotBlank()) _inserts.tryEmit(Insert.File(path.trim()))
    }

    fun insertSkill(name: String, path: String = "") {
        val n = name.trim().removePrefix("$").removePrefix("/")
        if (n.isNotBlank()) _inserts.tryEmit(Insert.Skill(n, path.trim()))
    }

    fun insertCommand(name: String, payload: String = "") {
        val n = name.trim().let { if (it.startsWith("/")) it else "/$it" }
        if (n.length > 1) _inserts.tryEmit(Insert.Command(n, payload.ifBlank { n }))
    }
}
