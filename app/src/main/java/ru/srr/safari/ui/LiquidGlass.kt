package ru.srr.safari.ui

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import ru.srr.safari.R
import ru.srr.safari.data.BrowserSettings

/** Liquid Glass: blur (API 31+), stroke refraction, squircle clip, spring press. */
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

    fun capsuleDrawable(context: Context, opacityPercent: Int, cornerDp: Float = 22f): Drawable {
        val d = density(context)
        return glassRect(
            context,
            ContextCompat.getColor(context, R.color.safari_glass_fill),
            cornerDp * d,
            opacityPercent
        )
    }

    /** Mode switch liquid droplet — solid fill tint (light ↔ private), glass stroke. */
    fun modeBlobDrawable(context: Context, fillRgb: Int, opacityPercent: Int, cornerDp: Float = 18f): Drawable {
        val d = density(context)
        return glassRect(context, fillRgb or 0xFF000000.toInt(), cornerDp * d, opacityPercent.coerceIn(70, 100))
    }

    /** Mutate body fill of a [modeBlobDrawable] without reallocating layers. */
    fun updateModeBlobFill(drawable: Drawable?, fillRgb: Int, opacityPercent: Int): Boolean {
        val layer = drawable as? LayerDrawable ?: return false
        val body = layer.getDrawable(0) as? GradientDrawable ?: return false
        body.setColor(withAlpha(fillRgb or 0xFF000000.toInt(), opacityPercent.coerceIn(70, 100)))
        return true
    }

    /**
     * Menu / sheet glass with denser scrim so text stays readable over web pages.
     * Light: apple grey-white ~72–80% + tint scrim.
     * Private: true-black scrim ~75% under glass body.
     */
    fun menuPopoverDrawable(
        context: Context,
        opacityPercent: Int,
        cornerDp: Float = 26f,
        privateMode: Boolean = false
    ): Drawable {
        val d = density(context)
        val cornerPx = cornerDp * d
        val strokeW = (1.1f * d).toInt().coerceAtLeast(1)
        val floor = if (privateMode) 75 else 72
        val bodyOpacity = opacityPercent.coerceIn(floor, 100)

        val scrim = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setColor(
                ContextCompat.getColor(
                    context,
                    if (privateMode) R.color.safari_menu_scrim_private else R.color.safari_menu_scrim_light
                )
            )
        }
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            val fill = ContextCompat.getColor(
                context,
                if (privateMode) R.color.safari_mode_blob_private else R.color.safari_popover
            )
            setColor(withAlpha(fill, bodyOpacity))
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
            setStroke(strokeW, ContextCompat.getColor(context, R.color.safari_glass_stroke))
        }
        val innerHighlight = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (cornerPx - strokeW).coerceAtLeast(0f)
            setColor(0x00000000)
            setStroke(
                strokeW,
                ContextCompat.getColor(context, R.color.safari_glass_inner_stroke)
            )
        }
        return LayerDrawable(arrayOf(scrim, body, specular, stroke, innerHighlight)).apply {
            val inset = strokeW
            setLayerInset(4, inset, inset, inset, inset)
        }
    }

    /** @deprecated Prefer [menuPopoverDrawable] for menus; kept for address chrome. */
    fun popoverDrawable(context: Context, opacityPercent: Int, cornerDp: Float = 26f): Drawable {
        return menuPopoverDrawable(context, opacityPercent, cornerDp, privateMode = false)
    }

    fun circleDrawable(context: Context, opacityPercent: Int): Drawable {
        return glassOval(
            context,
            ContextCompat.getColor(context, R.color.safari_circle_fill),
            opacityPercent
        )
    }

    fun polishCapsule(view: View, cornerDp: Float = 22f, pressFeedback: Boolean = true) {
        view.clipToOutline = true
        view.outlineProvider = SquircleOutlineProvider(cornerDp)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.invalidateOutline()
        }
        // Glass = translucent fill + inner stroke (blur not applied to chrome text/icons).
        if (pressFeedback) attachSpringPress(view, 0.97f)
    }

    fun polishCircle(view: View) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        attachSpringPress(view, 0.94f)
    }

    fun polishChrome(view: View) {
        view.elevation = 0f
    }

    fun polishSheet(view: View) {
        view.elevation = 0f
    }

    fun polishTabCard(view: View, cornerDp: Float = 18f) {
        view.clipToOutline = true
        view.outlineProvider = SquircleOutlineProvider(cornerDp)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.invalidateOutline()
        }
    }

    /**
     * Frost a decorative backdrop layer (not interactive chrome).
     * API 31+: RenderEffect blur of the view's own pixels.
     * Older: no-op — rely on translucent XML fill + stroke.
     */
    fun polishFrostedBackdrop(view: View, radiusPx: Float = 32f) {
        applyBackdropBlur(view, radiusPx)
    }

    fun clearBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }

    private fun applyBackdropBlur(view: View, radiusPx: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(
                    RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                )
            } catch (_: Throwable) {
                view.setRenderEffect(null)
            }
        }
    }

    private fun glassRect(
        context: Context,
        fill: Int,
        cornerPx: Float,
        opacityPercent: Int
    ): Drawable {
        val strokeW = (1.1f * density(context)).toInt().coerceAtLeast(1)
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
            setStroke(strokeW, ContextCompat.getColor(context, R.color.safari_glass_stroke))
        }
        val innerHighlight = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (cornerPx - strokeW).coerceAtLeast(0f)
            setColor(0x00000000)
            setStroke(
                strokeW,
                ContextCompat.getColor(context, R.color.safari_glass_inner_stroke)
            )
        }
        return LayerDrawable(arrayOf(body, specular, stroke, innerHighlight)).apply {
            val inset = strokeW
            setLayerInset(3, inset, inset, inset, inset)
        }
    }

    private fun glassOval(context: Context, fill: Int, opacityPercent: Int): Drawable {
        val strokeW = (1.1f * density(context)).toInt().coerceAtLeast(1)
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
            setStroke(strokeW, ContextCompat.getColor(context, R.color.safari_glass_stroke))
        }
        return LayerDrawable(arrayOf(body, specular, stroke))
    }

    private fun withAlpha(color: Int, opacityPercent: Int): Int {
        val rgb = color and 0x00FFFFFF
        return (alphaByte(opacityPercent) shl 24) or rgb
    }

    private fun density(context: Context): Float =
        context.resources.displayMetrics.density

    private fun attachSpringPress(view: View, pressedScale: Float) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    SafariMotion.spring(
                        v, DynamicAnimation.SCALE_X, pressedScale,
                        SafariMotion.STIFFNESS, SafariMotion.DAMPING
                    )
                    SafariMotion.spring(
                        v, DynamicAnimation.SCALE_Y, pressedScale,
                        SafariMotion.STIFFNESS, SafariMotion.DAMPING
                    )
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    SafariMotion.spring(
                        v, DynamicAnimation.SCALE_X, 1f,
                        SafariMotion.STIFFNESS, SafariMotion.DAMPING
                    )
                    SafariMotion.spring(
                        v, DynamicAnimation.SCALE_Y, 1f,
                        SafariMotion.STIFFNESS, SafariMotion.DAMPING
                    )
                }
            }
            false
        }
    }
}
