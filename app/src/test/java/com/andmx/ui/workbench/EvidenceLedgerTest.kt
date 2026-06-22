package com.andmx.ui.workbench

import com.andmx.agent.ToolRisk
import com.andmx.ui.conversation.Attachment
import com.andmx.ui.conversation.Attachments
import com.andmx.ui.conversation.ImageAttachmentMeta
import com.andmx.ui.conversation.ChatItem
import com.andmx.workspace.FileChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceLedgerTest {

    @Test
    fun evidenceLedgerCollectsFilesWebVerificationChangesAndApprovals() {
        val ledger = buildEvidenceLedger(
            chatItems = listOf(
                ChatItem.User(1, "继续"),
                ChatItem.User(6, "看图\n🖼 Codex screenshot.png · UI 参考"),
                ChatItem.ToolUse(2, "call-1", "read_file", """{"path":"/root/app.kt"}""", output = "ok", running = false),
                ChatItem.ToolUse(3, "call-2", "web_search", """{"query":"codex ui"}""", output = "result", running = false),
                ChatItem.ToolUse(
                    key = 4,
                    callId = "call-3",
                    name = "run_shell",
                    args = """{"command":"./gradlew test"}""",
                    output = "BUILD SUCCESSFUL",
                    running = false,
                ),
                ChatItem.Approval(5, "apply_patch", "path=/root/app.kt", ToolRisk.WRITE, resolved = true, allowed = true),
            ),
            changes = listOf(
                FileChange("/root/app.kt", oldContent = "a\n", newContent = "a\nb\n"),
            ),
        )

        assertEquals(1, ledger.fileCount)
        assertEquals(1, ledger.webCount)
        assertEquals(1, ledger.uiReferenceCount)
        assertEquals(1, ledger.verificationCount)
        assertEquals(1, ledger.changeCount)
        assertEquals(1, ledger.approvalCount)
        assertTrue(ledger.items.any { it.kind == EvidenceKind.FILE && it.target == "/root/app.kt" })
        assertTrue(ledger.items.any { it.kind == EvidenceKind.WEB && it.target.contains("duckduckgo.com") })
        assertTrue(ledger.items.any { it.kind == EvidenceKind.UI_REFERENCE && it.title.contains("Codex screenshot") })
        assertTrue(ledger.items.any { it.kind == EvidenceKind.VERIFY && it.state == "通过" })
        assertTrue(ledger.items.any { it.kind == EvidenceKind.CHANGE && it.detail.contains("+1") })
        assertTrue(ledger.items.any { it.kind == EvidenceKind.APPROVAL && it.state == "允许" })
    }

    @Test
    fun evidenceLedgerTextShowsFallbackAndGroupedItems() {
        val empty = evidenceLedgerText(EvidenceLedger(emptyList()))
        assertTrue(empty.contains("## 证据账本"))
        assertTrue(empty.contains("暂无可追溯证据"))

        val text = evidenceLedgerText(
            EvidenceLedger(
                listOf(
                    EvidenceItem(EvidenceKind.FILE, "/root/app.kt", "来自工具 read_file", "/root/app.kt", "完成"),
                    EvidenceItem(EvidenceKind.UI_REFERENCE, "Codex screenshot.png · UI 参考", "来自用户图片/截图输入", state = "图片"),
                    EvidenceItem(EvidenceKind.VERIFY, "./gradlew test", "BUILD SUCCESSFUL", state = "通过"),
                ),
            ),
        )

        assertTrue(text.contains("### 文件"))
        assertTrue(text.contains("完成 · /root/app.kt"))
        assertTrue(text.contains("### UI参考"))
        assertTrue(text.contains("图片 · Codex screenshot.png"))
        assertTrue(text.contains("### 验证"))
        assertTrue(text.contains("通过 · ./gradlew test"))
        assertTrue(text.contains("/activity"))
        assertTrue(text.contains("/changes"))
    }

    @Test
    fun evidenceLedgerDedupesRepeatedTargets() {
        val ledger = buildEvidenceLedger(
            chatItems = listOf(
                ChatItem.ToolUse(1, "call-1", "read_file", """{"path":"/root/app.kt"}""", output = "ok", running = false),
                ChatItem.ToolUse(2, "call-2", "read_file", """{"path":"/root/app.kt"}""", output = "again", running = false),
            ),
            changes = emptyList(),
        )

        assertEquals(1, ledger.fileCount)
        assertEquals(1, ledger.items.count { it.kind == EvidenceKind.FILE && it.target == "/root/app.kt" })
    }

    @Test
    fun uiReferenceTextSummarizesScreenshotInputs() {
        val text = uiReferenceText(
            buildUiReferenceLedger(
                listOf(
                    ChatItem.User(1, "复刻\n🖼 截图.png · UI 参考"),
                    ChatItem.User(2, "说明\n📎 notes.md"),
                ),
            ),
        )

        assertTrue(text.contains("## 截图与附件参考"))
        assertTrue(text.contains("图片: 1"))
        assertTrue(text.contains("附件: 2"))
        assertTrue(text.contains("截图.png · UI 参考"))
        assertTrue(text.contains("布局结构"))
    }

    @Test
    fun uiReferenceLedgerCarriesScreenshotMetadata() {
        val ledger = buildUiReferenceLedger(
            listOf(
                ChatItem.User(1, "复刻\n🖼 Codex screenshot.png · UI 参考 · 1440x900 · image/png · 421.9 KB · ref:abcd1234 · asset:ui-references/ref-abcd1234.png"),
            ),
        )
        val reference = ledger.items.first()

        assertEquals("Codex screenshot.png · UI 参考", reference.label)
        assertEquals("1440x900 · image/png · 421.9 KB · ref:abcd1234", reference.meta)
        assertEquals("ui-references/ref-abcd1234.png", reference.assetPath)
        assertEquals("ref:abcd1234", reference.referenceId)
        assertEquals(1, ledger.identifiedCount)
        assertEquals(1, ledger.assetCount)
        assertTrue(reference.detail.contains("元数据: 1440x900 · image/png · 421.9 KB · ref:abcd1234"))
        assertTrue(reference.detail.contains("参考ID: ref:abcd1234"))
        assertTrue(reference.detail.contains("本地资产: ui-references/ref-abcd1234.png"))
        assertTrue(uiReferenceText(ledger).contains("图片元数据: 1440x900 · image/png · 421.9 KB · ref:abcd1234"))
        assertTrue(uiReferenceText(ledger).contains("参考ID: ref:abcd1234"))
        assertTrue(uiReferenceText(ledger).contains("本地资产: `ui-references/ref-abcd1234.png`"))
    }

    @Test
    fun slashCommandWithScreenshotStillBuildsReferenceAndEvidence() {
        val userDisplay = Attachments.displayText(
            "/references",
            listOf(
                Attachment(
                    name = "Codex command palette screenshot.png",
                    content = "(UI 截图参考)",
                    imageDataUrl = "data:image/png;base64,abc",
                    imageMeta = ImageAttachmentMeta(mime = "image/png", byteSize = 432_000, width = 1440, height = 900),
                    imageAssetPath = "ui-references/ref-abcd1234.png",
                ),
            ),
        )
        val chatItems = listOf(ChatItem.User(1, userDisplay))

        val references = buildUiReferenceLedger(chatItems)
        val evidence = buildEvidenceLedger(chatItems, changes = emptyList())

        assertEquals(1, references.attachmentCount)
        assertEquals("Codex command palette screenshot.png · UI 参考", references.items.first().label)
        assertTrue(references.items.first().meta.contains("1440x900 · image/png · 421.9 KB"))
        assertEquals("ui-references/ref-abcd1234.png", references.items.first().assetPath)
        assertTrue(references.items.first().referenceId.matches(Regex("""ref:[a-f0-9]{8}""")))
        assertEquals(1, references.identifiedCount)
        assertEquals(1, references.assetCount)
        assertEquals(1, evidence.uiReferenceCount)
        assertTrue(evidence.items.any { it.kind == EvidenceKind.UI_REFERENCE && it.title.contains("command palette") })
    }

    @Test
    fun uiReferenceBoardBuildsPerScreenshotObjectsFromLedger() {
        val references = buildUiReferenceLedger(
            listOf(
                ChatItem.User(1, "复刻\n🖼 Codex screenshot.png · UI 参考 · 1440x900 · image/png · 421.9 KB · ref:abcd1234 · asset:ui-references/ref-abcd1234.png"),
            ),
        )
        val board = buildUiReferenceBoard(
            references = references,
            blueprint = UiReplicaBlueprint(
                state = BlueprintState.READY_TO_EXTRACT,
                title = "先提取界面模式",
                referenceCount = 1,
                extractionTasks = listOf("布局"),
                targetSurfaces = listOf("ChatPane"),
                acceptanceChecks = listOf("compile"),
                primaryCommand = "/blueprint",
            ),
            screenshotExtraction = ScreenshotExtractionSummary(
                title = "等待提取截图内容",
                referenceCount = 1,
                items = listOf(
                    ScreenshotExtractionItem("图 1", ScreenshotExtractionState.NEEDS_EXTRACTION, "wait", emptyList(), emptyList(), emptyList(), "/blueprint"),
                ),
                primaryCommand = "/blueprint",
            ),
            visualAcceptance = VisualAcceptanceSummary(
                title = "等待截图参考",
                referenceCount = 1,
                items = listOf(VisualAcceptanceItem("截图证据", VisualAcceptanceState.NEEDS_EXTRACTION, "wait", "/blueprint")),
                primaryCommand = "/blueprint",
            ),
            designSystem = CodexDesignSystemAudit(
                title = "Codex 设计系统正在对齐",
                referenceCount = 1,
                items = listOf(CodexDesignSystemItem("截图证据", DesignAuditState.WATCH, "principle", "andmx", "/references")),
                primaryCommand = "/references",
            ),
            evidence = EvidenceLedger(listOf(EvidenceItem(EvidenceKind.UI_REFERENCE, "Codex screenshot.png", "来自截图", state = "图片"))),
            snapshot = AgentInspectorSnapshot(
                project = "andmx",
                model = "gpt-test",
                baseUrl = "https://api.example.test",
                apiConfigured = true,
                approvalModeLabel = "按需",
                goalText = "复刻 Codex UI",
                goalPhaseLabel = "运行中",
                goalNote = "",
                busy = false,
                reasoningEffort = "medium",
                persona = "默认",
                messageCount = 2,
                userMessages = 1,
                assistantMessages = 0,
                toolEvents = 0,
                runningTools = 0,
                failedTools = 0,
                approvalEvents = 0,
                pendingApprovals = 0,
                changedFiles = 0,
                sourceLinks = 0,
                uiReferences = 1,
                tokenEstimate = 1_000,
                contextPressure = "轻量",
                builtInTools = 9,
                totalTools = 9,
                mcpServers = 0,
            ),
        )

        assertEquals(UiReferenceKind.CODEX_SCREENSHOT, board.items.first().kind)
        assertEquals(UiReferenceBoardState.READY_TO_EXTRACT, board.items.first().state)
        assertTrue(uiReferenceBoardText(board).contains("## 截图参考板"))
        assertTrue(uiReferenceBoardText(board).contains("图片元数据: 1440x900 · image/png · 421.9 KB · ref:abcd1234"))
        assertTrue(uiReferenceBoardText(board).contains("参考ID: ref:abcd1234"))
        assertTrue(uiReferenceBoardText(board).contains("本地资产: ui-references/ref-abcd1234.png"))
    }
}
