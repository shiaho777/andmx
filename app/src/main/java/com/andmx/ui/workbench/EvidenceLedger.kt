package com.andmx.ui.workbench

import com.andmx.agent.ToolArgs
import com.andmx.agent.changeSummaryItems
import com.andmx.agent.label
import com.andmx.ui.conversation.ChatItem
import com.andmx.ui.conversation.UiReference
import com.andmx.workspace.FileChange

internal enum class EvidenceKind(val label: String) {
    FILE("文件"),
    WEB("网页"),
    UI_REFERENCE("UI参考"),
    VERIFY("验证"),
    CHANGE("变更"),
    APPROVAL("授权"),
    ACTIVITY("活动"),
}

internal data class EvidenceItem(
    val kind: EvidenceKind,
    val title: String,
    val detail: String,
    val target: String = "",
    val state: String = "",
)

internal data class EvidenceLedger(
    val items: List<EvidenceItem>,
) {
    val fileCount: Int get() = items.count { it.kind == EvidenceKind.FILE }
    val webCount: Int get() = items.count { it.kind == EvidenceKind.WEB }
    val uiReferenceCount: Int get() = items.count { it.kind == EvidenceKind.UI_REFERENCE }
    val verificationCount: Int get() = items.count { it.kind == EvidenceKind.VERIFY }
    val changeCount: Int get() = items.count { it.kind == EvidenceKind.CHANGE }
    val approvalCount: Int get() = items.count { it.kind == EvidenceKind.APPROVAL }
}

internal fun buildEvidenceLedger(
    chatItems: List<ChatItem>,
    changes: List<FileChange>,
    limit: Int = 18,
): EvidenceLedger {
    val evidence = mutableListOf<EvidenceItem>()
    val seen = LinkedHashSet<String>()

    fun add(item: EvidenceItem) {
        val key = "${item.kind}:${item.target.ifBlank { item.title + item.detail }}"
        if (seen.add(key)) evidence += item
    }

    chatItems.filterIsInstance<ChatItem.ToolUse>().forEach { tool ->
        val file = ToolArgs.filePath(tool.name, tool.args).ifBlank { ToolArgs.editedPath(tool.name, tool.args) }
        val url = ToolArgs.webUrl(tool.name, tool.args)
        val state = when {
            tool.running -> "运行中"
            tool.error -> "失败"
            else -> "完成"
        }
        if (file.isNotBlank()) {
            add(
                EvidenceItem(
                    kind = EvidenceKind.FILE,
                    title = file,
                    detail = "来自工具 ${tool.name}",
                    target = file,
                    state = state,
                ),
            )
        }
        if (url.isNotBlank()) {
            add(
                EvidenceItem(
                    kind = EvidenceKind.WEB,
                    title = url,
                    detail = "来自工具 ${tool.name}",
                    target = url,
                    state = state,
                ),
            )
        }
    }

    buildUiReferenceLedger(chatItems).items.forEach { reference ->
        add(
            EvidenceItem(
                kind = EvidenceKind.UI_REFERENCE,
                title = reference.label,
                detail = reference.detail,
                state = if (reference.image) "图片" else "附件",
            ),
        )
    }

    verificationEntries(chatItems, limit = limit).asReversed().forEach { entry ->
        add(
            EvidenceItem(
                kind = EvidenceKind.VERIFY,
                title = entry.command,
                detail = entry.detail.ifBlank { "(等待输出)" },
                state = entry.state.verificationStateLabel(),
            ),
        )
    }

    changeSummaryItems(changes).forEach { change ->
        add(
            EvidenceItem(
                kind = EvidenceKind.CHANGE,
                title = change.path,
                detail = "${if (change.isNew) "新建" else "修改"} · +${change.added} / -${change.removed}",
                target = change.path,
                state = "待审",
            ),
        )
    }

    chatItems.filterIsInstance<ChatItem.Approval>().forEach { approval ->
        add(
            EvidenceItem(
                kind = EvidenceKind.APPROVAL,
                title = "${approval.risk.label} · ${approval.toolName}",
                detail = approval.summary.ifBlank { "(无摘要)" },
                state = when {
                    !approval.resolved -> "等待"
                    approval.allowed -> "允许"
                    else -> "拒绝"
                },
            ),
        )
    }

    runLogEntries(chatItems, limit = limit).asReversed().forEach { entry ->
        if (entry.kind != RunLogKind.TOOL && entry.kind != RunLogKind.APPROVAL) {
            add(
                EvidenceItem(
                    kind = EvidenceKind.ACTIVITY,
                    title = entry.title,
                    detail = entry.detail,
                    state = entry.state.runLogStateLabel(),
                ),
            )
        }
    }

    return EvidenceLedger(evidence.takeLast(limit).asReversed())
}

