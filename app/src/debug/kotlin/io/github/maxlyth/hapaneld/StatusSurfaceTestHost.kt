package io.github.maxlyth.hapaneld

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * An empty Activity that exists only so instrumented tests can measure [StatusSurface] on a real
 * runtime without launching a screen that has its own wiring and side effects.
 *
 * It lives in the DEBUG variant rather than in the test APK because instrumentation runs inside the
 * app process, and Android refuses to launch an Activity that belongs to the separate test package.
 * It is never part of a release build.
 */
class StatusSurfaceTestHost : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}
