package dev.moavia.codriod.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFilesTest {
    private val entries = listOf(
        ProjectEntry("a", directory = true),
        ProjectEntry("a/b", directory = true),
        ProjectEntry("a/b/c.txt", directory = false),
        ProjectEntry("a/d.txt", directory = false),
        ProjectEntry("root.txt", directory = false)
    )

    @Test
    fun parentDirectoryFindsTheParentOfRelativePaths() {
        assertEquals("", parentDirectory("a"))
        assertEquals("a", parentDirectory("a/b"))
        assertEquals("a/b", parentDirectory("a/b/c.txt"))
    }

    @Test
    fun rootKeepsTheExistingFullyExpandedTree() {
        assertEquals(entries, visibleEntries(entries, "", emptySet()))
    }

    @Test
    fun collapsedFolderHidesItsDescendants() {
        val shown = visibleEntries(entries, "", setOf("a"))
        assertEquals(listOf("a", "root.txt"), shown.map { it.path })
    }

    @Test
    fun currentFolderOnlyShowsItsOwnSubtree() {
        val shown = visibleEntries(entries, "a", emptySet())
        assertEquals(listOf("a/b", "a/b/c.txt", "a/d.txt"), shown.map { it.path })
    }

    @Test
    fun enteringAFolderShowsOnlyItsDirectContents() {
        val collapsed = collapsedAfterNavigation(entries, "a", emptySet())
        assertEquals(setOf("a/b"), collapsed)
        assertEquals(listOf("a/b", "a/d.txt"), visibleEntries(entries, "a", collapsed).map { it.path })
    }
}
