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
import androidx.dynamicanimation.animation.SpringAnimation
import ru.srr.safari.R
import ru.srr.safari.data.BrowserSettings

/** Liquid Glass: refraction strokes, specular, squircle clip, spring press (no dup listeners). */
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

    fun capsuleDrawable(context: Context, opacityPercent: Int, cornerDp: Float = 24f): Drawable {
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
        // Body is index 1 after edge layer.
        val body = layer.getDrawable(1) as? GradientDrawable ?: return false
        body.setColor(withAlpha(fillRgb or 0xFF000000.toInt(), opacityPercent.coerceIn(70, 100)))
        return true
    }

    /**
     * Menu / sheet glass with denser scrim so text stays readable over web pages.
     * Light: apple grey-white + tint scrim. Private: true-black scrim under glass body.
     */
    fun menuPopoverDrawable(
        context: Context,
        opacityPercent: Int,
        cornerDp: Float = 28f,
        privateMode: Boolean = false
    ): Drawable {
        val d = density(context)
        val cornerPx = cornerDp * d
        val strokeW = (1f * d).toInt().coerceAtLeast(1)
        val floor = if (privateMode) 80 else 76
        val bodyOpacity = opacityPercent.coerceIn(floor, 100)

        val edge = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setColor(0x00000000)
            setStroke(strokeW, ContextCompat.getColor(context, R.color.safari_glass_edge))
        }
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
                ContextCompat.getColor(context, R.color.safari_glass_specular_mid),
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
        return LayerDrawable(arrayOf(edge, scrim, body, specular, stroke, innerHighlight)).apply {
            setLayerInset(5, strokeW, strokeW, strokeW, strokeW)
        }
    }

    /** @deprecated Prefer [menuPopoverDrawable] for menus; kept for address chrome. */
    fun popoverDrawable(context: Context, opacityPercent: Int, cornerDp: Float = 28f): Drawable {
        return menuPopoverDrawable(context, opacityPercent, cornerDp, privateMode = false)
    }

    fun circleDrawable(context: Context, opacityPercent: Int): Drawable {
        return glassOval(
            context,
            ContextCompat.getColor(context, R.color.safari_circle_fill),
            opacityPercent
        )
    }

    fun polishCapsule(view: View, cornerDp: Float = 24f, pressFeedback: Boolean = true) {
        view.clipToOutline = true
        view.outlineProvider = SquircleOutlineProvider(cornerDp)
        attachOutlineInvalidator(view)
        if (pressFeedback) attachSpringPress(view, 0.97f)
    }

    fun polishCircle(view: View) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        attachOutlineInvalidator(view)
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
        attachOutlineInvalidator(view)
    }

    /**
     * Frost a decorative backdrop layer (not interactive chrome).
     * API 31+: RenderEffect blur of the view's own pixels.
     * Default radius ≥ 25dp for premium liquid glass.
     */
    fun polishFrostedBackdrop(view: View, radiusPx: Float = 0f) {
        val r = if (radiusPx > 0f) radiusPx else blurRadiusPx(view.context)
        applyBackdropBlur(view, r)
    }

    fun blurRadiusPx(context: Context): Float {
        val fromRes = try {
            context.resources.getDimension(R.dimen.safari_glass_blur)
        } catch (_: Exception) {
            0f
        }
        return if (fromRes > 0f) fromRes else 28f * density(context)
    }

    /** Full-bleed translucent glass wash over a blurred page snapshot. */
    fun dialogBodyDrawable(context: Context, opacityPercent: Int = 66): Drawable {
        val fill = ContextCompat.getColor(context, R.color.safari_glass_fill)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(withAlpha(fill, opacityPercent.coerceIn(40, 90)))
        }
    }

    fun clearBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }

    /** Cancel in-flight press springs (call from Activity onDestroy / dialog dismiss). */
    fun clearPress(view: View) {
        cancelPressSprings(view)
        view.scaleX = 1f
        view.scaleY = 1f
        if (view.getTag(R.id.tag_glass_press) == true) {
            view.setOnTouchListener(null)
            view.setTag(R.id.tag_glass_press, null)
        }
    }

    /** Remove outline invalidator so config-recreated / dialog views do not retain listeners. */
    fun clearOutline(view: View) {
        val listener = view.getTag(R.id.tag_glass_outline) as? View.OnLayoutChangeListener ?: return
        view.removeOnLayoutChangeListener(listener)
        view.setTag(R.id.tag_glass_outline, null)
    }

    /** Full glass teardown for destroy / dialog dismiss. */
    fun release(view: View) {
        clearPress(view)
        clearOutline(view)
        clearBlur(view)
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
        val strokeW = (1f * density(context)).toInt().coerceAtLeast(1)
        val edge = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setColor(0x00000000)
            setStroke(strokeW, ContextCompat.getColor(context, R.color.safari_glass_edge))
        }
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
                ContextCompat.getColor(context, R.color.safari_glass_specular_mid),
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
        return LayerDrawable(arrayOf(edge, body, specular, stroke, innerHighlight)).apply {
            setLayerInset(4, strokeW, strokeW, strokeW, strokeW)
        }
    }

    private fun glassOval(context: Context, fill: Int, opacityPercent: Int): Drawable {
        val strokeW = (1f * density(context)).toInt().coerceAtLeast(1)
        val edge = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000)
            setStroke(strokeW, ContextCompat.getColor(context, R.color.safari_glass_edge))
        }
        val body = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(fill, opacityPercent))
        }
        val specular = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(
                ContextCompat.getColor(context, R.color.safari_glass_specular),
                ContextCompat.getColor(context, R.color.safari_glass_specular_mid),
                0x00FFFFFF
            )
            orientation = GradientDrawable.Orientation.TL_BR
        }
        val stroke = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000)
            setStroke(strokeW, ContextCompat.getColor(context, R.color.safari_glass_stroke))
        }
        val innerHighlight = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000)
            setStroke(
                strokeW,
                ContextCompat.getColor(context, R.color.safari_glass_inner_stroke)
            )
        }
        return LayerDrawable(arrayOf(edge, body, specular, stroke, innerHighlight)).apply {
            setLayerInset(4, strokeW, strokeW, strokeW, strokeW)
        }
    }

    private fun withAlpha(color: Int, opacityPercent: Int): Int {
        val rgb = color and 0x00FFFFFF
        return (alphaByte(opacityPercent) shl 24) or rgb
    }

    private fun density(context: Context): Float =
        context.resources.displayMetrics.density

    private fun attachOutlineInvalidator(view: View) {
        if (view.getTag(R.id.tag_glass_outline) != null) return
        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.invalidateOutline()
        }
        view.setTag(R.id.tag_glass_outline, listener)
        view.addOnLayoutChangeListener(listener)
    }

    private fun cancelPressSprings(view: View) {
        @Suppress("UNCHECKED_CAST")
        val springs = view.getTag(R.id.tag_glass_springs) as? Array<SpringAnimation>
        springs?.forEach { anim ->
            try {
                anim.cancel()
            } catch (_: Exception) {
            }
        }
        view.setTag(R.id.tag_glass_springs, null)
    }

    private fun attachSpringPress(view: View, pressedScale: Float) {
        if (view.getTag(R.id.tag_glass_press) == true) return
        view.setTag(R.id.tag_glass_press, true)
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPressSprings(v)
                    // Instant optical press — no wait for spring settle.
                    v.scaleX = pressedScale
                    v.scaleY = pressedScale
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPressSprings(v)
                    val sx = SafariMotion.spring(
                        v, DynamicAnimation.SCALE_X, 1f,
                        SafariMotion.STIFFNESS, SafariMotion.DAMPING
                    )
                    val sy = SafariMotion.spring(
                        v, DynamicAnimation.SCALE_Y, 1f,
                        SafariMotion.STIFFNESS, SafariMotion.DAMPING
                    )
                    v.setTag(R.id.tag_glass_springs, arrayOf(sx, sy))
                }
            }
            false
        }
    }
}
