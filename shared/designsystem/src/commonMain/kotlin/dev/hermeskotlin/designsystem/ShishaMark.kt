package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The Ukiyo-e vermilion (AccentPalette) the agent's mark sits on, kept whatever accent the app is drawn in. */
val ShishaBrandVermilion = Color(0xFFA63F2D)

/** The washi paper the mark is drawn in. */
val ShishaMarkPaper = Color(0xFFF4EDD9)

/**
 * Shisha's mark: 使 (shi, "envoy"), as an icon to tint like any other. Its outline is that of Noto Serif JP Bold
 * (SIL Open Font License 1.1), drawn in a 56-unit box, so no font ships for it.
 */
val ShishaMark: ImageVector by lazy {
    ImageVector.Builder(name = "ShishaMark", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 56f, viewportHeight = 56f)
        .addPath(addPathNodes("M21.37 16.24H38.52L41.02 12.99Q41.02 12.99 41.47 13.34Q41.93 13.7 42.64 14.28Q43.36 14.85 44.14 15.49Q44.92 16.13 45.55 16.69Q45.48 16.99 45.19 17.14Q44.91 17.29 44.5 17.29H21.67ZM25.14 29.21H40.44V30.27H25.14ZM39.22 21.15H38.85L40.83 18.98L44.99 22.14Q44.83 22.36 44.44 22.58Q44.05 22.8 43.47 22.93V31.04Q43.47 31.18 42.89 31.45Q42.3 31.73 41.48 31.95Q40.67 32.18 39.93 32.18H39.22ZM22.11 21.15V19.42L26.48 21.15H39.83V22.21H26.27V31.28Q26.27 31.46 25.74 31.76Q25.21 32.06 24.39 32.3Q23.57 32.55 22.7 32.55H22.11ZM25.32 32.5Q26.55 34.6 28.64 36.1Q30.73 37.6 33.46 38.6Q36.2 39.59 39.38 40.07Q42.57 40.55 46 40.6L45.97 41.02Q44.7 41.4 43.75 42.55Q42.81 43.7 42.55 45.36Q37.83 44.62 34.38 43.12Q30.93 41.63 28.6 39.1Q26.27 36.56 24.87 32.73ZM30.63 10.68 36.25 11.22Q36.22 11.59 35.94 11.87Q35.66 12.15 34.92 12.26V29.26Q34.89 32.48 34.04 35.06Q33.2 37.65 31.37 39.65Q29.54 41.65 26.56 43.1Q23.58 44.54 19.28 45.44L19.05 44.98Q23.24 43.36 25.78 41.18Q28.32 38.99 29.48 36.07Q30.63 33.15 30.63 29.27ZM14.73 22.42 16.37 20.26 20.11 21.63Q20.01 21.89 19.75 22.07Q19.48 22.25 18.95 22.35V44.18Q18.95 44.34 18.43 44.64Q17.91 44.94 17.14 45.19Q16.37 45.44 15.52 45.44H14.73ZM17.55 10.56 23.56 12.36Q23.42 12.72 23.06 12.94Q22.7 13.16 22.07 13.13Q20.7 16.8 18.92 19.92Q17.14 23.04 15.03 25.56Q12.92 28.09 10.46 30.04L10 29.74Q11.53 27.41 12.97 24.31Q14.42 21.2 15.62 17.67Q16.82 14.13 17.55 10.56Z"), fill = SolidColor(Color.Black))
        .build()
}
