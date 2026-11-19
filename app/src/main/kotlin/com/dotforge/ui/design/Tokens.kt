package com.dotforge.ui.design

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dotforge.R

/**
 * Design tokens from the production design ("Design system"). Screens use these and the M3 type roles
 * (MaterialTheme.typography.*), never raw colours, dp or sp.
 */
object Tokens {
    // colours
    val Background = Color(0xFF000000)
    val Card = Color(0xFF141414)
    val Sheet = Color(0xFF121212)
    /** Chips, buttons and rows inside a card or sheet. */
    val Raised = Color(0xFF1F1F1F)
    val Text = Color(0xFFFFFFFF)
    val Secondary = Color(0xFFA3A3A3)
    val Hairline = Color(0xFF2A2A2A)
    /** An LED that is off: 20% grey. */
    val DotOff = Color(0xFF333333)
    /** Only for the main button, switches that are on, and destructive actions. */
    val Accent = Color(0xFFD71921)
    /** A permission's status dot: allowed (green), needed (the accent red), optional (grey). Always beside a word. */
    val Allowed = Color(0xFF3DBE6E)
    /** The accent where it is small text on black (#D71921 is too dark for 14 sp). */
    val AccentText = Color(0xFFFF4B55)

    // spacing (4 dp steps)
    val Tiny = 4.dp
    val Small = 8.dp
    val Gap = 12.dp
    val Gutter = 16.dp
    val Section = 24.dp

    // shapes
    val CardRadius = 20.dp
    val SheetRadius = 28.dp
    val CardShape = RoundedCornerShape(CardRadius)
    val SheetShape = RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius)

    // sizes
    val Target = 48.dp
    val Icon = 24.dp
    val Row = 64.dp
    val RowTall = 72.dp
    val OptionRow = 76.dp
    val Tile = 104.dp
    val FeatureCard = 120.dp
    val BottomBar = 80.dp
    val Avatar = 40.dp
    val PreviewSmall = 40.dp
    val PreviewMedium = 96.dp
    val PreviewLarge = 200.dp

    // motion
    const val StandardMs = 200
    const val SheetMs = 300
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** The standard 200 ms transition, or none when the system's animations are off. */
    fun <T> standard(reduce: Boolean): FiniteAnimationSpec<T> = if (reduce) snap() else tween(StandardMs, easing = StandardEasing)
}

/** True when the system's "Remove animations" is on (animator duration scale 0). */
val LocalReduceMotion = staticCompositionLocalOf { false }

fun reduceMotion(ctx: Context): Boolean =
    runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

private fun scheme() = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    tertiary = Tokens.Accent,
    background = Tokens.Background,
    onBackground = Tokens.Text,
    surface = Tokens.Background,
    onSurface = Tokens.Text,
    surfaceVariant = Tokens.Raised,
    onSurfaceVariant = Tokens.Secondary,
    surfaceContainerLowest = Tokens.Background,
    surfaceContainerLow = Tokens.Sheet,
    surfaceContainer = Tokens.Card,
    surfaceContainerHigh = Tokens.Raised,
    surfaceContainerHighest = Tokens.Raised,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color.White,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    outline = Color(0xFF8A8A8A),
    outlineVariant = Tokens.Hairline,
    error = Tokens.AccentText,
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) =
    Font(R.font.inter, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

@OptIn(ExperimentalTextApi::class)
private fun doto(weight: Int) =
    Font(R.font.doto, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Inter (OFL): body, labels, buttons. */
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** Doto (OFL), the dot-matrix display font: titles and big numbers only. */
val Doto = FontFamily(doto(700), doto(900))

/**
 * M3 type roles: Doto for display and headline (titles, big numbers), Inter for the rest. Body 16 sp, secondary
 * 14 sp, labels 12 sp, section titles 22 sp.
 */
val AppTypography: Typography = Typography().let { t ->
    fun TextStyle.inter() = copy(fontFamily = Inter)
    fun TextStyle.doto() = copy(fontFamily = Doto, fontWeight = FontWeight(900))
    t.copy(
        displayLarge = t.displayLarge.doto(),
        displayMedium = t.displayMedium.doto(),
        displaySmall = t.displaySmall.doto(),
        headlineLarge = t.headlineLarge.doto().copy(fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = t.headlineMedium.doto().copy(fontSize = 26.sp, lineHeight = 34.sp),
        headlineSmall = t.headlineSmall.inter().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.inter().copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.inter().copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        titleSmall = t.titleSmall.inter().copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = t.bodyLarge.inter().copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = t.bodyMedium.inter().copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.inter().copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = t.labelLarge.inter().copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.inter().copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = t.labelSmall.inter().copy(fontSize = 11.sp, lineHeight = 16.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(Tokens.CardRadius),
    extraLarge = RoundedCornerShape(Tokens.SheetRadius),
)

/** The app theme: tokens, type roles, shapes, and the reduce-motion flag. */
@Composable
fun DotForgeTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val reduce = remember { reduceMotion(ctx) }
    CompositionLocalProvider(LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = scheme(), typography = AppTypography, shapes = AppShapes) {
            // text outside a Surface (pages, sheets' own rows) is white on black too
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Tokens.Text, content = content)
        }
    }
}
