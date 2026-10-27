package com.noxvault.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = NoxAccent,
    onPrimary = NoxOnAccentDark,
    primaryContainer = NoxAccentContainerDark,
    onPrimaryContainer = NoxAccent,
    secondary = NoxTextMutedDark,
    onSecondary = NoxTextPrimaryDark,
    secondaryContainer = NoxCardElevatedDark,
    onSecondaryContainer = NoxTextPrimaryDark,
    background = NoxBackgroundDark,
    onBackground = NoxTextPrimaryDark,
    surface = NoxSurfaceDark,
    onSurface = NoxTextPrimaryDark,
    surfaceVariant = NoxCardDark,
    onSurfaceVariant = NoxTextMutedDark,
    surfaceContainer = NoxCardDark,
    surfaceContainerHigh = NoxCardElevatedDark,
    outline = NoxOutlineDark,
    outlineVariant = NoxOutlineDark,
    error = NoxError,
    onError = NoxBackgroundDark
)

private val LightColors = lightColorScheme(
    primary = NoxAccentLight,
    onPrimary = NoxSurfaceLight,
    primaryContainer = NoxAccentContainerLight,
    onPrimaryContainer = NoxAccentLight,
    secondary = NoxTextMutedLight,
    onSecondary = NoxSurfaceLight,
    secondaryContainer = NoxCardElevatedLight,
    onSecondaryContainer = NoxTextPrimaryLight,
    background = NoxBackgroundLight,
    onBackground = NoxTextPrimaryLight,
    surface = NoxSurfaceLight,
    onSurface = NoxTextPrimaryLight,
    surfaceVariant = NoxCardElevatedLight,
    onSurfaceVariant = NoxTextMutedLight,
    surfaceContainer = NoxCardLight,
    surfaceContainerHigh = NoxCardElevatedLight,
    outline = NoxOutlineLight,
    outlineVariant = NoxOutlineLight,
    error = NoxError,
    onError = NoxSurfaceLight
)

private val NoxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val NoxTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 38.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp, lineHeight = 27.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 23.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp, lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp
    )
)

@Composable
fun NOXVaultTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NoxTypography,
        shapes = NoxShapes,
        content = content
    )
}
