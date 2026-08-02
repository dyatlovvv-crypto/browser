package ru.srr.safari.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs

/**
 * iOS-style horizontal tab swipe on the address field.
 * Consumes touches so EditText / WebView parents cannot steal ACTION_MOVE,
 * follows the finger, then SpringAnimation commits or rubber-bands back.
 */
class AddressTabSwipe(
    private val touchTarget: View,
    private val slideTarget: View,
    private val canSwipe: () -> Boolean,
    private val tabCount: () -> Int,
    private val onSwitchTab: (next: Boolean) -> Unit,
    private val onTapAddress: () -> Unit
) {
    private var tracking = false
    private var dragging = false
    private var startRawX = 0f
    private var startRawY = 0f
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(touchTarget.context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(touchTarget.context).scaledMinimumFlingVelocity * 2

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        touchTarget.isLongClickable = false
        touchTarget.setOnTouchListener { _, event -> handle(event) }
    }

    fun detach() {
        touchTarget.setOnTouchListener(null)
        recycleVelocity()
    }

    private fun handle(event: MotionEvent): Boolean {
        if (!canSwipe()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                dragging = false
                startRawX = event.rawX
                startRawY = event.rawY
                recycleVelocity()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                // Must return true so we keep receiving MOVE (EditText would otherwise eat them).
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                velocityTracker?.addMovement(event)
                val dx = event.rawX - startRawX
                val dy = abs(event.rawY - startRawY)
                if (!dragging) {
                    if (abs(dx) > touchSlop && abs(dx) > dy * 1.15f) {
                        dragging = true
                        disallowParents(true)
                    } else if (dy > touchSlop && dy > abs(dx)) {
                        // vertical — abandon so page/chrome can scroll
                        tracking = false
                        return false
                    }
                }
                if (dragging) {
                    val resistance = if (tabCount() < 2) 0.22f else 0.92f
                    slideTarget.translationX = dx * resistance
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                recycleVelocity()
                disallowParents(false)

                if (!dragging) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) onTapAddress()
                    return true
                }
                dragging = false

                val w = slideTarget.width.toFloat().coerceAtLeast(1f)
                val tx = slideTarget.translationX
                val canFlip = tabCount() >= 2
                val goNext = canFlip && (tx < -w * 0.22f || vx < -minFling)
                val goPrev = canFlip && (tx > w * 0.22f || vx > minFling)

                when {
                    goNext -> settleAndSwitch(next = true, width = w)
                    goPrev -> settleAndSwitch(next = false, width = w)
                    else -> SafariMotion.snapX(slideTarget, 0f)
                }
                return true
            }
        }
        return false
    }

    private fun settleAndSwitch(next: Boolean, width: Float) {
        val endX = if (next) -width else width
        SafariMotion.snapX(slideTarget, endX) {
            onSwitchTab(next)
            slideTarget.translationX = if (next) width * 0.12f else -width * 0.12f
            SafariMotion.snapX(slideTarget, 0f)
        }
    }

    private fun disallowParents(disallow: Boolean) {
        var p = touchTarget.parent
        while (p is ViewGroup) {
            p.requestDisallowInterceptTouchEvent(disallow)
            p = p.parent
        }
    }

    private fun recycleVelocity() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
