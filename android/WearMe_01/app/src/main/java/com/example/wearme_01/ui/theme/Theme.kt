package com.example.wearme_01.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = MoonMilk,
    onPrimary = DeepPastureGreen,
    primaryContainer = PastureGreen,
    onPrimaryContainer = CloudWhite,
    secondary = DewGreen,
    onSecondary = DeepPastureGreen,
    secondaryContainer = NightGrass,
    onSecondaryContainer = MoonMilk,
    tertiary = BarnRed,
    onTertiary = CloudWhite,
    tertiaryContainer = SoilBrown,
    onTertiaryContainer = MistWhite,
    background = NightField,
    onBackground = MistWhite,
    surface = NightGrass,
    onSurface = MistWhite,
    surfaceVariant = DeepPastureGreen,
    onSurfaceVariant = DewGreen,
    outline = LeafOutline
)

private val LightColorScheme = lightColorScheme(
    primary = PastureGreen,
    onPrimary = CloudWhite,
    primaryContainer = DewGreen,
    onPrimaryContainer = DeepPastureGreen,
    secondary = MeadowGreen,
    onSecondary = CloudWhite,
    secondaryContainer = SageSurface,
    onSecondaryContainer = DeepPastureGreen,
    tertiary = BarnRed,
    onTertiary = CloudWhite,
    tertiaryContainer = Color(0xFFF4DFD7),
    onTertiaryContainer = Color(0xFF4B2418),
    background = MistWhite,
    onBackground = ForestInk,
    surface = CloudWhite,
    onSurface = ForestInk,
    surfaceVariant = SageSurface,
    onSurfaceVariant = Color(0xFF4C6046),
    outline = LeafOutline
)

@Composable
fun WearMe_01Theme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
