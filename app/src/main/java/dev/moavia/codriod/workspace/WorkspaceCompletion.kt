package dev.moavia.codriod.workspace

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Language-aware, fully local autocomplete for the Codriod editor.
 *
 * The engine is intentionally provider based: [EditorLanguage] decides which provider runs,
 * each provider inspects only the text around the caret (never the whole document), and returns
 * the range to replace plus the ranked suggestions. New languages can be added by extending
 * [EditorLanguage] and [CompletionEngine].
 */

enum class EditorLanguage(val label: String) {
    Html("HTML"), Css("CSS"), JavaScript("JavaScript"), TypeScript("TypeScript"),
    Jsx("JSX"), Tsx("TSX"), Json("JSON"), Xml("XML"), Python("Python"), Java("Java"),
    Kotlin("Kotlin"), C("C"), Cpp("C++"), Php("PHP"), Sql("SQL"), Shell("Shell"),
    Markdown("Markdown"), Plain("Plain Text");

    val supportsCompletion: Boolean get() = this != Plain
}

enum class CompletionKind { Keyword, Property, Tag, Snippet, Function, Value, Type, Variable }

/** A single completion. [insert] replaces the detected token; [cursor] is the caret offset inside it. */
data class CompletionItem(
    val label: String,
    val detail: String,
    val insert: String = label,
    val cursor: Int = insert.length,
    val kind: CompletionKind = CompletionKind.Keyword
)

/** Suggestions for the caret at [replaceStart]..[replaceEnd] (the token currently being typed). */
data class CompletionResult(
    val replaceStart: Int,
    val replaceEnd: Int,
    val items: List<CompletionItem>,
    val language: EditorLanguage
)

object Languages {
    fun of(path: String): EditorLanguage = when (ImageFiles.extension(path)) {
        "html", "htm", "xhtml" -> EditorLanguage.Html
        "css", "scss", "sass", "less" -> EditorLanguage.Css
        "js", "mjs", "cjs" -> EditorLanguage.JavaScript
        "ts", "mts", "cts" -> EditorLanguage.TypeScript
        "jsx" -> EditorLanguage.Jsx
        "tsx" -> EditorLanguage.Tsx
        "json", "jsonc" -> EditorLanguage.Json
        "xml", "svg", "plist", "gradle" -> EditorLanguage.Xml
        "py", "pyw" -> EditorLanguage.Python
        "java" -> EditorLanguage.Java
        "kt", "kts" -> EditorLanguage.Kotlin
        "c", "h" -> EditorLanguage.C
        "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> EditorLanguage.Cpp
        "php" -> EditorLanguage.Php
        "sql" -> EditorLanguage.Sql
        "sh", "bash", "zsh", "fish", "command" -> EditorLanguage.Shell
        "md", "markdown", "mdx" -> EditorLanguage.Markdown
        else -> EditorLanguage.Plain
    }

    /** True for languages where a leading `#` starts a line comment. */
    internal fun hashComments(language: EditorLanguage) = language == EditorLanguage.Python ||
        language == EditorLanguage.Shell || language == EditorLanguage.Markdown

