package com.andmx.ui2.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
        onSelectConversation = {
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
                // 配置链
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
                // 上下文
                contextChips = contextChips,
                onRemoveContextChip = { viewModel.removeContextChip(it) },
                attachments = attachments,
                onRemoveAttachment = { i ->
                    attachments = attachments.filterIndexed { idx, _ -> idx != i }
                },
                // + 菜单
                onAddAttachment = { imagePicker.launch("image/*") },
                onInsertMention = {
                    // 打开工作区文件浏览浮层；选中文件后通过 ChatComposerBus 回注 @path
                    showFiles = true
                },
                onInsertConversation = {
                    // 在输入框插入 # 触发联想
                    insertAtCursor("#")
                },
                onInsertCommand = {
                    insertAtCursor("/")
                },
                onInsertSkill = {
                    insertAtCursor("\$")
                },
                // 建议
                slashSuggestions = slashSuggestions,
                onPickSlash = { spec ->
                    inputText = SlashCommands.complete(spec)
                },
                conversationSuggestions = conversationSuggestions,
                onPickConversation = { pick ->
                    // 去掉行尾 #query，加上 chip
                    inputText = Regex("""(?:^|\s)#[^\s#]*$""").replace(inputText) { mr ->
                        mr.value.takeWhile { it.isWhitespace() }
                    }.trimEnd()
                    viewModel.addConversationContext(pick)
                },
                skillSuggestions = skillSuggestions,
                onPickSkill = { skill ->
                    // 去掉行尾 $query，加上 chip
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
