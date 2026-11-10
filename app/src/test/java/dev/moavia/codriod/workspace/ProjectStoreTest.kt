package dev.moavia.codriod.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private fun store() = ProjectStore(temporaryFolder.newFolder("workspaces"))

    @Test
    fun webProjectStartsWithEditableAndRunnableFiles() {
        val store = store()
        val project = store.createProject("hello-web", "Web")
        assertEquals("hello-web", store.projects().single().name)
        assertTrue(store.read(project.id, "index.html").contains("<h1>Hello, Codriod.</h1>"))
        assertTrue(store.read(project.id, "style.css").contains("#007acc"))
        assertTrue(store.read(project.id, "app.js").contains("Page loaded"))
        assertTrue(store.entries(project.id).any { it.path == "README.md" && !it.directory })
    }

    @Test
    fun editedFilesCanBeSavedReopenedRenamedAndSearched() {
        val store = store()
        val project = store.createProject("my-shell", "Shell")
        store.createEntry(project.id, "src", folder = true)
        store.createEntry(project.id, "src/hello.sh", folder = false)
        store.write(project.id, "src/hello.sh", "printf 'hello\\n'\n")
        store.write(project.id, "src/hello.sh", "printf 'goodbye\\n'\n")
        assertEquals("printf 'goodbye\\n'\n", store.read(project.id, "src/hello.sh"))
        assertEquals(listOf(1), store.search(project.id, "goodbye").map { it.line })
        store.rename(project.id, "src/hello.sh", "src/run.sh")
        assertEquals("printf 'goodbye\\n'\n", store.read(project.id, "src/run.sh"))
    }

    @Test
    fun rejectsPathTraversalProtectedMetadataAndNonEmptyFolderDeletion() {
        val store = store()
        val project = store.createProject("safe", "Empty")
        assertThrows(IllegalArgumentException::class.java) { store.resolve(project.id, "../outside") }
        assertThrows(IllegalArgumentException::class.java) { store.resolve(project.id, ".codriod-project.properties") }
        assertThrows(IllegalArgumentException::class.java) { store.resolve(project.id, ".git/config") }
        assertThrows(IllegalArgumentException::class.java) { store.resolve(project.id, "src/.git/config") }
        store.createEntry(project.id, "src", folder = true)
        store.createEntry(project.id, "src/file.txt", folder = false)
        assertThrows(IllegalArgumentException::class.java) { store.delete(project.id, "src") }
        store.delete(project.id, "src/file.txt")
        store.delete(project.id, "src")
        assertFalse(store.entries(project.id).any { it.path.startsWith("src") })
    }

    @Test
    fun rejectsBinaryAndOversizedEditorFiles() {
        val store = store()
        val project = store.createProject("limited", "Empty")
        store.createEntry(project.id, "image.bin", folder = false)
        store.write(project.id, "image.bin", "text")
        store.write(project.id, "image.bin", "\u0000binary")
        assertThrows(IllegalArgumentException::class.java) { store.read(project.id, "image.bin") }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(project.id, "README.md", "x".repeat(ProjectStore.MAX_TEXT_BYTES + 1))
        }
    }

    @Test
    fun openTreeFolderPersistsMetadataAndListsWorkspace() {
        val store = store()
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AProjects%2FMyApp"
        val proj = store.openTreeFolder("MyApp", treeUri)
        assertEquals("MyApp", proj.name)
        assertEquals(treeUri, proj.treeUri)
        assertEquals("Folder", proj.template)

        val listed = store.projects().single()
        assertEquals("MyApp", listed.name)
        assertEquals(treeUri, listed.treeUri)
        assertEquals(proj.id, listed.id)
    }
}
