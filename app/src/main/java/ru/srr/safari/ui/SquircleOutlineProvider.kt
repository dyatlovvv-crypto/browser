package ru.srr.safari.ui

import android.graphics.Outline
import android.graphics.Path
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider

/**
 * iOS-like smooth corners (squircle approximation).
 * Uses continuous-corner path when available; falls back to round-rect outline.
 */
class SquircleOutlineProvider(
    private val cornerDp: Float
) : ViewOutlineProvider() {

    override fun getOutline(view: View, outline: Outline) {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        val r = (cornerDp * view.resources.displayMetrics.density)
            .coerceAtMost(minOf(w, h) / 2f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outline.setPath(smoothCornerPath(w.toFloat(), h.toFloat(), r))
        } else {
            outline.setRoundRect(0, 0, w, h, r)
        }
    }

    companion object {
        /** Cubic approximation of Apple continuous corners (~0.55 control). */
        fun smoothCornerPath(width: Float, height: Float, radius: Float): Path {
            val r = radius.coerceAtMost(minOf(width, height) / 2f)
            val k = 0.55f * r
            return Path().apply {
                moveTo(r, 0f)
                lineTo(width - r, 0f)
                cubicTo(width - r + k, 0f, width, r - k, width, r)
                lineTo(width, height - r)
                cubicTo(width, height - r + k, width - r + k, height, width - r, height)
                lineTo(r, height)
                cubicTo(r - k, height, 0f, height - r + k, 0f, height - r)
                lineTo(0f, r)
                cubicTo(0f, r - k, r - k, 0f, r, 0f)
                close()
            }
        }
    }
}
