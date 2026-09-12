package com.andmx.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class StarterSuggestion(val title: String, val prompt: String)

fun starterSuggestions(
    hasWorkspace: Boolean,
    projectName: String,
    isGitRepo: Boolean,
    hasChanges: Boolean,
    dirtyFileCount: Int,
    ahead: Int,
): List<StarterSuggestion> {
    if (!hasWorkspace) {
        return listOf(
            StarterSuggestion("认识一下", "介绍一下你能帮我做哪些事，用中文回答"),
            StarterSuggestion("写段代码", "用 Kotlin 写一个带取消支持的防抖函数，并逐行解释"),
            StarterSuggestion("学点东西", "用通俗的方式讲讲 Jetpack Compose 的重组机制"),
        )
    }
    val project = projectName.ifBlank { "当前工作区" }
    val out = ArrayList<StarterSuggestion>(3)
    if (isGitRepo && hasChanges && dirtyFileCount > 0) {
        out += StarterSuggestion(
            "Review 未提交改动",
            "看一下工作区里未提交的 $dirtyFileCount 个文件的改动，逐个点评风险，最后给出 commit 信息建议",
        )
    }
    if (isGitRepo && ahead > 0) {
        out += StarterSuggestion(
            "推送前检查",
            "当前分支领先远端 $ahead 个提交，帮我检查这些提交是否适合 push，有没有明显问题",
        )
    }
    if (out.size < 3) {
        out += StarterSuggestion(
            "讲解这个项目",
            "介绍一下 $project 的整体结构和关键模块，指出新人最应该先看的三个地方，用中文回答",
        )
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { suggestion ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onPick(suggestion.prompt) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.NorthEast,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
