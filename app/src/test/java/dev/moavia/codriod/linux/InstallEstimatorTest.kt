package dev.moavia.codriod.linux

import org.junit.Assert.*
import org.junit.Test

class InstallEstimatorTest {
    private var now = 0L
    private fun estimator() = InstallEstimator { now }

    @Test fun progressAndTimeLeftMoveForward() {
        val e = estimator()
        e.plan(listOf("check", "download", "extract", "configure", "test", "base", "node", "cli:claude"))
        val total = e.secondsLeft()
        assertTrue("whole install is several minutes: $total", total in 300..900)
        e.start("check"); now += 2000; e.finish("check")
        e.start("download")
        e.measuredSpeed(4.0 * 1024 * 1024) // fast Wi-Fi
        e.report("download", 0.5f); now += 5000
        val mid = e.secondsLeft()
        assertTrue(mid < total)
        assertTrue(e.overall() in 0.01f..0.2f)
        e.finish("download"); e.skip("extract")
        assertTrue(e.secondsLeft() < mid)
        // apt: real progress from its status lines
        e.start("base")
        val (f, msg) = InstallEstimator.parseLine("pmstatus:git:50.0:Unpacking git (arm64)")!!
        e.report("base", f)
        assertEquals("Unpacking git (arm64)", msg)
        assertEquals(0.75f, e.stepFraction(), 0.16f)
        e.report("base", 0.2f) // never goes back
        assertTrue(e.stepFraction() >= 0.74f)
    }

    @Test fun slowInternetMakesItLonger() {
        val fast = estimator().apply { plan(listOf("base", "node")); measuredSpeed(8.0 * 1024 * 1024) }
        val slow = estimator().apply { plan(listOf("base", "node")); measuredSpeed(200.0 * 1024) }
        assertTrue(slow.secondsLeft() > fast.secondsLeft() * 2)
    }

    @Test fun timeBasedProgressWithoutReports() {
        val e = estimator(); e.plan(listOf("cli:gemini")); e.start("cli:gemini")
        now += 30_000; val a = e.stepFraction()
        now += 120_000; val b = e.stepFraction()
        assertTrue(a in 0.1f..0.5f); assertTrue(b > a); assertTrue(b < 0.96f)
        assertTrue(e.secondsLeft() >= 0)
    }

    @Test fun parsesProgressLinesAndText() {
        assertEquals(0.10f + 0.40f * 0.5f, InstallEstimator.parseLine("dlstatus:2:50.0000:Retrieving file 2 of 4")!!.first, 0.001f)
        assertEquals(0.65f, InstallEstimator.parseLine("::progress 65")!!.first, 0.001f)
        assertNull(InstallEstimator.parseLine("Get:1 http://ports.ubuntu.com noble InRelease"))
        assertTrue(InstallEstimator.isProgressLine("pmstatus:dpkg-exec:0.0:Running dpkg"))
        assertEquals("almost done", InstallEstimator.left(5))
        assertEquals("about 40 sec left", InstallEstimator.left(33))
        assertEquals("about 6 min left", InstallEstimator.left(355))
        assertEquals("3:05", InstallEstimator.clock(185))
        val e = InstallEstimator { 0 }.apply { plan(listOf("base", "node")) }
        assertTrue(e.usualRange().matches(Regex("\\d+ min–\\d+ min")))
    }
}
