package ru.vlad.triliumnotes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Dark = darkColorScheme(
    primary = Color(0xFFBCA0F7), onPrimary = Color(0xFF281940),
    primaryContainer = Color(0xFF342741), onPrimaryContainer = Color(0xFFE7D8FF),
    background = Color(0xFF16151B), onBackground = Color(0xFFEAE6F0),
    surface = Color(0xFF16151B), onSurface = Color(0xFFEAE6F0),
    surfaceVariant = Color(0xFF26232D), onSurfaceVariant = Color(0xFFACA6B7),
    outline = Color(0xFF494250), outlineVariant = Color(0xFF302C38),
)
private val Light = lightColorScheme(
    primary = Color(0xFF7251A5), onPrimary = Color.White,
    primaryContainer = Color(0xFFEBE1F6), onPrimaryContainer = Color(0xFF35224B),
    background = Color(0xFFFAF9FC), onBackground = Color(0xFF29252E),
    surface = Color(0xFFFAF9FC), onSurface = Color(0xFF29252E),
    surfaceVariant = Color(0xFFEFEBF3), onSurfaceVariant = Color(0xFF746C7E),
    outline = Color(0xFFB0A6BA), outlineVariant = Color(0xFFE8E2ED),
)

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 31.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 27.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
        ), content = content,
    )
}
