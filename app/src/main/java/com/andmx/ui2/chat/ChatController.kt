package com.andmx.ui2.chat

import android.content.Context
import com.andmx.agent.AgentEngine
import com.andmx.agent.AgentEvent
import com.andmx.agent.TurnContext
import com.andmx.data.ConversationRepository
import com.andmx.llm.LlmClient
import com.andmx.settings.ProviderStore
import com.andmx.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow

class ChatController(private val context: Context) {
    private val settingsStore = SettingsStore(context)
    private val providerStore = ProviderStore(context)
    private val repo = ConversationRepository(context)
    
    suspend fun sendMessage(
        conversationId: Long,
        text: String
    ): Flow<ChatEvent> = flow {
        emit(ChatEvent.UserMessage(text))
        
        repo.addMessage(conversationId, "user", text)
        
        val settings = settingsStore.settings.firstOrNull()
            ?: return@flow emit(ChatEvent.Error("设置未初始化"))

        if (!settings.hasSelection) {
            return@flow emit(ChatEvent.Error("请先选择模型"))
        }

        // 与 Composer 配置链一致：优先 activeProviderId，再回退 primary
        val providers = providerStore.providers.firstOrNull().orEmpty()
        val provider = providers.firstOrNull { it.id == settings.activeProviderId && it.enabled }
            ?: providerStore.primary.firstOrNull()
            ?: return@flow emit(ChatEvent.Error("未配置提供商"))

        if (!provider.isUsable) {
            return@flow emit(ChatEvent.Error("当前提供商不可用，请检查 API Key"))
        }

        val client = LlmClient(provider)
        val tools = emptyList<com.andmx.agent.Tool>()

        val engine = AgentEngine(
            tools = tools,
            client = client,
            systemPrompt = buildSystemPrompt(settings),
        )

        val turnContext = TurnContext(provider, settings.model)
        
        try {
            engine.runTurn(settings, turnContext, text).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> {
                        emit(ChatEvent.AssistantChunk(event.text))
                    }
                    
                    is AgentEvent.Assistant -> {
                        emit(ChatEvent.AssistantComplete(event.text))
                        repo.addMessage(conversationId, "assistant", event.text)
                    }
                    
                    is AgentEvent.ToolStarted -> {
                        emit(ChatEvent.ToolCallStarted(event.id, event.name, event.arguments))
                    }
                    
                    is AgentEvent.ToolFinished -> {
                        emit(ChatEvent.ToolCallFinished(event.id, event.output, event.isError))
                        repo.addMessage(
                            conversationId,
                            "tool",
                            event.output,
                            toolName = event.name,
                            toolError = event.isError
                        )
                    }
                    
                    is AgentEvent.Failed -> {
                        emit(ChatEvent.Error(event.message))
                    }
                    
                    is AgentEvent.Done -> {
                        emit(ChatEvent.Done)
                    }
                }
            }
        } catch (e: Exception) {
            emit(ChatEvent.Error(e.message ?: "未知错误"))
        }
    }
    
    private fun buildSystemPrompt(settings: com.andmx.settings.ProviderSettings): String {
        val base = "你是一个专业的AI助手。"
        return if (settings.customInstructions.isNotBlank()) {
            "$base\n\n${settings.customInstructions}"
        } else {
            base
        }
    }
}
