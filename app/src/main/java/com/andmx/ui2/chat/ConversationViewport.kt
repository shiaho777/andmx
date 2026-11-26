package com.andmx.ui2.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

internal class ConversationViewport(val list: LazyListState) {
    var following by mutableStateOf(true)
    var restored by mutableStateOf(false)

    val scrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                following = !list.canScrollBackward
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!list.canScrollBackward) following = true
            return Velocity.Zero
        }
    }

    fun position(items: List<TimelineItem>): ChatScrollMemory.Position? {
        if (!restored || list.layoutInfo.visibleItemsInfo.isEmpty()) return null
        if (following) return ChatScrollMemory.Position(0, 0)
        val index = list.firstVisibleItemIndex
        val offset = list.firstVisibleItemScrollOffset
        val key = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key
        val item = items.getOrNull(index)?.takeIf { it.stableId == key }
            ?: items.firstOrNull { it.stableId == key }
            ?: return null
        return ChatScrollMemory.Position(index, offset, item.stableId, item.sortKey)
    }

    suspend fun latest() {
        following = true
        list.animateScrollToItem(0)
    }
}

@Composable
internal fun rememberConversationViewport(
    conversationId: Long,
    items: List<TimelineItem>,
    restoringHistory: Boolean,
    memory: ChatScrollMemory,
): ConversationViewport {
    val list = key(conversationId) { rememberLazyListState() }
    val viewport = remember(list) { ConversationViewport(list) }
    val latestItems by key(conversationId) { rememberUpdatedState(items) }
    val ready = !restoringHistory && items.isNotEmpty()
    LaunchedEffect(viewport, ready) {
        if (!ready || viewport.restored) return@LaunchedEffect
        val saved = memory.load(conversationId)
        viewport.following = saved == null || (saved.index == 0 && saved.offset == 0 && saved.itemId == null)
        val target = saved?.resolve(latestItems) ?: ChatScrollMemory.Position(0, 0)
        list.scrollToItem(target.index, target.offset)
        viewport.restored = true
    }
    LaunchedEffect(viewport, items.firstOrNull()?.stableId, viewport.restored) {
        if (viewport.restored && viewport.following && !list.isScrollInProgress && items.isNotEmpty()) {
            list.requestScrollToItem(0)
        }
    }
    LaunchedEffect(viewport, conversationId) {
        snapshotFlow { viewport.position(latestItems) }.filterNotNull().collectLatest { position ->
            delay(240)
            memory.save(conversationId, position)
        }
    }
    DisposableEffect(viewport, conversationId) {
        onDispose {
            viewport.position(latestItems)?.let { memory.save(conversationId, it) }
        }
    }
    return viewport
}
