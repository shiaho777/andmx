package com.andmx.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.BuildConfig
import com.andmx.agent.PlanItemStatus
import com.andmx.agent.label
import com.andmx.exec.proot.ProotRuntime
import com.andmx.exec.proot.RootfsInstaller
import com.andmx.ui.components.InfoRow as SharedInfoRow
import com.andmx.ui.conversation.ConversationController
import com.andmx.ui.conversation.label
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/** Right-pane self-inspection surface: current agent state, limits, tools and local commands. */
@Composable
fun AgentInspectorPane(
    controller: ConversationController,
    changedFiles: Int,
    onOpenProgress: () -> Unit,
    onOpenSettings: () -> Unit,
    onRunCommand: (String) -> Unit,
    onOpenFile: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = AndmxTheme.colors
    val context = LocalContext.current
    val runtimeEnvironment = remember(context, controller.items.size) {
        val runtime = ProotRuntime(context)
        buildRuntimeEnvironmentSummary(
            flavor = BuildConfig.FLAVOR,
            targetSdk = BuildConfig.PROBE_TARGET_SDK,
            abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            prootBundled = runtime.isBundled(),
            rootfsInstalled = RootfsInstaller(runtime).isInstalled(),
            prootBinExists = runtime.prootBin.exists(),
            loaderBinExists = runtime.loaderBin.exists(),
            rootfsPath = runtime.rootfsDir.path,
            usrPath = runtime.usrDir.path,
            tmpPath = runtime.tmpDir.path,
        )
    }
    val snapshot = remember(
        controller.project,
        controller.settings,
        controller.approvalMode,
        controller.goal,
        controller.busy,
        controller.items.toList(),
        changedFiles,
        controller.toolList().size,
        controller.toolCapabilities().size,
        controller.mcpServers().size,
    ) {
        buildAgentInspectorSnapshot(
            project = controller.project,
            model = controller.settings.model,
        baseUrl = controller.endpointLabel,
        apiConfigured = controller.apiConfigured,
            approvalModeLabel = controller.approvalMode.label,
            goalText = controller.goal.text,
            goalPhaseLabel = controller.goal.phase.label,
            goalNote = controller.goal.note,
            busy = controller.busy,
            reasoningEffort = controller.settings.reasoningEffort,
            persona = controller.settings.persona,
            items = controller.items.toList(),
            changedFiles = changedFiles,
            builtInTools = controller.toolCapabilities().size,
            totalTools = controller.toolList().size,
            mcpServers = controller.mcpServers().size,
        )
    }
    val plan = remember(snapshot, controller.goal, controller.items.toList(), changedFiles) { controller.taskPlanSnapshot() }
    val handoffAdvice = remember(snapshot) { contextHandoffAdvice(snapshot) }
    val runLog = remember(controller.items.toList()) { runLogEntries(controller.items, limit = 5) }
    val verifications = remember(controller.items.toList()) { verificationEntries(controller.items, limit = 5) }
    val evidence = remember(controller.items.toList(), changedFiles) { controller.evidenceLedger() }
    val blueprint = remember(controller.items.toList(), changedFiles, snapshot, evidence) { controller.uiReplicaBlueprint() }
    val surfaceMap = remember(controller.items.toList(), changedFiles, snapshot, blueprint) { controller.codexSurfaceMap() }
    val visualAcceptance = remember(controller.items.toList(), changedFiles, snapshot, blueprint, surfaceMap, evidence, verifications) {
        controller.visualAcceptanceSummary()
    }
    val designSystem = remember(controller.items.toList(), changedFiles, snapshot, blueprint, surfaceMap, visualAcceptance, evidence) {
        controller.codexDesignSystemAudit()
    }
    val screenshotExtraction = remember(controller.items.toList(), changedFiles, snapshot, blueprint, surfaceMap, visualAcceptance, designSystem, evidence) {
        controller.screenshotExtractionSummary()
    }
    val referenceBoard = remember(controller.items.toList(), changedFiles, snapshot, blueprint, visualAcceptance, designSystem, screenshotExtraction, evidence) {
        controller.uiReferenceBoard()
    }
    val screenshotTrace = remember(controller.items.toList(), changedFiles, snapshot, referenceBoard, surfaceMap, evidence, verifications) {
        controller.screenshotImplementationTrace()
    }
    val policy = remember(controller.approvalMode, controller.toolCapabilities()) { controller.toolPolicySummary() }
    val checklist = remember(snapshot, plan, verifications, runLog.size) {
        buildSessionChecklist(snapshot, plan, verifications, runLog.size)
    }
    val nextAction = remember(snapshot, checklist, verifications) {
        buildNextActionDecision(snapshot, checklist, verifications)
    }
    val interactionFlow = remember(controller.items.toList(), changedFiles, snapshot, plan, verifications, evidence, checklist, nextAction, screenshotExtraction) {
        controller.codexInteractionFlow()
    }
    val selfModel = remember(controller.items.toList(), changedFiles, snapshot, designSystem, screenshotExtraction, interactionFlow, evidence, runtimeEnvironment) {
        controller.codexSelfModel()
    }
    val parity = remember(snapshot, runtimeEnvironment, policy, evidence, checklist, designSystem, screenshotExtraction, interactionFlow, selfModel) {
        buildCodexParityAudit(snapshot, runtimeEnvironment, policy, evidence, checklist, designSystem, screenshotExtraction, interactionFlow, selfModel)
    }
    val architecture = remember(snapshot, runtimeEnvironment, evidence, checklist, parity) {
        controller.agentArchitectureBlueprint()
    }
    val report = remember(snapshot, checklist, nextAction, parity, blueprint, evidence, verifications, changedFiles) {
        controller.deliveryReport()
    }
    val instructionSummary = remember(controller.settings, controller.toolCapabilities().size, controller.mcpServers().size) {
        buildInstructionStackSummary(
            apiConfigured = controller.apiConfigured,
            mcpConfigured = controller.settings.mcpServers.isNotBlank(),
            customInstructions = controller.settings.customInstructions,
            builtInTools = controller.toolCapabilities().size,
            mcpServers = controller.mcpServers().size,
        )
    }

    LazyColumn(
        modifier.background(colors.canvas).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            InspectorHeader(snapshot, nextAction, onOpenProgress, onRunCommand)
        }
        item {
            InspectorSection("上下文") {
                MetricGrid(
                    listOf(
                        "消息" to "${snapshot.messageCount}",
                        "工具" to "${snapshot.toolEvents}",
                        "授权" to "${snapshot.approvalEvents}",
                        "UI" to "${snapshot.uiReferences}",
                        "变更" to "${snapshot.changedFiles}",
                    ),
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    "~${snapshot.tokenEstimate} tokens · ${snapshot.contextPressure}",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.xs))
                LinearProgressIndicator(
                    progress = { contextPressureFraction(snapshot.tokenEstimate) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(Radii.pill),
                    color = if (snapshot.contextPressure == "需要压缩") colors.warning else colors.accent,
                    trackColor = colors.sunken,
                )
                Spacer(Modifier.height(Spacing.md))
                HandoffAdviceCard(handoffAdvice, onRunCommand)
            }
        }
        item {
            InspectorSection("目标") {
                InfoRow("状态", snapshot.goalPhaseLabel)
                if (snapshot.goalNote.isNotBlank()) InfoRow("最近", snapshot.goalNote)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    snapshot.goalText.ifBlank { "还没有明确目标。第一条用户消息会成为当前线程的目标锚点。" },
                    style = AndmxTheme.typography.bodyMedium,
                    color = if (snapshot.goalText.isBlank()) colors.textTertiary else colors.textPrimary,
                )
            }
        }
        item {
            InspectorSection("任务计划") {
                plan.items.forEach { item ->
                    InspectorPlanRow(item)
                }
            }
        }
        item {
            InspectorSection("会话清单") {
                Text(checklist.title, style = AndmxTheme.typography.labelMedium, color = checklistTint(checklist, colors))
                Spacer(Modifier.height(Spacing.xs))
                Text(checklist.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
                Spacer(Modifier.height(Spacing.sm))
                checklist.items.forEach { item ->
                    InspectorChecklistRow(item, onRunCommand)
                }
            }
        }
        item {
            InspectorSection("验证") {
                if (verifications.isEmpty()) {
                    Text("尚无测试、构建或诊断记录", style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
                } else {
                    verifications.forEach { entry ->
                        VerificationRow(entry)
                    }
                }
            }
        }
        item {
            InspectorSection("证据账本") {
                MetricGrid(
                    listOf(
                        "文件" to "${evidence.fileCount}",
                        "网页" to "${evidence.webCount}",
                        "UI" to "${evidence.uiReferenceCount}",
                        "验证" to "${evidence.verificationCount}",
                        "变更" to "${evidence.changeCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                if (evidence.items.isEmpty()) {
                    Text("尚无可追溯证据", style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
                } else {
                    evidence.items.take(4).forEach { item ->
                        InspectorEvidenceRow(item, onOpenFile, onOpenUrl)
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "/evidence",
                        style = AndmxTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/evidence") }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        "/references",
                        style = AndmxTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/references") }
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                }
            }
        }
        item {
            InspectorSection("截图参考板") {
                MetricGrid(
                    listOf(
                        "参考" to "${referenceBoard.referenceCount}",
                        "Codex" to "${referenceBoard.codexCount}",
                        "闭环" to "${referenceBoard.readyCount}",
                        "待处理" to "${referenceBoard.openCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(referenceBoard.title, style = AndmxTheme.typography.labelMedium, color = referenceBoardTint(referenceBoard, colors))
                Spacer(Modifier.height(Spacing.sm))
                referenceBoard.items.take(3).forEach { item ->
                    ReferenceBoardItemRow(item, onRunCommand)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/references",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/references") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("UI 复刻蓝图") {
                InfoRow("状态", blueprint.state.label)
                InfoRow("参考", "${blueprint.referenceCount}")
                Spacer(Modifier.height(Spacing.sm))
                blueprint.extractionTasks.take(3).forEach { task ->
                    BulletLine(task)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/blueprint",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/blueprint") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("Codex UI 表面") {
                MetricGrid(
                    listOf(
                        "已具备" to "${surfaceMap.readyCount}",
                        "待补齐" to "${surfaceMap.partialCount}",
                        "等截图" to "${surfaceMap.waitingCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(surfaceMap.title, style = AndmxTheme.typography.labelMedium, color = surfaceMapTint(surfaceMap, colors))
                Spacer(Modifier.height(Spacing.sm))
                surfaceMap.surfaces.take(4).forEach { surface ->
                    SurfaceSpecRow(surface, onRunCommand)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/surfaces",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/surfaces") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("视觉验收") {
                MetricGrid(
                    listOf(
                        "就绪" to "${visualAcceptance.readyCount}",
                        "待处理" to "${visualAcceptance.waitingCount}",
                        "参考" to "${visualAcceptance.referenceCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(visualAcceptance.title, style = AndmxTheme.typography.labelMedium, color = visualAcceptanceTint(visualAcceptance, colors))
                Spacer(Modifier.height(Spacing.sm))
                visualAcceptance.items.take(4).forEach { item ->
                    VisualAcceptanceRow(item, onRunCommand)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/visual-check",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/visual-check") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("设计系统") {
                MetricGrid(
                    listOf(
                        "已对齐" to "${designSystem.readyCount}",
                        "注意" to "${designSystem.watchCount}",
                        "缺口" to "${designSystem.gapCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(designSystem.title, style = AndmxTheme.typography.labelMedium, color = designSystemTint(designSystem, colors))
                Spacer(Modifier.height(Spacing.sm))
                designSystem.items.take(4).forEach { item ->
                    DesignSystemRow(item, onRunCommand)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/design-system",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/design-system") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("截图解析") {
                MetricGrid(
                    listOf(
                        "参考" to "${screenshotExtraction.referenceCount}",
                        "已闭环" to "${screenshotExtraction.readyCount}",
                        "待处理" to "${screenshotExtraction.waitingCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(screenshotExtraction.title, style = AndmxTheme.typography.labelMedium, color = screenshotExtractionTint(screenshotExtraction, colors))
                Spacer(Modifier.height(Spacing.sm))
                screenshotExtraction.items.take(3).forEach { item ->
                    ScreenshotExtractionRow(item, onRunCommand)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/screenshot-extract",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/screenshot-extract") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("实现追踪") {
                MetricGrid(
                    listOf(
                        "参考" to "${screenshotTrace.referenceCount}",
                        "闭环" to "${screenshotTrace.readyCount}",
                        "待处理" to "${screenshotTrace.waitingCount}",
                        "变更" to "${screenshotTrace.changedFileCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(screenshotTrace.title, style = AndmxTheme.typography.labelMedium, color = screenshotTraceTint(screenshotTrace, colors))
                Spacer(Modifier.height(Spacing.sm))
                screenshotTrace.items.take(3).forEach { item ->
                    ScreenshotTraceRow(item, onRunCommand)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/trace",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/trace") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("交互流程") {
                MetricGrid(
                    listOf(
                        "就绪" to "${interactionFlow.readyCount}",
                        "进行中" to "${interactionFlow.activeCount}",
                        "注意" to "${interactionFlow.watchCount}",
                        "阻塞" to "${interactionFlow.blockedCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(interactionFlow.title, style = AndmxTheme.typography.labelMedium, color = interactionFlowTint(interactionFlow, colors))
                Spacer(Modifier.height(Spacing.sm))
                interactionFlow.steps.take(4).forEach { step ->
                    InteractionFlowStepRow(step, onRunCommand)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/flow",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/flow") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("自我模型") {
                MetricGrid(
                    listOf(
                        "已建模" to "${selfModel.readyCount}",
                        "关注" to "${selfModel.watchCount}",
                        "缺口" to "${selfModel.gapCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(selfModel.title, style = AndmxTheme.typography.labelMedium, color = selfModelTint(selfModel, colors))
                Spacer(Modifier.height(Spacing.sm))
                selfModel.layers.take(4).forEach { layer ->
                    SelfModelLayerRow(layer, onRunCommand)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/self-model",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/self-model") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("交付报告") {
                InfoRow("状态", report.state.label)
                InfoRow("变更", "${report.changedFiles.size}")
                InfoRow("验证", "${report.verifications.size}")
                Spacer(Modifier.height(Spacing.sm))
                Text(report.title, style = AndmxTheme.typography.labelMedium, color = deliveryReportTint(report, colors))
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/report",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/report") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("最近活动") {
                if (runLog.isEmpty()) {
                    Text("尚无运行记录", style = AndmxTheme.typography.bodySmall, color = colors.textTertiary)
                } else {
                    runLog.forEach { entry ->
                        InspectorRunLogRow(entry, onOpenFile, onOpenUrl)
                    }
                }
            }
        }
        item {
            InspectorSection("运行环境") {
                InfoRow("项目", snapshot.project)
                InfoRow("模型", snapshot.model)
                InfoRow("端点", snapshot.baseUrl.ifBlank { "(未设置)" })
                InfoRow("API", if (snapshot.apiConfigured) "已配置" else "未配置")
                InfoRow("推理", snapshot.reasoningEffort)
                InfoRow("语气", snapshot.persona)
                Spacer(Modifier.height(Spacing.md))
                RuntimeEnvironmentCard(runtimeEnvironment, onRunCommand)
            }
        }
        item {
            InspectorSection("Codex 对标") {
                MetricGrid(
                    listOf(
                        "已具备" to "${parity.readyCount}",
                        "注意" to "${parity.watchCount}",
                        "缺口" to "${parity.gapCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(parity.title, style = AndmxTheme.typography.labelMedium, color = parityTint(parity, colors))
                Spacer(Modifier.height(Spacing.sm))
                parity.items.take(4).forEach { item ->
                    ParityRow(item, onRunCommand)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/parity",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/parity") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("系统架构蓝图") {
                MetricGrid(
                    listOf(
                        "就绪" to "${architecture.readyCount}",
                        "注意" to "${architecture.watchCount}",
                        "缺口" to "${architecture.gapCount}",
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(architecture.title, style = AndmxTheme.typography.labelMedium, color = architectureTint(architecture, colors))
                Spacer(Modifier.height(Spacing.sm))
                architecture.layers.take(4).forEach { layer ->
                    ArchitectureLayerRow(layer, onRunCommand)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/architecture",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/architecture") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("可见指令栈") {
                InstructionStatusRow("API", instructionSummary.apiStatus)
                InstructionStatusRow("MCP", instructionSummary.mcpStatus)
                InstructionStatusRow("自定义", instructionSummary.customInstructionStatus)
                Spacer(Modifier.height(Spacing.sm))
                instructionSummary.visibleLayers.forEach { layer ->
                    BulletLine(layer)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    instructionSummary.customInstructionPreview,
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    instructionSummary.safetyBoundary,
                    style = AndmxTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "/instructions",
                        style = AndmxTheme.typography.labelSmall,
                        color = colors.accent,
                        modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/instructions") }
                            .padding(horizontal = Spacing.xs, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        "设置",
                        style = AndmxTheme.typography.labelSmall,
                        color = colors.accent,
                        modifier = Modifier.clip(Radii.sm).clickable(onClick = onOpenSettings)
                            .padding(horizontal = Spacing.xs, vertical = 2.dp),
                    )
                }
            }
        }
        item {
            InspectorSection("工具与授权") {
                MetricGrid(
                    listOf(
                        "内置" to "${snapshot.builtInTools}",
                        "总工具" to "${snapshot.totalTools}",
                        "MCP" to "${snapshot.mcpServers}",
                        "来源" to "${snapshot.sourceLinks}",
                    ),
                )
                Spacer(Modifier.height(Spacing.md))
                InfoRow("授权", snapshot.approvalModeLabel)
                InfoRow("自动", "${policy.autoCount}")
                InfoRow("询问", "${policy.promptCount}")
                InfoRow("阻止", "${policy.denyCount}")
                InfoRow("边界", "${policy.boundaryRows.size}")
                Spacer(Modifier.height(Spacing.sm))
                policy.rows.forEach { row ->
                    RiskEffectRow(row.risk.label, "${row.toolCount}", row.decision.label())
                }
                Spacer(Modifier.height(Spacing.sm))
                policy.boundaryRows.forEach { row ->
                    SafetyBoundaryEffectRow(row)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "/policy",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable { onRunCommand("/policy") }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
        item {
            InspectorSection("本地命令") {
                CommandActionRow(Icons.Outlined.Info, "/status", "会话、模型、环境", onRunCommand)
                CommandActionRow(Icons.Outlined.TrackChanges, "/next", "下一步与决策原因", onRunCommand)
                CommandActionRow(Icons.Outlined.Info, "/evidence", "来源、验证、变更依据", onRunCommand)
                CommandActionRow(Icons.Outlined.Info, "/references", "截图与附件参考", onRunCommand)
                CommandActionRow(Icons.Outlined.TrackChanges, "/blueprint", "截图驱动复刻蓝图", onRunCommand)
                CommandActionRow(Icons.Outlined.TrackChanges, "/surfaces", "Codex UI 表面地图", onRunCommand)
                CommandActionRow(Icons.Outlined.Info, "/visual-check", "视觉验收清单", onRunCommand)
                CommandActionRow(Icons.Outlined.TrackChanges, "/design-system", "密度、布局与控件规范", onRunCommand)
                CommandActionRow(Icons.Outlined.Info, "/screenshot-extract", "逐图拆解截图内容", onRunCommand)
                CommandActionRow(Icons.Outlined.Psychology, "/self-model", "指令、工具、环境与工作循环", onRunCommand)
                CommandActionRow(Icons.Outlined.AutoAwesome, "/improve", "自我完善队列与下一入口", onRunCommand)
                CommandActionRow(Icons.Outlined.Security, "/policy", "工具授权策略", onRunCommand)
                CommandActionRow(Icons.Outlined.Security, "/safety", "Live UI 安全边界", onRunCommand)
                CommandActionRow(Icons.Outlined.TrackChanges, "/context", "上下文预算与来源", onRunCommand)
                CommandActionRow(Icons.AutoMirrored.Outlined.FormatListBulleted, "/plan", "当前任务计划", onRunCommand)
                CommandActionRow(Icons.AutoMirrored.Outlined.FormatListBulleted, "/checklist", "交付前会话清单", onRunCommand)
                CommandActionRow(Icons.Outlined.History, "/activity", "最近活动时间线", onRunCommand)
                CommandActionRow(Icons.Outlined.Extension, "/tools", "工具能力边界", onRunCommand)
                CommandActionRow(Icons.Outlined.TrackChanges, "/parity", "Codex 对标缺口审计", onRunCommand)
                CommandActionRow(Icons.AutoMirrored.Outlined.FormatListBulleted, "/report", "交付报告", onRunCommand)
                CommandActionRow(Icons.Outlined.Psychology, "/architecture", "系统架构蓝图", onRunCommand)
                CommandActionRow(Icons.Outlined.Terminal, "/diag", "探测 W^X、proot、rootfs", onRunCommand)
                CommandActionRow(Icons.Outlined.Psychology, "/method", "AndMX 执行循环", onRunCommand)
                CommandActionRow(Icons.Outlined.Settings, "/instructions", "可见指令栈", onRunCommand)
                CommandActionRow(Icons.Outlined.Security, "/handoff", "生成交接摘要", onRunCommand)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "打开设置",
                    style = AndmxTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(Radii.sm).clickable(onClick = onOpenSettings)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun VerificationRow(entry: VerificationEntry) {
    val colors = AndmxTheme.colors
    val tint = when (entry.state) {
        VerificationState.PASSED -> colors.accent
        VerificationState.FAILED -> colors.warning
        VerificationState.RUNNING -> colors.textSecondary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(9.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(entry.command, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.detail.ifBlank { "(等待输出)" }, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            when (entry.state) {
                VerificationState.PASSED -> "通过"
                VerificationState.FAILED -> "失败"
                VerificationState.RUNNING -> "运行中"
            },
            style = AndmxTheme.typography.labelSmall,
            color = tint,
        )
    }
}

@Composable
private fun InspectorEvidenceRow(
    item: EvidenceItem,
    onOpenFile: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    val canOpen = item.target.isNotBlank() && item.kind in setOf(EvidenceKind.FILE, EvidenceKind.WEB)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = canOpen) {
                when (item.kind) {
                    EvidenceKind.FILE -> onOpenFile(item.target)
                    EvidenceKind.WEB -> onOpenUrl(item.target)
                    else -> Unit
                }
            }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(9.dp).clip(Radii.pill).background(evidenceTint(item.kind, colors)))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text("${item.kind.label} · ${item.title}", style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (canOpen) "打开" else item.state,
            style = AndmxTheme.typography.labelSmall,
            color = if (canOpen) colors.accent else colors.textTertiary,
            maxLines = 1,
        )
    }
}

@Composable
private fun RuntimeEnvironmentCard(summary: RuntimeEnvironmentSummary, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = when (summary.level) {
        RuntimeEnvironmentLevel.READY -> colors.accent
        RuntimeEnvironmentLevel.WATCH -> colors.warning
        RuntimeEnvironmentLevel.LIMITED -> colors.textSecondary
    }
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .border(1.dp, if (summary.level == RuntimeEnvironmentLevel.READY) colors.border else tint, Radii.sm)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(summary.healthLabel, style = AndmxTheme.typography.labelMedium, color = tint, modifier = Modifier.weight(1f))
            Text(
                summary.primaryCommand,
                style = AndmxTheme.typography.labelSmall,
                color = colors.accent,
                modifier = Modifier.clip(Radii.sm).clickable { onRunCommand(summary.primaryCommand) }
                    .padding(horizontal = Spacing.xs, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(summary.healthDetail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
        Spacer(Modifier.height(Spacing.sm))
        InfoRow("执行", summary.executionSurface)
        InfoRow("引导", summary.bootstrapStatus)
        InfoRow("rootfs", summary.rootfsStatus)
        InfoRow("二进制", summary.binaryStatus)
        InfoRow("ABI", summary.abiStatus)
        InfoRow("根目录", summary.rootfsPath)
        InfoRow("usr", summary.usrPath)
        InfoRow("tmp", summary.tmpPath)
    }
}

@Composable
private fun InspectorRunLogRow(
    entry: RunLogEntry,
    onOpenFile: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    val canOpen = entry.targetKind != RunLogTargetKind.NONE
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = canOpen) {
                when (entry.targetKind) {
                    RunLogTargetKind.FILE -> onOpenFile(entry.targetPath)
                    RunLogTargetKind.WEB -> onOpenUrl(entry.targetUrl)
                    RunLogTargetKind.NONE -> Unit
                }
            }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(9.dp).clip(Radii.pill)
                .background(inspectorRunLogTint(entry, colors))
                .padding(1.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1)
            Text(entry.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (canOpen && entry.state == RunLogState.DONE) "打开" else entry.state.runLogStateLabel(),
            style = AndmxTheme.typography.labelSmall,
            color = if (canOpen && entry.state == RunLogState.DONE) colors.accent else inspectorRunLogTint(entry, colors),
        )
    }
}

@Composable
private fun InstructionStatusRow(label: String, value: String) {
    val colors = AndmxTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, modifier = Modifier.width(48.dp))
        Text(value, style = AndmxTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BulletLine(text: String) {
    val colors = AndmxTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = Spacing.xs).size(4.dp).clip(Radii.pill).background(colors.textTertiary))
        Spacer(Modifier.width(Spacing.sm))
        Text(text, style = AndmxTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun inspectorRunLogTint(
    entry: RunLogEntry,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when {
    entry.state in setOf(RunLogState.FAILED, RunLogState.DENIED, RunLogState.WAITING) -> colors.warning
    entry.state == RunLogState.RUNNING -> colors.accent
    entry.kind == RunLogKind.USER -> colors.accent
    else -> colors.textSecondary
}

@Composable
private fun HandoffAdviceCard(advice: ContextHandoffAdvice, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = when (advice.level) {
        HandoffAdviceLevel.OK -> colors.accent
        HandoffAdviceLevel.WATCH -> colors.textSecondary
        HandoffAdviceLevel.RECOMMENDED -> colors.warning
        HandoffAdviceLevel.REQUIRED -> colors.warning
    }
    Column(
        Modifier.fillMaxWidth().clip(Radii.sm).background(colors.sunken)
            .border(1.dp, if (advice.level == HandoffAdviceLevel.OK) colors.border else tint, Radii.sm)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(advice.title, style = AndmxTheme.typography.labelMedium, color = tint, modifier = Modifier.weight(1f))
            Text(
                advice.primaryCommand,
                style = AndmxTheme.typography.labelSmall,
                color = colors.accent,
                modifier = Modifier.clip(Radii.sm).clickable { onRunCommand(advice.primaryCommand) }
                    .padding(horizontal = Spacing.xs, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(advice.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
    }
}

@Composable
private fun InspectorHeader(
    snapshot: AgentInspectorSnapshot,
    nextAction: NextActionDecision,
    onOpenProgress: () -> Unit,
    onRunCommand: (String) -> Unit,
) {
    val colors = AndmxTheme.colors
    val tint = nextActionTint(nextAction.priority, colors)
    Column(
        Modifier.fillMaxWidth().clip(Radii.md).background(colors.surface)
            .border(1.dp, colors.border, Radii.md)
            .padding(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Psychology, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("Agent Inspector", style = AndmxTheme.typography.titleMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                if (snapshot.busy) "运行中" else "待命",
                style = AndmxTheme.typography.labelSmall,
                color = if (snapshot.busy) colors.accent else colors.textTertiary,
                modifier = Modifier.clip(Radii.pill).background(colors.sunken).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(nextAction.title, style = AndmxTheme.typography.bodyMedium, color = tint, modifier = Modifier.weight(1f))
            Text(
                nextAction.priority.label,
                style = AndmxTheme.typography.labelSmall,
                color = tint,
                modifier = Modifier.clip(Radii.pill).background(colors.sunken)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(nextAction.reason, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Spacing.sm))
        nextAction.evidence.take(2).forEach { evidence ->
            BulletLine(evidence)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                nextAction.command,
                style = AndmxTheme.typography.labelMedium,
                color = colors.accent,
                modifier = Modifier.clip(Radii.sm).clickable { onRunCommand(nextAction.command) }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "任务面板",
                style = AndmxTheme.typography.labelMedium,
                color = colors.accent,
                modifier = Modifier.clip(Radii.sm).clickable(onClick = onOpenProgress)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
    }
}

@Composable
private fun InspectorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = AndmxTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(Radii.md).background(colors.surface)
            .border(1.dp, colors.border, Radii.md)
            .padding(Spacing.lg),
    ) {
        Text(title, style = AndmxTheme.typography.titleSmall, color = colors.textPrimary)
        Spacer(Modifier.height(Spacing.md))
        content()
    }
}

@Composable
private fun InspectorPlanRow(item: com.andmx.agent.TaskPlanItem) {
    val colors = AndmxTheme.colors
    val tint = when (item.status) {
        PlanItemStatus.DONE -> colors.accent
        PlanItemStatus.ACTIVE -> colors.textPrimary
        PlanItemStatus.BLOCKED -> colors.warning
        PlanItemStatus.PENDING -> colors.textTertiary
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(10.dp).clip(Radii.pill).background(tint).padding(1.dp))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text("${item.title} · ${item.status.label}", style = AndmxTheme.typography.labelMedium, color = tint, maxLines = 1)
            Text(item.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun InspectorChecklistRow(item: SessionChecklistItem, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = checklistStateTint(item.state, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = item.command.isNotBlank()) { onRunCommand(item.command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(10.dp).clip(Radii.pill).background(tint).padding(1.dp))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text("${item.title} · ${item.state.label}", style = AndmxTheme.typography.labelMedium, color = tint, maxLines = 1)
            Text(item.detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (item.command.isNotBlank()) {
            Text(item.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MetricGrid(metrics: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        metrics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { (label, value) ->
                    MetricTile(label, value, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = AndmxTheme.colors
    Column(
        modifier.clip(Radii.sm).background(colors.sunken).padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(value, style = AndmxTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1)
        Text(label, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
    }
}

@Composable
private fun InfoRow(label: String, value: String) = SharedInfoRow(label = label, value = value, labelWidth = 48.dp)

@Composable
private fun RiskEffectRow(label: String, count: String, effect: String) {
    val colors = AndmxTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AndmxTheme.typography.labelSmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text("$count 个", style = AndmxTheme.typography.labelSmall, color = colors.textTertiary)
        Spacer(Modifier.width(Spacing.sm))
        Text(
            effect,
            style = AndmxTheme.typography.labelSmall,
            color = when (effect) {
                "自动" -> colors.accent
                "询问" -> colors.warning
                else -> colors.textTertiary
            },
            modifier = Modifier.clip(Radii.pill).background(colors.sunken)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

@Composable
private fun SafetyBoundaryEffectRow(row: SafetyBoundaryRow) {
    val colors = AndmxTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill)
                .background(safetyBoundaryTint(row.effect, colors)),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${row.title} · ${row.effect.label()}",
                style = AndmxTheme.typography.labelMedium,
                color = safetyBoundaryTint(row.effect, colors),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.examples.joinToString(" / "),
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ParityRow(item: CodexParityItem, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = parityItemTint(item.state, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = item.command.isNotBlank()) { onRunCommand(item.command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${item.title} · ${item.state.label}",
                style = AndmxTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.detail,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(item.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun parityTint(
    audit: CodexParityAudit,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when {
    audit.gapCount > 0 -> colors.warning
    audit.watchCount > 0 -> colors.textSecondary
    else -> colors.accent
}

private fun parityItemTint(
    state: ParityState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    ParityState.READY -> colors.accent
    ParityState.WATCH -> colors.textSecondary
    ParityState.GAP -> colors.warning
}

@Composable
private fun ArchitectureLayerRow(layer: ArchitectureLayer, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = architectureStateTint(layer.state, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = layer.command.isNotBlank()) { onRunCommand(layer.command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${layer.title} · ${layer.state.label}",
                style = AndmxTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                layer.detail,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(layer.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1)
    }
}

private fun architectureTint(
    blueprint: AgentArchitectureBlueprint,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (blueprint.state) {
    ArchitectureState.READY -> colors.accent
    ArchitectureState.WATCH -> colors.textSecondary
    ArchitectureState.GAP -> colors.warning
}

private fun architectureStateTint(
    state: ArchitectureState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    ArchitectureState.READY -> colors.accent
    ArchitectureState.WATCH -> colors.textSecondary
    ArchitectureState.GAP -> colors.warning
}

@Composable
private fun SurfaceSpecRow(surface: CodexSurfaceSpec, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = surfaceReadinessTint(surface.readiness, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = surface.command.isNotBlank()) { onRunCommand(surface.command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${surface.title} · ${surface.readiness.label}",
                style = AndmxTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                surface.andmxSurface,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(surface.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1)
    }
}

private fun surfaceMapTint(
    map: CodexSurfaceMap,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when {
    map.waitingCount > 0 -> colors.warning
    map.partialCount > 0 -> colors.textSecondary
    else -> colors.accent
}

private fun surfaceReadinessTint(
    readiness: SurfaceReadiness,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (readiness) {
    SurfaceReadiness.READY -> colors.accent
    SurfaceReadiness.PARTIAL -> colors.textSecondary
    SurfaceReadiness.WAITING_REFERENCE -> colors.warning
}

@Composable
private fun VisualAcceptanceRow(item: VisualAcceptanceItem, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = visualAcceptanceStateTint(item.state, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = item.command.isNotBlank()) { onRunCommand(item.command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${item.title} · ${item.state.label}",
                style = AndmxTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.detail,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(item.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun visualAcceptanceTint(
    summary: VisualAcceptanceSummary,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color =
    if (summary.waitingCount == 0) colors.accent else colors.warning

private fun visualAcceptanceStateTint(
    state: VisualAcceptanceState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    VisualAcceptanceState.READY -> colors.accent
    VisualAcceptanceState.NEEDS_REFERENCE -> colors.warning
    VisualAcceptanceState.NEEDS_EXTRACTION -> colors.textSecondary
    VisualAcceptanceState.NEEDS_IMPLEMENTATION -> colors.textSecondary
    VisualAcceptanceState.NEEDS_VERIFICATION -> colors.warning
}

@Composable
private fun DesignSystemRow(item: CodexDesignSystemItem, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = designSystemStateTint(item.state, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = item.command.isNotBlank()) { onRunCommand(item.command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${item.title} · ${item.state.label}",
                style = AndmxTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.andmxApplication,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(item.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun designSystemTint(
    audit: CodexDesignSystemAudit,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when {
    audit.gapCount > 0 -> colors.warning
    audit.watchCount > 0 -> colors.textSecondary
    else -> colors.accent
}

private fun designSystemStateTint(
    state: DesignAuditState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    DesignAuditState.READY -> colors.accent
    DesignAuditState.WATCH -> colors.textSecondary
    DesignAuditState.GAP -> colors.warning
}

@Composable
private fun ReferenceBoardItemRow(item: UiReferenceBoardItem, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = referenceBoardStateTint(item.state, colors)
    val command = item.commands.firstOrNull().orEmpty()
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = command.isNotBlank()) { onRunCommand(command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${item.kind.label} · ${item.state.label}",
                style = AndmxTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.label,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1)
    }
}

private fun referenceBoardTint(
    board: UiReferenceBoard,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color =
    if (board.openCount == 0) colors.accent else colors.warning

private fun referenceBoardStateTint(
    state: UiReferenceBoardState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    UiReferenceBoardState.READY -> colors.accent
    UiReferenceBoardState.WAITING -> colors.warning
    UiReferenceBoardState.READY_TO_EXTRACT -> colors.textSecondary
    UiReferenceBoardState.IMPLEMENTING -> colors.textSecondary
    UiReferenceBoardState.VERIFYING -> colors.warning
}

@Composable
private fun ScreenshotExtractionRow(item: ScreenshotExtractionItem, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = screenshotExtractionStateTint(item.state, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = item.command.isNotBlank()) { onRunCommand(item.command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${item.label} · ${item.state.label}",
                style = AndmxTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.detail,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(item.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun screenshotExtractionTint(
    summary: ScreenshotExtractionSummary,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color =
    if (summary.waitingCount == 0) colors.accent else colors.warning

private fun screenshotExtractionStateTint(
    state: ScreenshotExtractionState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    ScreenshotExtractionState.READY -> colors.accent
    ScreenshotExtractionState.WAITING_REFERENCE -> colors.warning
    ScreenshotExtractionState.NEEDS_EXTRACTION -> colors.textSecondary
    ScreenshotExtractionState.NEEDS_IMPLEMENTATION -> colors.textSecondary
    ScreenshotExtractionState.NEEDS_VERIFICATION -> colors.warning
}

@Composable
private fun ScreenshotTraceRow(item: ScreenshotTraceItem, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = screenshotTraceStateTint(item.state, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = item.command.isNotBlank()) { onRunCommand(item.command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${item.state.label} · ${item.targetSurface}",
                style = AndmxTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.reference,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(item.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun screenshotTraceTint(
    trace: ScreenshotImplementationTrace,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color =
    if (trace.waitingCount == 0) colors.accent else colors.warning

private fun screenshotTraceStateTint(
    state: TraceState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    TraceState.READY -> colors.accent
    TraceState.WAITING_REFERENCE -> colors.warning
    TraceState.NEEDS_MAPPING -> colors.textSecondary
    TraceState.IMPLEMENTING -> colors.textSecondary
    TraceState.NEEDS_VERIFICATION -> colors.warning
}

@Composable
private fun InteractionFlowStepRow(step: CodexInteractionStep, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = interactionFlowStateTint(step.state, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = step.command.isNotBlank()) { onRunCommand(step.command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${step.title} · ${step.state.label}",
                style = AndmxTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                step.andmxSignal,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(step.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1)
    }
}

private fun interactionFlowTint(
    flow: CodexInteractionFlow,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when {
    flow.blockedCount > 0 -> colors.warning
    flow.activeCount > 0 -> colors.accent
    flow.watchCount > 0 -> colors.textSecondary
    else -> colors.accent
}

private fun interactionFlowStateTint(
    state: InteractionFlowState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    InteractionFlowState.READY -> colors.accent
    InteractionFlowState.ACTIVE -> colors.accent
    InteractionFlowState.WATCH -> colors.textSecondary
    InteractionFlowState.BLOCKED -> colors.warning
}

@Composable
private fun SelfModelLayerRow(layer: CodexSelfModelLayer, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    val tint = selfModelStateTint(layer.state, colors)
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm)
            .clickable(enabled = layer.command.isNotBlank()) { onRunCommand(layer.command) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = Spacing.xs).size(8.dp).clip(Radii.pill).background(tint))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "${layer.title} · ${layer.state.label}",
                style = AndmxTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                layer.andmxImplementation,
                style = AndmxTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(layer.command, style = AndmxTheme.typography.labelSmall, color = colors.accent, maxLines = 1)
    }
}

private fun selfModelTint(
    model: CodexSelfModel,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when {
    model.gapCount > 0 -> colors.warning
    model.watchCount > 0 -> colors.textSecondary
    else -> colors.accent
}

private fun selfModelStateTint(
    state: SelfModelState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    SelfModelState.READY -> colors.accent
    SelfModelState.WATCH -> colors.textSecondary
    SelfModelState.GAP -> colors.warning
}

private fun deliveryReportTint(
    report: DeliveryReport,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (report.state) {
    DeliveryReportState.READY -> colors.accent
    DeliveryReportState.NEEDS_REVIEW -> colors.textSecondary
    DeliveryReportState.NEEDS_VERIFICATION -> colors.warning
    DeliveryReportState.BLOCKED -> colors.warning
}

private fun safetyBoundaryTint(
    effect: SafetyBoundaryEffect,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (effect) {
    SafetyBoundaryEffect.AUTO -> colors.accent
    SafetyBoundaryEffect.PROMPT -> colors.warning
    SafetyBoundaryEffect.DENY -> colors.textSecondary
    SafetyBoundaryEffect.HANDOFF -> colors.warning
}

private fun checklistTint(
    summary: SessionChecklistSummary,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when {
    summary.missingCount > 0 -> colors.warning
    summary.watchCount > 0 -> colors.textSecondary
    else -> colors.accent
}

private fun checklistStateTint(
    state: ChecklistState,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (state) {
    ChecklistState.READY -> colors.accent
    ChecklistState.WATCH -> colors.textSecondary
    ChecklistState.MISSING -> colors.warning
}

private fun nextActionTint(
    priority: NextActionPriority,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (priority) {
    NextActionPriority.BLOCKED -> colors.warning
    NextActionPriority.ACTIVE -> colors.accent
    NextActionPriority.REVIEW -> colors.textSecondary
    NextActionPriority.VERIFY -> colors.warning
    NextActionPriority.HANDOFF -> colors.textSecondary
    NextActionPriority.CONTINUE -> colors.accent
}

private fun evidenceTint(
    kind: EvidenceKind,
    colors: com.andmx.ui.theme.AndmxColors,
): androidx.compose.ui.graphics.Color = when (kind) {
    EvidenceKind.FILE -> colors.accent
    EvidenceKind.WEB -> colors.accent
    EvidenceKind.UI_REFERENCE -> colors.accent
    EvidenceKind.VERIFY -> colors.warning
    EvidenceKind.CHANGE -> colors.textSecondary
    EvidenceKind.APPROVAL -> colors.warning
    EvidenceKind.ACTIVITY -> colors.textSecondary
}

@Composable
private fun CommandActionRow(icon: ImageVector, command: String, detail: String, onRunCommand: (String) -> Unit) {
    val colors = AndmxTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(Radii.sm).clickable { onRunCommand(command) }
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(command, style = AndmxTheme.typography.labelLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = AndmxTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("运行", style = AndmxTheme.typography.labelSmall, color = colors.accent)
    }
}