    private val keywordSets: Map<EditorLanguage, List<String>> = mapOf(
        EditorLanguage.JavaScript to listOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "class",
            "import", "export", "async", "await", "try", "catch", "finally", "throw", "new",
            "this", "switch", "case", "break", "continue", "typeof", "instanceof", "delete",
            "in", "of", "do", "yield", "static", "get", "set", "true", "false", "null", "undefined",
            "document", "window", "console", "fetch", "Promise", "Array", "Object", "JSON"
        ),
        EditorLanguage.TypeScript to listOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "class",
            "import", "export", "async", "await", "try", "catch", "finally", "throw", "new",
            "this", "interface", "type", "enum", "public", "private", "protected", "readonly",
            "extends", "implements", "namespace", "declare", "as", "any", "string", "number",
            "boolean", "void", "never", "unknown", "true", "false", "null", "undefined", "console", "fetch"
        ),
        EditorLanguage.Jsx to listOf(
            "const", "let", "function", "return", "if", "else", "for", "while", "class", "import",
            "export", "default", "async", "await", "try", "catch", "new", "this", "useState",
            "useEffect", "useMemo", "useCallback", "useRef", "React", "props", "className", "onClick",
            "onChange", "true", "false", "null", "undefined", "console"
        ),
        EditorLanguage.Tsx to listOf(
            "const", "let", "function", "return", "if", "else", "for", "while", "class", "import",
            "export", "default", "async", "await", "try", "catch", "new", "this", "interface", "type",
            "enum", "public", "private", "protected", "readonly", "extends", "implements", "useState",
            "useEffect", "useMemo", "useCallback", "useRef", "React", "props", "className", "onClick",
            "onChange", "string", "number", "boolean", "true", "false", "null", "undefined", "console"
        ),
        EditorLanguage.Json to listOf("true", "false", "null"),
        EditorLanguage.Xml to listOf(
            "version", "encoding", "xmlns", "name", "value", "id", "type", "true", "false", "null"
        ),
        EditorLanguage.Python to listOf(
            "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while", "in",
            "not", "and", "or", "True", "False", "None", "try", "except", "finally", "with", "as",
            "lambda", "yield", "async", "await", "pass", "break", "continue", "raise", "global",
            "nonlocal", "print", "len", "range", "self", "is", "del"
        ),
        EditorLanguage.Java to listOf(
            "public", "private", "protected", "class", "interface", "extends", "implements", "import",
            "package", "static", "final", "void", "int", "long", "double", "float", "boolean", "char",
            "String", "new", "return", "if", "else", "for", "while", "switch", "case", "break",
            "continue", "try", "catch", "finally", "throw", "this", "super", "null", "true", "false",
            "System", "override", "abstract", "synchronized"
        ),
        EditorLanguage.Kotlin to listOf(
            "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "when",
            "if", "else", "for", "while", "do", "return", "import", "package", "private", "public",
            "protected", "internal", "override", "open", "abstract", "companion", "init", "try",
            "catch", "finally", "throw", "null", "true", "false", "it", "this", "super", "is", "as",
            "in", "by", "lazy", "lateinit", "suspend", "inline", "reified", "vararg", "typealias"
        ),
        EditorLanguage.C to listOf(
            "include", "define", "int", "char", "float", "double", "void", "long", "short", "unsigned",
            "signed", "struct", "typedef", "enum", "union", "return", "if", "else", "for", "while",
            "do", "switch", "case", "break", "continue", "const", "static", "sizeof", "NULL",
            "printf", "scanf", "malloc", "free", "sizeof", "goto", "extern"
        ),
        EditorLanguage.Cpp to listOf(
            "include", "using", "namespace", "class", "struct", "public", "private", "protected",
            "virtual", "override", "template", "typename", "const", "static", "int", "char", "float",
            "double", "void", "bool", "auto", "return", "if", "else", "for", "while", "new", "delete",
            "nullptr", "true", "false", "std", "cout", "cin", "vector", "string", "endl", "constexpr"
        ),
        EditorLanguage.Php to listOf(
            "function", "class", "public", "private", "protected", "static", "return", "if", "else",
            "elseif", "foreach", "for", "while", "echo", "print", "new", "namespace", "use", "require",
            "include", "true", "false", "null", "this", "array", "isset", "empty", "die", "exit"
        ),
        EditorLanguage.Sql to listOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
            "TABLE", "ALTER", "DROP", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY",
            "ORDER", "HAVING", "LIMIT", "DISTINCT", "AS", "AND", "OR", "NOT", "NULL", "PRIMARY",
            "KEY", "FOREIGN", "REFERENCES", "INDEX", "DATABASE", "COUNT", "SUM", "AVG", "MIN", "MAX"
        ),
        EditorLanguage.Shell to listOf(
            "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "case", "esac",
            "function", "echo", "export", "local", "readonly", "return", "exit", "cd", "ls", "cp",
            "mv", "rm", "mkdir", "grep", "sed", "awk", "cat", "chmod", "chown", "sudo", "apt",
            "git", "npm", "node", "python", "source", "read", "test"
        ),
        EditorLanguage.Markdown to listOf(
            "bold", "italic", "code", "link", "image", "table", "quote", "list", "heading",
            "checkbox", "divider", "strikethrough"
        )
    )

    internal fun keywords(language: EditorLanguage): List<String> = keywordSets[language].orEmpty()

    /** A single regex used for syntax highlighting, tailored to the language. */
    internal fun highlightRegex(language: EditorLanguage): Regex {
        val keywords = keywords(language).sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val parts = mutableListOf(
            "\"(?:\\\\.|[^\"\\\\])*\"",
            "'(?:\\\\.|[^'\\\\])*'",
            "`(?:\\\\.|[^`\\\\])*`",
            "//[^\n]*",
            "/\\*[\\s\\S]*?\\*/",
            "<!--[\\s\\S]*?-->"
        )
        if (hashComments(language)) parts += "#[^\n]*"
        if (language == EditorLanguage.Html || language == EditorLanguage.Xml) parts += "</?[A-Za-z][^>]*>"
        if (language == EditorLanguage.Css) parts += "#[0-9a-fA-F]{3,8}\\b"
        parts += "\\b\\d+(?:\\.\\d+)?\\b"
        if (keywords.isNotEmpty()) parts += "\\b(?:$keywords)\\b"
        return Regex(parts.joinToString("|"))
    }
}

