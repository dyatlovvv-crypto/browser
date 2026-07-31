package ru.srr.safari.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import ru.srr.safari.R
import ru.srr.safari.data.BrowserSettings

/** Soft Liquid Glass press feedback + opacity-aware surfaces. */
object LiquidGlass {

    fun alphaByte(opacityPercent: Int): Int {
        val p = opacityPercent.coerceIn(
            BrowserSettings.MIN_GLASS_OPACITY,
            BrowserSettings.MAX_GLASS_OPACITY
        )
        return (p * 255) / 100
    }

    fun applyOpacity(view: View, opacityPercent: Int) {
        val d = view.background?.mutate() ?: return
        d.alpha = alphaByte(opacityPercent)
        view.background = d
    }

    fun popoverDrawable(context: Context, opacityPercent: Int): Drawable {
        val d = density(context)
        return glassRect(
            context,
            ContextCompat.getColor(context, R.color.safari_popover),
            26f * d,
            opacityPercent
        )
    }

    fun capsuleDrawable(context: Context, opacityPercent: Int, cornerDp: Float = 22f): Drawable {
        val d = density(context)
        return glassRect(
            context,
            ContextCompat.getColor(context, R.color.safari_glass_fill),
            cornerDp * d,
            opacityPercent
        )
    }

    fun circleDrawable(context: Context, opacityPercent: Int): Drawable {
        return glassOval(
            context,
            ContextCompat.getColor(context, R.color.safari_circle_fill),
            opacityPercent
        )
    }

    fun polishCapsule(view: View, cornerDp: Float = 22f) {
        val r = cornerDp * view.resources.displayMetrics.density
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
        attachPress(view, 0.985f)
    }

    fun polishCircle(view: View) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        attachPress(view, 0.96f)
    }

    fun polishChrome(view: View) {
        view.elevation = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }

    fun polishSheet(view: View) {
        view.elevation = 0f
    }

    private fun glassRect(
        context: Context,
        fill: Int,
        cornerPx: Float,
        opacityPercent: Int
    ): Drawable {
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setColor(withAlpha(fill, opacityPercent))
        }
        val specular = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            colors = intArrayOf(
                ContextCompat.getColor(context, R.color.safari_glass_specular),
                0x00FFFFFF
            )
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        val stroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setColor(0x00000000)
            setStroke(
                (1f * density(context)).toInt().coerceAtLeast(1),
                ContextCompat.getColor(context, R.color.safari_glass_stroke)
            )
        }
        return LayerDrawable(arrayOf(body, specular, stroke))
    }

    private fun glassOval(context: Context, fill: Int, opacityPercent: Int): Drawable {
        val body = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(fill, opacityPercent))
        }
        val specular = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(
                ContextCompat.getColor(context, R.color.safari_glass_specular),
                0x00FFFFFF
            )
            orientation = GradientDrawable.Orientation.TL_BR
        }
        val stroke = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000)
            setStroke(
                (1f * density(context)).toInt().coerceAtLeast(1),
                ContextCompat.getColor(context, R.color.safari_glass_stroke)
            )
        }
        return LayerDrawable(arrayOf(body, specular, stroke))
    }

    private fun withAlpha(color: Int, opacityPercent: Int): Int {
        val rgb = color and 0x00FFFFFF
        return (alphaByte(opacityPercent) shl 24) or rgb
    }

    private fun density(context: Context): Float =
        context.resources.displayMetrics.density

    private fun attachPress(view: View, pressedScale: Float) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(SafariMotion.PRESS_IN)
                        .setInterpolator(SafariMotion.softInOut)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(SafariMotion.PRESS_OUT)
                        .setInterpolator(SafariMotion.softOut)
                        .start()
                }
            }
            false
        }
    }
}
