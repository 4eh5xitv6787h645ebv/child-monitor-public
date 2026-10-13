package de.rochefort.childmonitor

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import de.rochefort.childmonitor.accessibility.HighContrastColors
import kotlin.math.max
import kotlin.math.min

object ThemeManager {
    private const val PREFS = "ThemePreferences"
    private const val KEY_THEME_INDEX = "themeIndex"
    private const val KEY_HIGH_CONTRAST = "highContrast" // #362
    private const val DEFAULT_THEME_INDEX = 7 // Rose Petals

    data class ThemePalette(
        val name: String,
        val sourceHexes: List<String>,
        val gradientStart: Int,
        val gradientMid: Int,
        val gradientEnd: Int,
        val surfaceCard: Int,
        val surfaceSoft: Int,
        val surfaceBorder: Int,
        val buttonPrimary: Int,
        val buttonSecondary: Int,
        val buttonSecondaryBorder: Int,
        val buttonAccent: Int,
        val buttonTalkIdle: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textHint: Int,
        val flowerColors: IntArray,
    )

    private data class PaletteSeed(
        val name: String,
        val hexes: List<String>,
    )

    private val seeds = listOf(
        PaletteSeed("Pastel Vibes", listOf("#d6e6ff", "#d7f9f8", "#ffffea", "#fff0d4", "#fbe0e0", "#e5d4ef")),
        PaletteSeed("Pastel Rainbow", listOf("#ffadad", "#ffd6a5", "#fdffb6", "#caffbf", "#9bf6ff", "#a0c4ff", "#bdb2ff", "#ffc6ff")),
        PaletteSeed("Celestial Sea", listOf("#809bce", "#95b8d1", "#b8e0d4", "#d6eadf", "#eac4d5")),
        PaletteSeed("Forest Breeze", listOf("#f1ddbf", "#525e75", "#78938a", "#92ba92")),
        PaletteSeed("Yoda", listOf("#f0ead2", "#dde5b4", "#adc178", "#a98467", "#6c584c")),
        PaletteSeed("Purple Skies", listOf("#cac7ff", "#d7cdfe", "#e4d3fc", "#f1dafb", "#fee0f9")),
        PaletteSeed("Faded Blues", listOf("#edf2fa", "#d7e3fc", "#ccdbfd", "#c1d3fe", "#abc4ff")),
        PaletteSeed("Rose Petals", listOf("#ffe5ec", "#ffc2d1", "#ffb3c6", "#ff8fab", "#fb6f92")),
        PaletteSeed("Cotton Bloom", listOf("#c095e4", "#fcedf2", "#ffd1d4", "#ffb7c5", "#ffa0c5")),
        PaletteSeed("Vintage Petal", listOf("#c45161", "#e094a0", "#f2b6c0", "#f2dde1", "#cbc7d8", "#8db7d2", "#5e62a9", "#434279")),
    )

