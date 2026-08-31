package com.andmx.settings

/**
 * ZCode persists the provider display order as a standalone list of ids
 * (`gGi = f.object({providerIds, updatedAt})` in the engine) rather than a
 * per-provider sort field — so a reorder touches one small record, and a
 * provider that has never been dragged simply has no position.
 *
 * Two pure functions cover the whole behaviour:
 * - [reorderProviderIds] is the drag result (ZCode's `YLt`, dnd-kit arrayMove)
 * - [applyProviderOrder] merges a stored order with the current provider list
 *   (ZCode's `sz`: stored positions win, unpositioned ids keep their relative
 *   order at the end)
 */
internal fun reorderProviderIds(
    providerIds: List<String>,
    activeProviderId: String,
    overProviderId: String,
): List<String> {
    val from = providerIds.indexOf(activeProviderId)
    val to = providerIds.indexOf(overProviderId)
    if (from < 0 || to < 0 || from == to) return providerIds.toList()
    val moved = providerIds.toMutableList()
    val item = moved.removeAt(from)
    moved.add(to, item)
    return moved
}

internal fun applyProviderOrder(
    providerIds: List<String>,
    storedOrder: List<String>,
): List<String> {
    if (storedOrder.isEmpty()) return providerIds.toList()
    val rank = storedOrder.withIndex().associate { (index, id) -> id to index }
    // sortedBy is stable, so ids without a stored position land at the end
    // in exactly the order the store handed them to us.
    return providerIds.sortedBy { rank[it] ?: Int.MAX_VALUE }
}
