package com.timecable.app

data class Slot(val item: Item, val start: Long, val end: Long)

/**
 * Deterministic "live" schedule: a channel is a looping playlist anchored to the Unix epoch,
 * so the program and offset at any instant depend only on (items, time). No server needed.
 */
object Schedule {
    fun at(items: List<Item>, now: Long): Slot? {
        val cycle = items.sumOf { it.durMs }
        if (cycle <= 0L) return null
        val t = Math.floorMod(now, cycle)
        var acc = 0L
        for (i in items) {
            if (t < acc + i.durMs) {
                val s = now - (t - acc)
                return Slot(i, s, s + i.durMs)
            }
            acc += i.durMs
        }
        return null
    }

    fun between(items: List<Item>, from: Long, to: Long): List<Slot> {
        val out = mutableListOf<Slot>()
        var s = at(items, from) ?: return out
        out.add(s)
        while (s.end < to) {
            s = at(items, s.end) ?: break
            out.add(s)
        }
        return out
    }
}
