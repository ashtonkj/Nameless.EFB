package com.nameless.efb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Minimal JVM unit test — ensures the test harness runs. */
class SanityTest {

    @Test
    fun `app name constant is correct`() {
        assertEquals("Nameless EFB", APP_NAME)
    }

    companion object {
        const val APP_NAME = "Nameless EFB"
    }
}
