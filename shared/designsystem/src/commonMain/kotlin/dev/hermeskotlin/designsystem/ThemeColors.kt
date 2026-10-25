package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The Herald "modern" palette: graphite surfaces in dark, cool off-white in light. Surfaces step up in
 * lightness (surface → surface 2 → surface 3) instead of casting shadows, and hairlines are translucent.
 * Kept apart from HermesTheme.kt, which loads the fonts, so tests can read the colors without Android resources.
 */
internal object Palette {
    val Bg = Color(0xFF0A0A0C)
    val Surface = Color(0xFF16161A)
    val Surface2 = Color(0xFF1C1C22)
    val Surface3 = Color(0xFF232329)
    val Well = Color(0xFF121215)
    val Thumb = Color(0xFF2C2C33)
    val Sheet = Color(0xFF18181D)
    val Line = Color.White.copy(alpha = 0.07f)
    val LineStrong = Color.White.copy(alpha = 0.12f)
    val Text = Color(0xFFF4F4F6)
    val Text2 = Color(0xFFD4D4D8)
    val Text4 = Color(0xFF8A8A94)
    val Text5 = Color(0xFF6B6B75)

    val LightBg = Color(0xFFF7F7F9)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurface2 = Color(0xFFF1F1F5)
    val LightSurface3 = Color(0xFFE9E9EF)
    val LightWell = Color(0xFFF3F3F7)
    val LightLine = Color(0xFF0A0A1E).copy(alpha = 0.08f)
    val LightLineStrong = Color(0xFF0A0A1E).copy(alpha = 0.14f)
    val LightText = Color(0xFF0A0A0C)
    val LightText2 = Color(0xFF27272A)
    val LightText4 = Color(0xFF6B6B75)
    val LightText5 = Color(0xFF8E8E98)

    // Pure black keeps the graphite steps, each pulled a little toward black.
    val BlackSurface = Color(0xFF111114)
    val BlackSurface2 = Color(0xFF17171C)
    val BlackSurface3 = Color(0xFF1E1E24)
    val BlackWell = Color(0xFF0C0C0E)
    val BlackSheet = Color(0xFF141418)

    val Danger = Color(0xFFF85149)
    val Success = Color(0xFF3FB950)
    val Warning = Color(0xFFF5A524)
    val LightDanger = Color(0xFFCF222E)
    val LightSuccess = Color(0xFF1A7F37)
    val LightWarning = Color(0xFFB76E00)
}

/**
 * The neutral roles one scheme is drawn with: the grounds, the text steps, the hairlines, the inverse pill and the
 * three signal colors. The accent presets share the graphite and off-white sets below; a theme with a look of its
 * own (see [AccentPalette.Ukiyoe]) brings its own through [AccentColors.neutrals].
 */
