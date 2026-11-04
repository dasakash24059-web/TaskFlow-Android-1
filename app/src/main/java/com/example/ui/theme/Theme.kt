package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = Emerald400,
    onPrimary = Navy950,
    primaryContainer = Emerald900,
    onPrimaryContainer = Emerald400,
    secondary = Cyan400,
    onSecondary = Navy950,
    secondaryContainer = Cyan900,
    onSecondaryContainer = Cyan400,
    tertiary = Amber400,
    onTertiary = Navy950,
    tertiaryContainer = Amber900,
    onTertiaryContainer = Amber400,
    error = Rose400,
    onError = Navy950,
    errorContainer = Rose900,
    onErrorContainer = Rose400,
    background = Navy950,
    onBackground = Slate50,
    surface = Navy900,
    onSurface = Slate50,
    surfaceVariant = Navy800,
    onSurfaceVariant = Slate400,
    outline = Slate600
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    error = Color(0xFFE11D48),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    outline = Slate400
  )

@Composable
fun TaskFlowTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
