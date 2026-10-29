package com.timecable.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleTest {
    private val a = Item("a", "A", "a.mp4", 10_000L)
    private val b = Item("b", "B", "b.mp4", 20_000L)
    private val list = listOf(a, b) // 30 s loop anchored to the epoch

    @Test fun emptyListHasNoSlot() = assertNull(Schedule.at(emptyList(), 5_000L))

    @Test fun firstItemAtStartOfCycle() {
        val s = Schedule.at(list, 30_000L)!!
        assertEquals("a", s.item.id)
        assertEquals(30_000L, s.start)
        assertEquals(40_000L, s.end)
    }

    @Test fun secondItemMidCycle() {
        val s = Schedule.at(list, 45_000L)!!
        assertEquals("b", s.item.id)
        assertEquals(40_000L, s.start)
        assertEquals(60_000L, s.end)
    }

    @Test fun sameClockGivesSameProgram() =
        assertEquals(Schedule.at(list, 1_234_567L), Schedule.at(list, 1_234_567L))

    @Test fun betweenCoversWindowWithoutGaps() {
        val slots = Schedule.between(list, 35_000L, 95_000L)
        assertEquals(listOf("a", "b", "a", "b", "a"), slots.map { it.item.id })
        slots.zipWithNext().forEach { (x, y) -> assertEquals(x.end, y.start) }
    }
}
