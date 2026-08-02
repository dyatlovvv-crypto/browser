package ru.srr.safari.ui

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** Physics-based motion — SpringForce only, no linear ValueAnimators. */
object SafariMotion {

    const val PRESS_IN = 70L
    const val PRESS_OUT = 140L
    /** Kept for call-site compatibility; springs ignore duration. */
    const val CHROME = 180L
    const val OVERLAY = 200L
    const val POPOVER = 155L
    const val MODE = 280L

    val softOut = android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)
    val softIn = android.view.animation.PathInterpolator(0.4f, 0f, 0.7f, 0.2f)
    val softInOut = android.view.animation.PathInterpolator(0.33f, 0.05f, 0.2f, 1f)
    val modeSpring = android.view.animation.OvershootInterpolator(0.72f)

    fun spring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        finalPosition: Float,
        stiffness: Float = SpringForce.STIFFNESS_MEDIUM,
        damping: Float = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY,
        onEnd: (() -> Unit)? = null
    ): SpringAnimation {
        return SpringAnimation(view, property).apply {
            spring = SpringForce(finalPosition).also {
                it.stiffness = stiffness
                it.dampingRatio = damping
            }
            if (onEnd != null) {
                addEndListener { _, _, _, _ -> onEnd() }
            }
            start()
        }
    }

    fun appear(
        view: View,
        fromScale: Float = 0.96f,
        fromY: Float = 0f,
        duration: Long = POPOVER
    ) {
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale
        view.translationY = fromY
        spring(view, DynamicAnimation.ALPHA, 1f, SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY)
        spring(view, DynamicAnimation.SCALE_X, 1f, SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_LOW_BOUNCY)
        spring(view, DynamicAnimation.SCALE_Y, 1f, SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_LOW_BOUNCY)
        spring(view, DynamicAnimation.TRANSLATION_Y, 0f, SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_LOW_BOUNCY)
    }

    fun disappear(
        view: View,
        toScale: Float = 0.97f,
        toY: Float = 0f,
        duration: Long = OVERLAY,
        endAction: (() -> Unit)? = null
    ) {
        view.animate().cancel()
        var pending = 3
        fun done() {
            pending--
            if (pending <= 0) endAction?.invoke()
        }
        spring(view, DynamicAnimation.ALPHA, 0f, SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY) { done() }
        spring(view, DynamicAnimation.SCALE_X, toScale, SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY) { done() }
        spring(view, DynamicAnimation.TRANSLATION_Y, toY, SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY) { done() }
        spring(view, DynamicAnimation.SCALE_Y, toScale, SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY)
    }

    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
        view.translationX = 0f
    }

    fun snapX(
        view: View,
        toX: Float,
        stiffness: Float = SpringForce.STIFFNESS_MEDIUM,
        damping: Float = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY,
        onEnd: (() -> Unit)? = null
    ) = spring(view, DynamicAnimation.TRANSLATION_X, toX, stiffness, damping, onEnd)
}
