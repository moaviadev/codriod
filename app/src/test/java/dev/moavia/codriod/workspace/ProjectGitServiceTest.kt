package dev.moavia.codriod.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.eclipse.jgit.api.Git

class ProjectGitServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun workspace(): Triple<ProjectStore, Project, ProjectGitService> {
        val store = ProjectStore(temporaryFolder.newFolder("workspaces"))
        val project = store.createProject("git-test", "Empty")
        return Triple(store, project, ProjectGitService())
    }

    @Test
    fun initializeIgnoresCodriodMetadataAndShowsProjectFiles() {
        val (store, project, git) = workspace()
        git.initialize(store.root(project.id))

        val snapshot = git.snapshot(store.root(project.id))
        assertTrue(snapshot.initialized)
        assertEquals("main", snapshot.branch)
        assertEquals(listOf("README.md"), snapshot.untracked)
        assertFalse(store.entries(project.id).any { it.path.startsWith(".git") })
        assertFalse(store.entries(project.id).any { it.path == ".codriod-project.properties" })
    }

    @Test
    fun stageUnstageAndCommitProduceHistoryAndBranch() {
        val (store, project, git) = workspace()
        val root = store.root(project.id)
        git.initialize(root)

        git.stage(root, "README.md")
        assertEquals(listOf("README.md"), git.snapshot(root).staged)
        assertTrue(git.diff(root, "README.md", cached = true).contains("+# git-test"))
        git.unstage(root, "README.md")
        assertTrue(git.snapshot(root).staged.isEmpty())

        git.stageAll(root)
        assertTrue(git.snapshot(root).staged.contains("README.md"))
        git.unstageAll(root)
        assertTrue(git.snapshot(root).staged.isEmpty())

        git.stage(root, "README.md")
        val commit = git.commit(root, "Start project", "Codriod Tester", "tester@example.com")
        val snapshot = git.snapshot(root)
        assertTrue(snapshot.hasHead)
        assertEquals("main", snapshot.branch)
        assertEquals(commit.id, snapshot.commits.single().id)
        assertEquals("Start project", snapshot.commits.single().message)

        git.createBranch(root, "feature/ui")
        assertTrue(git.snapshot(root).branches.contains("feature/ui"))
        git.checkout(root, "feature/ui")
        assertEquals("feature/ui", git.snapshot(root).branch)
    }

    @Test
    fun untrackedDiffShowsAddedLinesAndCheckoutRefusesDirtyFiles() {
        val (store, project, git) = workspace()
        val root = store.root(project.id)
        git.initialize(root)
        git.stage(root, "README.md")
        git.commit(root, "Initial", "Codriod Tester", "tester@example.com")
        git.createBranch(root, "feature")
        Git.open(root).use { opened ->
            val blob = opened.repository.resolve("HEAD:README.md")
            assertTrue(blob != null)
            assertTrue(opened.repository.open(blob).bytes.isNotEmpty())
        }

        store.write(project.id, "README.md", "# Changed\n")
        val diff = git.diff(root, "README.md", cached = false)
        assertTrue(diff.contains("-# git-test"))
        assertTrue(diff.contains("+# Changed"))

        var refused = false
        try { git.checkout(root, "feature") } catch (_: IllegalArgumentException) { refused = true }
        assertTrue(refused)
    }
}
