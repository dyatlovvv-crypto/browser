package ru.srr.safari.ui

import android.view.View
import android.view.animation.PathInterpolator

/** Soft, fast motion — gentle curves without long durations. */
object SafariMotion {
    /** Settle / appear — soft ease-out */
    val softOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    /** Hide / dismiss */
    val softIn = PathInterpolator(0.4f, 0f, 0.7f, 0.2f)

    /** Press / chrome slide */
    val softInOut = PathInterpolator(0.33f, 0.05f, 0.2f, 1f)

    const val PRESS_IN = 72L
    const val PRESS_OUT = 150L
    const val CHROME = 160L
    const val OVERLAY = 155L
    const val POPOVER = 145L

    fun appear(
        view: View,
        fromScale: Float = 0.94f,
        fromY: Float = 0f,
        duration: Long = POPOVER
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
            .setDuration(duration)
            .setInterpolator(softOut)
            .start()
    }

    fun disappear(
        view: View,
        toScale: Float = 0.96f,
        toY: Float = 0f,
        duration: Long = OVERLAY,
        endAction: (() -> Unit)? = null
    ) {
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleX(toScale)
            .scaleY(toScale)
            .translationY(toY)
            .setDuration(duration)
            .setInterpolator(softIn)
            .withEndAction {
                endAction?.invoke()
            }
            .start()
    }

    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
    }
}