object CompletionEngine {

    private val htmlTags = listOf(
        "html", "head", "body", "title", "meta", "link", "script", "style", "div", "span",
        "section", "header", "footer", "main", "article", "button", "input", "form", "img", "a",
        "p", "h1", "h2", "h3", "h4", "ul", "ol", "li", "nav", "label", "textarea", "select",
        "option", "table", "thead", "tbody", "tr", "td", "th", "br", "hr", "canvas", "video",
        "audio", "source", "iframe", "svg", "template", "figure", "figcaption"
    )
    private val voidTags = setOf(
        "meta", "link", "input", "img", "br", "hr", "source", "area", "base", "col", "embed",
        "param", "track", "wbr"
    )

    private val cssProperties = listOf(
        "display", "position", "color", "background", "background-color", "background-image",
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left", "padding",
        "padding-top", "padding-right", "padding-bottom", "padding-left", "width", "height",
        "min-width", "max-width", "min-height", "max-height", "border", "border-radius",
        "border-color", "border-width", "flex", "flex-direction", "flex-wrap", "grid",
        "grid-template-columns", "grid-template-rows", "justify-content", "align-items",
        "align-content", "gap", "row-gap", "column-gap", "font-size", "font-family", "font-weight",
        "line-height", "letter-spacing", "text-align", "text-decoration", "text-transform",
        "animation", "transition", "transform", "opacity", "overflow", "z-index", "top", "right",
        "bottom", "left", "cursor", "box-shadow", "object-fit", "visibility"
    )

    fun suggest(path: String, text: String, cursor: Int): CompletionResult? {
        val language = Languages.of(path)
        if (!language.supportsCompletion) return null
        val caret = cursor.coerceIn(0, text.length)
        return when (language) {
            EditorLanguage.Html -> htmlSuggest(text, caret)
            EditorLanguage.Xml -> xmlSuggest(text, caret)
            EditorLanguage.Css -> cssSuggest(text, caret)
            EditorLanguage.Markdown -> markdownSuggest(text, caret)
            else -> wordSuggest(language, text, caret)
        }
    }

    // ---- HTML ----------------------------------------------------------------

    private fun htmlSuggest(text: String, caret: Int): CompletionResult? {
        val start = tagTokenStart(text, caret) ?: return null
        val typed = text.substring(start + 1, caret)
        val items = htmlTags.filter { it.startsWith(typed, true) }
            .map { tag -> if (tag == "html") fullHtmlDocument() else tagCompletion(tag) }
        if (items.isEmpty()) return null
        return CompletionResult(start, caret, items, EditorLanguage.Html)
    }

    /** Returns the index of the `<` that begins the tag currently being typed, or null. */
    private fun tagTokenStart(text: String, caret: Int): Int? {
        val window = (caret - 400).coerceAtLeast(0)
        val lt = text.lastIndexOf('<', caret - 1)
        if (lt < window) return null
        val between = text.substring(lt + 1, caret)
        if (between.any { it == '>' || it == '/' || it.isWhitespace() }) return null
        return lt
    }

    private fun tagCompletion(tag: String): CompletionItem {
        if (tag in voidTags) {
            val insert = "<$tag>"
            return CompletionItem(tag, "tag · void", insert, insert.length, CompletionKind.Tag)
        }
        val insert = "<$tag></$tag>"
        return CompletionItem(tag, "tag", insert, tag.length + 2, CompletionKind.Tag)
    }

    private fun fullHtmlDocument(): CompletionItem {
        val insert = buildString {
            append("<!DOCTYPE html>\n")
            append("<html>\n")
            append("<head>\n")
            append("  <meta charset=\"utf-8\">\n")
            append("  <title></title>\n")
            append("</head>\n")
            append("<body>\n")
            append("  \n")
            append("</body>\n")
            append("</html>")
        }
        val cursor = insert.indexOf("<body>\n  ") + "<body>\n  ".length
        return CompletionItem("html", "full document", insert, cursor, CompletionKind.Snippet)
    }

