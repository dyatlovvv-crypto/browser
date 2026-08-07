package ru.srr.safari.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs

/**
 * Interactive edge-swipe back (iOS pop).
 *
 * Dedicated left-edge strip over [parent] (typically the WebView FrameLayout).
 * Does NOT attach to the WebView itself — otherwise Google «Режим ИИ» (left tab)
 * taps are eaten. [topExcludeDp] keeps SERP tabs clickable.
 */
class EdgeBackGesture(
    private val parent: ViewGroup,
    private val slideTarget: View,
    private val underlay: View,
    private val edgeWidthDp: Float = 22f,
    private val topExcludeDp: Float = 168f,
    private val canGoBack: () -> Boolean,
    private val onCommitBack: () -> Unit,
    private val onDownExtra: (() -> Unit)? = null
) {
    private var tracking = false
    private var dragging = false
    private var startX = 0f
    private var startY = 0f
    private val touchSlop = ViewConfiguration.get(parent.context).scaledTouchSlop
    private var edgeStrip: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        detach()
        val density = parent.resources.displayMetrics.density
        val strip = View(parent.context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            // No elevation — extra HW layers over WebView freeze Google AI Mode paint on ColorOS.
        }
        val lp = FrameLayout.LayoutParams(
            (edgeWidthDp * density).toInt().coerceAtLeast(1),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START
        ).apply {
            topMargin = (topExcludeDp * density).toInt()
        }
        parent.addView(strip, lp)
        strip.setOnTouchListener { _, event -> handle(event) }
        edgeStrip = strip
    }

    fun detach() {
        edgeStrip?.let { strip ->
            strip.setOnTouchListener(null)
            (strip.parent as? ViewGroup)?.removeView(strip)
        }
        edgeStrip = null
        tracking = false
        dragging = false
        resetLayers(animated = false)
    }

    private fun handle(event: MotionEvent): Boolean {
        val w = slideTarget.width.toFloat().coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onDownExtra?.invoke()
                if (!canGoBack()) {
                    tracking = false
                    dragging = false
                    return false
                }
                tracking = true
                dragging = false
                startX = event.rawX
                startY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = (event.rawX - startX).coerceAtLeast(0f)
                val dy = abs(event.rawY - startY)
                if (!dragging) {
                    if (dx < touchSlop && dy < touchSlop) return true
                    if (dy > dx) {
                        tracking = false
                        return false
                    }
                    dragging = true
                    underlay.visibility = View.VISIBLE
                    underlay.alpha = 1f
                    underlay.scaleX = 0.92f
                    underlay.scaleY = 0.92f
                    underlay.translationX = -w * 0.18f
                }
                val p = (dx / w).coerceAtLeast(0f).coerceAtMost(1f)
                slideTarget.translationX = dx
                underlay.translationX = -w * 0.18f * (1f - p)
                val s = 0.92f + 0.08f * p
                underlay.scaleX = s
                underlay.scaleY = s
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                val wasDragging = dragging
                tracking = false
                dragging = false
                if (!wasDragging) return true
                val dx = (event.rawX - startX).coerceAtLeast(0f)
                val commit = dx > w * 0.38f && event.actionMasked == MotionEvent.ACTION_UP
                if (commit) {
                    SafariMotion.snapX(slideTarget, w) {
                        onCommitBack()
                        resetLayers(animated = false)
                    }
                    SafariMotion.spring(underlay, DynamicAnimation.SCALE_X, 1f)
                    SafariMotion.spring(underlay, DynamicAnimation.SCALE_Y, 1f)
                    SafariMotion.snapX(underlay, 0f)
                } else {
                    resetLayers(animated = true)
                }
                return true
            }
        }
        return false
    }

    private fun resetLayers(animated: Boolean) {
        if (animated) {
            SafariMotion.snapX(slideTarget, 0f)
            SafariMotion.snapX(underlay, 0f)
            SafariMotion.spring(underlay, DynamicAnimation.SCALE_X, 1f)
            SafariMotion.spring(underlay, DynamicAnimation.SCALE_Y, 1f) {
                underlay.visibility = View.GONE
            }
        } else {
            slideTarget.translationX = 0f
            underlay.translationX = 0f
            underlay.scaleX = 1f
            underlay.scaleY = 1f
            underlay.visibility = View.GONE
        }
    }
}
