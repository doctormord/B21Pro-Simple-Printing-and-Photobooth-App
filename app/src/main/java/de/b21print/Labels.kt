package de.b21print

import android.content.Context
import androidx.annotation.StringRes

// Printable window on a pre-printed label, in mm from the label's top left corner
data class PhotoWindow(val x: Double, val y: Double, val w: Double, val h: Double)

data class LabelPreset(
    // Stable key, stored in preferences and history
    val name: String,
    @StringRes val title: Int,
    val widthMm: Double,
    val heightMm: Double,
    // The app prints the frame itself (for plain white labels)
    val polaroid: Boolean = false,
    val freestyle: Boolean = false,
    // Pre-printed frame: only the window is printed
    val window: PhotoWindow? = null,
    val frameColor: Int? = null,
    val code: String? = null,
    // Round label: diameter = widthMm = heightMm
    val round: Boolean = false,
) {
    fun title(c: Context): String =
        if (round) c.getString(title, "%.0f".format(widthMm)) else c.getString(title)
}

object Labels {
    val FREESTYLE = LabelPreset("Freestyle", R.string.label_freestyle, 50.0, 30.0, freestyle = true)

    // NB835 "Memory Film" photo labels, 54 × 67 mm, 120 per roll.
    // Window measured from the product photo (±0.3 mm); fine-tuning corrects the rest.
    val NB835 = LabelPreset(
        "Memory Film", R.string.label_memory_film, 54.0, 67.0,
        window = PhotoWindow(3.3, 6.5, 47.5, 47.0),
        frameColor = 0xFF3C3A58.toInt(),
        code = "NB835",
    )

    // The official app loads its catalog from the cloud; these are common B21 sizes.
    val ALL = listOf(
        FREESTYLE,
        NB835,
        LabelPreset("Polaroid", R.string.label_polaroid, 50.0, 60.0, polaroid = true),
        LabelPreset("Polaroid XL", R.string.label_polaroid_xl, 50.0, 70.0, polaroid = true),
        LabelPreset("Polaroid mini", R.string.label_polaroid_mini, 40.0, 50.0, polaroid = true),
        LabelPreset("Circle 50", R.string.label_circle, 50.0, 50.0, round = true),
        LabelPreset("Circle 40", R.string.label_circle, 40.0, 40.0, round = true),
        LabelPreset("Circle 30", R.string.label_circle, 30.0, 30.0, round = true),
        LabelPreset("Circle 25", R.string.label_circle, 25.0, 25.0, round = true),
        LabelPreset("Circle 20", R.string.label_circle, 20.0, 20.0, round = true),
        LabelPreset("Standard", R.string.label_standard, 50.0, 30.0),
        LabelPreset("Square", R.string.label_square, 50.0, 50.0),
        LabelPreset("Wide", R.string.label_wide, 50.0, 20.0),
        LabelPreset("Tall", R.string.label_tall, 50.0, 80.0),
        LabelPreset("Medium", R.string.label_medium, 40.0, 30.0),
        LabelPreset("Square M", R.string.label_square_m, 40.0, 40.0),
        LabelPreset("Tall M", R.string.label_tall_m, 40.0, 60.0),
        LabelPreset("Small", R.string.label_small, 30.0, 20.0),
        LabelPreset("Square S", R.string.label_square_s, 30.0, 30.0),
        LabelPreset("Mini", R.string.label_mini, 30.0, 15.0),
        LabelPreset("Square XS", R.string.label_square_xs, 25.0, 25.0),
        LabelPreset("Narrow", R.string.label_narrow, 20.0, 20.0),
    )
}
