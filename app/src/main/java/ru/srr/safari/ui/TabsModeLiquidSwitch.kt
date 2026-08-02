package ru.srr.safari.ui

import android.annotation.SuppressLint
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import ru.srr.safari.R
import kotlin.math.abs
import kotlin.math.sin

/**
 * iOS-style All ↔ Private switch: track follows finger, liquid blob
 * stretches (mercury) with Spring settle, tint lerps light → private.
 *
 * progress: 0 = All (normal), 1 = Private
 */
class TabsModeLiquidSwitch(
    private val host: FrameLayout,
    private val blob: View,
    private val track: ViewGroup,
    private val labelPrivate: TextView,
    private val labelAll: TextView,
    private val glassOpacity: () -> Int,
    private val isPrivateMode: () -> Boolean,
    private val onCommit: (private: Boolean) -> Unit
) {
    /** 0 = All, 1 = Private */
    var progress: Float = 0f
        private set

    private var spring: SpringAnimation? = null
    private var dragging = false
    private var downX = 0f
    private var startProgress = 0f
    private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val density = host.resources.displayMetrics.density
    private val stretchMaxPx = 22f * density
    private val cornerDp = 18f

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        progress = if (isPrivateMode()) 1f else 0f
        polishLabels()
        LiquidGlass.polishCapsule(blob, cornerDp, pressFeedback = false)
        host.setOnTouchListener { _, event -> handleTouch(event) }
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!dragging) applyVisuals(progress, animateBlobLayout = false)
        }
        applyVisuals(progress, animateBlobLayout = false)
    }

    fun syncFromMode(animate: Boolean) {
        val target = if (isPrivateMode()) 1f else 0f
        if (animate) springTo(target, commit = false)
        else {
            cancelSpring()
            progress = target
            applyVisuals(progress, animateBlobLayout = false)
        }
    }

    fun refreshChrome() {
        applyVisuals(progress, animateBlobLayout = false)
    }

    private fun polishLabels() {
        listOf(labelPrivate, labelAll).forEach { tv ->
            tv.background = null
            tv.clipToOutline = true
            tv.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = cornerDp * density
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.invalidateOutline()
            }
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSpring()
                downX = event.x
                startProgress = progress
                dragging = false
                host.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!dragging && abs(dx) > touchSlop) dragging = true
                if (dragging) {
                    val range = swipeRange().coerceAtLeast(1f)
                    // Finger right → toward Private (labels sit Private | All, track moves right for Private)
                    val next = (startProgress + dx / range).coerceIn(0f, 1f)
                    progress = next
                    applyVisuals(progress, animateBlobLayout = false)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                host.parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    dragging = false
                    val commitPrivate = progress >= 0.5f
                    springTo(if (commitPrivate) 1f else 0f, commit = true)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    // Tap: hit-test labels in host space
                    val xInTrack = event.x - track.translationX
                    val target = when {
                        xInTrack < labelPrivate.right -> 1f
                        xInTrack > labelAll.left -> 0f
                        else -> if (progress >= 0.5f) 0f else 1f
                    }
                    springTo(target, commit = true)
                }
                return true
            }
        }
        return false
    }

    private fun swipeRange(): Float {
        val a = trackTargetX(private = false)
        val b = trackTargetX(private = true)
        val fromTrack = abs(b - a)
        return if (fromTrack > 8f) fromTrack else host.width * 0.42f
    }

    private fun springTo(target: Float, commit: Boolean) {
        cancelSpring()
        val holder = FloatValueHolder(progress)
        spring = SpringAnimation(holder).setSpring(
            SpringForce(target).setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
        ).also { anim ->
            anim.addUpdateListener { _, value, _ ->
                progress = value
                applyVisuals(progress, animateBlobLayout = false)
            }
            anim.addEndListener { _, canceled, value, _ ->
                if (!canceled) {
                    progress = value
                    applyVisuals(progress, animateBlobLayout = false)
                    if (commit) {
                        val wantPrivate = progress >= 0.5f
                        if (wantPrivate != isPrivateMode()) onCommit(wantPrivate)
                    }
                }
            }
            anim.start()
        }
    }

    private fun cancelSpring() {
        spring?.cancel()
        spring = null
    }

    private fun trackTargetX(private: Boolean): Float {
        val active = if (private) labelPrivate else labelAll
        if (host.width == 0 || active.width == 0) return 0f
        val activeCenter = active.left + active.width / 2f
        return host.width / 2f - activeCenter
    }

    private fun applyVisuals(p: Float, animateBlobLayout: Boolean) {
        if (host.width == 0) return
        val tx = lerp(trackTargetX(false), trackTargetX(true), p)
        track.translationX = tx

        // Label frames in host coordinates
        val allL = labelAll.left + tx
        val allR = labelAll.right + tx
        val privL = labelPrivate.left + tx
        val privR = labelPrivate.right + tx
        val allW = (allR - allL).coerceAtLeast(1f)
        val privW = (privR - privL).coerceAtLeast(1f)

        val stretch = sin(Math.PI.toFloat() * p.coerceIn(0f, 1f)) * stretchMaxPx
        val blobW = lerp(allW, privW, p) + stretch
        val blobCenter = lerp(allL + allW / 2f, privL + privW / 2f, p)
        val blobL = blobCenter - blobW / 2f
        val blobH = labelAll.height.coerceAtLeast(labelPrivate.height).coerceAtLeast(1)
        val blobT = ((host.height - blobH) / 2f).coerceAtLeast(0f)

        val lp = (blob.layoutParams as FrameLayout.LayoutParams).apply {
            width = blobW.toInt().coerceAtLeast(1)
            height = blobH
            leftMargin = blobL.toInt()
            topMargin = blobT.toInt()
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        blob.layoutParams = lp
        if (animateBlobLayout) blob.requestLayout()

        val light = ContextCompat.getColor(host.context, R.color.safari_mode_blob_light)
        val dark = ContextCompat.getColor(host.context, R.color.safari_mode_blob_private)
        val fill = lerpColor(light, dark, p)
        blob.background = LiquidGlass.modeBlobDrawable(host.context, fill, glassOpacity())
        applyBlobBlur(p)

        // Text: dissolve + tighten tracking at peak stretch; clip keeps glyphs inside pill
        val peak = sin(Math.PI.toFloat() * p.coerceIn(0f, 1f))
        val privAlpha = (lerp(0.55f, 1f, p) * (1f - 0.42f * peak)).coerceIn(0.2f, 1f)
        val allAlpha = (lerp(1f, 0.55f, p) * (1f - 0.42f * peak)).coerceIn(0.2f, 1f)
        labelPrivate.alpha = privAlpha
        labelAll.alpha = allAlpha
        labelPrivate.letterSpacing = -0.035f * peak
        labelAll.letterSpacing = -0.035f * peak

        val text = ContextCompat.getColor(host.context, R.color.safari_text)
        val muted = ContextCompat.getColor(host.context, R.color.safari_muted)
        labelPrivate.setTextColor(lerpColor(muted, text, p))
        labelAll.setTextColor(lerpColor(text, muted, p))
        blob.invalidateOutline()
    }

    private fun applyBlobBlur(p: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Soft liquid edge: mild blur grows toward private
        val radius = lerp(0f, 14f, p)
        try {
            if (radius < 0.5f) {
                blob.setRenderEffect(null)
            } else {
                blob.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                )
            }
        } catch (_: Throwable) {
            blob.setRenderEffect(null)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val clampf = t.coerceIn(0f, 1f)
        val aA = (a ushr 24) and 0xFF
        val aR = (a ushr 16) and 0xFF
        val aG = (a ushr 8) and 0xFF
        val aB = a and 0xFF
        val bA = (b ushr 24) and 0xFF
        val bR = (b ushr 16) and 0xFF
        val bG = (b ushr 8) and 0xFF
        val bB = b and 0xFF
        return ((lerp(aA.toFloat(), bA.toFloat(), clampf).toInt() and 0xFF) shl 24) or
            ((lerp(aR.toFloat(), bR.toFloat(), clampf).toInt() and 0xFF) shl 16) or
            ((lerp(aG.toFloat(), bG.toFloat(), clampf).toInt() and 0xFF) shl 8) or
            (lerp(aB.toFloat(), bB.toFloat(), clampf).toInt() and 0xFF)
    }
}
