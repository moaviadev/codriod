package dev.moavia.codriod.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Projects in the phone's storage (Internal storage/Codriod) when Codriod has storage access. */
class ProjectStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun newProjectsGoToPhoneStorageWithReadableNames() {
        val home = File(tmp.root, "sdcard/Codriod")
        var access = true
        val store = ProjectStore(tmp.newFolder("private"), null, { access }, { home })
        val p = store.createProject("My App", "Web")
        val folder = File(home, "My App")
        assertEquals(folder.absolutePath, p.path)
        assertTrue(File(folder, "index.html").isFile)
        assertEquals(folder.canonicalFile, store.root(p.id))
        assertFalse("settings stay private", File(folder, ".codriod-project.properties").exists())
        val second = store.createProject("My App", "Empty")
        assertEquals(File(home, "My App (2)").absolutePath, second.path)
        store.write(p.id, "notes.txt", "hello")
        assertEquals("hello", File(folder, "notes.txt").readText())
        assertArrayEquals("hello".toByteArray(), store.readBytes(p.id, "notes.txt"))
        assertEquals(File(folder, "notes.txt").absolutePath, store.fileOrNull(p.id, "notes.txt")!!.absolutePath)
        // Weird names are made safe
        assertTrue(store.createProject("a/b:c*", "Empty").path!!.endsWith("a-b-c"))
        // Without access new projects stay private
        access = false
        assertNull(store.createProject("Private one", "Empty").path)
        // Deleting a created project deletes its folder in Internal storage/Codriod
        store.deleteProject(store.projects().first { it.id == p.id })
        assertFalse(folder.exists())
    }

    @Test fun olderPrivateProjectsMoveToPhoneStorage() {
        val home = File(tmp.root, "sdcard/Codriod")
        var access = false
        val store = ProjectStore(tmp.newFolder("private"), null, { access }, { home })
        val old = store.createProject("Old", "Web")
        store.createEntry(old.id, "src", folder = true)
        store.write(old.id, "src/a.js", "x")
        assertNull(old.path)
        assertEquals(1, store.privateProjects().size)
        access = true
        val moved = store.moveToShared(store.projects().single())
        assertEquals(File(home, "Old").absolutePath, moved.path)
        assertEquals("x", File(home, "Old/src/a.js").readText())
        assertTrue(store.read(moved.id, "index.html").contains("Hello, Codriod"))
        assertEquals(0, store.privateProjects().size)
        assertEquals(moved.path, store.projects().single().path)
    }

    @Test fun clonedProjectsGoToPhoneStorage() {
        val home = File(tmp.root, "sdcard/Codriod")
        val store = ProjectStore(tmp.newFolder("private"), null, { true }, { home })
        val p = store.createFromClone("repo") { dir -> File(dir, "README.md").writeText("# repo") }
        assertEquals("# repo", File(home, "repo/README.md").readText())
        assertEquals("# repo", store.read(p.id, "README.md"))
    }
}
