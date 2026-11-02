package com.andmx.ui2.chat

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 进程内入站入口：系统分享、外部链接、通知点击。
 *
 * 用 Channel 而不是 SharedFlow：冷启动时 MainActivity 先发事件、ChatScreen
 * 后开始收集，replay=0 的 SharedFlow 会直接丢掉第一条。
 */
object InboundBus {
    sealed interface Event {
        data class Text(val text: String) : Event
        data class Image(val uri: String, val name: String) : Event
        data class OpenConversation(val id: Long) : Event
    }

    private val channel = Channel<Event>(capacity = Channel.BUFFERED)
    val events: Flow<Event> = channel.receiveAsFlow()

    fun shareText(text: String) {
        if (text.isNotBlank()) channel.trySend(Event.Text(text))
    }

    fun shareImage(uri: String, name: String = "分享图片") {
        if (uri.isNotBlank()) channel.trySend(Event.Image(uri, name.ifBlank { "分享图片" }))
    }

    fun openConversation(id: Long) {
        if (id > 0L) channel.trySend(Event.OpenConversation(id))
    }
}