internal fun evidenceLedgerText(ledger: EvidenceLedger): String = buildString {
    appendLine("## 证据账本")
    appendLine("- 文件: ${ledger.fileCount}")
    appendLine("- 网页: ${ledger.webCount}")
    appendLine("- UI 参考: ${ledger.uiReferenceCount}")
    appendLine("- 验证: ${ledger.verificationCount}")
    appendLine("- 变更: ${ledger.changeCount}")
    appendLine("- 授权: ${ledger.approvalCount}")
    appendLine()
    if (ledger.items.isEmpty()) {
        appendLine("- 暂无可追溯证据")
        appendLine()
        appendLine("### 建议")
        appendLine("- 读取文件、打开网页、运行验证或产生变更后, 这里会汇总依据")
        appendLine("- 添加截图或图片后, UI 参考也会进入这里")
        return@buildString
    }
    ledger.items.groupBy { it.kind }.forEach { (kind, items) ->
        appendLine("### ${kind.label}")
        items.forEach { item ->
            appendLine("- ${item.state.ifBlank { kind.label }} · ${item.title}: ${item.detail}")
            if (item.target.isNotBlank()) appendLine("  - 目标: `${item.target}`")
        }
        appendLine()
    }
    appendLine("### 相关入口")
    appendLine("- `/activity` 查看时间线")
    appendLine("- `/verify` 查看验证摘要")
    appendLine("- `/changes` 查看待审变更")
}

internal data class UiReferenceItem(
    val label: String,
    val detail: String,
    val image: Boolean,
    val meta: String = "",
    val assetPath: String = "",
)

internal data class UiReferenceLedger(
    val items: List<UiReferenceItem>,
) {
    val imageCount: Int get() = items.count { it.image }
    val attachmentCount: Int get() = items.size
    val uiHintCount: Int get() = items.count { it.detail.contains("UI", ignoreCase = true) || it.detail.contains("界面") }
    val identifiedCount: Int get() = items.count { it.referenceId.isNotBlank() }
    val assetCount: Int get() = items.count { it.assetPath.isNotBlank() }
}

internal fun buildUiReferenceLedger(
    chatItems: List<ChatItem>,
    limit: Int = 12,
): UiReferenceLedger {
    val items = chatItems.filterIsInstance<ChatItem.User>()
        .flatMap { user ->
            com.andmx.ui.conversation.Attachments.referencesFromDisplay(user.text).map { reference ->
                val baseDetail = if (reference.image) "来自用户图片/截图输入" else "来自用户附件输入"
                UiReferenceItem(
                    label = reference.label,
                    detail = listOfNotNull(
                        baseDetail,
                        reference.meta.takeIf { it.isNotBlank() }?.let { "元数据: $it" },
                        reference.referenceId.takeIf { it.isNotBlank() }?.let { "参考ID: $it" },
                        reference.assetPath.takeIf { it.isNotBlank() }?.let { "本地资产: $it" },
                    ).joinToString(" · "),
                    image = reference.image,
                    meta = reference.meta,
                    assetPath = reference.assetPath,
                )
            }
        }
        .takeLast(limit)
        .asReversed()
    return UiReferenceLedger(items)
}

internal fun uiReferenceText(ledger: UiReferenceLedger): String = buildString {
    appendLine("## 截图与附件参考")
    appendLine("- 图片: ${ledger.imageCount}")
    appendLine("- 附件: ${ledger.attachmentCount}")
    appendLine("- UI 线索: ${ledger.uiHintCount}")
    appendLine("- 可追踪ID: ${ledger.identifiedCount}")
    appendLine("- 本地资产: ${ledger.assetCount}")
    appendLine()
    if (ledger.items.isEmpty()) {
        appendLine("- 暂无截图或附件参考")
        appendLine()
        appendLine("### 建议")
        appendLine("- 拖入或添加 Codex/竞品/草图截图后, AndMX 会把它们作为 UI 参考证据。")
        appendLine("- 发送截图时, 模型会按布局、控件、状态、交互和实现线索提取可落地改进。")
        return@buildString
    }
    ledger.items.forEachIndexed { index, item ->
        appendLine("- 图/附件 ${index + 1}: ${item.label}")
        appendLine("  - ${item.detail}")
        if (item.meta.isNotBlank()) appendLine("  - 图片元数据: ${item.meta}")
        if (item.referenceId.isNotBlank()) appendLine("  - 参考ID: ${item.referenceId}")
        if (item.assetPath.isNotBlank()) appendLine("  - 本地资产: `${item.assetPath}`")
    }
    appendLine()
    appendLine("### 分析方式")
    appendLine("- 先抽取布局结构、控件层级和状态表达")
    appendLine("- 再映射到 AndMX 的移动工作台、任务面板、Inspector、Diff 和终端流程")
    appendLine("- 最后用测试、构建或截图核验改动")
}

internal val UiReferenceItem.referenceId: String
    get() = meta.split(" · ")
        .map { it.trim() }
        .firstOrNull { it.matches(Regex("""ref:[a-f0-9]{8}""", RegexOption.IGNORE_CASE)) }
        .orEmpty()

private val UiReference.referenceId: String
    get() = meta.split(" · ")
        .map { it.trim() }
        .firstOrNull { it.matches(Regex("""ref:[a-f0-9]{8}""", RegexOption.IGNORE_CASE)) }
        .orEmpty()
