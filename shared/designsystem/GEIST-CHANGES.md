# Changes to the bundled Geist fonts

`geist_regular.ttf`, `geist_medium.ttf` and `geist_semibold.ttf` (here and in `wearApp/src/main/res/font/`) are
Geist as released, under the SIL Open Font License (see `GEIST-OFL.txt`), with one change: the `cmap` entry that
mapped U+03C9 (Greek small letter omega, ω) to the glyph of the capital Omega (`uni03A9`) was removed.

Geist ships only six Greek symbols (Λ Ω λ μ π ω). Because ω pointed at the capital's glyph, every ω the app drew
in these fonts came out as Ω. With the entry gone the system font draws ω, correctly. Nothing else in the fonts
was changed (same glyphs, same other mappings). The Geist Mono fonts have no ω and were not touched.

`GeistFontsTest` reads the font files and fails if any lowercase Greek letter shares a glyph with its capital, so a
newer Geist copied in later can't bring the problem back.
