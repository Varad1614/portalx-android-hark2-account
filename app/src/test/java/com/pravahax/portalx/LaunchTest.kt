package com.pravahax.portalx

import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class LaunchTest {
    @Test fun launches() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ShadowLooper.idleMainLooper()
            it.onActivity { a -> println("LAUNCH_OK state=" + a.lifecycle.currentState) }
        }
    }
}
