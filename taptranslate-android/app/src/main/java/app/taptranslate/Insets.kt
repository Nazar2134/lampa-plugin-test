package app.taptranslate

import android.os.Build
import android.view.WindowInsets

/** Bottom inset covering the navigation bar and keyboard. */
fun WindowInsets.bottomBarsAndIme(): Int =
    if (Build.VERSION.SDK_INT >= 30) getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom
    else @Suppress("DEPRECATION") systemWindowInsetBottom

fun WindowInsets.topBar(): Int =
    if (Build.VERSION.SDK_INT >= 30) getInsets(WindowInsets.Type.systemBars()).top
    else @Suppress("DEPRECATION") systemWindowInsetTop
