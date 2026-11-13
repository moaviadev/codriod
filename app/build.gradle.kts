import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Decompresses data.tar.xz inside Termux's proot packages while fetching the Linux runtime (fetchProot).
        classpath("org.tukaani:xz:1.10")
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.moavia.codriod"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.moavia.codriod"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // proot runs as a program, so it must be extracted onto disk (Android only lets apps execute files from
        // their native library folder). Keep its symbols: stripping can break the loader.
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libproot.so", "**/libproot-loader.so", "**/libproot-loader32.so", "**/libtalloc.so", "**/libandroid-shmem.so")
        }
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// =========================================================================================================
// Linux runtime (proot) fetch. Cross-platform version of scripts/fetch-proot.sh, so local builds on any
// OS bundle proot (Ubuntu needs it; without it the app says "Not available on this phone").
// Downloads Termux's Android builds of proot, talloc and libandroid-shmem, unpacks the .deb files
// (ar -> data.tar.xz -> tar), renames the pieces to lib*.so (the only executables Android installs
// runnable) and rewrites proot's library reference "libtalloc.so.2" to the bundled name "libtalloc.so".
// =========================================================================================================

object ProotFetcher {
    private const val REPO = "https://packages.termux.dev/apt/termux-main"
    /** termux arch -> Android abi. */
    private val ABIS = linkedMapOf("aarch64" to "arm64-v8a", "x86_64" to "x86_64")
    private val PACKAGES = listOf("proot", "libtalloc", "libandroid-shmem")
    private const val USR = "data/data/com.termux/files/usr"
    private val ALLOWED_NEEDED = setOf("libc.so", "libdl.so", "libm.so", "liblog.so", "libtalloc.so", "libandroid-shmem.so")
    private val REQUIRED_OUTPUTS = listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so")

    /** True when every abi folder already has the runtime, verified (so a broken cache is re-fetched). */
    fun complete(outDir: File): Boolean = ABIS.values.all { abi ->
        val dir = File(outDir, abi)
        REQUIRED_OUTPUTS.all { File(dir, it).let { f -> f.isFile && f.length() > 0 && neededLibraries(f.readBytes()).all { it in ALLOWED_NEEDED } } }
    }

    fun run(outDir: File, cacheDir: File, log: (String) -> Unit) {
        cacheDir.mkdirs()
        for ((arch, abi) in ABIS) {
            val out = File(outDir, abi).apply { mkdirs() }
            log("proot: fetching $arch ($abi)")
            val index = httpText("$REPO/dists/stable/main/binary-$arch/Packages")
            val wanted = PACKAGES.associateWith { pkg ->
                val stanza = Regex("(?s)(?:^|\n)Package: $pkg\n.*?(?=\\n\\n|\\z)").find(index)?.value
                    ?: error("proot: package $pkg not found for $arch in the Termux repository")
                Pair(
                    Regex("Filename: (.+)").find(stanza)!!.groupValues[1].trim(),
                    Regex("SHA256: ([0-9a-f]{64})").find(stanza)!!.groupValues[1].trim())
            }
            val files = HashMap<String, ByteArray>()
            val links = HashMap<String, String>()
            for (pkg in PACKAGES) {
                val (filename, sha) = wanted[pkg]!!
                val deb = File(cacheDir, "$arch-$pkg.deb")
                download("$REPO/$filename", deb)
                check(sha256(deb) == sha) { "proot: $pkg download is damaged (checksum mismatch)" }
                readDeb(deb, files, links)
            }
            put(out, "libproot.so", resolve(files, links, "$USR/bin/proot") ?: error("proot: bin/proot missing for $arch"))
            put(out, "libproot-loader.so", resolve(files, links, "$USR/libexec/proot/loader") ?: error("proot: loader missing for $arch"))
            resolve(files, links, "$USR/libexec/proot/loader32")?.let { put(out, "libproot-loader32.so", it) }
            put(out, "libtalloc.so", resolve(files, links, "$USR/lib/libtalloc.so.2") ?: error("proot: libtalloc missing for $arch"))
            put(out, "libandroid-shmem.so", resolve(files, links, "$USR/lib/libandroid-shmem.so") ?: error("proot: libandroid-shmem missing for $arch"))
        }
        log("proot: Linux runtime ready in ${outDir.absolutePath}")
    }

    private fun put(dir: File, name: String, bytes: ByteArray) {
        val patched = if (name == "libproot.so") renameTallocNeeded(bytes) else bytes
        if (name != "libproot-loader32.so") { // the 32-bit loader is an ARM32 ELF, only the 64-bit files are checked
            val libs = neededLibraries(patched)
            val unexpected = libs.filterNot { it in ALLOWED_NEEDED }
            check(unexpected.isEmpty()) { "proot: $name needs $unexpected, which is not bundled" }
        }
        File(dir, name).writeBytes(patched)
    }

