package com.pravahax.portalx

import android.graphics.drawable.ColorDrawable
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLooper

/** v0.3.0: the app must stay LIGHT even when the phone is in dark mode (the v0.2.0 complaint). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "night")
@ConscryptMode(ConscryptMode.Mode.OFF)
class ThemeTest {
    @Test fun staysLightInSystemDarkModeAndFiltersObscuredTouches() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            ShadowLooper.idleMainLooper()
            sc.onActivity { a ->
                val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
                assertTrue("status bar icons must be dark (light-appearance) on parchment", c.isAppearanceLightStatusBars)
                assertTrue("nav bar icons must be dark (light-appearance)", c.isAppearanceLightNavigationBars)
                val bg = (a.window.decorView.background as? ColorDrawable)?.color
                assertEquals(0xFFFBFAF7.toInt(), bg) // parchment, never slate-950
                assertTrue(!a.window.decorView.filterTouchesWhenObscured) // v0.9.1: overlays no longer kill every tap
                println("THEME_OK light in night mode")
            }
        }
    }
}
