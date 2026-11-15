package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.theme.PresetTheme

val HezamThemePreset by lazy {
    PresetTheme(
        id = "hezam",
        name = {
            Text("Hezam")
        },
        standardLight = BlackThemePreset.standardLight.copy(
            primary = Color(0xFF5B4BE0),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE9E9EE),
            onPrimaryContainer = Color(0xFF1B1B1F),
            secondary = Color(0xFF3B74E0),
            onSecondary = Color(0xFFFFFFFF),
            tertiary = Color(0xFF9333EA),
            onTertiary = Color(0xFFFFFFFF),
        ),
        standardDark = BlackThemePreset.standardDark.copy(
            primary = Color(0xFF9B8CFF),
            onPrimary = Color(0xFF0B0B10),
            primaryContainer = Color(0xFF26262A),
            onPrimaryContainer = Color(0xFFF2F2F5),
            secondary = Color(0xFF6AA8FF),
            onSecondary = Color(0xFF0B0B10),
            secondaryContainer = Color(0xFF1E2238),
            onSecondaryContainer = Color(0xFFE6E8FF),
            tertiary = Color(0xFFC084FC),
            onTertiary = Color(0xFF0B0B10),
            tertiaryContainer = Color(0xFF2A1E3D),
            onTertiaryContainer = Color(0xFFEBDDFF),
            background = Color(0xFF000000),
            onBackground = Color(0xFFF5F5F7),
            surface = Color(0xFF000000),
            onSurface = Color(0xFFF5F5F7),
            surfaceVariant = Color(0xFF232326),
            onSurfaceVariant = Color(0xFFA6A6AD),
            outline = Color(0xFF5C5C63),
            outlineVariant = Color(0xFF2E2E33),
            surfaceDim = Color(0xFF000000),
            surfaceBright = Color(0xFF2A2A2E),
            surfaceContainerLowest = Color(0xFF000000),
            surfaceContainerLow = Color(0xFF0D0D0F),
            surfaceContainer = Color(0xFF141416),
            surfaceContainerHigh = Color(0xFF1D1D20),
            surfaceContainerHighest = Color(0xFF28282C),
        ),
    )
}
