package dev.moavia.codriod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionLifecycleTest {
    @Test
    fun lifecycleStartsResizesAndTerminates() {
        val lifecycle = SessionLifecycle()
        lifecycle.start()
        lifecycle.resize(80, 24)
        assertEquals(80, lifecycle.lastColumns)
        assertEquals(24, lifecycle.lastRows)
        lifecycle.terminate()
        assertEquals(SessionLifecycle.State.TERMINATED, lifecycle.state)
    }

    @Test
    fun finishedSessionCanFinishOnce() {
        val lifecycle = SessionLifecycle()
        lifecycle.start()
        lifecycle.finish()
        assertEquals(SessionLifecycle.State.FINISHED, lifecycle.state)
    }

    @Test
    fun resizeRejectsInvalidDimensions() {
        val lifecycle = SessionLifecycle()
        lifecycle.start()
        assertThrows(IllegalArgumentException::class.java) {
            lifecycle.resize(0, 24)
        }
    }

    @Test
    fun terminateIsIdempotent() {
        val lifecycle = SessionLifecycle()
        lifecycle.start()
        lifecycle.terminate()
        lifecycle.terminate()
        assertEquals(SessionLifecycle.State.TERMINATED, lifecycle.state)
    }
}
