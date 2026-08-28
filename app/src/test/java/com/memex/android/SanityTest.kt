package com.memex.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SanityTest {

    @Test
    fun testEnvironmentSanity() {
        val packageName = "com.memex.android"
        assertEquals("com.memex.android", packageName)
        assertTrue(true, "Sanity test environment should be functional")
    }

    @Test
    fun testBasicMath() {
        val sum = 2 + 2
        assertEquals(4, sum)
    }
}