    val palettes: List<ThemePalette> = seeds.map { seed ->
        val colors = seed.hexes.map { Color.parseColor(it) }
        val c0 = colors.getOrElse(0) { Color.parseColor("#d6e6ff") }
        val c1 = colors.getOrElse(1) { c0 }
        val c2 = colors.getOrElse(2) { c1 }
        val c3 = colors.getOrElse(3) { c2 }
        val c4 = colors.getOrElse(4) { c3 }

        val gradientStart = darken(blend(c2, c3, 0.5f), 0.82f)
        val gradientMid = darken(blend(c1, c2, 0.45f), 0.68f)
        val gradientEnd = darken(blend(c0, c1, 0.35f), 0.58f)

        val surfaceCard = darken(blend(c0, c2, 0.55f), 0.66f)
        val surfaceSoft = darken(blend(c1, c3, 0.55f), 0.57f)
        val surfaceBorder = lighten(blend(c0, c1, 0.5f), 0.1f)

        val buttonPrimary = blend(c0, Color.WHITE, 0.22f)
        val buttonSecondary = darken(blend(c1, c2, 0.5f), 0.52f)
        val buttonSecondaryBorder = lighten(buttonSecondary, 0.2f)
        val buttonAccent = blend(c4, Color.WHITE, 0.15f)
        val buttonTalkIdle = blend(c3, Color.WHITE, 0.12f)

        val flowerPalette = intArrayOf(
            withAlpha(c0, 36),
            withAlpha(c1, 34),
            withAlpha(c2, 32),
            withAlpha(c3, 34),
            withAlpha(c4, 30),
        )

        ThemePalette(
            name = seed.name,
            sourceHexes = seed.hexes,
            gradientStart = gradientStart,
            gradientMid = gradientMid,
            gradientEnd = gradientEnd,
            surfaceCard = surfaceCard,
            surfaceSoft = surfaceSoft,
            surfaceBorder = surfaceBorder,
            buttonPrimary = buttonPrimary,
            buttonSecondary = buttonSecondary,
            buttonSecondaryBorder = buttonSecondaryBorder,
            buttonAccent = buttonAccent,
            buttonTalkIdle = buttonTalkIdle,
            textPrimary = Color.parseColor("#F7F5EE"),
            textSecondary = Color.parseColor("#D1D8D0"),
            textHint = Color.parseColor("#AEB8B1"),
            flowerColors = flowerPalette,
        )
    }

    // #362: a fixed WCAG AA high-contrast palette, applied as an overlay (independent of the chosen
    // pastel theme) when the accessibility toggle is on. Colors are unit-verified in HighContrastColors.
    private val highContrastPalette: ThemePalette = ThemePalette(
        name = "High contrast",
        sourceHexes = emptyList(),
        gradientStart = HighContrastColors.gradientStart,
        gradientMid = HighContrastColors.gradientMid,
        gradientEnd = HighContrastColors.gradientEnd,
        surfaceCard = HighContrastColors.surfaceCard,
        surfaceSoft = HighContrastColors.surfaceSoft,
        surfaceBorder = HighContrastColors.surfaceBorder,
        buttonPrimary = HighContrastColors.buttonPrimary,
        buttonSecondary = HighContrastColors.buttonSecondary,
        buttonSecondaryBorder = HighContrastColors.buttonSecondaryBorder,
        buttonAccent = HighContrastColors.buttonAccent,
        buttonTalkIdle = HighContrastColors.buttonTalkIdle,
        textPrimary = HighContrastColors.textPrimary,
        textSecondary = HighContrastColors.textSecondary,
        textHint = HighContrastColors.textHint,
        flowerColors = HighContrastColors.flowerColors,
    )

    fun isHighContrast(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIGH_CONTRAST, false)