    // ---- XML -----------------------------------------------------------------

    private fun xmlSuggest(text: String, caret: Int): CompletionResult? {
        val start = tagTokenStart(text, caret) ?: return null
        val typed = text.substring(start + 1, caret)
        if (typed.startsWith("?")) return null
        val tags = listOf(
            "project", "modelVersion", "groupId", "artifactId", "version", "name", "description",
            "dependency", "dependencies", "build", "plugins", "plugin", "properties", "item",
            "root", "node", "value", "entry", "resources", "resource"
        )
        val items = tags.filter { it.startsWith(typed, true) }.map { tagCompletion(it) }
        if (items.isEmpty()) return null
        return CompletionResult(start, caret, items, EditorLanguage.Xml)
    }

    // ---- CSS -----------------------------------------------------------------

    private fun cssSuggest(text: String, caret: Int): CompletionResult? {
        val lastOpen = text.lastIndexOf('{', caret - 1)
        val lastClose = text.lastIndexOf('}', caret - 1)
        if (lastOpen < 0 || lastOpen < lastClose) return null
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val before = text.substring(lineStart, caret)
        val match = Regex("""^(\s*)([A-Za-z-]*)$""").find(before) ?: return null
        val typed = match.groupValues[2]
        val start = lineStart + match.groupValues[1].length
        val items = cssProperties.filter { it.startsWith(typed, true) }.map { property ->
            val insert = "$property: ;"
            CompletionItem(property, "property", insert, property.length + 2, CompletionKind.Property)
        }
        if (items.isEmpty()) return null
        return CompletionResult(start, caret, items, EditorLanguage.Css)
    }

    // ---- Markdown ------------------------------------------------------------

    private fun markdownSuggest(text: String, caret: Int): CompletionResult? {
        val start = wordStart(text, caret)
        val typed = text.substring(start, caret)
        if (typed.isEmpty()) return null
        val items = listOf(
            CompletionItem("heading", "snippet", "# ", 2, CompletionKind.Snippet),
            CompletionItem("bold", "snippet", "**bold**", 2, CompletionKind.Snippet),
            CompletionItem("italic", "snippet", "*italic*", 1, CompletionKind.Snippet),
            CompletionItem("code", "snippet", "`code`", 1, CompletionKind.Snippet),
            CompletionItem("link", "snippet", "[text](url)", 1, CompletionKind.Snippet),
            CompletionItem("image", "snippet", "![alt](url)", 2, CompletionKind.Snippet),
            CompletionItem("table", "snippet", "| a | b |\n| --- | --- |\n|  |  |", 2, CompletionKind.Snippet),
            CompletionItem("quote", "snippet", "> ", 2, CompletionKind.Snippet),
            CompletionItem("list", "snippet", "- ", 2, CompletionKind.Snippet),
            CompletionItem("checkbox", "snippet", "- [ ] ", 6, CompletionKind.Snippet),
            CompletionItem("divider", "snippet", "---\n", 4, CompletionKind.Snippet),
            CompletionItem("strikethrough", "snippet", "~~text~~", 2, CompletionKind.Snippet)
        ).filter { it.label.startsWith(typed, true) }
        if (items.isEmpty()) return null
        return CompletionResult(start, caret, items, EditorLanguage.Markdown)
    }

    // ---- Keyword languages (JS/TS/JSX/TSX/JSON/Python/Java/Kotlin/C/C++/PHP/SQL/Shell) ----

    private fun wordSuggest(language: EditorLanguage, text: String, caret: Int): CompletionResult? {
        val start = wordStart(text, caret)
        val typed = text.substring(start, caret)
        if (typed.isEmpty() || typed.first().isDigit()) return null
        if (insideStringOrComment(language, text, caret)) return null
        val items = keywordItems(language).filter { it.label.startsWith(typed, true) }
        if (items.isEmpty()) return null
        return CompletionResult(start, caret, items, language)
    }

    private fun wordStart(text: String, caret: Int): Int {
        var i = caret
        while (i > 0) {
            val c = text[i - 1]
            if (c.isLetterOrDigit() || c == '_' || c == '.' || c == '$' || c == '-') i-- else break
        }
        return i
    }

