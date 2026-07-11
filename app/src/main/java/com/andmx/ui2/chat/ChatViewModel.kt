package com.andmx.ui2.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val controller = ChatController(context)
    private val settingsStore = SettingsStore(context)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _toolCalls = MutableStateFlow<List<ToolCall>>(emptyList())
    val toolCalls: StateFlow<List<ToolCall>> = _toolCalls.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 待发送消息队列（生成中入队，本轮结束自动继续）。 */
    private val _queue = MutableStateFlow<List<String>>(emptyList())
    val queue: StateFlow<List<String>> = _queue.asStateFlow()

    private var currentConversationId = 1L
    private var currentAssistantText = ""
    private var turnJob: Job? = null

    /** 发送消息。生成中时行为取决于交互行为设置：queue=入队，guide=同样入队等待本轮结束。 */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (_isLoading.value) {
            _queue.value = _queue.value + trimmed
            return
        }
        runTurn(trimmed)
    }

    private fun runTurn(text: String) {
        turnJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                controller.sendMessage(currentConversationId, text).collect { event ->
                    handleEvent(event)
                }
            } finally {
                _isLoading.value = false
                drainQueue()
            }
        }
    }

    /** 停止当前生成。 */
    fun stop() {
        turnJob?.cancel()
        turnJob = null
        _isLoading.value = false
        // 收尾：把流式中的最后一条标记为完成
        val current = _messages.value.toMutableList()
        val idx = current.indexOfLast { it.role == "assistant" && it.isStreaming }
        if (idx >= 0) current[idx] = current[idx].copy(isStreaming = false)
        _messages.value = current
        currentAssistantText = ""
    }

    private fun drainQueue() {
        val next = _queue.value.firstOrNull() ?: return
        _queue.value = _queue.value.drop(1)
        runTurn(next)
    }

    fun removeFromQueue(index: Int) {
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
    }

    /** 立即发送队列中的某条（若空闲）。 */
    fun sendQueuedNow(index: Int) {
        val item = _queue.value.getOrNull(index) ?: return
        if (_isLoading.value) return
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
        runTurn(item)
    }

    private fun handleEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.UserMessage -> {
                _messages.value += ChatMessage(role = "user", content = event.text)
            }
            is ChatEvent.AssistantChunk -> {
                currentAssistantText += event.text
                val current = _messages.value.toMutableList()
                val lastIndex = current.indexOfLast { it.role == "assistant" && it.isStreaming }
                if (lastIndex >= 0) {
                    current[lastIndex] = current[lastIndex].copy(content = currentAssistantText)
                } else {
                    current.add(ChatMessage(role = "assistant", content = currentAssistantText, isStreaming = true))
                }
                _messages.value = current
            }
            is ChatEvent.AssistantComplete -> {
                currentAssistantText = ""
                val current = _messages.value.toMutableList()
                val lastIndex = current.indexOfLast { it.role == "assistant" }
                if (lastIndex >= 0) {
                    current[lastIndex] = current[lastIndex].copy(content = event.fullText, isStreaming = false)
                } else {
                    current.add(ChatMessage(role = "assistant", content = event.fullText, isStreaming = false))
                }
                _messages.value = current
            }
            is ChatEvent.ToolCallStarted -> {
                _toolCalls.value += ToolCall(id = event.id, name = event.name, args = event.args, isRunning = true)
            }
            is ChatEvent.ToolCallFinished -> {
                val current = _toolCalls.value.toMutableList()
                val index = current.indexOfFirst { it.id == event.id }
                if (index >= 0) {
                    current[index] = current[index].copy(
                        output = event.output, isRunning = false, isError = event.isError
                    )
                }
                _toolCalls.value = current
            }
            is ChatEvent.Error -> _error.value = event.message
            is ChatEvent.Done -> {}
        }
    }

    fun clearError() { _error.value = null }
}
