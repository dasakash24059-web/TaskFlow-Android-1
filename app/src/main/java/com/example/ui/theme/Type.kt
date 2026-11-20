package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Single canonical Material 3 typography system for TaskFlow.
 * Uses native Android Roboto Sans-serif across Normal, Medium, and SemiBold weights.
 */
val TaskFlowFontFamily: FontFamily = FontFamily.SansSerif

val TaskFlowTypography =
  Typography(
    displayLarge =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.25).sp
      ),
    displayMedium =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.15).sp
      ),
    displaySmall =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.1).sp
      ),
    headlineLarge =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.15).sp
      ),
    headlineMedium =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp
      ),
    headlineSmall =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
      ),
    titleLarge =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp
      ),
    titleMedium =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
      ),
    titleSmall =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
      ),
    bodyLarge =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
      ),
    bodyMedium =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp
      ),
    bodySmall =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp
      ),
    labelLarge =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
      ),
    labelMedium =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.15.sp
      ),
    labelSmall =
      TextStyle(
        fontFamily = TaskFlowFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp
      )
  )
