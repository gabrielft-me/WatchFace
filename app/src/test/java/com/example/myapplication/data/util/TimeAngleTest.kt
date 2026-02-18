package com.example.myapplication.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeAngleTest {
    @Test
    fun angleForMinutes_midnight_isTop() {
        val angle = angleForMinutes(0)
        assertEquals(270f, angle, 0.01f)
    }

    @Test
    fun angleForMinutes_noon_isBottom() {
        val angle = angleForMinutes(720)
        assertEquals(90f, angle, 0.01f)
    }

    @Test
    fun isAngleWithinArc_wrapsAcrossZero() {
        val start = 300f
        val end = 60f
        assertTrue(isAngleWithinArc(350f, start, end))
        assertTrue(isAngleWithinArc(10f, start, end))
    }
}
