package com.pravahax.portalx.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pravahax.portalx.core.ui.R

/*
 * Design tokens copied from the web portal stylesheet (https://portal.pravahax.com/assets/app-CR0H9OFW.css).
 * The web remaps Tailwind's `slate` to a warm parchment/umber scale and `indigo` to the brand gold.
 */
object Web {
    // --portal-*
    val Primary = Color(0xFFB8832B)        // --portal-primary / --portal-brand-primary
    val Secondary = Color(0xFFC8953C)      // --portal-secondary / --portal-brand-secondary
    val PrimarySoft = Color(0xFFF4EADB)    // --portal-primary-soft
    val SecondarySoft = Color(0xFFF7EDE0)  // --portal-secondary-soft
    // --color-slate-* (warm)
    val Slate50 = Color(0xFFFBFAF7); val Slate100 = Color(0xFFF4F0E7); val Slate200 = Color(0xFFE7DFD0)
    val Slate300 = Color(0xFFD2C4AC); val Slate400 = Color(0xFFAE9D83); val Slate500 = Color(0xFF8D7B62)
    val Slate600 = Color(0xFF6F5E4A); val Slate700 = Color(0xFF534636); val Slate800 = Color(0xFF352C24)
    val Slate900 = Color(0xFF282018); val Slate950 = Color(0xFF17120E)
    // --color-indigo-* (gold)
    val Gold50 = Color(0xFFFDF8ED); val Gold100 = Color(0xFFF8EDCF); val Gold200 = Color(0xFFEFD9A4)
    val Gold300 = Color(0xFFDFBB6A); val Gold400 = Color(0xFFCF9E42); val Gold500 = Color(0xFFBD882E)
    val Gold600 = Color(0xFFAD7824); val Gold700 = Color(0xFF8D5F20); val Gold800 = Color(0xFF70491B)
    val Gold900 = Color(0xFF563715); val Gold950 = Color(0xFF30200F)
    // brand panels (.platform-login-intro, .platform-registry-hero)
    val HeroTop = Color(0xFF241A10); val HeroBottom = Color(0xFF100D0A)
    val EyebrowOnDark = Color(0xFFE0BD76); val EyebrowOnLight = Color(0xFF9B6C1C)
    val ShadowInk = Color(0xFF15110D)      // --shadow-soft / --shadow-lifted tint
    val Danger = Color(0xFFB42318)          // .platform-danger-action
    val DangerSoft = Color(0xFFFFF5F4)
    // status colours: Tailwind v4 oklch tokens converted to sRGB
    val Emerald600 = Color(0xFF009966); val Emerald50 = Color(0xFFECFDF5); val Green700 = Color(0xFF008236); val Green500 = Color(0xFF00C950)
    val Amber600 = Color(0xFFE17100); val Amber700 = Color(0xFFBB4D00); val Amber50 = Color(0xFFFFFBEB); val Amber500 = Color(0xFFFE9A00)
    val Red600 = Color(0xFFE7000B); val Red700 = Color(0xFFC10007); val Red50 = Color(0xFFFEF2F2); val Red500 = Color(0xFFFB2C36)
    val Blue700 = Color(0xFF1447E6); val Blue50 = Color(0xFFEFF6FF); val Blue200 = Color(0xFFBEDBFF)
    val Purple700 = Color(0xFF8200DB); val Purple50 = Color(0xFFFAF5FF); val Purple500 = Color(0xFFAD46FF)
}

// Back-compat aliases used across screens.
val Gold = Web.Primary
val GoldLight = Web.Secondary
val Ink = Web.Slate950
/** Gold fill behind WHITE text: indigo-700 → indigo-600 keeps every pixel ≥ 3.8:1 (large/bold text) — see Action. */
val GoldBrush = Brush.linearGradient(listOf(Web.Gold700, Web.Gold600))
/** Decorative gold (no text on it). */
val GoldAccentBrush = Brush.linearGradient(listOf(Web.Gold600, Web.Gold400))
/** Light brand panel: the web's login wrap — parchment with a gold radial glow (radial-gradient(circle at 100% 0, #be882e1f, transparent 42%), #fbfaf7). */
val GlowGold = Color(0x1FBE882E)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** --font-sans: "Manrope" (variable, wght 200–800). */
val Manrope = FontFamily(
    variable(R.font.manrope, 400), variable(R.font.manrope, 500), variable(R.font.manrope, 600),
    variable(R.font.manrope, 700), variable(R.font.manrope, 800)
)
/** --font-display / --font-serif: "Playfair Display" (variable, wght 400–900). */
val Playfair = FontFamily(
    variable(R.font.playfair, 400), variable(R.font.playfair, 500), variable(R.font.playfair, 600), variable(R.font.playfair, 700)
)

