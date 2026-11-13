package dev.moavia.codriod.ext

import androidx.compose.ui.graphics.Color
import dev.moavia.codriod.workspace.CodeColors

/** An editor theme: the colours the built-in code editor (and everything using LocalCodeColors) draws with. */
data class EditorTheme(val id: String, val name: String, val code: CodeColors) {
    val dark get() = code.dark
}

/** Built-in editor themes for the More extensions add-on. They change code colours only — never the app theme. */
object EditorThemes {
    val all = listOf(
        EditorTheme("vs-dark", "Default dark (VS Code)",
            CodeColors(Color(0xFF2D2D30), Color(0xFF094771), Color(0xFF569CD6),
                Color(0xFFCE9178), Color(0xFF6A9955), Color(0xFF89D185), true)),
        EditorTheme("vs-light", "Default light (VS Code)",
            CodeColors(Color(0xFFECECEC), Color(0xFFD6EBFF), Color(0xFF0000FF),
                Color(0xFFA31515), Color(0xFF008000), Color(0xFF137333), false,
                number = Color(0xFF098658), tag = Color(0xFF800000))),
        EditorTheme("dracula", "Dracula",
            CodeColors(Color(0xFF282A36), Color(0xFF44475A), Color(0xFFFF79C6),
                Color(0xFFF1FA8C), Color(0xFF6272A4), Color(0xFF50FA7B), true,
                number = Color(0xFFBD93F9), tag = Color(0xFF50FA7B))),
        EditorTheme("monokai", "Monokai",
            CodeColors(Color(0xFF272822), Color(0xFF49483E), Color(0xFFF92672),
                Color(0xFFE6DB74), Color(0xFF75715E), Color(0xFFA6E22E), true,
                number = Color(0xFFAE81FF), tag = Color(0xFFF92672))),
        EditorTheme("one-dark", "One Dark",
            CodeColors(Color(0xFF282C34), Color(0xFF3E4451), Color(0xFFC678DD),
                Color(0xFF98C379), Color(0xFF5C6370), Color(0xFF98C379), true,
                number = Color(0xFFD19A66), tag = Color(0xFFE5C07B))),
        EditorTheme("solarized-dark", "Solarized Dark",
            CodeColors(Color(0xFF002B36), Color(0xFF073642), Color(0xFF859900),
                Color(0xFF2AA198), Color(0xFF586E75), Color(0xFF859900), true,
                number = Color(0xFFD33682), tag = Color(0xFF268BD2))),
        EditorTheme("solarized-light", "Solarized Light",
            CodeColors(Color(0xFFFDF6E3), Color(0xFFEEE8D5), Color(0xFF859900),
                Color(0xFF2AA198), Color(0xFF93A1A1), Color(0xFF859900), false,
                number = Color(0xFFD33682), tag = Color(0xFF268BD2))),
        EditorTheme("github-light", "GitHub Light",
            CodeColors(Color(0xFFFFFFFF), Color(0xFFD6EBFF), Color(0xFFD73A49),
                Color(0xFF032F62), Color(0xFF6A737D), Color(0xFF22863A), false,
                number = Color(0xFF005CC5), tag = Color(0xFF22863A))))

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    /** Sample tokens for the preview, (text, which colour to use). */
    val sample = listOf(
        "// preview" to "comment",
        "fun " to "keyword", "main" to "text", "() {" to "text",
        "val " to "keyword", "n" to "text", " = " to "text", "42" to "number",
        "println(" to "text", "\"hello\"" to "string", ")" to "text", "  // done" to "comment",
        "\n" to "text",
        "return " to "keyword", "n > " to "text", "0" to "number", "  " to "text", "// ok" to "comment")
}
