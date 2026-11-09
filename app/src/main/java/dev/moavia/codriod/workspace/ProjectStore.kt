package dev.moavia.codriod.workspace

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Properties
import java.util.UUID

data class Project(val id: String, val name: String, val template: String, val modified: Long)
data class ProjectEntry(val path: String, val directory: Boolean)
data class SearchHit(val path: String, val line: Int, val text: String)

/** App-private projects. All editor and preview access passes through the same boundary. */
class ProjectStore(private val directory: File) {
    companion object {
        const val MAX_TEXT_BYTES = 256 * 1024
        private const val META = ".codriod-project.properties"
    }

    init { check(directory.isDirectory || directory.mkdirs()) { "Cannot create project storage" } }

    @Synchronized
    fun projects(): List<Project> = directory.listFiles().orEmpty().filter { it.isDirectory }
        .mapNotNull { folder ->
            runCatching {
                val props = Properties().apply { File(folder, META).inputStream().use { load(it) } }
                Project(folder.name, props.getProperty("name"), props.getProperty("template", "Files"),
                    props.getProperty("opened", "0").toLong())
            }.getOrNull()
        }.sortedByDescending { it.modified }

    fun root(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid project ID" }
        val root = File(directory, id).canonicalFile
        require(root.parentFile == directory.canonicalFile && root.isDirectory) { "Project not found" }
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
        try {
            val project = Project(id, title, template, System.currentTimeMillis())
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
            throw error
        }
    }

    @Synchronized
    fun touch(project: Project) = project.copy(modified = System.currentTimeMillis()).also(::saveMetadata)

    private fun saveMetadata(project: Project) {
        val props = Properties().apply {
            setProperty("name", project.name)
            setProperty("template", project.template)
            setProperty("opened", project.modified.toString())
        }
        File(root(project.id), META).outputStream().use { props.store(it, "Codriod project") }
    }

    fun entries(id: String): List<ProjectEntry> {
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
        val file = resolve(id, path)
        require(file.isFile) { "File not found" }
        require(file.length() <= MAX_TEXT_BYTES) { "Editor supports text files up to 256 KB" }
        val bytes = file.inputStream().use { it.readBytes() }
        require(bytes.size <= MAX_TEXT_BYTES && bytes.none { it == 0.toByte() }) { "Not a supported text file" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    @Synchronized
    fun write(id: String, path: String, text: String) {
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
        val target = resolve(id, path)
        require(!target.exists()) { "A file or folder already uses this name" }
        require(target.parentFile?.isDirectory == true) { "Parent folder not found" }
        check(if (folder) target.mkdir() else target.createNewFile()) { "Could not create entry" }
    }

    @Synchronized
    fun rename(id: String, old: String, new: String) {
        val source = resolve(id, old)
        val target = resolve(id, new)
        require(source.exists() && !target.exists()) { "Source missing or destination already exists" }
        require(target.parentFile?.isDirectory == true) { "Destination folder not found" }
        check(source.renameTo(target)) { "Could not rename entry" }
    }

    @Synchronized
    fun delete(id: String, path: String) {
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
