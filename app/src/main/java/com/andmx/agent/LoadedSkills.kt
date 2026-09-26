package com.andmx.agent

import com.andmx.llm.ApiMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 上游 agent/loaded-skills.ts 移植：判据刻意取自 provider 可见历史而不是单独的会话状态——
 * compaction 把 Skill 调用挤出上下文后技能正文也不在了，门随之重新关上；resume/rewind
 * 重建历史时答案随之重建。
 */
object LoadedSkills {

    const val DYNAMIC_WORKFLOWS = "dynamic-workflows"
    const val SKILL_TOOL_NAME = "Skill"

    /** formatSkillPayload 成功载荷的稳定标记；失败回话（"技能未找到"/"Skill failed"）不含它。 */
    private const val SKILL_LOADED_MARKER = "Skill loaded into context"

    private val argsJson = Json { ignoreUnknownKeys = true }

    /**
     * 历史中是否有一次成功完成的 `Skill` 调用加载了 [skillName]。
     * 成功 = assistant 发出的调用有对应 tool 结果且载荷是成功形态（无 isError 位，
     * 用 SkillLoadedMarker 判成功而不是猜失败文案）。
     */
    fun sessionHasLoadedSkill(history: List<ApiMessage>, skillName: String): Boolean {
        val pendingCallIds = mutableSetOf<String>()
        for (message in history) {
            if (message.role == "assistant") {
                for (call in message.toolCalls.orEmpty()) {
                    if (call.function.name == SKILL_TOOL_NAME &&
                        skillInputNames(call.function.arguments) == skillName
                    ) {
                        pendingCallIds += call.id
                    }
                }
                continue
            }
            if (message.role == "tool" &&
                message.toolCallId != null &&
                message.toolCallId in pendingCallIds &&
                message.content?.contains(SKILL_LOADED_MARKER) == true
            ) {
                return true
            }
        }
        return false
    }

    /** 与上游一致：同时认当前形 `{ skill }` 与旧形 `{ name }`。 */
    internal fun skillInputNames(arguments: String): String? {
        val obj = runCatching { argsJson.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return null
        return obj["skill"]?.jsonPrimitive?.content
            ?: obj["name"]?.jsonPrimitive?.content
    }
}
