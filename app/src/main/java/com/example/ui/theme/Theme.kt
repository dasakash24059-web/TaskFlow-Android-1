package com.example.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val title: String, val description: String) {
  SYSTEM_DEFAULT("System Default", "Follows device appearance settings automatically"),
  LIGHT("Light Mode", "Clean neutral bright appearance with subtle gold accents"),
  DARK("Dark Mode", "Deep charcoal and navy surfaces for low-light comfort")
}

object ThemePreferences {
  private const val PREFS_NAME = "taskflow_theme_prefs"
  private const val KEY_THEME_MODE = "app_theme_mode"

  private val _currentThemeMode = MutableStateFlow(AppThemeMode.LIGHT)
  val currentThemeMode: StateFlow<AppThemeMode> = _currentThemeMode.asStateFlow()

  fun init(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val savedName = prefs.getString(KEY_THEME_MODE, AppThemeMode.LIGHT.name)
    val mode =
      try {
        AppThemeMode.valueOf(savedName ?: AppThemeMode.LIGHT.name)
      } catch (e: Exception) {
        AppThemeMode.LIGHT
      }
    _currentThemeMode.value = mode
  }

  fun setThemeMode(context: Context, mode: AppThemeMode) {
    _currentThemeMode.value = mode
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
  }
}

/**
 * Semantic Light/Dark adaptive visual tokens for restrained gold/amber CTAs,
 * subtle champagne-gold reward badges, and neutral confirmation surfaces.
 */
@Immutable
data class TaskFlowSemanticColors(
  val isDark: Boolean,
  val claimContainer: Color,
  val claimContent: Color,
  val charcoalContainer: Color,
  val charcoalContent: Color,
  val rewardBadgeBg: Color,
  val rewardBadgeBorder: Color,
  val rewardBadgeText: Color,
  val goldAccent: Color,
  val goldText: Color,
  val statusConfirmedBg: Color,
  val statusConfirmedBorder: Color,
  val statusConfirmedFg: Color,
  val neutralSurfaceBg: Color,
  val neutralSurfaceBorder: Color,
  val neutralSurfaceFg: Color,
  val debitText: Color
)

private val LightSemanticColors =
  TaskFlowSemanticColors(
    isDark = false,
    claimContainer = GoldAccentLight,
    claimContent = Color.White,
    charcoalContainer = CharcoalLight,
    charcoalContent = Color.White,
    rewardBadgeBg = ChampagneSurfaceLight,
    rewardBadgeBorder = ChampagneBorderLight,
    rewardBadgeText = ChampagneOnSurfaceLight,
    goldAccent = GoldAccentLight,
    goldText = GoldTextLight,
    statusConfirmedBg = SubtleStatusBgLight,
    statusConfirmedBorder = SubtleStatusBorderLight,
    statusConfirmedFg = SubtleStatusFgLight,
    neutralSurfaceBg = NeutralChipBgLight,
    neutralSurfaceBorder = NeutralChipBorderLight,
    neutralSurfaceFg = NeutralChipFgLight,
    debitText = Color(0xFFDC2626)
  )

private val DarkSemanticColors =
  TaskFlowSemanticColors(
    isDark = true,
    claimContainer = GoldAccentDark,
    claimContent = Slate900,
    charcoalContainer = CharcoalDark,
    charcoalContent = Slate900,
    rewardBadgeBg = ChampagneSurfaceDark,
    rewardBadgeBorder = ChampagneBorderDark,
    rewardBadgeText = ChampagneOnSurfaceDark,
    goldAccent = GoldAccentDark,
    goldText = GoldTextDark,
    statusConfirmedBg = SubtleStatusBgDark,
    statusConfirmedBorder = SubtleStatusBorderDark,
    statusConfirmedFg = SubtleStatusFgDark,
    neutralSurfaceBg = NeutralChipBgDark,
    neutralSurfaceBorder = NeutralChipBorderDark,
    neutralSurfaceFg = NeutralChipFgDark,
    debitText = Color(0xFFF87171)
  )

/**
 * Resolves Light/Dark semantic tokens directly from the active MaterialTheme colorScheme,
 * ensuring consistent contrast even in previews or tests wrapped with MaterialTheme directly.
 */
@Composable
fun taskFlowColors(): TaskFlowSemanticColors {
  val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  return if (isDark) DarkSemanticColors else LightSemanticColors
}

// TaskFlow Dark Theme — Deep Navy, Neutral Slate & Warm Champagne Gold
private val DarkColorScheme =
  darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = GoldAccentDark,
    onTertiary = Color(0xFF0F172A),
    tertiaryContainer = ChampagneSurfaceDark,
    onTertiaryContainer = ChampagneOnSurfaceDark,
    background = NavyBgDark,
    onBackground = Slate50,
    surface = NavySurfaceDark,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    error = Color(0xFFF87171),
    onError = Color(0xFF0F172A),
    errorContainer = Color(0xFF451A1A),
    onErrorContainer = Color(0xFFFECACA),
    outline = Slate700,
    outlineVariant = Slate800
  )

// TaskFlow Light Theme (Default) — Deep Navy, Clean White & Restrained Gold/Slate
private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF334155),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = GoldAccentLight,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = ChampagneSurfaceLight,
    onTertiaryContainer = ChampagneOnSurfaceLight,
    background = Slate50,
    onBackground = Slate900,
    surface = Color(0xFFFFFFFF),
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    outline = Slate300,
    outlineVariant = Slate200
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
