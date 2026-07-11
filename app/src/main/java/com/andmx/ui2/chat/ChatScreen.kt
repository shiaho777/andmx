package com.andmx.ui2.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.andmx.agent.SlashCommands
import com.andmx.ui2.drawer.ConversationDrawer
import com.andmx.ui2.files.FilesScreen
import com.andmx.ui2.settings.SettingsScreen
import com.andmx.ui2.terminal.TerminalScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val toolCalls by viewModel.toolCalls.collectAsState()
    val error by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val config by viewModel.composerConfig.collectAsState()
    val contextChips by viewModel.contextChips.collectAsState()
    val recentConversations by viewModel.recentConversations.collectAsState()
    val skills by viewModel.skills.collectAsState()

    // ZCode 对齐：对话为唯一主屏，终端/文件/设置均为浮层
    val context = LocalContext.current
    val projectName by viewModel.projectName.collectAsState()
    val gitInfo by viewModel.gitInfo.collectAsState()
    val hostPath by viewModel.hostPath.collectAsState()
    val branch = gitInfo?.branch.orEmpty()

    var showTerminal by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }

    // 浮层打开时拦截返回键：先关浮层，再走系统返回
    BackHandler(enabled = showTerminal || showFiles || showSettings) {
        when {
            showTerminal -> showTerminal = false
            showFiles -> showFiles = false
            showSettings -> showSettings = false
        }
    }

    var drawerOpen by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }

    // 文件页 @ 引用注入
    LaunchedEffect(Unit) {
        ChatComposerBus.inserts.collect { snippet ->
            val s = snippet.trim()
            when {
                // @ 引用 → 进 context chip（不留在输入框，避免发送时重复引用）
                s.startsWith("@") -> {
                    val path = s.removePrefix("@").trim()
                    if (path.isNotBlank()) viewModel.addFileContext(path)
                }
                else -> {
                    inputText = if (inputText.isBlank()) snippet else "$inputText\n$snippet"
                }
            }
        }
    }

    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "图片"
            attachments = attachments + Attachment(name = name, uri = uri.toString())
        }
    }

    // SAF 目录选择器：选择工作区文件夹
    val workspacePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // SAF tree uri → 真实文件系统路径（MANAGE_EXTERNAL_STORAGE 语义）
            val seg = uri.lastPathSegment.orEmpty()
            val path = if (seg.startsWith("primary:")) {
                val base = android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"
                "$base/${seg.removePrefix("primary:")}"
            } else {
                seg
            }
            if (path.isNotBlank()) viewModel.selectProject(path)
        }
    }

    // ── 输入触发：/ 与 # 建议 ──
    val slashSuggestions by remember {
        derivedStateOf {
            val t = inputText
            // 仅当整行以 / 开头且尚无空格时给命令建议（ZCode 触发）
            if (t.startsWith("/") && !t.contains(' ')) {
                SlashCommands.suggestions(t, 6)
            } else emptyList()
        }
    }
    val conversationSuggestions by remember {
        derivedStateOf {
            val t = inputText
            // 行尾 #query 触发会话联想
            val match = Regex("""(?:^|\s)#([^\s#]*)$""").find(t) ?: return@derivedStateOf emptyList()
            val q = match.groupValues[1]
            recentConversations
                .filter {
                    q.isBlank() ||
                        it.title.contains(q, ignoreCase = true) ||
                        it.subtitle.contains(q, ignoreCase = true)
                }
                .take(8)
        }
    }
    val skillSuggestions by remember {
        derivedStateOf {
            val t = inputText
            // 行尾 $query 触发技能联想
            val match = Regex("""(?:^|\s)\$([^\s$]*)$""").find(t) ?: return@derivedStateOf emptyList()
            val q = match.groupValues[1]
            skills
                .filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }
                .take(8)
                .map { SkillSuggestion(name = it.name, path = it.path) }
        }
    }

    fun insertAtCursor(snippet: String, replaceTrigger: Regex? = null) {
        val cur = inputText
        inputText = if (replaceTrigger != null) {
            replaceTrigger.replace(cur) { mr ->
                val prefix = mr.value.takeWhile { it.isWhitespace() }
                "$prefix$snippet"
            }
        } else {
            if (cur.isBlank()) snippet
            else if (cur.endsWith(" ") || cur.endsWith("\n")) cur + snippet
            else "$cur $snippet"
        }
    }

    ConversationDrawer(
        open = drawerOpen,
        onDismiss = { drawerOpen = false },
        onSelectConversation = { id ->
            viewModel.switchToConversation(id)
            drawerOpen = false
        },
        onOpenFiles = {
            drawerOpen = false
            showFiles = true
        },
        onOpenSettings = {
            drawerOpen = false
            showSettings = true
        },
        workspaceName = projectName,
        suggestedRoots = remember { viewModel.suggestedRoots() },
        onSelectWorkspace = { viewModel.selectProject(it); drawerOpen = false },
        onPickWorkspaceDir = { workspacePicker.launch(null); drawerOpen = false },
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {

            // Composer 复用：空状态和对话状态共用同一套调用（局部闭包）
            @Composable
            fun ComposerBlock() {
                Composer(
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank() || attachments.isNotEmpty() || contextChips.isNotEmpty()) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                            attachments = emptyList()
                        }
                    },
                    isLoading = isLoading,
                    onStop = { viewModel.stop() },
                    modelLabel = config.modelLabel,
                    selectedModel = config.settings.model,
                    activeProviderId = config.settings.activeProviderId
                        .ifBlank { config.primary?.id.orEmpty() },
                    providers = config.providers,
                    onSwitchModel = { pid, mid -> viewModel.switchModel(pid, mid) },
                    reasoningEffort = config.settings.reasoningEffort,
                    reasoning = config.reasoning,
                    onReasoningSelected = { viewModel.setReasoningEffort(it) },
                    execMode = config.execMode,
                    onExecModeSelected = { viewModel.setExecMode(it) },
                    contextChips = contextChips,
                    onRemoveContextChip = { viewModel.removeContextChip(it) },
                    attachments = attachments,
                    onRemoveAttachment = { i ->
                        attachments = attachments.filterIndexed { idx, _ -> idx != i }
                    },
                    onAddAttachment = { imagePicker.launch("image/*") },
                    onInsertMention = { showFiles = true },
                    onInsertConversation = { insertAtCursor("#") },
                    onInsertCommand = { insertAtCursor("/") },
                    onInsertSkill = { insertAtCursor("\$") },
                    slashSuggestions = slashSuggestions,
                    onPickSlash = { spec -> inputText = SlashCommands.complete(spec) },
                    conversationSuggestions = conversationSuggestions,
                    onPickConversation = { pick ->
                        inputText = Regex("""(?:^|\s)#[^\s#]*$""").replace(inputText) { mr ->
                            mr.value.takeWhile { it.isWhitespace() }
                        }.trimEnd()
                        viewModel.addConversationContext(pick)
                    },
                    skillSuggestions = skillSuggestions,
                    onPickSkill = { skill ->
                        inputText = Regex("""(?:^|\s)\$[^\s$]*$""").replace(inputText) { mr ->
                            mr.value.takeWhile { it.isWhitespace() }
                        }.trimEnd()
                        viewModel.addSkillByName(skill.name, skill.path)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }

            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(
                            enabled = branch.isNotBlank(),
                        ) { showBranchDialog = true },
                    ) {
                        Text(
                            projectName,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (branch.isNotBlank()) {
                            Text(
                                "  ⎇ $branch",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { drawerOpen = true }) {
                        Icon(Icons.Outlined.Menu, "菜单")
                    }
                },
                // ZCode：终端从对话头部右上角唤起
                actions = {
                    IconButton(onClick = { showTerminal = true }) {
                        Icon(Icons.Outlined.Terminal, "终端")
                    }
                },
            )

            // ZCode 对齐：无消息时 Composer 居中 + 欢迎语；有消息时 Composer 在底部
            val isEmpty = messages.isEmpty() && toolCalls.isEmpty()

            if (isEmpty) {
                EmptyConversationState(
                    hasWorkspace = hostPath != null,
                    projectName = projectName,
                    branch = branch,
                    onPickWorkspace = { workspacePicker.launch(null) },
                    onPickBranch = { showBranchDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    ComposerBlock()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    reverseLayout = true,
                ) {
                    items(
                        items = (messages + toolCalls.map { it as Any }).reversed(),
                        key = { item ->
                            when (item) {
                                is ChatMessage -> item.id
                                is ToolCall -> item.id
                                else -> System.currentTimeMillis()
                            }
                        },
                    ) { item ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInVertically { it / 2 },
                            exit = fadeOut() + slideOutVertically(),
                        ) {
                            when (item) {
                                is ChatMessage -> MessageBubble(item)
                                is ToolCall -> ToolCallCard(item)
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = error != null,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    error?.let {
                        Snackbar(
                            modifier = Modifier.padding(8.dp),
                            action = {
                                androidx.compose.material3.TextButton(
                                    onClick = { viewModel.clearError() },
                                ) {
                                    Text("关闭")
                                }
                            },
                        ) {
                            Text(it)
                        }
                    }
                }

                if (queue.isNotEmpty()) {
                    QueueStrip(
                        queue = queue,
                        onRemove = { viewModel.removeFromQueue(it) },
                        onSendNow = { viewModel.sendQueuedNow(it) },
                        canSendNow = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    )
                }

                ComposerBlock()
            }
        } // end Column

            // ── 浮层：终端 / 文件 / 设置（ZCode 对齐：非独占 TAB，从对话唤起）──
            if (showTerminal) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 1f)),
                ) {
                    TerminalScreen(modifier = Modifier.fillMaxSize())
                    IconButton(
                        onClick = { showTerminal = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    ) { Icon(Icons.Outlined.Close, "关闭终端", tint = Color.White) }
                }
            }
            if (showFiles) {
                FilesScreen(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
            if (showSettings) {
                SettingsScreen(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        } // end Box

        // ── 分支切换对话框（ZCode：分支作为工作区上下文）──
        if (showBranchDialog) {
            BranchSwitchDialog(
                currentBranch = branch,
                onListBranches = { viewModel.listBranches() },
                onCheckout = { name, create, cb -> viewModel.checkoutBranch(name, create, cb) },
                onDismiss = { showBranchDialog = false },
            )
        }
    }
}

/**
 * ZCode 对齐空状态：无消息时 Composer 居中，上方有工作区/分支信息和欢迎语。
 * 未选工作区时显示「选择文件夹」引导。
 */
@Composable
private fun EmptyConversationState(
    hasWorkspace: Boolean,
    projectName: String,
    branch: String,
    onPickWorkspace: () -> Unit,
    onPickBranch: () -> Unit,
    modifier: Modifier = Modifier,
    composer: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hasWorkspace) {
            // 工作区 + 分支上下文条（可点击切换分支）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .clickable(enabled = branch.isNotBlank(), onClick = onPickBranch),
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    projectName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (branch.isNotBlank()) {
                    Text(
                        "⎇ $branch",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                    )
                }
            }
            Text(
                "开始一个新任务",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "描述你的目标，Agent 会帮你规划并实现",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        } else {
            // 未选工作区：引导选择文件夹
            Icon(
                Icons.Outlined.DriveFolderUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Text(
                "选择工作区开始",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "选择一个项目文件夹，Agent 将在它的真实文件上工作",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            androidx.compose.material3.FilledTonalButton(onClick = onPickWorkspace) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                Text("  选择文件夹", modifier = Modifier.padding(start = 6.dp))
            }
            androidx.compose.material3.HorizontalDivider(
                Modifier.padding(vertical = 20.dp, horizontal = 40.dp),
            )
        }
        composer()
    }
}
