package com.jake.duolauncher.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Turns a [Drawable] into the finished icon bitmap: appearance (FR-14), monochrome fallback
 * (FR-15) and shape mask (FR-16), all at the real pixel size the caller asked for (FR-12).
 *
 * Nothing here recycles a bitmap. A rendered icon is handed to Compose and may still be referenced
 * by a frame in flight, so the only safe lifetime is the garbage collector's (`docs/architecture.md`).
 * Intermediate bitmaps that never leave this file are the one exception, and they are not recycled
 * either, because they are small and short-lived and a mistaken recycle is a crash.
 */
internal object IconRasterizer {

    /**
     * Masks are pure geometry: one per shape and size, shared by every icon on screen. Without this
     * a 4x6 grid would rebuild the same mask 24 times on every appearance change.
     */
    private val maskCache = HashMap<Pair<IconShape, Int>, Bitmap>()

    /**
     * Renders [drawable] at [sizePx] in [style].
     *
     * [fromPack] marks artwork that came from an icon pack. A pack icon is already a finished,
     * deliberately styled icon, so it is drawn as it is: masking it to a different shape would cut
     * into art the pack author already shaped (FR-18).
     */
    @Synchronized
    fun rasterize(drawable: Drawable, sizePx: Int, style: IconStyle, fromPack: Boolean = false): Bitmap {
        val size = sizePx.coerceIn(MIN_ICON_PX, MAX_ICON_PX)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val adaptive = drawable as? AdaptiveIconDrawable
        when {
            fromPack || adaptive == null -> drawLegacy(canvas, drawable, size)
            style.appearance.isMonochrome -> drawMonochrome(canvas, adaptive, size, style)
            else -> drawAdaptive(canvas, adaptive, size, style)
        }
        // Only adaptive artwork is shaped (FR-16); a legacy or pack icon keeps its own silhouette.
        if (adaptive != null && !fromPack) applyMask(canvas, style.shape, size)
        return bitmap
    }

    /** The mask path for [shape] at [sizePx], walked from the pure geometry in [IconShapes]. */
    fun maskPath(shape: IconShape, sizePx: Int): Path {
        val path = Path()
        for (command in IconShapes.outline(shape, sizePx)) when (command) {
            is PathCommand.MoveTo -> path.moveTo(command.x, command.y)
            is PathCommand.LineTo -> path.lineTo(command.x, command.y)
            is PathCommand.CubicTo -> path.cubicTo(
                command.x1, command.y1, command.x2, command.y2, command.x3, command.y3,
            )
            PathCommand.Close -> path.close()
        }
        return path
    }

    /** Frees the shared mask bitmaps. They hold no published pixels, so this one is safe. */
    @Synchronized
    fun clearMaskCache() {
        maskCache.clear()
    }

    // -----------------------------------------------------------------------
    // Appearances (FR-14)
    // -----------------------------------------------------------------------

    /** Default and Dark: the app's own artwork, with only the background layer changed. */
    private fun drawAdaptive(canvas: Canvas, adaptive: AdaptiveIconDrawable, size: Int, style: IconStyle) {
        adaptive.setBounds(0, 0, size, size)
        val background = adaptive.background
        if (background != null) {
            val previous = background.colorFilter
            if (style.appearance == IconAppearance.DARK) background.colorFilter = darkenFilter()
            background.draw(canvas)
            background.colorFilter = previous
        } else if (style.appearance == IconAppearance.DARK) {
            canvas.drawColor(Color.argb(255, 32, 32, 32))
        }
        adaptive.foreground?.draw(canvas)
    }

    /**
     * Clear and Tinted: one monochrome glyph on a neutral ground.
     *
     * The glyph is the app's own `monochrome` layer where it ships one, and the generated fallback
     * where it does not (FR-15), so the two cases are indistinguishable on screen.
     */
    private fun drawMonochrome(canvas: Canvas, adaptive: AdaptiveIconDrawable, size: Int, style: IconStyle) {
        canvas.drawColor(groundColor(style))
        val ink = inkColor(style)
        val monochrome = monochromeLayer(adaptive)
        if (monochrome != null) {
            adaptive.setBounds(0, 0, size, size)
            val previous = monochrome.colorFilter
            monochrome.setTint(ink)
            monochrome.draw(canvas)
            monochrome.colorFilter = previous
            return
        }
        val glyph = generatedGlyph(adaptive, size, ink) ?: run {
            // No glyph could be derived: the app's own icon beats an empty tile.
            drawAdaptive(canvas, adaptive, size, style.copy(appearance = IconAppearance.DEFAULT))
            return
        }
        canvas.drawBitmap(glyph, 0f, 0f, null)
    }

