package com.andmx.ui2.chat

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.andmx.ui2.settings.tr

data class ShortcutCommand(
    val id: String,
    val label: String,
    val scope: String,
    val defaultBinding: String,
)

object SessionShortcuts {
    const val OPEN_COMMAND_CENTER = "openCommandCenter"
    const val OPEN_SETTINGS = "openSettings"
    const val TOGGLE_SIDEBAR = "toggleSidebar"
    const val TOGGLE_TERMINAL = "toggleTerminal"
    const val SWITCH_THEME = "switchTheme"
    const val CYCLE_THOUGHT = "cycleThoughtLevel"
    const val CYCLE_MODE = "cycleSessionMode"
    const val NEW_TASK = "newTask"
    const val OPEN_FILES = "openWorkspace"
    const val STOP = "stop"
    const val FIND_IN_TASK = "findInTask"
    const val PREVIOUS_CONVERSATION = "previousConversation"
    const val NEXT_CONVERSATION = "nextConversation"
    const val NAVIGATE_BACK = "navigateBack"
    const val NAVIGATE_FORWARD = "navigateForward"
    const val OPEN_MODEL_MENU = "openModelMenu"
    const val ZOOM_IN = "zoomIn"
    const val ZOOM_OUT = "zoomOut"
    const val ZOOM_RESET = "resetZoom"
    const val SEND = "composerSend"
    const val NEWLINE = "composerInsertNewline"

    val commands = listOf(
        ShortcutCommand(OPEN_COMMAND_CENTER, tr("打开命令面板"), tr("全局"), "Ctrl+K"),
        ShortcutCommand(OPEN_SETTINGS, tr("打开设置"), tr("全局"), "Ctrl+,"),
        ShortcutCommand(TOGGLE_SIDEBAR, tr("切换左侧栏"), tr("全局"), "Ctrl+B"),
        ShortcutCommand(TOGGLE_TERMINAL, tr("打开终端"), tr("全局"), "Ctrl+J"),
        ShortcutCommand(SWITCH_THEME, tr("切换主题"), tr("全局"), "Ctrl+Shift+L"),
        ShortcutCommand(CYCLE_THOUGHT, tr("切换思考级别"), tr("全局"), "Ctrl+T"),
        ShortcutCommand(CYCLE_MODE, tr("切换会话模式"), tr("全局"), "Ctrl+Shift+M"),
        ShortcutCommand(NEW_TASK, tr("新建任务"), tr("全局"), "Ctrl+N"),
        ShortcutCommand(OPEN_FILES, tr("打开文件"), tr("全局"), "Ctrl+O"),
        ShortcutCommand(FIND_IN_TASK, tr("在任务中查找"), tr("全局"), "Ctrl+F"),
        ShortcutCommand(PREVIOUS_CONVERSATION, tr("上一个任务"), tr("全局"), "Ctrl+Shift+["),
        ShortcutCommand(NEXT_CONVERSATION, tr("下一个任务"), tr("全局"), "Ctrl+Shift+]"),
        ShortcutCommand(NAVIGATE_BACK, tr("上一回合"), tr("全局"), "Ctrl+["),
        ShortcutCommand(NAVIGATE_FORWARD, tr("下一回合"), tr("全局"), "Ctrl+]"),
        ShortcutCommand(OPEN_MODEL_MENU, tr("打开模型菜单"), tr("全局"), "Ctrl+M"),
        ShortcutCommand(ZOOM_IN, tr("放大界面"), tr("全局"), "Ctrl+="),
        ShortcutCommand(ZOOM_OUT, tr("缩小界面"), tr("全局"), "Ctrl+-"),
        ShortcutCommand(ZOOM_RESET, tr("重置界面大小"), tr("全局"), "Ctrl+0"),
        ShortcutCommand(STOP, tr("停止生成"), tr("输入框"), "Esc"),
        ShortcutCommand(SEND, tr("发送"), tr("输入框"), "Enter"),
        ShortcutCommand(NEWLINE, tr("换行"), tr("输入框"), "Shift+Enter"),
    )

    @Volatile
    var overrides: Map<String, String> = emptyMap()

    fun bindingOf(id: String): String {
        val custom = overrides[id]?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        return commands.firstOrNull { it.id == id }?.defaultBinding.orEmpty()
    }

