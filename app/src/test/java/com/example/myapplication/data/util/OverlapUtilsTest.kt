package com.example.myapplication.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlapUtilsTest {
    @Test
    fun assignLayers_stacksOverlaps() {
        val intervals = listOf(
            IntervalMinutes("a", 60, 120),
            IntervalMinutes("b", 90, 150),
            IntervalMinutes("c", 150, 180),
        )
        val layers = assignLayers(intervals)
        val byId = layers.associateBy { it.interval.id }
        assertEquals(0, byId.getValue("a").layerIndex)
        assertEquals(1, byId.getValue("b").layerIndex)
        assertEquals(0, byId.getValue("c").layerIndex)
    }

    @Test
    fun backToBackMarkers_detectsTransitions() {
        val intervals = listOf(
            IntervalMinutes("a", 60, 120),
            IntervalMinutes("b", 120, 180),
            IntervalMinutes("c", 200, 210),
        )
        val markers = backToBackMarkers(intervals, toleranceMinutes = 2)
        assertEquals(listOf(120), markers)
    }
}
