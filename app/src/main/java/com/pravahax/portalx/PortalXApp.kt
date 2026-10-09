package com.pravahax.portalx

import android.app.Application
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.net.Connectivity

/** Process-wide singletons. Survives rotation/activity recreation (v0.1.x rebuilt the HTTP stack on every rotate). */
class PortalXApp : Application() {
    val repo: Repo by lazy { Repo(this) }
    val connectivity: Connectivity by lazy { Connectivity(this) }
}
