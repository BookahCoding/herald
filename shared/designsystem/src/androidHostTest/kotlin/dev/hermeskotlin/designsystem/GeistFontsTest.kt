package dev.hermeskotlin.designsystem

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Reads the bundled Geist fonts themselves. The proportional ones once mapped the lowercase omega (U+03C9) to the
 * capital Omega's glyph, so every "ω" the app drew came out as "Ω". A lowercase Greek letter may be missing from a
 * font (the system font then draws it) but must never share a glyph with its capital.
 */
class GeistFontsTest {

    private val phoneFonts = File("src/androidMain/res/font")
    private val watchFonts = File("../../wearApp/src/main/res/font")

    private fun fonts(dir: File): List<File> {
        val found = dir.listFiles { f -> f.name.startsWith("geist") && f.name.endsWith(".ttf") }.orEmpty().sortedBy { it.name }
        assertTrue(found.isNotEmpty(), "no Geist fonts found in ${dir.absolutePath}")
        return found
    }

    /** The glyph a font's cmap gives [codePoint], per subtable (formats 4 and 12), 0 when it has none. */
    private fun glyphs(font: ByteArray, codePoint: Int): List<Int> {
        val data = ByteBuffer.wrap(font)
        val tables = data.getShort(4).toInt() and 0xFFFF
        var cmap = -1
        for (i in 0 until tables) {
            val record = 12 + i * 16
            if (String(font, record, 4, Charsets.US_ASCII) == "cmap") cmap = data.getInt(record + 8)
        }
        assertTrue(cmap >= 0, "no cmap table")
        val subtables = data.getShort(cmap + 2).toInt() and 0xFFFF
        val out = mutableListOf<Int>()
        for (i in 0 until subtables) {
            val at = cmap + data.getInt(cmap + 4 + i * 8 + 4)
            when (val format = data.getShort(at).toInt() and 0xFFFF) {
                4 -> out += format4(data, at, codePoint)
                12 -> out += format12(data, at, codePoint)
                else -> Unit // other formats aren't used for this range
            }
        }
        return out
    }

    private fun u16(d: ByteBuffer, at: Int) = d.getShort(at).toInt() and 0xFFFF

    private fun format4(d: ByteBuffer, at: Int, cp: Int): Int {
        if (cp > 0xFFFF) return 0
        val segs = u16(d, at + 6) / 2
        val ends = at + 14
        val starts = ends + segs * 2 + 2
        val deltas = starts + segs * 2
        val ranges = deltas + segs * 2
        for (s in 0 until segs) {
            if (cp > u16(d, ends + s * 2)) continue
            if (cp < u16(d, starts + s * 2)) return 0
            val delta = u16(d, deltas + s * 2)
            val range = u16(d, ranges + s * 2)
            if (range == 0) return (cp + delta) and 0xFFFF
            val glyph = u16(d, ranges + s * 2 + range + 2 * (cp - u16(d, starts + s * 2)))
            return if (glyph == 0) 0 else (glyph + delta) and 0xFFFF
        }
        return 0
    }

    private fun format12(d: ByteBuffer, at: Int, cp: Int): Int {
        val groups = d.getInt(at + 12)
        for (g in 0 until groups) {
            val base = at + 16 + g * 12
            val start = d.getInt(base)
            val end = d.getInt(base + 4)
            if (cp in start..end) return d.getInt(base + 8) + (cp - start)
        }
        return 0
    }

    @Test
    fun noLowercaseGreekLetterSharesAGlyphWithItsCapital() {
        for (font in fonts(phoneFonts) + fonts(watchFonts)) {
            val bytes = font.readBytes()
            // α..ω against Α..Ω (the final sigma at U+03C2 has no capital of its own).
            for (lower in 0x03B1..0x03C9) {
                if (lower == 0x03C2) continue
                val upper = lower - 0x20
                val smallGlyphs = glyphs(bytes, lower)
                val capitalGlyphs = glyphs(bytes, upper)
                for ((small, capital) in smallGlyphs.zip(capitalGlyphs)) {
                    if (small == 0) continue
                    assertNotEquals(
                        capital, small,
                        "${font.name}: U+%04X draws the same glyph as its capital U+%04X".format(lower, upper),
                    )
                }
            }
        }
    }

    @Test
    fun theSmallOmegaIsNotTheCapitalOmega() {
        for (font in fonts(phoneFonts) + fonts(watchFonts)) {
            val bytes = font.readBytes()
            assertTrue(glyphs(bytes, 0x03A9).all { it != 0 }, "${font.name} lost the capital Omega")
            assertTrue(glyphs(bytes, 0x03C9).all { it != glyphs(bytes, 0x03A9).first() }, "${font.name}: ω is drawn as Ω")
        }
    }

    @Test
    fun theOtherGreekSymbolsTheFontShipsAreStillThere() {
        // Only the wrong mapping went; Λ Ω λ μ π stay.
        for (font in fonts(phoneFonts).filter { !it.name.contains("mono") }) {
            val bytes = font.readBytes()
            for (cp in listOf(0x039B, 0x03A9, 0x03BB, 0x03BC, 0x03C0)) {
                assertTrue(glyphs(bytes, cp).any { it != 0 }, "${font.name} lost U+%04X".format(cp))
            }
        }
    }

    @Test
    fun theWatchAppHasTheSameFontsAsThePhone() {
        for (watch in fonts(watchFonts)) {
            assertContentEquals(File(phoneFonts, watch.name).readBytes(), watch.readBytes(), watch.name)
        }
        assertEquals(setOf("geist_medium.ttf", "geist_regular.ttf", "geist_semibold.ttf"), fonts(watchFonts).map { it.name }.toSet())
    }
}
