package com.andmx.ui2.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.andmx.ui2.drawer.ConversationDrawer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val toolCalls by viewModel.toolCalls.collectAsState()
    val error by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val queue by viewModel.queue.collectAsState()
    var drawerOpen by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        ChatComposerBus.inserts.collect { snippet ->
            inputText = if (inputText.isBlank()) snippet else "$inputText\n$snippet"
        }
    }

    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "图片"
            attachments = attachments + Attachment(name = name, uri = uri.toString())
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
            com.andmx.ui2.nav.NavBus.navigateTo(com.andmx.ui2.nav.Screen.Files.route)
        }
    ) {
        Column(modifier = modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("AndMX") },
                navigationIcon = {
                    IconButton(onClick = { drawerOpen = true }) {
                        Icon(Icons.Outlined.Menu, "菜单")
                    }
                }
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reverseLayout = true
            ) {
                items(
                    items = (messages + toolCalls.map { it as Any }).reversed(),
                    key = { item ->
                        when (item) {
                            is ChatMessage -> item.id
                            is ToolCall -> item.id
                            else -> System.currentTimeMillis()
                        }
                    }
                ) { item ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically()
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
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                error?.let {
                    Snackbar(
                        modifier = Modifier.padding(8.dp),
                        action = {
                            androidx.compose.material3.TextButton(
                                onClick = { viewModel.clearError() }
                            ) {
                                Text("关闭")
                            }
                        }
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }

            Composer(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank() || attachments.isNotEmpty()) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                        attachments = emptyList()
                    }
                },
                isLoading = isLoading,
                onStop = { viewModel.stop() },
                attachments = attachments,
                onAddAttachment = { imagePicker.launch("image/*") },
                onRemoveAttachment = { i -> attachments = attachments.filterIndexed { idx, _ -> idx != i } },
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(8.dp)
            )
        }
    }
}
