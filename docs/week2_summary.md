# Week 2 — 对话流 + 工具卡片

## 完成内容

### 流式对话管理
`ui2/chat/`（实测约 975 行）：
- `ChatController.kt` — 集成 AgentEngine 流式对话，处理 AssistantDelta 事件，工具调用管理，错误处理
- `ChatViewModel.kt` — ViewModel + StateFlow 状态管理，流式文本增量更新，工具调用状态追踪
- `ChatEvent.kt` — 事件密封类（ChatMessage / ToolCall 等数据模型）
- `ChatComposerBus.kt` / `QueueStrip.kt` — 输入与排队

### UI 组件
- `ToolCallCard.kt` — 可展开/折叠卡片，运行状态指示器，错误状态高亮，Spring 展开动画
- `StreamingText.kt` — Markdown 渲染集成 + 流式光标动画
- `MessageBubble.kt` — 消息气泡
- `Composer.kt` — 输入框

### Material 动画
- 消息进入/退出：fadeIn/slideInVertically、fadeOut/slideOutVertically
- 工具卡片：expandVertically / shrinkVertically
- 光标：无限循环透明度
- 错误：AnimatedVisibility Snackbar
- LazyColumn 使用 key 优化重组

## 备注
- 流式滚动帧率未做定量测量。
- 行数为代码实测值。
