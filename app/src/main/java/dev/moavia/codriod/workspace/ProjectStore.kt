package dev.moavia.codriod.workspace

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Properties
import java.util.UUID

data class Project(
    val id: String,
    val name: String,
    val template: String,
    val modified: Long,
    val treeUri: String? = null,
    /** Folder in the phone's storage holding the files (null = Codriod's private storage). */
    val path: String? = null
)
data class ProjectEntry(val path: String, val directory: Boolean)
data class SearchHit(val path: String, val line: Int, val text: String)

/**
 * Projects: their settings live in Codriod's private storage; their files live in the phone's storage
 * (Internal storage/Codriod/<name>) when Codriod has storage access, else in private storage. Opened folders are edited
 * in place, directly when Codriod has storage access, or through Android's document picker (SAF) otherwise.
 * All editor, preview, terminal and agent access passes through the same boundary.
 */
class ProjectStore(
    private val directory: File,
    private val context: Context? = null,
    /** True when Codriod may use the phone's storage directly. */
    private val sharedAccess: () -> Boolean = { false },
    /** Where new projects go when [sharedAccess] is true. */
    private val sharedHome: () -> File? = { null }
) {
    companion object {
        const val MAX_TEXT_BYTES = 256 * 1024
        private const val META = ".codriod-project.properties"
    }

    private val saf = context?.let { SafWorkspaceHelper(it) }

    init { check(directory.isDirectory || directory.mkdirs()) { "Cannot create project storage" } }

    private fun meta(id: String): Properties? {
        val metaFile = File(File(directory, id), META)
        return if (metaFile.exists()) runCatching { Properties().apply { metaFile.inputStream().use { load(it) } } }.getOrNull() else null
    }

    /** The project's files as a real folder: its storage path, or an opened folder's path when Codriod has storage access. */
    private fun localRoot(id: String): File? {
        val props = meta(id) ?: return null
        props.getProperty("path")?.let { return File(it) }
        val uri = props.getProperty("treeUri") ?: return null
        if (!sharedAccess() || saf == null) return null
        return saf.findLocalFile(uri)?.takeIf { it.isDirectory && it.canWrite() }
    }

    /** True when the files must be reached through Android's document provider (no direct path). */
    private fun usesSaf(id: String) = treeUri(id) != null && saf != null && localRoot(id) == null

    /** Where the project's files are, for people: "Internal storage/Codriod/My App" or "Codriod (private)". */
    fun location(id: String): String = localRoot(id)?.let { StorageAccess.displayPath(it) }
        ?: if (treeUri(id) != null) "Opened folder (document access)" else "Codriod private storage"

    fun treeUri(id: String): String? {
        val metaFile = File(File(directory, id), META)
        return if (metaFile.exists()) {
            runCatching {
                Properties().apply { metaFile.inputStream().use { load(it) } }.getProperty("treeUri")
            }.getOrNull()
        } else null
    }

    @Synchronized
    fun projects(): List<Project> = directory.listFiles().orEmpty().filter { it.isDirectory }
        .mapNotNull { folder ->
            runCatching {
                val props = Properties().apply { File(folder, META).inputStream().use { load(it) } }
                Project(
                    id = folder.name,
                    name = props.getProperty("name"),
                    template = props.getProperty("template", "Files"),
                    modified = props.getProperty("opened", "0").toLong(),
                    treeUri = props.getProperty("treeUri"),
                    path = props.getProperty("path")
                )
            }.getOrNull()
        }.sortedByDescending { it.modified }

    fun root(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid project ID" }
        val root = File(directory, id).canonicalFile
        require(root.parentFile == directory.canonicalFile && root.isDirectory) { "Project not found" }
        localRoot(id)?.let { local ->
            if (!local.isDirectory) throw IOException("The project folder is missing: ${StorageAccess.displayPath(local)}" +
                if (!sharedAccess()) ". Allow storage access for Codriod." else "")
            return local.canonicalFile
        }
        val uri = treeUri(id)
        if (uri != null && saf != null) {
            val local = saf.findLocalFile(uri)
            if (local != null) return local
        }
        return root
    }

    fun resolve(id: String, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute &&
            path.split('/', '\\').none { it == ".." || it == ".git" || it.startsWith(".codriod-") } &&
            path.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid file path" }
        val root = root(id)
        val result = File(root, path).canonicalFile
        require(result.path.startsWith(root.path + File.separator)) { "Path is outside this project" }
        return result
    }

    @Synchronized
    fun createProject(name: String, template: String): Project {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 64 && title.none { it.isISOControl() }) {
            "Use a project name between 1 and 64 characters"
        }
        require(template in listOf("Web", "Shell", "Empty")) { "Unknown template" }
        val id = UUID.randomUUID().toString()
        val folder = File(directory, id)
        check(folder.mkdir()) { "Cannot create project" }
        val shared = newSharedFolder(title)
        try {
            val project = Project(id, title, template, System.currentTimeMillis(), path = shared?.absolutePath)
            saveMetadata(project)
            when (template) {
                "Web" -> {
                    write(id, "index.html", """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>My Codriod project</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main>
    <span class="eyebrow">BUILT ON MY PHONE</span>
    <h1>Hello, Codriod.</h1>
    <p>Your next idea starts right here.</p>
    <button id="hello">Get started</button>
    <p id="message" aria-live="polite"></p>
  </main>
  <script src="app.js"></script>
</body>
</html>
""")
                    write(id, "style.css", """body { margin: 0; background: #f5f8fc; color: #202020; font: 16px system-ui; }
main { max-width: 680px; margin: 12vh auto; padding: 32px; }
.eyebrow { color: #007acc; font-size: 12px; letter-spacing: .15em; }
h1 { font-size: clamp(32px, 8vw, 64px); letter-spacing: -.04em; }
p { line-height: 1.7; }
button { background: #007acc; color: white; border: 0; border-radius: 6px; padding: 14px 22px; font: inherit; }
""")
                    write(id, "app.js", """document.querySelector('#hello').addEventListener('click', () => {
  document.querySelector('#message').textContent = 'Your preview is working.';
  console.log('Button clicked');
});
console.log('Page loaded');
""")
                }
                "Shell" -> write(id, "main.sh", """#!/system/bin/sh
printf 'Hello from Codriod!\n'
printf 'Working directory: '
pwd
printf '\nProject files:\n'
ls -la
""")
            }
            write(id, "README.md", "# $title\n\nCreated on this phone with Codriod.\n")
            return project
        } catch (error: Exception) {
            folder.deleteRecursively()
            shared?.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun openTreeFolder(name: String, treeUri: String): Project {
        val cleanName = name.trim().ifEmpty { "Opened Folder" }.take(64)
        val existing = projects().firstOrNull { it.treeUri == treeUri }
        if (existing != null) {
            val updated = existing.copy(name = cleanName, modified = System.currentTimeMillis())
            saveMetadata(updated)
            return updated
        }
        val id = "saf-" + UUID.randomUUID().toString()
        val folder = File(directory, id)
        check(folder.mkdir()) { "Cannot create workspace metadata folder" }
        val project = Project(id, cleanName, "Folder", System.currentTimeMillis(), treeUri)
        saveMetadata(project)
        return project
    }

    /** Creates a project whose files come from [populate], e.g. a Git clone into the empty folder. */
    @Synchronized
    fun createFromClone(name: String, populate: (File) -> Unit): Project {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 64 && title.none { it.isISOControl() }) {
            "Use a project name between 1 and 64 characters"
        }
        val id = UUID.randomUUID().toString()
        val folder = File(directory, id)
        check(folder.mkdir()) { "Cannot create project" }
        val shared = newSharedFolder(title)
        try {
            populate(shared ?: folder)
            val project = Project(id, title, "Git", System.currentTimeMillis(), path = shared?.absolutePath)
            saveMetadata(project)
            return project
        } catch (error: Throwable) {
            folder.deleteRecursively()
            shared?.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun renameProject(project: Project, name: String): Project {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 64 && title.none { it.isISOControl() }) {
            "Use a project name between 1 and 64 characters"
        }
        return project.copy(name = title).also(::saveMetadata)
    }

    /**
     * Deletes a project. Projects Codriod created or cloned are deleted with their files; for an opened device folder
     * only Codriod's link is removed and the folder itself stays.
     */
    @Synchronized
    fun deleteProject(project: Project) {
        require(project.id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid project ID" }
        val folder = File(directory, project.id).canonicalFile
        require(folder.parentFile == directory.canonicalFile && folder.isDirectory) { "Project not found" }
        val path = meta(project.id)?.getProperty("path")
        if (path != null && project.treeUri == null) {
            val files = File(path)
            val home = sharedHome()
            // Only folders inside Internal storage/Codriod are deleted with the project.
            if (home != null && files.canonicalPath.startsWith(home.canonicalPath + File.separator)) files.deleteRecursively()
        }
        check(folder.deleteRecursively()) { "Could not delete the project" }
    }

    /** A new, empty folder in Internal storage/Codriod named after the project, or null without storage access. */
    private fun newSharedFolder(title: String): File? {
        if (!sharedAccess()) return null
        val home = sharedHome() ?: return null
        if (!home.isDirectory && !home.mkdirs()) return null
        val base = title.replace(Regex("[^\\p{L}\\p{N} ._()-]+"), "-").trim(' ', '.', '-').ifBlank { "Project" }.take(60)
        var candidate = File(home, base)
        var n = 2
        while (candidate.exists()) candidate = File(home, "$base ($n)").also { n++ }
        return candidate.takeIf { it.mkdirs() }
    }

    /** Private projects that can move to Internal storage/Codriod. */
    fun privateProjects(): List<Project> = projects().filter { it.path == null && it.treeUri == null }

    /** Moves a private project's files to Internal storage/Codriod/<name>. */
    @Synchronized
    fun moveToShared(project: Project): Project {
        val target = newSharedFolder(project.name) ?: throw IOException("Allow storage access for Codriod first")
        val source = File(directory, project.id)
        try {
            source.listFiles().orEmpty().filter { it.name != META }.forEach { child ->
                child.copyRecursively(File(target, child.name), overwrite = true)
            }
        } catch (error: Exception) {
            target.deleteRecursively(); throw IOException("Could not copy ${project.name}: ${error.message}")
        }
        val moved = project.copy(path = target.absolutePath)
        saveMetadata(moved)
        source.listFiles().orEmpty().filter { it.name != META }.forEach { it.deleteRecursively() }
        return moved
    }

    @Synchronized
    fun touch(project: Project) = project.copy(modified = System.currentTimeMillis()).also(::saveMetadata)

    private fun saveMetadata(project: Project) {
        val props = Properties().apply {
            setProperty("name", project.name)
            setProperty("template", project.template)
            setProperty("opened", project.modified.toString())
            project.treeUri?.let { setProperty("treeUri", it) }
            project.path?.let { setProperty("path", it) }
        }
        val dir = File(directory, project.id)
        if (!dir.exists()) dir.mkdirs()
        File(dir, META).outputStream().use { props.store(it, "Codriod project") }
    }

    fun entries(id: String): List<ProjectEntry> {
        if (usesSaf(id)) return saf!!.entries(treeUri(id)!!)
        val root = root(id)
        val entries = mutableListOf<ProjectEntry>()
        fun visit(folder: File, depth: Int) {
            if (depth > 20 || entries.size >= 1500) return
            folder.listFiles().orEmpty().sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .forEach { file ->
                    if (file.name == ".git" || file.name.startsWith(".codriod-") || entries.size >= 1500) return@forEach
                    val path = file.relativeTo(root).path.replace(File.separatorChar, '/')
                    val safe = runCatching { resolve(id, path) }.getOrNull() ?: return@forEach
                    // Do not follow symlinks, including links back into this project.
                    if (safe.absolutePath != file.absolutePath) return@forEach
                    entries.add(ProjectEntry(path, file.isDirectory))
                    if (file.isDirectory) visit(file, depth + 1)
                }
        }
        visit(root, 0)
        return entries
    }

    fun read(id: String, path: String): String {
        if (usesSaf(id)) return saf!!.read(treeUri(id)!!, path)
        val file = resolve(id, path)
        require(file.isFile) { "File not found" }
        require(file.length() <= MAX_TEXT_BYTES) { "Editor supports text files up to 256 KB" }
        val bytes = file.inputStream().use { it.readBytes() }
        require(bytes.size <= MAX_TEXT_BYTES && bytes.none { it == 0.toByte() }) { "Not a supported text file" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    /** Raw bytes of a file (images and other binary files), up to [max] bytes. */
    fun readBytes(id: String, path: String, max: Int = 40 * 1024 * 1024): ByteArray {
        if (usesSaf(id)) return saf!!.readBytes(treeUri(id)!!, path, max)
        val file = resolve(id, path)
        require(file.isFile) { "File not found" }
        require(file.length() <= max) { "The file is too large to open (${file.length() / 1_048_576} MB)" }
        return file.readBytes()
    }

    /** A real file path for a project file when there is one (for sharing or attaching to the agent). */
    fun fileOrNull(id: String, path: String): File? = if (usesSaf(id)) null else runCatching { resolve(id, path) }.getOrNull()

    @Synchronized
    fun write(id: String, path: String, text: String) {
        if (usesSaf(id)) { saf!!.write(treeUri(id)!!, path, text); return }
        val file = resolve(id, path)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Editor supports text files up to 256 KB" }
        require(file.parentFile?.isDirectory == true) { "Parent folder not found" }
        val temp = File(file.parentFile, ".codriod-write-" + UUID.randomUUID())
        try {
            temp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!temp.renameTo(file)) throw IOException("Could not save $path; previous file preserved")
        } finally { temp.delete() }
    }

    @Synchronized
    fun createEntry(id: String, path: String, folder: Boolean) {
        if (usesSaf(id)) { saf!!.createEntry(treeUri(id)!!, path, folder); return }
        val target = resolve(id, path)
        require(!target.exists()) { "A file or folder already uses this name" }
        require(target.parentFile?.isDirectory == true) { "Parent folder not found" }
        check(if (folder) target.mkdir() else target.createNewFile()) { "Could not create entry" }
    }

    @Synchronized
    fun rename(id: String, old: String, new: String) {
        if (usesSaf(id)) { saf!!.rename(treeUri(id)!!, old, new); return }
        val source = resolve(id, old)
        val target = resolve(id, new)
        require(source.exists() && !target.exists()) { "Source missing or destination already exists" }
        require(target.parentFile?.isDirectory == true) { "Destination folder not found" }
        check(source.renameTo(target)) { "Could not rename entry" }
    }

    @Synchronized
    fun delete(id: String, path: String) {
        if (usesSaf(id)) { saf!!.delete(treeUri(id)!!, path); return }
        val file = resolve(id, path)
        require(file.isFile || (file.isDirectory && file.listFiles().orEmpty().isEmpty())) {
            "Only files and empty folders can be deleted"
        }
        check(file.delete()) { "Could not delete entry" }
    }

    fun search(id: String, query: String): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        for (entry in entries(id).filterNot { it.directory }) {
            val text = runCatching { read(id, entry.path) }.getOrNull() ?: continue
            text.lineSequence().forEachIndexed { index, line ->
                if (hits.size < 100 && line.contains(query, ignoreCase = true)) {
                    hits.add(SearchHit(entry.path, index + 1, line.take(180)))
                }
            }
            if (hits.size >= 100) break
        }
        return hits
    }
}
