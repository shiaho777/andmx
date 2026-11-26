package com.andmx.ui2.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andmx.ui2.settings.UiLang
import com.andmx.ui2.theme.AndMXType
import com.andmx.ui2.theme.Chrome
import androidx.compose.ui.draw.clip
import com.andmx.ui2.settings.tr

data class StarterSuggestion(val title: String, val prompt: String)

fun starterSuggestions(
    hasWorkspace: Boolean,
    projectName: String,
    isGitRepo: Boolean,
    hasChanges: Boolean,
    dirtyFileCount: Int,
    ahead: Int,
): List<StarterSuggestion> {
    val english = UiLang.tag == "en"
    if (!hasWorkspace) {
        return if (english) {
            listOf(
                StarterSuggestion("Introduce yourself", "Tell me what you can help me with."),
                StarterSuggestion(
                    "Write some code",
                    "Write a Kotlin debounce function with cancellation support, and explain it line by line.",
                ),
                StarterSuggestion(
                    "Learn something",
                    "Explain Jetpack Compose recomposition in simple terms.",
                ),
            )
        } else {
            listOf(
                StarterSuggestion(tr("认识一下"), "介绍一下你能帮我做哪些事，用中文回答"),
                StarterSuggestion(tr("写段代码"), "用 Kotlin 写一个带取消支持的防抖函数，并逐行解释"),
                StarterSuggestion(tr("学点东西"), "用通俗的方式讲讲 Jetpack Compose 的重组机制"),
            )
        }
    }
    val project = projectName.ifBlank { if (english) "this workspace" else "当前工作区" }
    val out = ArrayList<StarterSuggestion>(3)
    if (isGitRepo && hasChanges && dirtyFileCount > 0) {
        out += if (english) {
            StarterSuggestion(
                "Review uncommitted changes",
                "Review the changes in $dirtyFileCount uncommitted files, call out risks one by one, and suggest a commit message.",
            )
        } else {
            StarterSuggestion(
                tr("Review 未提交改动"),
                "看一下工作区里未提交的 $dirtyFileCount 个文件的改动，逐个点评风险，最后给出 commit 信息建议",
            )
        }
    }
    if (isGitRepo && ahead > 0) {
        out += if (english) {
            StarterSuggestion(
                "Pre-push check",
                "This branch is $ahead commits ahead of the remote. Check whether these commits are ready to push and flag any obvious issues.",
            )
        } else {
            StarterSuggestion(
                tr("推送前检查"),
                "当前分支领先远端 $ahead 个提交，帮我检查这些提交是否适合 push，有没有明显问题",
            )
        }
    }
    if (out.size < 3) {
        out += if (english) {
            StarterSuggestion(
                "Explain this project",
                "Introduce the overall structure and key modules of $project, and point out the three places a newcomer should read first.",
            )
        } else {
            StarterSuggestion(
                tr("讲解这个项目"),
                "介绍一下 $project 的整体结构和关键模块，指出新人最应该先看的三个地方，用中文回答",
            )
        }
    }
    if (out.size < 3) {
        out += if (english) {
            StarterSuggestion(
                "Find the test entry points",
                "Find the test commands and the smallest useful check to run in $project.",
            )
        } else {
            StarterSuggestion(
                "找到测试入口",
                "帮我找到 $project 的测试命令，并指出最适合先跑的一项检查。",
            )
        }
    }
    if (out.size < 3) {
        out += if (english) {
            StarterSuggestion(
                "Plan a change",
                "Help me scope a small change in $project and write a plan with verifiable steps.",
            )
        } else {
            StarterSuggestion(
                "规划一个改动",
                "帮我把 $project 的下一步改动拆成可验证的步骤，先给出计划。",
            )
        }
    }
    return out.take(3)
}

@Composable
fun StarterSuggestions(
    suggestions: List<StarterSuggestion>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        suggestions.forEach { suggestion ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Chrome.control)
                    .clickable { onPick(suggestion.prompt) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        suggestion.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        suggestion.prompt,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = AndMXType.cardMeta.fontSize),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.NorthEast,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
