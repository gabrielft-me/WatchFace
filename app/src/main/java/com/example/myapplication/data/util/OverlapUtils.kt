package com.example.myapplication.data.util

import kotlin.math.abs

/** Greedy layering for overlapping intervals. */
fun assignLayers(intervals: List<IntervalMinutes>): List<LayeredInterval> {
    val layerEnds = mutableListOf<Int>()
    val result = ArrayList<LayeredInterval>(intervals.size)
    for (interval in intervals.sortedBy { it.startMinutes }) {
        val index = layerEnds.indexOfFirst { interval.startMinutes >= it }
        val layer = if (index == -1) {
            layerEnds.add(interval.endMinutes)
            layerEnds.lastIndex
        } else {
            layerEnds[index] = interval.endMinutes
            index
        }
        result.add(LayeredInterval(interval, layer))
    }
    return result
}

fun backToBackMarkers(intervals: List<IntervalMinutes>, toleranceMinutes: Int): List<Int> {
    if (intervals.size < 2) return emptyList()
    val markers = mutableListOf<Int>()
    val sorted = intervals.sortedBy { it.startMinutes }
    for (i in 0 until sorted.lastIndex) {
        val current = sorted[i]
        val next = sorted[i + 1]
        if (abs(next.startMinutes - current.endMinutes) <= toleranceMinutes) {
            markers.add(next.startMinutes)
        }
    }
    return markers
}

data class IntervalMinutes(
    val id: String,
    val startMinutes: Int,
    val endMinutes: Int,
)

data class LayeredInterval(
    val interval: IntervalMinutes,
    val layerIndex: Int,
)