// Web type scale (Tailwind v4): xs 12, sm 14, base 16, lg 18, xl 20, 2xl 24, 3xl 30, 4xl 36 (1rem = 16px → 16sp).
// How the web uses each font (verified in the 2026-10 bundle):
//  • Page titles      "font-display text-3xl font-semibold tracking-tight text-slate-900"  → headlineLarge (Playfair 600, 30/36, -0.025em)
//  • Card/section h2  "font-display text-2xl font-semibold"                                → headlineMedium (Playfair 600, 24/32)
//  • Card/section h3  "font-display text-lg font-semibold text-slate-900"                  → headlineSmall (Playfair 600, 18/28)
//  • Everything else  body { font-family: var(--font-sans) } = Manrope, colour slate-800; buttons "text-sm font-semibold".
//  • Kickers          "text-xs font-semibold uppercase tracking-[0.24em] text-indigo-600"  → Eyebrow (Manrope 600, 12sp, 0.24em)
private val tight = (-0.025).em
val PortalType = Typography(
    displayLarge = TextStyle(fontFamily = Playfair, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = tight),
    displayMedium = TextStyle(fontFamily = Playfair, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = tight),
    displaySmall = TextStyle(fontFamily = Playfair, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = tight),
    headlineLarge = TextStyle(fontFamily = Playfair, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = tight),
    headlineMedium = TextStyle(fontFamily = Playfair, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Playfair, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.05.em),
)

/** Uppercase kicker: the web's `text-xs font-semibold uppercase tracking-[0.24em]` labels. */
val Eyebrow = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.24.em)

// Web radii: --radius-md 6, --radius-lg 8, --radius-xl 16, --radius-2xl 22 (rem→dp at 16px), hero 24.
val PortalShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** 4dp spacing scale (web --spacing: .25rem). */
object Space { val xxs = 2.dp; val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 20.dp; val xxl = 24.dp; val xxxl = 32.dp }

/*
 * Light only. The web has no dark theme (no prefers-color-scheme / .dark rules in app-CR0H9OFW.css), so the app
 * ignores the system dark setting entirely (v0.3.0). Every colour below is a web token.
 *
 * WCAG AA: the web's filled buttons are white on indigo-600 #AD7824 (3.8:1) and its kickers are indigo-600 on
 * parchment (3.7:1), both under the 4.5:1 AA minimum for normal text. The app therefore uses the web's own
 * hover/pressed token indigo-700 #8D5F20 (5.5:1 with white, 5.3:1 on parchment) wherever gold carries TEXT,
 * and keeps #B8832B / #AD7824 for non-text accents (icons, rings, gradients), where 3:1 is the bar.
 */
val Action = Web.Gold700
private val LightColors = lightColorScheme(
    primary = Action, onPrimary = Color.White, primaryContainer = Web.PrimarySoft, onPrimaryContainer = Web.Gold900,
    inversePrimary = Web.Gold300,
    secondary = Web.Primary, onSecondary = Color.White, secondaryContainer = Web.SecondarySoft, onSecondaryContainer = Web.Gold900,
    tertiary = Web.Green700, onTertiary = Color.White, tertiaryContainer = Web.Emerald50, onTertiaryContainer = Web.Green700,
    background = Web.Slate50, onBackground = Web.Slate900,
    surface = Color.White, onSurface = Web.Slate800, surfaceVariant = Web.Slate100, onSurfaceVariant = Web.Slate600,
    surfaceTint = Color.Transparent, inverseSurface = Web.Slate900, inverseOnSurface = Web.Slate100,
    surfaceBright = Color.White, surfaceDim = Web.Slate100,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Web.Slate50, surfaceContainer = Color.White,
    surfaceContainerHigh = Web.Slate100, surfaceContainerHighest = Web.Slate200,
    outline = Web.Slate300, outlineVariant = Web.Slate200, scrim = Web.Slate950,
    error = Web.Danger, onError = Color.White, errorContainer = Web.DangerSoft, onErrorContainer = Web.Red700,
)

/** Semantic status palette (chips, dots). Text-safe shades: each is ≥ 4.5:1 on white and on its own 10 % tint. */
@Immutable
data class StatusPalette(val success: Color, val warning: Color, val danger: Color, val info: Color, val accent: Color, val neutral: Color, val eyebrow: Color)
private val LightStatus = StatusPalette(Web.Green700, Web.Amber700, Web.Red700, Web.Blue700, Web.Purple700, Web.Slate600, Action)
val LocalStatus = staticCompositionLocalOf { LightStatus }

object PortalTheme {
    val status: StatusPalette @Composable @ReadOnlyComposable get() = LocalStatus.current
}

/** Always the light palette, whatever the system setting. */
@Composable
fun PortalTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatus provides LightStatus) {
        MaterialTheme(colorScheme = LightColors, typography = PortalType, shapes = PortalShapes, content = content)
    }
}
