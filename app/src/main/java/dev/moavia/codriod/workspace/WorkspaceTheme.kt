package dev.moavia.codriod.workspace

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.ext.EditorTheme

@Immutable
data class CodeColors(val header: Color, val selection: Color, val keyword: Color,
    val string: Color, val comment: Color, val success: Color, val dark: Boolean,
    val number: Color = Color(0xFFB5CEA8), val tag: Color = Color(0xFF569CD6))

val LocalCodeColors = staticCompositionLocalOf {
    CodeColors(Color(0xFF2D2D30), Color(0xFF094771), Color(0xFF569CD6),
        Color(0xFFCE9178), Color(0xFF6A9955), Color(0xFF89D185), true)
}

@Composable
fun WorkspaceTheme(mode: String = "System", editorTheme: EditorTheme? = null, content: @Composable () -> Unit) {
    val dark = when (mode) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF007ACC), onPrimary = Color.White,
        primaryContainer = Color(0xFF094771), onPrimaryContainer = Color.White,
        secondary = Color(0xFF9CDCFE), onSecondary = Color(0xFF1E1E1E),
        background = Color(0xFF1E1E1E), onBackground = Color(0xFFD4D4D4),
        surface = Color(0xFF252526), onSurface = Color(0xFFD4D4D4),
        surfaceVariant = Color(0xFF333333), onSurfaceVariant = Color(0xFFB3B3B3),
        outline = Color(0xFF707070), outlineVariant = Color(0xFF3C3C3C),
        error = Color(0xFFFF8585), surfaceContainer = Color(0xFF2D2D30)
    ) else lightColorScheme(
        primary = Color(0xFF007ACC), onPrimary = Color.White,
        primaryContainer = Color(0xFFD6EBFF), onPrimaryContainer = Color(0xFF003B63),
        secondary = Color(0xFF005A9E), onSecondary = Color.White,
        background = Color.White, onBackground = Color(0xFF333333),
        surface = Color(0xFFF3F3F3), onSurface = Color(0xFF333333),
        surfaceVariant = Color(0xFFE5E5E5), onSurfaceVariant = Color(0xFF616161),
        outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFFD4D4D4),
        error = Color(0xFFB00020), surfaceContainer = Color(0xFFECECEC)
    )
    val code = editorTheme?.code ?: if (dark) LocalCodeColors.current else CodeColors(
        Color(0xFFECECEC), Color(0xFFD6EBFF), Color(0xFF0000FF),
        Color(0xFFA31515), Color(0xFF008000), Color(0xFF137333), false,
        number = Color(0xFF098658), tag = Color(0xFF800000))
    val type = Typography(
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
            fontSize = 26.sp, letterSpacing = (-0.6).sp),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
        labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    )
    CompositionLocalProvider(LocalCodeColors provides code) {
        MaterialTheme(colorScheme = colors, typography = type,
            shapes = Shapes(extraSmall = RoundedCornerShape(3.dp), small = RoundedCornerShape(5.dp),
                medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp)),
            content = content)
    }
}
