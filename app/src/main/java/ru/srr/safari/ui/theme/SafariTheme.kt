package ru.srr.safari.ui.theme

/**
 * Design tokens. XML mirror: values/dimens.xml (`safari_space_*`, radius, elevation).
 * Views must use those dimens — not ad-hoc dp.
 */
object SafariSpace {
    const val xs = 4
    const val sm = 8
    const val md = 16
    const val lg = 24
    const val xl = 32
}

object SafariRadii {
    const val card = 20
    const val capsule = 24
    const val chrome = 24
    const val sheet = 28
}

object SafariElevation {
    const val card = 2
}

object SafariChrome {
    const val height = 52
    const val btn = 44
    /** (btn − 24dp glyph) / 2 — keeps icons optically centered in glass circles. */
    const val btnPad = 10
    const val icon = 28
}

object SafariGlass {
    /** Backdrop blur for AI Mode / frosted sheets (dp). */
    const val blurDp = 28
    /** Semi-transparent body tint over blurred page (percent). */
    const val bodyOpacity = 66
}
