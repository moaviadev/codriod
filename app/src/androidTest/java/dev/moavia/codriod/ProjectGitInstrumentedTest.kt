package dev.moavia.codriod

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.moavia.codriod.workspace.ProjectGitService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectGitInstrumentedTest {
    @Test
    fun localGitLifecycleWorksOnAndroidRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "codriod-git-${UUID.randomUUID()}")
        check(root.mkdirs())
        try {
            File(root, "README.md").writeText("# android Git test\n")
            val git = ProjectGitService()
            git.initialize(root)
            assertEquals("main", git.snapshot(root).branch)
            assertEquals(listOf("README.md"), git.snapshot(root).untracked)

            git.stage(root, "README.md")
            val first = git.commit(root, "Initial", "Codriod Android Test", "android-test@example.com")
            assertEquals(first.id, git.snapshot(root).commits.single().id)

            git.createBranch(root, "feature")
            git.checkout(root, "feature")
            File(root, "README.md").writeText("# Updated on Android\n")
            val diff = git.diff(root, "README.md", cached = false)
            assertTrue(diff.contains("+# Updated on Android"))
            assertTrue(diff.contains("-# Android Git test"))

            var dirtyCheckoutRejected = false
            try { git.checkout(root, "main") } catch (_: IllegalArgumentException) {
                dirtyCheckoutRejected = true
            }
            assertTrue(dirtyCheckoutRejected)
        } finally {
            root.deleteRecursively()
        }
    }
}