    /** The app's monochrome adaptive layer, where the platform and the app both provide one. */
    private fun monochromeLayer(adaptive: AdaptiveIconDrawable): Drawable? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) adaptive.monochrome else null

    /**
     * The FR-15 fallback: rasterize the foreground layer alone, derive a coverage map from it, and
     * paint that map in [ink].
     */
    private fun generatedGlyph(adaptive: AdaptiveIconDrawable, size: Int, ink: Int): Bitmap? {
        val foreground = adaptive.foreground ?: return null
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        adaptive.setBounds(0, 0, size, size)
        foreground.draw(Canvas(layer))
        val pixels = IntArray(size * size)
        layer.getPixels(pixels, 0, size, 0, 0, size, size)
        val coverage = MonochromeGlyph.coverage(pixels, size, size) ?: return null
        val rgb = ink and 0x00FFFFFF
        val inkAlpha = Color.alpha(ink)
        for (index in pixels.indices) {
            val alpha = (coverage[index] * inkAlpha).toInt().coerceIn(0, 255)
            pixels[index] = (alpha shl 24) or rgb
        }
        val glyph = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        glyph.setPixels(pixels, 0, size, 0, 0, size, size)
        return glyph
    }

    /**
     * The ground a monochrome glyph sits on.
     *
     * Clear is literal glass: a translucent neutral that lets the wallpaper through. Tinted keeps
     * the same ground so the two appearances share a silhouette and only the ink differs.
     */
    private fun groundColor(style: IconStyle): Int =
        if (style.dark) Color.argb(46, 255, 255, 255) else Color.argb(26, 0, 0, 0)

    /**
     * The glyph colour.
     *
     * Clear is a plain neutral, white on dark and black on light. Tinted blends that neutral
     * towards the chosen tint by the intensity, so 0 is the neutral Clear glyph and 100 is the full
     * tint. Treating intensity as a blend rather than as an opacity is what keeps the icon legible
     * at every setting instead of fading it out at low values.
     */
    private fun inkColor(style: IconStyle): Int {
        val neutral = if (style.dark) Color.WHITE else Color.BLACK
        if (style.appearance != IconAppearance.TINTED) return neutral
        val amount = style.tintIntensity.coerceIn(0, IconStyle.MAX_TINT_INTENSITY) / 100f
        return blend(neutral, style.tint or (0xFF shl 24), amount)
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return Color.argb(255, channel(16), channel(8), channel(0))
    }

    /** Dark appearance: the background layer only, pulled down without crushing it to black. */
    private fun darkenFilter(): ColorMatrixColorFilter =
        ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            DARK_SCALE, 0f, 0f, 0f, 0f,
            0f, DARK_SCALE, 0f, 0f, 0f,
            0f, 0f, DARK_SCALE, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))

    private const val DARK_SCALE = 0.45f

    // -----------------------------------------------------------------------
    // Legacy and pack artwork
    // -----------------------------------------------------------------------

    /** Draws artwork that owns its own silhouette, scaled to fit and centred. */
    private fun drawLegacy(canvas: Canvas, drawable: Drawable, size: Int) {
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
    }

    // -----------------------------------------------------------------------
    // Shape mask (FR-16)
    // -----------------------------------------------------------------------

    /**
     * Clips the drawn layers to the shape.
     *
     * Compositing an anti-aliased mask with `DST_IN` rather than calling `Canvas.clipPath` is what
     * gives the squircle a clean edge: a software `clipPath` is hard-edged, which at a 60dp icon
     * reads as a visibly jagged corner.
     */
    private fun applyMask(canvas: Canvas, shape: IconShape, size: Int) {
        val mask = maskCache.getOrPut(shape to size) { buildMask(shape, size) }
        canvas.drawBitmap(mask, 0f, 0f, maskPaint)
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private fun buildMask(shape: IconShape, size: Int): Bitmap {
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        Canvas(mask).drawPath(maskPath(shape, size), paint)
        return mask
    }
}
