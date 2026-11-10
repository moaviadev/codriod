package dev.moavia.codriod.workspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Handles Storage Access Framework (SAF) operations for external folders selected by the user.
 * Allows browsing, reading, writing, creating, renaming, and deleting files across device storage.
 */
class SafWorkspaceHelper(private val context: Context) {

    fun hasPermission(uriString: String): Boolean {
        return runCatching {
            val uri = Uri.parse(uriString)
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && (it.isReadPermission || it.isWritePermission)
            }
        }.getOrDefault(false)
    }

    fun getRoot(uriString: String): DocumentFile? {
        return runCatching {
            val uri = Uri.parse(uriString)
            val doc = DocumentFile.fromTreeUri(context, uri) ?: return null
            if (doc.exists() && doc.canRead()) doc else null
        }.getOrNull()
    }

    fun entries(uriString: String): List<ProjectEntry> {
        val root = getRoot(uriString)
            ?: throw IOException("Folder is inaccessible or permission was revoked. Please select Open Folder to re-authorize.")
        val result = mutableListOf<ProjectEntry>()

        fun visit(folder: DocumentFile, parentPath: String, depth: Int) {
            if (depth > 20 || result.size >= 1500) return
            val children = folder.listFiles()
            val sorted = children.sortedWith(
                compareBy<DocumentFile> { !it.isDirectory }
                    .thenBy { it.name.orEmpty().lowercase() }
            )
            for (file in sorted) {
                val name = file.name ?: continue
                if (name == ".git" || name.startsWith(".codriod-") || result.size >= 1500) continue
                val relPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                result.add(ProjectEntry(relPath, file.isDirectory))
                if (file.isDirectory) {
                    visit(file, relPath, depth + 1)
                }
            }
        }

        visit(root, "", 0)
        return result
    }

    fun findDoc(root: DocumentFile, path: String): DocumentFile? {
        val clean = path.trim().removePrefix("/").removeSuffix("/")
        if (clean.isEmpty()) return root
        val segments = clean.split('/').filter { it.isNotEmpty() }
        var current: DocumentFile = root
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    fun getOrCreateParent(root: DocumentFile, path: String): DocumentFile? {
        val clean = path.trim().removePrefix("/").removeSuffix("/")
        val segments = clean.split('/').filter { it.isNotEmpty() }
        if (segments.size <= 1) return root
        var current: DocumentFile = root
        for (i in 0 until segments.size - 1) {
            val segment = segments[i]
            var child = current.findFile(segment)
            if (child == null) {
                child = current.createDirectory(segment) ?: return null
            }
            current = child
        }
        return current
    }

    fun read(uriString: String, path: String): String {
        val root = getRoot(uriString)
            ?: throw IOException("Storage is inaccessible. Please check permissions.")
        val doc = findDoc(root, path) ?: throw FileNotFoundException("File not found: $path")
        require(doc.length() <= ProjectStore.MAX_TEXT_BYTES) { "Editor supports text files up to 256 KB" }
        val bytes = context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot read file: $path")
        require(bytes.size <= ProjectStore.MAX_TEXT_BYTES && bytes.none { it == 0.toByte() }) {
            "Not a supported text file"
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun write(uriString: String, path: String, text: String) {
        val root = getRoot(uriString)
            ?: throw IOException("Storage is inaccessible. Please check permissions.")
        val clean = path.trim().removePrefix("/").removeSuffix("/")
        val segments = clean.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "Invalid file path" }
        val fileName = segments.last()
        val parent = getOrCreateParent(root, clean)
            ?: throw IOException("Parent folder not found for: $path")
        var doc = parent.findFile(fileName)
        if (doc == null) {
            doc = parent.createFile(mimeTypeFor(fileName), fileName)
                ?: throw IOException("Cannot create file: $path")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: throw IOException("Cannot write to file: $path")
    }

    fun createEntry(uriString: String, path: String, isFolder: Boolean) {
        val root = getRoot(uriString)
            ?: throw IOException("Storage is inaccessible. Please check permissions.")
        val clean = path.trim().removePrefix("/").removeSuffix("/")
        val segments = clean.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "Name cannot be empty" }
        val name = segments.last()
        val parent = getOrCreateParent(root, clean)
            ?: throw IOException("Parent directory not found")
        require(parent.findFile(name) == null) { "A file or folder already uses this name" }
        if (isFolder) {
            val created = parent.createDirectory(name)
            check(created != null) { "Could not create folder" }
        } else {
            val created = parent.createFile(mimeTypeFor(name), name)
            check(created != null) { "Could not create file" }
        }
    }

    fun rename(uriString: String, oldPath: String, newPath: String) {
        val root = getRoot(uriString)
            ?: throw IOException("Storage is inaccessible. Please check permissions.")
        val doc = findDoc(root, oldPath) ?: throw FileNotFoundException("Source missing: $oldPath")
        val newName = newPath.trim().removePrefix("/").removeSuffix("/").substringAfterLast('/')
        check(doc.renameTo(newName)) { "Could not rename entry to $newName" }
    }

    fun delete(uriString: String, path: String) {
        val root = getRoot(uriString)
            ?: throw IOException("Storage is inaccessible. Please check permissions.")
        val doc = findDoc(root, path) ?: throw FileNotFoundException("Entry not found: $path")
        check(doc.delete()) { "Could not delete entry" }
    }

    fun findLocalFile(treeUri: String): File? {
        return runCatching {
            val uri = Uri.parse(treeUri)
            if (uri.scheme == "file") return@runCatching uri.path?.let { File(it) }
            val docId = DocumentsContract.getTreeDocumentId(uri) ?: return@runCatching null
            if (docId.startsWith("primary:")) {
                val rel = docId.removePrefix("primary:")
                val file = File("/storage/emulated/0", rel)
                if (file.exists() && file.isDirectory) return@runCatching file
            }
            val parts = docId.split(':')
            if (parts.size == 2) {
                val file = File("/storage/emulated/0", parts[1])
                if (file.exists() && file.isDirectory) return@runCatching file
            }
            null
        }.getOrNull()
    }

    private fun mimeTypeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "sh" -> "application/x-sh"
            "xml" -> "text/xml"
            "kt" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "py" -> "text/x-python"
            else -> "text/plain"
        }
    }
}
