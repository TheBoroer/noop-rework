package com.noop

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedSmokeTest {
    @Test
    fun moduleWired() {
        assertEquals("shared", SharedSmoke.MODULE)
    }
}
