package ru.srr.safari.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

/**
 * Interactive edge-swipe back (iOS pop):
 * [slideTarget] follows finger on X; [underlay] scales up from depth.
 * [touchTarget] receives touches (typically WebView). Extra DOWN work via [onDownExtra].
 */
class EdgeBackGesture(
    private val touchTarget: View,
    private val slideTarget: View,
    private val underlay: View,
    private val edgeWidthDp: Float = 28f,
    private val canGoBack: () -> Boolean,
    private val onCommitBack: () -> Unit,
    private val onDownExtra: (() -> Unit)? = null
) {
    private var tracking = false
    private var startX = 0f
    private var startY = 0f
    private val touchSlop = ViewConfiguration.get(touchTarget.context).scaledTouchSlop

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        touchTarget.setOnTouchListener { _, event -> handle(event) }
    }

    fun detach() {
        touchTarget.setOnTouchListener(null)
        resetLayers(animated = false)
    }

    private fun edgePx(): Float =
        edgeWidthDp * touchTarget.resources.displayMetrics.density

    private fun handle(event: MotionEvent): Boolean {
        val w = slideTarget.width.toFloat().coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onDownExtra?.invoke()
                if (!canGoBack()) return false
                if (event.x > edgePx()) return false
                tracking = true
                startX = event.rawX
                startY = event.rawY
                underlay.visibility = View.VISIBLE
                underlay.alpha = 1f
                underlay.scaleX = 0.92f
                underlay.scaleY = 0.92f
                underlay.translationX = -w * 0.18f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = (event.rawX - startX).coerceAtLeast(0f)
                val dy = abs(event.rawY - startY)
                if (dy > dx && dx < touchSlop) return true
                val p = (dx / w).coerceIn(0f, 1f)
                slideTarget.translationX = dx
                underlay.translationX = -w * 0.18f * (1f - p)
                val s = 0.92f + 0.08f * p
                underlay.scaleX = s
                underlay.scaleY = s
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                val dx = (event.rawX - startX).coerceAtLeast(0f)
                val commit = dx > w * 0.38f && event.actionMasked == MotionEvent.ACTION_UP
                if (commit) {
                    SafariMotion.snapX(
                        slideTarget, w,
                        SpringForce.STIFFNESS_MEDIUM,
                        SpringForce.DAMPING_RATIO_NO_BOUNCY
                    ) {
                        onCommitBack()
                        resetLayers(animated = false)
                    }
                    SafariMotion.spring(
                        underlay, DynamicAnimation.SCALE_X, 1f,
                        SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY
                    )
                    SafariMotion.spring(
                        underlay, DynamicAnimation.SCALE_Y, 1f,
                        SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY
                    )
                    SafariMotion.snapX(
                        underlay, 0f,
                        SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY
                    )
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
            SafariMotion.snapX(
                slideTarget, 0f,
                SpringForce.STIFFNESS_MEDIUM,
                SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            )
            SafariMotion.snapX(
                underlay, 0f,
                SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY
            )
            SafariMotion.spring(
                underlay, DynamicAnimation.SCALE_X, 1f,
                SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY
            )
            SafariMotion.spring(
                underlay, DynamicAnimation.SCALE_Y, 1f,
                SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY
            ) {
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
