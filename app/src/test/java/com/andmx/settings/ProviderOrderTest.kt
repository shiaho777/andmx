package com.andmx.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ZCode's drag result (`YLt`) and order merge (`sz`):
 *
 * ```
 * function YLt(e){ let t = indexOf(active), n = indexOf(over)
 *                  return t<0||n<0||t===n ? [...ids] : arrayMove([...ids], t, n) }
 * ```
 *
 * plus `sz`, where a stored position wins and an id with no stored position
 * falls to the end (`n ?? 2**53-1`) keeping its relative order.
 *
 * The move is the one piece of reordering that is easy to get subtly wrong —
 * off-by-one at the ends, or dropping a tail element — so it is asserted for
 * every from/to pair in lists of several lengths.
 */
class ProviderOrderTest {

    private val ids = listOf("a", "b", "c", "d")

    @Test
    fun draggingOntoYourselfLeavesTheOrderAlone() {
        for (id in ids) {
            assertEquals(ids, reorderProviderIds(ids, id, id))
        }
    }

    @Test
    fun anUnknownIdLeavesTheOrderAlone() {
        assertEquals(ids, reorderProviderIds(ids, "zzz", "a"))
        assertEquals(ids, reorderProviderIds(ids, "a", "zzz"))
        assertEquals(ids, reorderProviderIds(ids, "zzz", "yyy"))
    }

    @Test
    fun anEmptyOrSingleListIsAlreadyInOrder() {
        assertEquals(emptyList<String>(), reorderProviderIds(emptyList(), "a", "b"))
        assertEquals(listOf("a"), reorderProviderIds(listOf("a"), "a", "a"))
    }

    @Test
    fun movingOneSlotDownSwapsThePair() {
        assertEquals(listOf("b", "a", "c", "d"), reorderProviderIds(ids, "a", "b"))
        assertEquals(listOf("a", "c", "b", "d"), reorderProviderIds(ids, "b", "c"))
        assertEquals(listOf("a", "b", "d", "c"), reorderProviderIds(ids, "c", "d"))
    }

    @Test
    fun movingOneSlotUpSwapsThePair() {
        assertEquals(listOf("b", "a", "c", "d"), reorderProviderIds(ids, "b", "a"))
        assertEquals(listOf("a", "c", "b", "d"), reorderProviderIds(ids, "c", "b"))
        assertEquals(listOf("a", "b", "d", "c"), reorderProviderIds(ids, "d", "c"))
    }

    @Test
    fun movingToAnEndPutsTheItemThere() {
        assertEquals(listOf("b", "c", "d", "a"), reorderProviderIds(ids, "a", "d"))
        assertEquals(listOf("d", "a", "b", "c"), reorderProviderIds(ids, "d", "a"))
    }

    @Test
    fun everyFromToPairKeepsEveryIdExactlyOnce() {
        for (size in 1..5) {
            val list = (0 until size).map { "p$it" }
            for (from in list) {
                for (to in list) {
                    val moved = reorderProviderIds(list, from, to)
                    assertEquals("$size $from->$to size", size, moved.size)
                    assertEquals("$size $from->$to set", list.toSet(), moved.toSet())
                    assertEquals("$size $from->$to dupes", moved.size, moved.toSet().size)
                }
            }
        }
    }

    @Test
    fun movingAnItemThenMovingItBackRestoresTheOriginal() {
        // arrayMove is not an involution: dragging A onto C and then C onto A
        // does not undo itself. The real inverse is to drag A back onto
        // whichever id now sits where A used to be.
        for (fromIndex in ids.indices) {
            for (toIndex in ids.indices) {
                if (fromIndex == toIndex) continue
                val there = reorderProviderIds(ids, ids[fromIndex], ids[toIndex])
                val displaced = there[fromIndex]
                val back = reorderProviderIds(there, ids[fromIndex], displaced)
                assertEquals("$fromIndex->$toIndex", ids, back)
            }
        }
    }

    @Test
    fun theInputListIsNeverMutated() {
        val original = ids.toMutableList()
        reorderProviderIds(original, "a", "d")
        assertEquals(listOf("a", "b", "c", "d"), original)
    }

    @Test
    fun anEmptyStoredOrderPreservesTheStoreOrder() {
        assertEquals(ids, applyProviderOrder(ids, emptyList()))
    }

    @Test
    fun aStoredOrderReordersTheList() {
        assertEquals(listOf("c", "a", "d", "b"), applyProviderOrder(ids, listOf("c", "a", "d", "b")))
    }

    @Test
    fun idsWithoutAStoredPositionKeepTheirOrderAtTheEnd() {
        assertEquals(listOf("c", "a", "b", "d"), applyProviderOrder(ids, listOf("c", "a")))
        assertEquals(listOf("d", "a", "b", "c"), applyProviderOrder(ids, listOf("d")))
    }

    @Test
    fun storedIdsForDeletedProvidersAreIgnored() {
        assertEquals(listOf("b", "a", "c", "d"), applyProviderOrder(ids, listOf("gone", "b", "also-gone", "a")))
    }

    @Test
    fun applyingAnOrderIsIdempotent() {
        val order = listOf("d", "b", "c", "a")
        val once = applyProviderOrder(ids, order)
        assertEquals(once, applyProviderOrder(once, order))
    }

    @Test
    fun aFullStoredOrderSurvivesTheRoundTripThroughApply() {
        val order = listOf("d", "c", "b", "a")
        assertEquals(order, applyProviderOrder(ids, order))
    }
}
