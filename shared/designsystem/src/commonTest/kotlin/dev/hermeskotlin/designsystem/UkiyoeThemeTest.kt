package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The "Ukiyo-e" theme: the colors of a woodblock print, and that every one of them can be read. */
class UkiyoeThemeTest {

    private val palette = AccentPalette.Ukiyoe

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private val schemes = mapOf(
        "light" to lightColors(palette.light),
        "dark" to darkColors(palette.dark),
        "black" to pureBlackColors(palette.black),
    )

    private val grounds = listOf(background, surface, surface2, surface3, surfaceElevated)

    @Test
    fun itIsOneOfThePresetsAndIsFoundByItsName() {
        assertTrue(palette in AccentPalette.all)
        assertSame(palette, AccentPalette.named("Ukiyo-e"))
        assertEquals("Ukiyo-e", palette.name)
    }

    @Test
    fun theGroundsAreThePrintsPaperAndIndigoNight() {
        // Washi paper as measured from the print, and indigo from its umbrella; pure black stays black.
        assertEquals(Color(0xFFF4EDD9), schemes.getValue("light").getValue(background))
        assertEquals(Color(0xFF0E1424), schemes.getValue("dark").getValue(background))
        assertEquals(Color.Black, schemes.getValue("black").getValue(background))
        // Paper is warm and indigo is blue: not the graphite and off-white of the other presets.
        val paper = schemes.getValue("light").getValue(background)
        assertTrue(paper.red > paper.green && paper.green > paper.blue, "paper should be warm")
        val night = schemes.getValue("dark").getValue(background)
        assertTrue(night.blue > night.red, "indigo night should lean blue")
    }

    @Test
    fun theAccentIsTheKimonosVermilionOnPaperAndAPaleCoralOnIndigo() {
        val vermilion = palette.light.accent
        assertTrue(vermilion.red > 0.5f && vermilion.red > vermilion.green * 2 && vermilion.red > vermilion.blue * 2, "red-orange on paper")
        val coral = palette.dark.accent
        assertTrue(coral.red > 0.8f && coral.red > coral.green && coral.green > coral.blue, "a lighter coral on indigo")
        assertEquals(coral, palette.black.accent)
    }

    @Test
    fun everyTextStepReadsOnEveryGround() {
        for ((scheme, colors) in schemes) {
            val tinted = colors.getValue(accentSoft).compositeOver(colors.getValue(surface))
            val all = grounds.map { colors.getValue(it) } + tinted + colors.getValue(well)
            for (ground in all) {
                assertTrue(contrast(colors.getValue(text), ground) >= 7f, "$scheme text on $ground")
                assertTrue(contrast(colors.getValue(textSecondary), ground) >= 7f, "$scheme secondary text on $ground")
                assertTrue(contrast(colors.getValue(textTertiary), ground) >= 4.5f, "$scheme tertiary text on $ground")
            }
        }
    }

    @Test
    fun theSignalColorsReadAsTextOnTheGrounds() {
        for ((scheme, colors) in schemes) {
            for (signal in listOf(danger, success, warning)) {
                for (ground in grounds.map { colors.getValue(it) }) {
                    val ratio = contrast(colors.getValue(signal), ground)
                    assertTrue(ratio >= 4.5f, "$scheme signal on $ground is $ratio:1")
                }
            }
        }
    }

    @Test
    fun theInversePillReadsAndStaysApartFromTheAccent() {
        for ((scheme, colors) in schemes) {
            assertTrue(contrast(colors.getValue(onInverse), colors.getValue(inverse)) >= 7f, "$scheme inverse")
            // A destructive action is never mistaken for the accent.
            assertTrue(colors.getValue(danger) != colors.getValue(accent), "$scheme danger vs accent")
        }
    }

    @Test
    fun theUsersBubbleReadsOnPaperAndOnIndigo() {
        for ((scheme, colors) in schemes) {
            assertTrue(contrast(colors.getValue(onUserBubble), colors.getValue(userBubble)) >= 4.5f, scheme)
        }
    }

    @Test
    fun theOtherPresetsAreExactlyWhatTheyWere() {
        // Only this preset brings neutrals of its own; the rest keep the graphite and off-white sets.
        for (other in AccentPalette.all.filter { it != palette }) {
            assertNull(other.light.neutrals, other.name)
            assertNull(other.dark.neutrals, other.name)
            assertNull(other.black.neutrals, other.name)
        }
        val blue = AccentPalette.Blue
        val light = lightColors(blue.light)
        val dark = darkColors(blue.dark)
        val black = pureBlackColors(blue.black)
        assertEquals(Palette.LightBg, light.getValue(background))
        assertEquals(Palette.LightText, light.getValue(text))
        assertEquals(Palette.LightSurface, light.getValue(thumb))
        assertEquals(Color.White, light.getValue(onInverse))
        assertEquals(Palette.LightDanger.copy(alpha = 0.09f), light.getValue(dangerSoft))
        assertEquals(Palette.Bg, dark.getValue(background))
        assertEquals(Palette.Thumb, dark.getValue(thumb))
        assertEquals(Palette.Sheet, dark.getValue(surfaceElevated))
        assertEquals(Palette.Bg, dark.getValue(onInverse))
        assertEquals(Palette.Danger.copy(alpha = 0.14f), dark.getValue(dangerSoft))
        assertEquals(Color.Black, black.getValue(background))
        assertEquals(Palette.BlackSheet, black.getValue(surfaceElevated))
        assertEquals(Palette.BlackSurface3, black.getValue(surface3))
        assertEquals(Palette.Bg, black.getValue(onInverse))
    }
}
