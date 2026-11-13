package dev.moavia.codriod.ext

import dev.moavia.codriod.linux.LinuxScripts

enum class ExtCategory(val label: String) { LanguageServer("Language server"), Debugger("Debugger"), Theme("Theme") }

/**
 * One entry of the More extensions add-on. Only things with a real installation/integration method are listed:
 *
 * - Language servers install into the existing Ubuntu rootfs (npm / release archives) and are verified by
 *   running them. They work from the Ubuntu terminal; the built-in editor does not connect to language
 *   servers yet (there is no LSP client behind the BasicTextField editor) — [integration] says so plainly.
 * - Debuggers ship inside the toolchains the phone already has (jdb with the JDK, node inspect with Node.js,
 *   pdb with Python) — nothing to download, status comes from a live probe.
 * - Themes are built into the app and change the code editor's colours.
 */
data class ExtSpec(
    val id: String,
    val name: String,
    val category: ExtCategory,
    val blurb: String,
    /** What the extension is (and is not) able to do in Codriod today — shown in the card details. */
    val integration: String,
    /** command that must exist for the extension to be usable (probed live in Ubuntu). */
    val binary: String = "",
    val needsJava: Boolean = false,
    val experimental: Boolean = false,
    val installScript: String = "",
    val removeScript: String = "",
    val size: String = ""
)

object ExtensionsCatalog {
    private val lspNote = "Runs inside Ubuntu on this phone and is verified after install. It is usable from the Ubuntu terminal " +
        "(and for anything that talks to it). The built-in editor does not connect to language servers yet, so completions and " +
        "diagnostics inside the editor stay local until Codriod adds an LSP client."

    val all: List<ExtSpec> = listOf(
        ExtSpec("pyright", "Python — pyright", ExtCategory.LanguageServer,
            "Microsoft's fast Python type checker and language server (npm). Also usable as a plain command-line checker: `pyright file.py`.",
            lspNote + " As a CLI checker it is fully usable today.",
            binary = "pyright",
            installScript = LinuxScripts.npmInstall("pyright"),
            removeScript = LinuxScripts.npmRemove("pyright"), size = "about 60 MB"),
        ExtSpec("typescript-ls", "JavaScript & TypeScript — tsserver", ExtCategory.LanguageServer,
            "The official typescript-language-server on top of TypeScript's own tsserver (npm), plus the `tsc` compiler.",
            lspNote + " The bundled `tsc` compiler is fully usable from the terminal today.",
            binary = "typescript-language-server",
            installScript = LinuxScripts.npmInstall("typescript-language-server typescript"),
            removeScript = LinuxScripts.npmRemove("typescript-language-server") + LinuxScripts.npmRemove("typescript"),
            size = "about 70 MB"),
        ExtSpec("vscode-ls", "HTML, CSS & JSON — VS Code servers", ExtCategory.LanguageServer,
            "The HTML, CSS and JSON language servers extracted from Visual Studio Code (vscode-langservers-extracted, npm).",
            lspNote,
            binary = "vscode-html-language-server",
            installScript = LinuxScripts.npmInstall("vscode-langservers-extracted"),
            removeScript = LinuxScripts.npmRemove("vscode-langservers-extracted"),
            size = "about 20 MB"),
        ExtSpec("java-ls", "Java — Eclipse JDT LS", ExtCategory.LanguageServer,
            "Eclipse JDT's language server (eclipse.jdt.ls), the same engine behind VS Code's Java support. Needs the JDK.",
            lspNote,
            binary = "", needsJava = true,
            installScript = jdtScript(),
            removeScript = "set -e\nrm -rf /opt/jdtls\n",
            size = "about 120 MB"),
        ExtSpec("kotlin-ls", "Kotlin — JetBrains LSP (beta)", ExtCategory.LanguageServer,
            "JetBrains' official Kotlin language server (an early GitHub release). Needs the JDK.",
            lspNote,
            binary = "", needsJava = true, experimental = true,
            installScript = kotlinLspScript(),
            removeScript = "set -e\nrm -rf /opt/kotlin-lsp /usr/local/bin/kotlin-lsp\n",
            size = "about 150 MB"),
        ExtSpec("php-ls", "PHP — intelephense", ExtCategory.LanguageServer,
            "The intelephense PHP language server (npm). Runs without a PHP runtime; running PHP code itself would need apt's php package.",
            lspNote,
            binary = "intelephense",
            installScript = LinuxScripts.npmInstall("intelephense"),
            removeScript = LinuxScripts.npmRemove("intelephense"),
            size = "about 30 MB"),
        ExtSpec("jdb", "Java debugger — jdb", ExtCategory.Debugger,
            "The JDK's own command-line debugger: breakpoints, stepping and watching variables in any Java program.",
            "Bundled with the JDK — nothing to download. Fully usable from the Ubuntu terminal: `jdb -classpath . Main`.",
            binary = "jdb", needsJava = true),
        ExtSpec("node-inspect", "JavaScript debugger — node inspect", ExtCategory.Debugger,
            "Node.js' built-in inspector: `node inspect script.js` for breakpoints and stepping in JavaScript.",
            "Bundled with Node.js — nothing to download. Fully usable from the Ubuntu terminal.",
            binary = "node"),
        ExtSpec("pdb", "Python debugger — pdb", ExtCategory.Debugger,
            "Python's standard-library debugger: `python3 -m pdb script.py`.",
            "Bundled with Python — nothing to download. Fully usable from the Ubuntu terminal.",
            binary = "python3"))

    fun byId(id: String) = all.firstOrNull { it.id == id }
    val themes get() = EditorThemes.all

