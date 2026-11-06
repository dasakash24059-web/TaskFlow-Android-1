package com.example.ui.theme

import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val title: String, val description: String) {
  SYSTEM_DEFAULT("System Default", "Follows device appearance settings automatically"),
  LIGHT("Light Mode", "Clean purple-and-white bright appearance"),
  DARK("Dark Mode", "Deep violet and dark surface for low-light comfort")
}

object ThemePreferences {
  private const val PREFS_NAME = "taskflow_theme_prefs"
  private const val KEY_THEME_MODE = "app_theme_mode"

  private val _currentThemeMode = MutableStateFlow(AppThemeMode.SYSTEM_DEFAULT)
  val currentThemeMode: StateFlow<AppThemeMode> = _currentThemeMode.asStateFlow()

  fun init(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val savedName = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM_DEFAULT.name)
    val mode =
      try {
        AppThemeMode.valueOf(savedName ?: AppThemeMode.SYSTEM_DEFAULT.name)
      } catch (e: Exception) {
        AppThemeMode.SYSTEM_DEFAULT
      }
    _currentThemeMode.value = mode
  }

  fun setThemeMode(context: Context, mode: AppThemeMode) {
    _currentThemeMode.value = mode
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
  }
}

// TaskFlow Professional Purple-and-White Palette
private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF6D28D9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF2E1065),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E8FF),
    onSecondaryContainer = Color(0xFF3B0764),
    tertiary = Color(0xFF059669),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    background = Color(0xFFF8F7FF),
    onBackground = Color(0xFF1E1B4B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E1B4B),
    surfaceVariant = Color(0xFFF3F0FF),
    onSurfaceVariant = Color(0xFF4C1D95),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFFC4B5FD)
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF4C1D95),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFFC4B5FD),
    onSecondary = Color(0xFF2E1065),
    secondaryContainer = Color(0xFF3B0764),
    onSecondaryContainer = Color(0xFFF3E8FF),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF064E3B),
    tertiaryContainer = Color(0xFF065F46),
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF0F0B1E),
    onBackground = Color(0xFFF5F3FF),
    surface = Color(0xFF1A1433),
    onSurface = Color(0xFFF5F3FF),
    surfaceVariant = Color(0xFF281F4D),
    onSurfaceVariant = Color(0xFFDDD6FE),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    outline = Color(0xFF5B21B6)
  )

val TaskFlowTypography =
  Typography(
    displayLarge =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
      ),
    headlineLarge =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp
      ),
    headlineMedium =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 28.sp
      ),
    titleLarge =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
      ),
    titleMedium =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
      ),
    bodyLarge =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
      ),
    bodyMedium =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
      ),
    labelLarge =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
      ),
    labelSmall =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
      )
  )

@Composable
fun TaskFlowTheme(
  themeMode: AppThemeMode = AppThemeMode.SYSTEM_DEFAULT,
  content: @Composable () -> Unit
) {
  val isDark =
    when (themeMode) {
      AppThemeMode.LIGHT -> false
      AppThemeMode.DARK -> true
      AppThemeMode.SYSTEM_DEFAULT -> isSystemInDarkTheme()
    }

  val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
  MaterialTheme(
    colorScheme = colorScheme,
    typography = TaskFlowTypography,
    content = content
  )
}
