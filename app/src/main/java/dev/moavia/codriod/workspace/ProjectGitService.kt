package dev.moavia.codriod.workspace

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.ByteArrayOutputStream
import java.io.File

data class GitCommit(
    val id: String,
    val message: String,
    val author: String,
    val timestamp: Long
)

data class GitSnapshot(
    val initialized: Boolean = false,
    val branch: String = "",
    val staged: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val untracked: List<String> = emptyList(),
    val branches: List<String> = emptyList(),
    val commits: List<GitCommit> = emptyList(),
    val authorName: String = "",
    val authorEmail: String = "",
    val hasHead: Boolean = false
) {
    val changedCount: Int get() = (staged + modified + untracked).distinct().size
}

/** Local Git operations for one app-private workspace. Remote transport is intentionally separate. */
class ProjectGitService {
    fun snapshot(root: File): GitSnapshot {
        val git = open(root) ?: return GitSnapshot()
        git.use { repository ->
            val status = repository.status().call()
            val staged = (status.added + status.changed + status.removed).distinct().sorted()
            val modified = (status.modified + status.missing + status.conflicting).distinct().sorted()
            val untracked = status.untracked.sorted()
            val head = repository.repository.resolve(Constants.HEAD)
            val commits = if (head == null) emptyList() else repository.log().setMaxCount(50).call().map {
                GitCommit(
                    id = it.name,
                    message = it.shortMessage,
                    author = it.authorIdent?.name.orEmpty(),
                    timestamp = it.authorIdent?.whenAsInstant?.toEpochMilli() ?: 0L
                )
            }
            val branches = repository.branchList().call()
                .map { it.name.removePrefix(Constants.R_HEADS) }.sorted()
            val config = repository.repository.config
            return GitSnapshot(
                initialized = true,
                branch = runCatching { repository.repository.branch }.getOrDefault("HEAD"),
                staged = staged,
                modified = modified,
                untracked = untracked,
                branches = branches,
                commits = commits,
                authorName = config.getString("user", null, "name").orEmpty(),
                authorEmail = config.getString("user", null, "email").orEmpty(),
                hasHead = head != null
            )
        }
    }

