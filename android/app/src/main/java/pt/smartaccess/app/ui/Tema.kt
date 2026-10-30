package pt.smartaccess.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Azul "seguranca" como cor principal, verde-agua para o que esta aberto/ativo, ambar para o que espera aprovacao
private val Claro = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE4FF),
    onPrimaryContainer = Color(0xFF0B1E5B),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF2EC),
    onSecondaryContainer = Color(0xFF053B36),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8C7),
    onTertiaryContainer = Color(0xFF4A2300),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFFE1DE),
    onErrorContainer = Color(0xFF5F0B0B),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE3E8F2),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEEF2F9),
    surfaceContainerHigh = Color(0xFFE8EDF6),
    surfaceContainerHighest = Color(0xFFE2E8F2),
    outline = Color(0xFF9CA3AF),
    outlineVariant = Color(0xFFD9DEE8),
)

private val Escuro = darkColorScheme(
    primary = Color(0xFF9DB4FF),
    onPrimary = Color(0xFF0B1E5B),
    primaryContainer = Color(0xFF243A8F),
    onPrimaryContainer = Color(0xFFDBE4FF),
    secondary = Color(0xFF6FD8C9),
    onSecondary = Color(0xFF053B36),
    secondaryContainer = Color(0xFF0B4F49),
    onSecondaryContainer = Color(0xFFCCF2EC),
    tertiary = Color(0xFFFBBF6A),
    onTertiary = Color(0xFF4A2300),
    tertiaryContainer = Color(0xFF6B3A06),
    onTertiaryContainer = Color(0xFFFFE8C7),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFFE1DE),
    background = Color(0xFF0E1320),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF0E1320),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF2A3245),
    onSurfaceVariant = Color(0xFFB4BCCB),
    surfaceContainerLowest = Color(0xFF0A0E18),
    surfaceContainerLow = Color(0xFF161C2B),
    surfaceContainer = Color(0xFF1A2131),
    surfaceContainerHigh = Color(0xFF212938),
    surfaceContainerHighest = Color(0xFF283142),
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFF2F3849),
)

private val Formas = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TemaSmartAccess(conteudo: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Escuro else Claro,
        shapes = Formas,
        content = conteudo,
    )
}
