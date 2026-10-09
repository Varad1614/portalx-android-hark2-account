package com.pravahax.portalx

import android.app.Application
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.net.Connectivity
import com.pravahax.portalx.sync.OutboxWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Process-wide singletons (Hilt, see di/AppModule). Lazy so app start doesn't pay for the HTTP stack or Keystore. */
@HiltAndroidApp
class PortalXApp : Application() {
    @Inject lateinit var repoProvider: dagger.Lazy<Repo>
    @Inject lateinit var connectivityProvider: dagger.Lazy<Connectivity>
    val repo: Repo get() = repoProvider.get()
    val connectivity: Connectivity get() = connectivityProvider.get()

    override fun onCreate() {
        super.onCreate()
        // Anything queued before the process died is sent once a network is available.
        OutboxWorker.schedule(this)
    }
}