    fun setHighContrast(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIGH_CONTRAST, enabled)
            .apply()
    }

    fun getCurrentPalette(context: Context): ThemePalette {
        if (isHighContrast(context)) return highContrastPalette
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val index = prefs.getInt(KEY_THEME_INDEX, DEFAULT_THEME_INDEX).coerceIn(0, palettes.lastIndex)
        return palettes[index]
    }

    fun getCurrentPaletteIndex(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME_INDEX, DEFAULT_THEME_INDEX).coerceIn(0, palettes.lastIndex)
    }

    fun setThemeIndex(context: Context, index: Int) {
        val safeIndex = index.coerceIn(0, palettes.lastIndex)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_INDEX, safeIndex)
            .apply()
    }

    fun isLightColorForUi(color: Int): Boolean = isLightColor(color)

    /** Dark text placed on a light button (single source of truth, shared with the theme picker). */
    val onLightButtonText: Int = HighContrastColors.onLightButtonText

    fun applyTheme(activity: Activity) {
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        val palette = getCurrentPalette(activity)

        applyRootBackground(root, palette)
        applyRecursive(root, palette)
    }

    private fun applyRootBackground(root: View, palette: ThemePalette) {
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(palette.gradientStart, palette.gradientMid, palette.gradientEnd),
        )
        root.background = gradient
    }

    private fun applyRecursive(view: View, palette: ThemePalette) {
        if (view is AnimatedGardenBackgroundView) {
            view.setFlowerPalette(palette.flowerColors)
        }

        val tag = view.tag?.toString()
        when (tag) {
            "theme_card" -> view.background = roundedRect(palette.surfaceCard, palette.surfaceBorder, 22f)
            "theme_soft" -> view.background = roundedRect(palette.surfaceSoft, palette.surfaceBorder, 16f)
            "theme_chip" -> view.background = roundedRect(palette.surfaceSoft, palette.surfaceBorder, 999f)
            "theme_input" -> view.background = roundedRect(palette.surfaceSoft, palette.surfaceBorder, 14f)
            "theme_btn_primary" -> view.background = roundedRect(palette.buttonPrimary, 0, 16f)
            "theme_btn_secondary" -> view.background = roundedRect(palette.buttonSecondary, palette.buttonSecondaryBorder, 16f)
            "theme_btn_accent" -> view.background = roundedRect(palette.buttonAccent, 0, 16f)
            "theme_btn_talk" -> view.background = roundedRect(palette.buttonTalkIdle, 0, 20f)
            "theme_fab" -> view.background = roundedRect(blend(palette.buttonSecondary, palette.buttonPrimary, 0.25f), palette.surfaceBorder, 999f)
            "theme_text_primary" -> if (view is TextView) view.setTextColor(palette.textPrimary)
            "theme_text_secondary" -> if (view is TextView) view.setTextColor(palette.textSecondary)
        }

        when (view) {
            is Button -> {
                val bgColor = extractSolidColor(view.background)
                if (bgColor != null) {
                    view.setTextColor(if (isLightColor(bgColor)) onLightButtonText else palette.textPrimary)
                }
            }
            is CheckBox -> {
                view.setTextColor(palette.textPrimary)
                view.buttonTintList = ColorStateList.valueOf(palette.buttonPrimary)
            }
            is EditText -> {
                view.setTextColor(palette.textPrimary)
                view.setHintTextColor(palette.textHint)
            }
            is TextView -> {
                if (tag == null) {
                    val current = view.currentTextColor
                    val luminance = relativeLuminance(current)
                    if (luminance > 0.82f) {
                        view.setTextColor(palette.textPrimary)
                    } else if (luminance > 0.45f) {
                        view.setTextColor(palette.textSecondary)
                    }
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i), palette)
            }
        }
    }

    private fun roundedRect(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radiusDp
        d.setColor(fill)
        if (stroke != 0) {
            d.setStroke(2, stroke)
        }
        return d
    }

    private fun extractSolidColor(background: android.graphics.drawable.Drawable?): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && background is GradientDrawable) {
            return try {
                background.color?.defaultColor
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun blend(c1: Int, c2: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val inv = 1f - r
        return Color.rgb(
            (Color.red(c1) * inv + Color.red(c2) * r).toInt().coerceIn(0, 255),
            (Color.green(c1) * inv + Color.green(c2) * r).toInt().coerceIn(0, 255),
            (Color.blue(c1) * inv + Color.blue(c2) * r).toInt().coerceIn(0, 255),
        )
    }

    private fun darken(c: Int, factor: Float): Int {
        return Color.rgb(
            (Color.red(c) * factor).toInt().coerceIn(0, 255),
            (Color.green(c) * factor).toInt().coerceIn(0, 255),
            (Color.blue(c) * factor).toInt().coerceIn(0, 255),
        )
    }

    private fun lighten(c: Int, amount: Float): Int {
        return Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * amount).toInt().coerceIn(0, 255),
            (Color.green(c) + (255 - Color.green(c)) * amount).toInt().coerceIn(0, 255),
            (Color.blue(c) + (255 - Color.blue(c)) * amount).toInt().coerceIn(0, 255),
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun isLightColor(color: Int): Boolean {
        return relativeLuminance(color) > 0.58f
    }

    private fun relativeLuminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