class Neutrals(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val well: Color,
    /** One step above surface 3, whatever the ground. */
    val thumb: Color,
    /** Sheets and menus. */
    val elevated: Color,
    val line: Color,
    val lineStrong: Color,
    val text: Color,
    val text2: Color,
    val text4: Color,
    val text5: Color,
    val inverse: Color,
    val onInverse: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
) {
    internal companion object {
        val Light = Neutrals(
            bg = Palette.LightBg, surface = Palette.LightSurface, surface2 = Palette.LightSurface2,
            surface3 = Palette.LightSurface3, well = Palette.LightWell, thumb = Palette.LightSurface,
            elevated = Palette.LightSurface, line = Palette.LightLine, lineStrong = Palette.LightLineStrong,
            text = Palette.LightText, text2 = Palette.LightText2, text4 = Palette.LightText4, text5 = Palette.LightText5,
            inverse = Palette.LightText, onInverse = Color.White,
            danger = Palette.LightDanger, success = Palette.LightSuccess, warning = Palette.LightWarning,
        )

        val Dark = Neutrals(
            bg = Palette.Bg, surface = Palette.Surface, surface2 = Palette.Surface2,
            surface3 = Palette.Surface3, well = Palette.Well, thumb = Palette.Thumb,
            elevated = Palette.Sheet, line = Palette.Line, lineStrong = Palette.LineStrong,
            text = Palette.Text, text2 = Palette.Text2, text4 = Palette.Text4, text5 = Palette.Text5,
            inverse = Palette.Text, onInverse = Palette.Bg,
            danger = Palette.Danger, success = Palette.Success, warning = Palette.Warning,
        )

        /** Pure black keeps the graphite steps, each pulled a little toward black. */
        val Black = Neutrals(
            bg = Color.Black, surface = Palette.BlackSurface, surface2 = Palette.BlackSurface2,
            surface3 = Palette.BlackSurface3, well = Palette.BlackWell, thumb = Palette.Thumb,
            elevated = Palette.BlackSheet, line = Palette.Line, lineStrong = Palette.LineStrong,
            text = Palette.Text, text2 = Palette.Text2, text4 = Palette.Text4, text5 = Palette.Text5,
            inverse = Palette.Text, onInverse = Palette.Bg,
            danger = Palette.Danger, success = Palette.Success, warning = Palette.Warning,
        )
    }
}

internal fun lightColors(accentColors: AccentColors, n: Neutrals = accentColors.neutrals ?: Neutrals.Light) = mapOf(
    background to n.bg,
    surface to n.surface,
    surface2 to n.surface2,
    surface3 to n.surface3,
    well to n.well,
    thumb to n.thumb,
    surfaceElevated to n.elevated,
    input to n.surface,
    sidebar to n.surface,
    userBubble to accentColors.bubble,
    userBubbleStroke to accentColors.bubbleStroke,
    onUserBubble to accentColors.onAccent,
    text to n.text,
    textSecondary to n.text2,
    textTertiary to n.text4,
    textMuted to n.text5,
    accent to accentColors.accent,
    onAccent to accentColors.onAccent,
    accentText to accentColors.text,
    accentSoft to accentColors.soft,
    inverse to n.inverse,
    onInverse to n.onInverse,
    stroke to n.line,
    strokeStrong to n.lineStrong,
    danger to n.danger,
    success to n.success,
    warning to n.warning,
    dangerSoft to n.danger.copy(alpha = 0.09f),
    successSoft to n.success.copy(alpha = 0.10f),
    warningSoft to n.warning.copy(alpha = 0.10f),
)

/** Pure black: the dark roles on a true black ground, with each graphite step pulled toward black. */
internal fun pureBlackColors(accentColors: AccentColors) = darkColors(accentColors, accentColors.neutrals ?: Neutrals.Black)

/** Dark and pure black share every role but the grounds they sit on. */
internal fun darkColors(accentColors: AccentColors, n: Neutrals = accentColors.neutrals ?: Neutrals.Dark) = mapOf(
    background to n.bg,
    surface to n.surface,
    surface2 to n.surface2,
    surface3 to n.surface3,
    well to n.well,
    thumb to n.thumb,
    surfaceElevated to n.elevated,
    input to n.surface,
    sidebar to n.surface,
    userBubble to accentColors.bubble,
    userBubbleStroke to accentColors.bubbleStroke,
    onUserBubble to accentColors.onAccent,
    text to n.text,
    textSecondary to n.text2,
    textTertiary to n.text4,
    textMuted to n.text5,
    accent to accentColors.accent,
    onAccent to accentColors.onAccent,
    accentText to accentColors.text,
    accentSoft to accentColors.soft,
    inverse to n.inverse,
    onInverse to n.onInverse,
    stroke to n.line,
    strokeStrong to n.lineStrong,
    danger to n.danger,
    success to n.success,
    warning to n.warning,
    dangerSoft to n.danger.copy(alpha = 0.14f),
    successSoft to n.success.copy(alpha = 0.13f),
    warningSoft to n.warning.copy(alpha = 0.13f),
)
