package dev.hermeskotlin.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The accent the app is drawn in, for colors outside the theme's tokens (the assistant's edge light). */
val LocalAccentPalette = staticCompositionLocalOf { AccentPalette.Blue }

/**
 * The colors one accent gives a scheme: [accent] as a fill with [onAccent] on it, [text] for the accent as
 * text or an icon, the [soft] tint, and the user's bubble, which is filled with the accent. [neutrals] are the
 * grounds, text and hairlines of a theme with a look of its own; null keeps the graphite and off-white ones.
 */
class AccentColors(
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val soft: Color,
    val bubble: Color = accent,
    val bubbleStroke: Color = accent,
    val neutrals: Neutrals? = null,
)

/**
 * An accent the user can pick in Settings. [Blue] is Herald blue #2F6BF5: one fill with white on it in both
 * schemes, and a lighter blue for text on dark. The others use their accent as fill and text alike, so a preset
 * only needs a light and a dark accent. Light accents carry white text and dark accents near-black text, each
 * at 4.5:1 or more, and every accent's text reads at 4.5:1 on its background (AccentPaletteTest).
 */
class AccentPalette private constructor(
    val name: String,
    val light: AccentColors,
    val dark: AccentColors,
    val black: AccentColors,
) {
    companion object {
        private val DarkText = Color(0xFF0A0A0C)

        private val HeraldBlue = Color(0xFF2F6BF5)

        val Blue = AccentPalette(
            "Blue",
            light = AccentColors(HeraldBlue, Color.White, text = Color(0xFF1F56D6), soft = HeraldBlue.copy(alpha = 0.10f)),
            dark = AccentColors(HeraldBlue, Color.White, text = Color(0xFF7AA5FF), soft = Color(0xFF4A84FE).copy(alpha = 0.14f)),
            black = AccentColors(HeraldBlue, Color.White, text = Color(0xFF7AA5FF), soft = Color(0xFF4A84FE).copy(alpha = 0.14f)),
        )
        val Violet = mixed("Violet", light = Color(0xFF7C3AED), dark = Color(0xFFA78BFA))
        val Green = mixed("Green", light = Color(0xFF137538), dark = Color(0xFF3FB950))
        val Orange = mixed("Orange", light = Color(0xFFB04700), dark = Color(0xFFF0883E))
        val Pink = mixed("Pink", light = Color(0xFFB0327E), dark = Color(0xFFF778BA))
        val Teal = mixed("Teal", light = Color(0xFF0C6A84), dark = Color(0xFF22D3EE))

        /**
         * "Ukiyo-e": a Japanese woodblock print, in the colors measured from one. Washi paper (#F4EDD9) and sumi ink
         * in light, indigo night (the umbrella's navy) in dark and pure black, with the kimono's terracotta vermilion
         * as the accent (a deeper #A63F2D on paper, a lighter #E5927E on indigo). Unlike the other presets it also
         * sets the grounds, text and signal colors, each checked for contrast in UkiyoeThemeTest.
         */
        val Ukiyoe: AccentPalette = run {
            val ink = Color(0xFFF4EDD9)
            val light = Neutrals(
                bg = Color(0xFFF4EDD9), surface = Color(0xFFFAF5E3), surface2 = Color(0xFFEFE6CF),
                surface3 = Color(0xFFE6DCC2), well = Color(0xFFF0E8D3), thumb = Color(0xFFFAF5E3),
                elevated = Color(0xFFFAF5E3),
                line = Color(0xFF3E3B2E).copy(alpha = 0.12f), lineStrong = Color(0xFF3E3B2E).copy(alpha = 0.20f),
                text = Color(0xFF2B2822), text2 = Color(0xFF403B31), text4 = Color(0xFF6A5F50), text5 = Color(0xFF8C8170),
                inverse = Color(0xFF262E48), onInverse = Color(0xFFFAF5E3),
                danger = Color(0xFF9F1F36), success = Color(0xFF3F6A4B), warning = Color(0xFF855608),
            )
            val dark = Neutrals(
                bg = Color(0xFF0E1424), surface = Color(0xFF151C30), surface2 = Color(0xFF1B2440),
                surface3 = Color(0xFF232D4D), well = Color(0xFF111829), thumb = Color(0xFF2C375A),
                elevated = Color(0xFF172038),
                line = ink.copy(alpha = 0.08f), lineStrong = ink.copy(alpha = 0.14f),
                text = ink, text2 = Color(0xFFDCD3BC), text4 = Color(0xFFA8A08C), text5 = Color(0xFF7F7B6C),
                inverse = ink, onInverse = Color(0xFF0E1424),
                danger = Color(0xFFF07A86), success = Color(0xFF7DBE8A), warning = Color(0xFFE3B458),
            )
            // Pure black: the same indigo steps, each pulled toward black.
            val black = Neutrals(
                bg = Color.Black, surface = Color(0xFF0B0E18), surface2 = Color(0xFF101528),
                surface3 = Color(0xFF171E38), well = Color(0xFF080B12), thumb = Color(0xFF2C375A),
                elevated = Color(0xFF0E1220),
                line = dark.line, lineStrong = dark.lineStrong,
                text = dark.text, text2 = dark.text2, text4 = dark.text4, text5 = dark.text5,
                inverse = dark.inverse, onInverse = dark.onInverse,
                danger = dark.danger, success = dark.success, warning = dark.warning,
            )
            val vermilion = Color(0xFFA63F2D)
            val coral = Color(0xFFE5927E)
            AccentPalette(
                "Ukiyo-e",
                light = AccentColors(vermilion, Color.White, text = vermilion, soft = vermilion.copy(alpha = 0.10f), neutrals = light),
                dark = AccentColors(coral, DarkText, text = coral, soft = coral.copy(alpha = 0.14f), neutrals = dark),
                black = AccentColors(coral, DarkText, text = coral, soft = coral.copy(alpha = 0.14f), neutrals = black),
            )
        }

        val all: List<AccentPalette> = listOf(Blue, Violet, Green, Orange, Pink, Teal, Ukiyoe)

        /** The palette called [name], or [Blue] for a name this version doesn't know. */
        fun named(name: String?): AccentPalette = all.firstOrNull { it.name == name } ?: Blue

        // The tints sit at Blue's alphas.
        private fun mixed(name: String, light: Color, dark: Color): AccentPalette {
            val darkColors = AccentColors(dark, DarkText, text = dark, soft = dark.copy(alpha = 0.14f))
            return AccentPalette(
                name,
                light = AccentColors(light, Color.White, text = light, soft = light.copy(alpha = 0.10f)),
                dark = darkColors,
                black = darkColors,
            )
        }
    }
}
