package com.blackforestbytes.projectivy.jellyfinwallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.util.Locale
import kotlin.math.min

/**
 * Draws backdrop, logo and metadata into a single image, the way the z9m plugin does.
 *
 * Every length below is in pixels of a 3840x2160 reference frame and scaled by [scaleOf], so the
 * layout is identical at 1080p and at 4K.
 */
object WallpaperComposer {

    private const val REF_WIDTH = 3840f

    /** Backdrop covers this fraction of the frame and is pinned to the top-right corner. */
    private const val ART_SCALE = 0.8f

    private const val PADDING_LEFT = 130f
    private const val CONTENT_WIDTH_RATIO = 0.45f
    private const val CONTENT_TOP_RATIO = 0.18f
    private const val LOGO_MAX_WIDTH = 1400f
    private const val LOGO_MAX_HEIGHT = 500f
    private const val TITLE_SIZE = 135f
    private const val META_SIZE = 46f
    private const val OVERVIEW_SIZE = 39f
    private const val BLOCK_GAP = 40f

    private const val META_COLOR = 0xFFFFD700.toInt()
    private const val OVERVIEW_COLOR = 0xFFDDDDDD.toInt()

    private fun scaleOf(width: Int) = width / REF_WIDTH

    /** Width to request the logo at, so it arrives no larger than it will be drawn. */
    fun logoRequestWidth(width: Int): Int = (LOGO_MAX_WIDTH * scaleOf(width)).toInt()

    /** Scales a frame dimension down to the box the backdrop is drawn into. */
    fun artSize(dimension: Int): Int = (dimension * ART_SCALE).toInt()

    fun compose(meta: ComposeMeta, art: Bitmap?, logo: Bitmap?, width: Int, height: Int): Bitmap {
        val scale = scaleOf(width)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val artWidth = width * ART_SCALE
        val artHeight = height * ART_SCALE
        val artLeft = width - artWidth
        if (art != null) {
            canvas.drawBitmap(
                art, null,
                RectF(artLeft, 0f, width.toFloat(), artHeight),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        }

        // Fade the backdrop's left edge into the black panel that carries the text…
        canvas.drawRect(0f, 0f, width.toFloat(), artHeight, Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, artLeft / width + 0.05f, 0.9f),
                Shader.TileMode.CLAMP
            )
        })
        // …and its bottom edge into the black below it.
        canvas.drawRect(0f, artHeight * 0.5f, width.toFloat(), artHeight, Paint().apply {
            shader = LinearGradient(
                0f, artHeight * 0.5f, 0f, artHeight,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
            )
        })

        val left = PADDING_LEFT * scale
        val contentWidth = (width * CONTENT_WIDTH_RATIO).toInt()
        var y = height * CONTENT_TOP_RATIO

        if (logo != null) {
            val fit = min(
                LOGO_MAX_WIDTH * scale / logo.width,
                LOGO_MAX_HEIGHT * scale / logo.height
            )
            val logoWidth = logo.width * fit
            val logoHeight = logo.height * fit
            canvas.drawBitmap(
                logo, null,
                RectF(left, y, left + logoWidth, y + logoHeight),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            y += logoHeight
        } else {
            y += drawText(
                canvas, meta.title, left, y, contentWidth, maxLines = 2,
                paint = textPaint(Color.WHITE, TITLE_SIZE * scale, bold = true).apply {
                    setShadowLayer(8f * scale, 0f, 4f * scale, Color.BLACK)
                }
            )
        }

        metaLine(meta)?.let { line ->
            y += BLOCK_GAP * scale
            y += drawText(
                canvas, line, left, y, contentWidth, maxLines = 2,
                paint = textPaint(META_COLOR, META_SIZE * scale, bold = true)
            )
        }

        meta.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            y += BLOCK_GAP * scale
            drawText(
                canvas, overview.trim(), left, y, contentWidth, maxLines = 3,
                paint = textPaint(OVERVIEW_COLOR, OVERVIEW_SIZE * scale, bold = false)
            )
        }

        return out
    }

    private fun metaLine(meta: ComposeMeta): String? {
        val parts = buildList {
            meta.year?.let { add(it.toString()) }
            meta.runtimeMinutes?.takeIf { it > 0 }?.let { add("${it}m") }
            meta.communityRating?.let { add(String.format(Locale.US, "★ %.1f", it)) }
            meta.officialRating?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(meta.genres.take(2))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("   •   ")
    }

    private fun textPaint(color: Int, size: Float, bold: Boolean) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    /** Returns the height consumed. */
    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        width: Int,
        maxLines: Int,
        paint: TextPaint,
    ): Float {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.1f)
            .build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return layout.height.toFloat()
    }

    /**
     * Decodes at the smallest power-of-two subsample that still covers [reqWidth]. A 4K backdrop is
     * 21 MB once decoded, and the canvas it lands on is another 33 MB.
     */
    fun decode(bytes: ByteArray, reqWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
}
