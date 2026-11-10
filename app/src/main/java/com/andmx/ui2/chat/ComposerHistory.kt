package com.andmx.ui2.chat

internal data class HistoryBrowseState(
    val index: Int? = null,
    val draft: String = "",
)

internal fun historyUp(
    history: List<String>,
    browse: HistoryBrowseState?,
    currentText: String,
): Pair<HistoryBrowseState, String>? {
    if (history.isEmpty()) return null
    if (browse?.index == null) {
        if (currentText.isNotEmpty()) return null
        return HistoryBrowseState(index = 0, draft = currentText) to history[0]
    }
    val next = (browse.index + 1).coerceAtMost(history.lastIndex)
    return browse.copy(index = next) to history[next]
}

internal fun historyDown(
    history: List<String>,
    browse: HistoryBrowseState?,
): Pair<HistoryBrowseState, String>? {
    val index = browse?.index ?: return null
    if (history.isEmpty()) return null
    if (index > 0) {
        return browse.copy(index = index - 1) to history[index - 1]
    }
    return HistoryBrowseState() to browse.draft
}
