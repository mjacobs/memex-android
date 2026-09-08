package com.memex.android.di

import com.memex.android.ui.settings.ConnectionTester
import com.memex.android.ui.settings.testMemexConnection
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The "Save & Test Connection" probe. It builds its own throwaway API stack so that an
 * unverified server never sees the app's live client or its stored key, which is why it
 * is bound here rather than assembled from [NetworkModule].
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    fun provideConnectionTester(): ConnectionTester =
        ConnectionTester { serverUrl, token -> testMemexConnection(serverUrl, token) }
}
