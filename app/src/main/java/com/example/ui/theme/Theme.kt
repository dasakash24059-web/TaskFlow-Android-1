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

// TaskFlow Premium Neon Glassmorphism Dark Theme (Default & Primary Vibe)
private val DarkColorScheme =
  darkColorScheme(
    primary = Color(0xFFA855F7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3B0764),
    onPrimaryContainer = Color(0xFFF3E8FF),
    secondary = Color(0xFF06B6D4),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF164E63),
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = Color(0xFFEC4899),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF831843),
    onTertiaryContainer = Color(0xFFFCE7F3),
    background = Color(0xFF070A17),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF0F1428),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF181F3B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFF43F5E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF881337),
    onErrorContainer = Color(0xFFFFE4E6),
    outline = Color(0x60A855F7)
  )

private val LightColorScheme = DarkColorScheme // Default to rich Neon Dark palette for cohesive aesthetic


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