    /** proot links against Termux's "libtalloc.so.2"; the APK bundles it as "libtalloc.so" (Android only extracts
     *  lib*.so files). Rewrites the NEEDED string in place — the replacement is shorter, so NUL padding keeps
     *  every offset intact (same trick patchelf uses). */
    private fun renameTallocNeeded(bytes: ByteArray): ByteArray {
        val from = "libtalloc.so.2\u0000".toByteArray(Charsets.ISO_8859_1)
        val to = "libtalloc.so\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        check(from.size == to.size)
        var count = 0
        for (i in 0..bytes.size - from.size) {
            if (from.indices.all { bytes[i + it] == from[it] }) {
                for (j in to.indices) bytes[i + j] = to[j]
                count++
            }
        }
        check(count > 0) { "proot: libtalloc.so.2 reference not found in libproot.so (proot may not need talloc anymore)" }
        return bytes
    }

    // ---- .deb (ar archive) -> data.tar -> files & symlinks in memory ----------------------------------------------

    private fun readDeb(deb: File, files: MutableMap<String, ByteArray>, links: MutableMap<String, String>) {
        val bytes = deb.readBytes()
        check(String(bytes, 0, 8, Charsets.US_ASCII) == "!<arch>\n") { "proot: ${deb.name} is not an ar archive" }
        var pos = 8
        while (pos + 60 <= bytes.size) {
            val name = String(bytes, pos, 16, Charsets.US_ASCII).trimEnd(' ', '/')
            val size = String(bytes, pos + 48, 10, Charsets.US_ASCII).trim().toLongOrNull() ?: break
            if (size < 0 || pos + 60 + size > bytes.size) break
            if (name.startsWith("data.tar")) readTar(decompress(bytes, (pos + 60).toLong(), size), files, links)
            pos += (60 + size + (if (size % 2 == 1L) 1 else 0)).toInt()
        }
    }

    private fun decompress(bytes: ByteArray, offset: Long, size: Long): InputStream {
        val body = bytes.copyOfRange(offset.toInt(), (offset + size).toInt())
        return when {
            body.size >= 6 && body[0] == 0xFD.toByte() && body[1] == 0x37.toByte() && body[2] == 0x7A.toByte() &&
                body[3] == 0x58.toByte() && body[4] == 0x5A.toByte() && body[5] == 0.toByte() ->
                XZInputStream(ByteArrayInputStream(body))
            body.size >= 2 && body[0] == 0x1F.toByte() && body[1] == 0x8B.toByte() ->
                GZIPInputStream(ByteArrayInputStream(body))
            body.size >= 262 && String(body, 257, 5, Charsets.US_ASCII) == "ustar" ->
                ByteArrayInputStream(body)
            else -> error("proot: unknown data.tar compression (xz, gz and plain tar are supported)")
        }
    }

