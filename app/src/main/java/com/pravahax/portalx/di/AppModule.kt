package com.pravahax.portalx.di

import android.content.Context
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.net.Connectivity
import com.pravahax.portalx.net.PortalApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** v0.7: process-wide singletons, provided by Hilt instead of hand-built in PortalXApp. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun portalApi(@ApplicationContext c: Context): PortalApi = PortalApi(c)
    @Provides @Singleton fun repo(@ApplicationContext c: Context, api: PortalApi): Repo = Repo(c, api)
    @Provides @Singleton fun connectivity(@ApplicationContext c: Context): Connectivity = Connectivity(c)
}