    /** Eclipse JDT LS: latest release tarball from GitHub, unpacked to /opt/jdtls. */
    private fun jdtScript() = """
set -e
echo "::step Checking for Java (the JDT server runs on it)"
echo "::progress 2"
[ -d /opt/jdk ] && export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:${'$'}PATH
command -v java >/dev/null 2>&1 || { echo "::fail The Java language server runs on Java — install the JDK in the Java & Kotlin add-on first"; exit 6; }
command -v python3 >/dev/null 2>&1 || { echo "::fail The jdtls launcher needs python3 (installed with Ubuntu's basic tools)"; exit 6; }
echo "::step Finding the latest Eclipse JDT language server"
echo "::progress 4"
tag=${'$'}(curl -fsSL --retry 4 https://api.github.com/repos/eclipse-jdt/eclipse.jdt.ls/releases/latest | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("tag_name","").lstrip("v"))
except Exception: print("")')
[ -n "${'$'}tag" ] || { echo "::fail Could not read the JDT LS release list from GitHub"; exit 4; }
echo "::step Downloading JDT LS ${'$'}tag"
echo "::progress 10"
curl -fL --retry 4 --retry-delay 3 -o /tmp/jdtls.tar.gz "https://github.com/eclipse-jdt/eclipse.jdt.ls/releases/download/v${'$'}tag/jdt-language-server-${'$'}tag.tar.gz"
[ -s /tmp/jdtls.tar.gz ] || { echo "::fail the JDT LS download failed"; exit 5; }
echo "::step Unpacking"
echo "::progress 60"
rm -rf /opt/jdtls.new /opt/jdtls && mkdir -p /opt/jdtls.new
tar -xzf /tmp/jdtls.tar.gz -C /opt/jdtls.new
rm -f /tmp/jdtls.tar.gz
echo "::step Checking the server"
echo "::progress 90"
test -x /opt/jdtls.new/bin/jdtls || { echo "::fail the jdtls launcher is missing from the archive"; exit 5; }
chmod 755 /opt/jdtls.new/bin/jdtls
mv /opt/jdtls.new /opt/jdtls
echo "${'$'}tag" > /opt/jdtls/.codriod-version
echo "jdtls ${'$'}tag installed at /opt/jdtls (start it with /opt/jdtls/bin/jdtls)"
echo "::step JDT language server installed"
""".trimIndent() + "\n"

    /** JetBrains' Kotlin LSP: newest .zip asset from its GitHub releases, unpacked to /opt/kotlin-lsp. */
    private fun kotlinLspScript() = """
set -e
echo "::step Checking for Java (the Kotlin language server runs on it)"
echo "::progress 2"
[ -d /opt/jdk ] && export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:${'$'}PATH
command -v java >/dev/null 2>&1 || { echo "::fail The Kotlin language server runs on Java — install the JDK in the Java & Kotlin add-on first"; exit 6; }
command -v unzip >/dev/null 2>&1 || apt-get install -y --no-install-recommends unzip >/dev/null 2>&1 || true
echo "::step Finding the latest JetBrains Kotlin LSP release"
echo "::progress 4"
set -- ${'$'}(curl -fsSL --retry 4 https://api.github.com/repos/JetBrains/kotlin-lsp/releases/latest | python3 -c 'import sys,json
try: d=json.load(sys.stdin)
except Exception: d={}
a=[x["browser_download_url"] for x in d.get("assets",[]) if x["name"].endswith(".zip")]
print(d.get("tag_name","").lstrip("v"), a[0] if a else "")')
tag=${'$'}{1:-}; url=${'$'}{2:-}
[ -n "${'$'}url" ] || { echo "::fail no Kotlin LSP zip was found in the latest release"; exit 4; }
echo "::step Downloading Kotlin LSP ${'$'}tag"
echo "::progress 8"
curl -fL --retry 4 --retry-delay 3 -o /tmp/kotlin-lsp.zip "${'$'}url"
echo "::step Unpacking"
echo "::progress 55"
rm -rf /opt/kotlin-lsp.new /opt/kotlin-lsp && mkdir -p /opt/kotlin-lsp.new
unzip -q -o /tmp/kotlin-lsp.zip -d /opt/kotlin-lsp.new
rm -f /tmp/kotlin-lsp.zip
echo "::step Checking the server"
echo "::progress 90"
dir=/opt/kotlin-lsp.new
if [ -d /opt/kotlin-lsp.new/kotlin-lsp ]; then dir=/opt/kotlin-lsp.new/kotlin-lsp; fi
launch=
for c in "${'$'}dir/kotlin-lsp.sh" "${'$'}dir/kotlin-lsp" "${'$'}(find "${'$'}dir" -maxdepth 2 -name 'kotlin-lsp.sh' | head -n 1)" "${'$'}(find "${'$'}dir" -maxdepth 2 -name 'kotlin-lsp' -type f | head -n 1)"; do
  if [ -n "${'$'}c" ] && [ -f "${'$'}c" ]; then launch="${'$'}c"; break; fi
done
[ -n "${'$'}launch" ] || { echo "::fail no kotlin-lsp launcher was found in the archive"; exit 5; }
chmod 755 "${'$'}launch"
rm -rf /opt/kotlin-lsp && mv "${'$'}dir" /opt/kotlin-lsp
rm -rf /opt/kotlin-lsp.new
ln -sf "${'$'}launch" /usr/local/bin/kotlin-lsp
echo "${'$'}tag" > /opt/kotlin-lsp/.codriod-version
echo "kotlin-lsp ${'$'}tag installed at /opt/kotlin-lsp (start it with kotlin-lsp)"
echo "::step Kotlin language server installed"
""".trimIndent() + "\n"
}
