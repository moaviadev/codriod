package dev.moavia.codriod.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectManageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun renameAndDeleteProjects() {
        val workspaces = temp.newFolder("workspaces")
        val store = ProjectStore(workspaces)
        val project = store.createProject("first", "Web")
        val renamed = store.renameProject(project, "  Portfolio site ")
        assertEquals("Portfolio site", store.projects().single().name)
        assertThrows(IllegalArgumentException::class.java) { store.renameProject(renamed, "   ") }
        store.deleteProject(renamed)
        assertTrue(store.projects().isEmpty())
        assertFalse(File(workspaces, project.id).exists())
    }

    @Test fun deletingAnOpenedFolderOnlyForgetsIt() {
        val workspaces = temp.newFolder("workspaces")
        val userFolder = temp.newFolder("my-phone-folder").apply { File(this, "keep.txt").writeText("mine") }
        val store = ProjectStore(workspaces)
        val linked = store.openTreeFolder("Phone folder", "content://tree/" + userFolder.name)
        store.deleteProject(linked)
        assertTrue(store.projects().isEmpty())
        assertEquals("mine", File(userFolder, "keep.txt").readText())
    }

    @Test fun cloneCreatesAProjectOnlyWhenPopulateSucceeds() {
        val workspaces = temp.newFolder("workspaces")
        val store = ProjectStore(workspaces)
        assertThrows(IllegalStateException::class.java) { store.createFromClone("bad") { error("network down") } }
        assertTrue(store.projects().isEmpty())
        assertEquals(0, workspaces.listFiles()!!.size)
        val ok = store.createFromClone("repo") { File(it, "README.md").writeText("hi") }
        assertEquals("Git", ok.template)
        assertEquals("hi", store.read(ok.id, "README.md"))
    }
}
