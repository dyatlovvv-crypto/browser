package ru.srr.safari.ui

import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** iOS Fluid Interfaces motion for classic Views. */
object SafariMotion {

    /** Fixed duration for open/close / menu / overlay transitions. */
    const val DURATION = 400L

    const val PRESS_IN = 70L
    const val PRESS_OUT = 140L
    const val CHROME = DURATION
    const val OVERLAY = DURATION
    const val POPOVER = DURATION
    const val MODE = DURATION

    /** Soft liquid-glass springs (noble glide, minimal bounce). */
    const val STIFFNESS = 150f
    const val DAMPING = 0.75f

    /** Apple ease-out cubic bezier. */
    val iosEaseOut = PathInterpolator(0.25f, 1f, 0.5f, 1f)

    val softOut = iosEaseOut
    val softIn = iosEaseOut
    val softInOut = iosEaseOut
    val modeSpring = iosEaseOut

    fun spring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        finalPosition: Float,
        stiffness: Float = STIFFNESS,
        damping: Float = DAMPING,
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
        duration: Long = DURATION
    ) {
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale
        view.translationY = fromY
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(DURATION)
            .setInterpolator(iosEaseOut)
            .withLayer()
            .start()
    }

    fun disappear(
        view: View,
        toScale: Float = 0.97f,
        toY: Float = 0f,
        duration: Long = DURATION,
        endAction: (() -> Unit)? = null
    ) {
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleX(toScale)
            .scaleY(toScale)
            .translationY(toY)
            .setDuration(DURATION)
            .setInterpolator(iosEaseOut)
            .withLayer()
            .withEndAction { endAction?.invoke() }
            .start()
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
        stiffness: Float = STIFFNESS,
        damping: Float = DAMPING,
        onEnd: (() -> Unit)? = null
    ) = spring(view, DynamicAnimation.TRANSLATION_X, toX, stiffness, damping, onEnd)
}