    fun applyStored(raw: String) {
        overrides = parse(raw)
    }

    fun parse(raw: String): Map<String, String> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.contains('|') }
        .map { it.substringBefore('|').trim() to it.substringAfter('|').trim() }
        .filter { (id, binding) -> id.isNotEmpty() && binding.isNotEmpty() }
        .toMap()

    fun serialize(map: Map<String, String>): String =
        map.entries.joinToString("\n") { "${it.key}|${it.value}" }

    fun format(event: KeyEvent): String? {
        val native = event.nativeKeyEvent ?: return null
        if (native.action != AndroidKeyEvent.ACTION_DOWN || native.repeatCount != 0) return null
        val name = keyName(native) ?: return null
        val parts = mutableListOf<String>()
        if (native.isCtrlPressed) parts += "Ctrl"
        if (native.isAltPressed) parts += "Alt"
        if (native.isShiftPressed) parts += "Shift"
        parts += name
        return parts.joinToString("+")
    }

    fun screenAction(event: KeyEvent): String? {
        if (event.type != KeyEventType.KeyDown) return null
        val combo = format(event) ?: return null
        val native = event.nativeKeyEvent ?: return null
        if (!native.isCtrlPressed) return null
        return commands.firstOrNull { command ->
            command.scope == "全局" && bindingOf(command.id).equals(combo, ignoreCase = true)
        }?.id
    }

    fun composerAction(
        event: KeyEvent,
        isLoading: Boolean,
        inputEmpty: Boolean,
        suggestionsEmpty: Boolean,
    ): String? {
        if (event.type != KeyEventType.KeyDown) return null
        val cycleDefault = commands.first { it.id == CYCLE_MODE }.defaultBinding
        val native = event.nativeKeyEvent
        if (
            event.key == Key.Tab &&
            event.isShiftPressed &&
            native?.isCtrlPressed != true &&
            bindingOf(CYCLE_MODE).equals(cycleDefault, ignoreCase = true)
        ) {
            return CYCLE_MODE
        }
        val combo = format(event) ?: return null
        if (
            bindingOf(STOP).equals(combo, ignoreCase = true) &&
            isLoading &&
            inputEmpty &&
            suggestionsEmpty
        ) {
            return STOP
        }
        if (bindingOf(CYCLE_MODE).equals(combo, ignoreCase = true)) return CYCLE_MODE
        if (bindingOf(SEND).equals(combo, ignoreCase = true) && !bindingOf(SEND).equals("Enter", ignoreCase = true)) {
            return SEND
        }
        if (bindingOf(NEWLINE).equals(combo, ignoreCase = true) && !bindingOf(NEWLINE).equals("Shift+Enter", ignoreCase = true)) {
            return NEWLINE
        }
        return null
    }

    private fun keyName(event: AndroidKeyEvent): String? = when (event.keyCode) {
        AndroidKeyEvent.KEYCODE_ESCAPE -> "Esc"
        AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
        AndroidKeyEvent.KEYCODE_TAB -> "Tab"
        AndroidKeyEvent.KEYCODE_COMMA -> ","
        AndroidKeyEvent.KEYCODE_PERIOD -> "."
        AndroidKeyEvent.KEYCODE_MINUS -> "-"
        AndroidKeyEvent.KEYCODE_EQUALS -> "="
        AndroidKeyEvent.KEYCODE_0 -> "0"
        AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> "["
        AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        AndroidKeyEvent.KEYCODE_K -> "K"
        AndroidKeyEvent.KEYCODE_B -> "B"
        AndroidKeyEvent.KEYCODE_J -> "J"
        AndroidKeyEvent.KEYCODE_L -> "L"
        AndroidKeyEvent.KEYCODE_T -> "T"
        AndroidKeyEvent.KEYCODE_M -> "M"
        AndroidKeyEvent.KEYCODE_N -> "N"
        AndroidKeyEvent.KEYCODE_O -> "O"
        AndroidKeyEvent.KEYCODE_F -> "F"
        AndroidKeyEvent.KEYCODE_W -> "W"
        else -> {
            val ch = event.unicodeChar.toChar()
            if (ch.isLetter()) ch.uppercase() else null
        }
    }
}