    private fun readTar(input: InputStream, files: MutableMap<String, ByteArray>, links: MutableMap<String, String>) {
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        fun data(size: Long): ByteArray {
            val data = ByteArray(size.toInt()); var read = 0
            while (read < data.size) { val n = input.read(data, read, data.size - read); if (n < 0) error("proot: tar ends early"); read += n }
            var left = (size + 511) / 512 * 512 - size
            val skip = ByteArray(8192)
            while (left > 0) left -= maxOf(0, input.read(skip, 0, minOf(skip.size.toLong(), left).toInt()))
            return data
        }
        fun nextHeader(): Boolean {
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) return false
                read += n
            }
            return true
        }
        while (true) {
            if (!nextHeader()) break
            if (header.all { it == 0.toByte() }) break
            val type = header[156].toInt().toChar()
            val size = octal(header, 124, 12)
            when (type) {
                'L' -> longName = String(data(size), Charsets.UTF_8).trimEnd('\u0000')
                'K' -> longLink = String(data(size), Charsets.UTF_8).trimEnd('\u0000')
                'x' -> {
                    val records = String(data(size), Charsets.UTF_8)
                    paxPath = Regex("path=([^\\n]+)").find(records)?.groupValues?.get(1)
                    paxLink = Regex("linkpath=([^\\n]+)").find(records)?.groupValues?.get(1)
                }
                'g' -> data(size)
                else -> {
                    val prefix = if (String(header, 257, 6, Charsets.US_ASCII).startsWith("ustar")) String(header, 345, 155, Charsets.US_ASCII).trimEnd('\u0000', '/') else ""
                    var name = paxPath ?: longName ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                    val link = paxLink ?: longLink ?: String(header, 157, 100, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                    name = name.trimStart('/', '.').trimStart('/')
                    when (type) {
                        '5' -> {}
                        '2' -> links[name] = link
                        '0', '\u0000', '7' -> files[name] = data(size)
                        else -> data(size)
                    }
                }
            }
            longName = null; longLink = null; paxPath = null; paxLink = null
        }
    }

    /** Follows symlinks (relative or absolute) within the tar's contents. */
    private fun resolve(files: Map<String, ByteArray>, links: Map<String, String>, path: String): ByteArray? {
        var current = path.trimStart('/')
        repeat(10) {
            files[current]?.let { return it }
            val link = links[current] ?: return null
            val parts = ArrayList<String>()
            for (part in (if (link.startsWith("/")) link else current.substringBeforeLast('/', "") + "/" + link).split('/')) {
                when (part) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(part)
                }
            }
            current = parts.joinToString("/")
        }
        error("proot: symlink loop at $path")
    }

    private fun octal(header: ByteArray, offset: Int, length: Int): Long {
        val s = String(header, offset, length, Charsets.US_ASCII).trim().trim('\u0000')
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    // ---- ELF (64-bit little-endian) checks -----------------------------------------------------------------------

    /** The shared libraries a bundled binary needs (DT_NEEDED), so nothing is missing on the phone.
     *  Read from the program headers: stripped binaries often have no section headers. */
    private fun neededLibraries(bytes: ByteArray): List<String> {
        check(bytes.size > 64 && bytes[4] == 2.toByte() && bytes[5] == 1.toByte()) { "proot: not a 64-bit little-endian ELF" }
        fun u16(off: Int) = (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8)
        fun u32(off: Int) = (u16(off).toLong() and 0xffff) or ((u16(off + 2).toLong() and 0xffff) shl 16)
        fun u64(off: Int) = (u32(off) and 0xffffffffL) or ((u32(off + 4) and 0xffffffffL) shl 32)
        val phoff = u64(0x20).toInt()
        val phentsize = u16(0x36); val phnum = u16(0x38)
        val loads = ArrayList<Triple<Long, Long, Long>>() // file offset, virtual address, file size
        var dynamicOffset = -1; var dynamicSize = 0L
        for (i in 0 until phnum) {
            val base = phoff + i * phentsize
            when (u32(base)) {
                1L -> loads += Triple(u64(base + 8), u64(base + 0x10), u64(base + 0x20)) // PT_LOAD
                2L -> { dynamicOffset = u64(base + 8).toInt(); dynamicSize = u64(base + 0x20) } // PT_DYNAMIC
            }
        }
        check(dynamicOffset >= 0 || true) // static binaries (the loader) have no dynamic segment
        if (dynamicOffset < 0) return emptyList()
        fun fileAddress(virtual: Long): Int = loads.firstOrNull { virtual >= it.second && virtual < it.second + it.third }
            ?.let { it.first + (virtual - it.second) }?.toInt()
            ?: error("proot: ELF address $virtual is outside the loaded segments")
        var strtab = -1
        val neededOffsets = ArrayList<Long>()
        var pos = dynamicOffset
        val end = dynamicOffset + dynamicSize.toInt()
        while (pos + 16 <= end) {
            val tag = u64(pos); if (tag == 0L) break
            if (tag == 5L) strtab = fileAddress(u64(pos + 8)) // DT_STRTAB
            if (tag == 1L) neededOffsets += u64(pos + 8) // DT_NEEDED (string offset)
            pos += 16
        }
        check(strtab >= 0) { "proot: ELF has no string table" }
        return neededOffsets.map { offset ->
            var start = (strtab + offset).toInt()
            var stop = start
            while (bytes[stop] != 0.toByte()) stop++
            String(bytes, start, stop - start, Charsets.US_ASCII)
        }
    }

    private fun ByteArray.indexOf(value: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == value) return i
        return size
    }

    // ---- network -------------------------------------------------------------------------------------------------

    private fun httpText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000; connection.readTimeout = 30_000
        check(connection.responseCode in 200..299) { "proot: HTTP ${connection.responseCode} for $url" }
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun download(url: String, file: File) {
        if (file.isFile && file.length() > 0) return // kept between builds, verified against the index checksum
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000; connection.readTimeout = 60_000
        check(connection.responseCode in 200..299) { "proot: HTTP ${connection.responseCode} for $url" }
        connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it, 64 * 1024) } }
        connection.disconnect()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val fetchProot = tasks.register("fetchProot") {
    group = "setup"
    description = "Fetches the Linux runtime (proot) into src/main/jniLibs so the APK can run Ubuntu."
    val outDir = layout.projectDirectory.dir("src/main/jniLibs")
    val cacheDir = layout.buildDirectory.dir("fetch-proot-cache")
    outputs.dir(outDir)
    outputs.upToDateWhen { ProotFetcher.complete(outDir.asFile) }
    doLast {
        ProotFetcher.run(outDir.asFile, cacheDir.get().asFile) { message -> logger.lifecycle(message) }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchProot) }

dependencies {
    implementation(project(":terminal-view"))
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r")
    // Git over SSH with the user's own key. JGit's JSch transport, on the maintained JSch fork
    // (rsa-sha2/ECDSA support, which GitHub requires); the unmaintained original is excluded.
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:7.7.1.202607240634-r") {
        exclude(group = "com.jcraft", module = "jsch")
    }
    implementation("com.github.mwiede:jsch:2.27.3")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.eclipse.jgit:org.eclipse.jgit.junit.ssh:7.7.1.202607240634-r")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