    fun initialize(root: File) {
        require(open(root) == null) { "Git is already initialized" }
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git -> excludeMetadata(git) }
    }

    /** Keeps Codriod's project metadata out of Git, for new and cloned repositories. */
    fun ensureMetadataExcluded(root: File) = withGit(root) { git -> excludeMetadata(git) }

    private fun excludeMetadata(git: Git) {
        val exclude = File(git.repository.directory, "info/exclude")
        check(exclude.parentFile?.isDirectory == true || exclude.parentFile?.mkdirs() == true) {
            "Could not prepare Git's local exclude file"
        }
        val current = exclude.takeIf { it.isFile }?.readText().orEmpty()
        if (".codriod-project.properties" !in current.lines()) {
            exclude.appendText("\n# Codriod app metadata\n.codriod-project.properties\n")
        }
    }

    fun stage(root: File, path: String) = withGit(root) { git ->
        validatePath(path)
        git.add().addFilepattern(path).call()
    }

    fun stageAll(root: File) = withGit(root) { git ->
        git.add().addFilepattern(".").call()
    }

    fun unstage(root: File, path: String) = withGit(root) { git ->
        validatePath(path)
        git.reset().addPath(path).call()
    }

    fun unstageAll(root: File) = withGit(root) { git ->
        val paths = git.status().call().let { it.added + it.changed + it.removed }.distinct()
        paths.forEach { git.reset().addPath(it).call() }
    }

    fun commit(root: File, message: String, authorName: String, authorEmail: String): GitCommit =
        withGit(root) { git ->
            val summary = message.trim()
            val name = authorName.trim()
            val email = authorEmail.trim()
            require(summary.isNotEmpty() && summary.length <= 200) { "Enter a commit message (up to 200 characters)" }
            require(name.isNotEmpty() && name.length <= 80) { "Enter your Git author name" }
            require(email.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { "Enter a valid author email" }
            require(git.status().call().let { it.added.isNotEmpty() || it.changed.isNotEmpty() || it.removed.isNotEmpty() }) {
                "Stage at least one file before committing"
            }
            val config = git.repository.config
            config.setString("user", null, "name", name)
            config.setString("user", null, "email", email)
            config.save()
            val result = git.commit().setMessage(summary).setAuthor(name, email).setCommitter(name, email).call()
            GitCommit(result.name, result.shortMessage, result.authorIdent?.name.orEmpty(),
                result.authorIdent?.whenAsInstant?.toEpochMilli() ?: 0L)
        }

    fun createBranch(root: File, branch: String) = withGit(root) { git ->
        val name = branch.trim()
        require(name.isNotEmpty() && name.length <= 80) { "Enter a branch name" }
        require(git.repository.resolve(Constants.HEAD) != null) { "Create the first commit before creating branches" }
        git.branchCreate().setName(name).call()
    }

    fun checkout(root: File, branch: String) = withGit(root) { git ->
        val status = git.status().call()
        require(status.isClean) { "Commit or discard changes before switching branches" }
        require(git.repository.resolve(Constants.HEAD) != null) { "Create the first commit before switching branches" }
        git.checkout().setName(branch).call()
    }

    fun diff(root: File, path: String, cached: Boolean): String {
        validatePath(path)
        val git = open(root) ?: error("Initialize Git for this project first")
        git.use { repository ->
            if (!cached && path in repository.status().call().untracked) return untrackedDiff(root, path)
            val output = ByteArrayOutputStream()
            DiffFormatter(output).use { formatter ->
                formatter.setRepository(repository.repository)
                formatter.setContext(3)
                val entries = if (cached) {
                    formatter.scan(headTree(repository.repository), DirCacheIterator(repository.repository.readDirCache()))
                } else {
                    formatter.scan(DirCacheIterator(repository.repository.readDirCache()), FileTreeIterator(repository.repository))
                }
                val entry = entries.firstOrNull { it.newPath == path || it.oldPath == path }
                    ?: return "No differences for this file."
                formatter.format(entry)
            }
            return output.toString(Charsets.UTF_8.name()).ifBlank { "No text differences." }
        }
    }

    private fun headTree(repository: org.eclipse.jgit.lib.Repository): AbstractTreeIterator {
        val head = repository.resolve(Constants.HEAD) ?: return EmptyTreeIterator()
        val commit = RevWalk(repository).use { it.parseCommit(head) }
        val tree = CanonicalTreeParser()
        repository.newObjectReader().use { reader -> tree.reset(reader, commit.tree) }
        return tree
    }

    private fun untrackedDiff(root: File, path: String): String {
        val file = File(root, path)
        require(file.isFile && file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Invalid project file" }
        require(file.length() <= ProjectStore.MAX_TEXT_BYTES) { "Diff preview supports files up to 256 KB" }
        val content = file.readText(Charsets.UTF_8)
        require('\u0000' !in content) { "Binary file diff preview is unavailable" }
        val lines = content.replace("\r\n", "\n").split('\n')
        return buildString {
            append("--- /dev/null\n+++ b/").append(path).append("\n")
            append("@@ -0,0 +1,").append(if (content.isEmpty()) 0 else lines.size).append(" @@\n")
            if (content.isNotEmpty()) lines.forEachIndexed { index, line ->
                if (index != lines.lastIndex || content.endsWith('\n')) append('+').append(line).append('\n')
            }
        }
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && !File(path).isAbsolute &&
            path.split('/', '\\').none { it == ".." || it == ".git" || it.startsWith(".codriod-") } &&
            path.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid Git path" }
    }

    private fun open(root: File): Git? = try { Git.open(root) } catch (_: Exception) { null }

    private inline fun <T> withGit(root: File, action: (Git) -> T): T {
        val git = open(root) ?: error("Initialize Git for this project first")
        return git.use(action)
    }
}