    private fun keywordItems(language: EditorLanguage): List<CompletionItem> {
        val special = when (language) {
            EditorLanguage.JavaScript, EditorLanguage.TypeScript, EditorLanguage.Jsx, EditorLanguage.Tsx -> listOf(
                CompletionItem("console.log", "function", "console.log()", 12, CompletionKind.Function),
                CompletionItem("fetch", "function", "fetch()", 6, CompletionKind.Function),
                CompletionItem("if", "keyword", "if ()", 4, CompletionKind.Keyword),
                CompletionItem("for", "keyword", "for ()", 5, CompletionKind.Keyword),
                CompletionItem("while", "keyword", "while ()", 7, CompletionKind.Keyword),
                CompletionItem("catch", "keyword", "catch ()", 7, CompletionKind.Keyword)
            )
            EditorLanguage.Python -> listOf(
                CompletionItem("print", "function", "print()", 6, CompletionKind.Function),
                CompletionItem("def", "keyword", "def ()", 5, CompletionKind.Keyword)
            )
            EditorLanguage.Java -> listOf(
                CompletionItem("System.out.println", "function", "System.out.println()", 19, CompletionKind.Function)
            )
            EditorLanguage.Php -> listOf(
                CompletionItem("<?php", "snippet", "<?php\n\n?>", 6, CompletionKind.Snippet)
            )
            EditorLanguage.C, EditorLanguage.Cpp -> listOf(
                CompletionItem("printf", "function", "printf()", 7, CompletionKind.Function),
                CompletionItem("main", "function", "main() {\n    \n}", 7, CompletionKind.Function)
            )
            else -> emptyList()
        }
        val overridden = special.map { it.label }.toSet()
        val plain = Languages.keywords(language)
            .filterNot { it in overridden }
            .map { CompletionItem(it, "keyword", it, it.length, CompletionKind.Keyword) }
        return special + plain
    }

    // ---- Shared helpers ------------------------------------------------------

    private fun insideStringOrComment(language: EditorLanguage, text: String, caret: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        var quote: Char? = null
        var escaped = false
        var i = lineStart
        while (i < caret) {
            val c = text[i]
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
            } else {
                when {
                    c == '"' || c == '\'' || c == '`' -> quote = c
                    c == '/' && i + 1 < caret && text[i + 1] == '/' -> return true
                    c == '#' && Languages.hashComments(language) -> return true
                }
            }
            i++
        }
        return quote != null
    }
}

/** Small, conservative typing assists: bracket/quote pairing, skip-over and newline indentation. */
object SmartEdit {

    private val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')
    private val closers = setOf(')', ']', '}', '"', '\'', '`')

    /** Returns an adjusted value, or null when the default edit should be used unchanged. */
    fun apply(old: TextFieldValue, new: TextFieldValue): TextFieldValue? {
        if (old.text == new.text) return null
        val oldSel = old.selection
        val newSel = new.selection
        if (!oldSel.collapsed || !newSel.collapsed) return null
        if (new.text.length != old.text.length + 1) return null
        val caret = newSel.start
        if (caret != oldSel.start + 1) return null
        if (old.text != new.text.removeRange(caret - 1, caret)) return null
        val inserted = new.text[caret - 1]

        // Typing a closing character that is already there (auto-inserted): step over it.
        if (inserted in closers && old.text.getOrNull(caret - 1) == inserted) {
            return old.copy(selection = TextRange(caret))
        }
        // Newline: copy indentation and add one level after an opening block.
        if (inserted == '\n') {
            val lineStart = old.text.lastIndexOf('\n', caret - 2) + 1
            val currentLine = old.text.substring(lineStart, caret - 1)
            val indent = currentLine.takeWhile { it == ' ' || it == '\t' }
            val previous = old.text.substring(0, caret - 1).trimEnd().lastOrNull()
            val extra = if (previous == '{' || previous == ':' || previous == '(' || previous == '[') "    " else ""
            val insertion = indent + extra
            if (insertion.isNotEmpty()) {
                val text = new.text.substring(0, caret) + insertion + new.text.substring(caret)
                return TextFieldValue(text, TextRange(caret + insertion.length))
            }
            return null
        }
        // Opening bracket/quote: insert its pair and keep the caret between them.
        val close = pairs[inserted] ?: return null
        if (inserted == '"' || inserted == '\'' || inserted == '`') {
            val before = old.text.getOrNull(caret - 2)
            val after = old.text.getOrNull(caret - 1)
            if (before?.isLetterOrDigit() == true || after?.isLetterOrDigit() == true) return null
        }
        val text = new.text.substring(0, caret) + close + new.text.substring(caret)
        return TextFieldValue(text, TextRange(caret))
    }
}
