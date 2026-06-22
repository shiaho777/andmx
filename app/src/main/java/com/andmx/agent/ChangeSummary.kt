package com.andmx.agent

import com.andmx.diff.DiffEngine
import com.andmx.workspace.FileChange

data class ChangeSummaryItem(
    val path: String,
    val added: Int,
    val removed: Int,
    val isNew: Boolean,
)

fun changeSummaryItems(changes: List<FileChange>): List<ChangeSummaryItem> =
    changes.map { change ->
        val stats = DiffEngine.stats(DiffEngine.diff(change.oldContent, change.newContent))
        ChangeSummaryItem(
            path = change.path,
            added = stats.added,
            removed = stats.removed,
            isNew = change.isNew,
        )
    }

fun changeSummaryText(changes: List<FileChange>): String = buildString {
    val items = changeSummaryItems(changes)
    appendLine("## 变更摘要")
    if (items.isEmpty()) {
        appendLine("- 暂无待审变更")
        appendLine()
        appendLine("### 建议")
        appendLine("- agent 编辑文件后, 这里会汇总每个文件的 diff 规模")
        appendLine("- 打开 Diff 面板可审查、保留或丢弃变更")
        return@buildString
    }
    val totalAdded = items.sumOf { it.added }
    val totalRemoved = items.sumOf { it.removed }
    appendLine("- 文件: ${items.size}")
    appendLine("- 行数: +$totalAdded / -$totalRemoved")
    appendLine()
    appendLine("### 文件")
    items.forEach { item ->
        val marker = if (item.isNew) "新建" else "修改"
        appendLine("- `${item.path}` · $marker · +${item.added} / -${item.removed}")
    }
    appendLine()
    appendLine("### 下一步")
    appendLine("- 打开 Diff 面板逐个审查")
    appendLine("- 审查后运行 `/verify` 更新验证摘要")
}
