package com.memex.android.di

import com.memex.android.data.api.ApiClient
import com.memex.android.data.api.MemexApiService
import com.memex.android.data.api.SseChatClient
import com.memex.android.data.local.AppPreferences
import com.memex.android.data.security.SecureTokenStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.scopes.ActivityRetainedScoped
import okhttp3.OkHttpClient

/**
 * The HTTP stack, bound to whatever server URL was configured when the screen was
 * created. It is deliberately not a process singleton: Retrofit fixes its base URL at
 * construction, so re-reading the preference each time the UI is built is what makes a
 * changed server URL take effect after a restart rather than never.
 */
@Module
@InstallIn(ActivityRetainedComponent::class)
object NetworkModule {

    @Provides
    @ActivityRetainedScoped
    fun provideOkHttpClient(
        tokenStorage: SecureTokenStorage,
        appPreferences: AppPreferences
    ): OkHttpClient = ApiClient.createOkHttpClient(
        tokenStorage = tokenStorage,
        // The key travels only to the currently configured server, even if this
        // client was built before the user pointed the app somewhere else.
        allowedOrigin = { appPreferences.serverUrl }
    )

    @Provides
    @ActivityRetainedScoped
    fun provideMemexApiService(
        appPreferences: AppPreferences,
        okHttpClient: OkHttpClient
    ): MemexApiService = ApiClient.createApiService(
        baseUrl = appPreferences.serverUrl,
        okHttpClient = okHttpClient
    )

    @Provides
    @ActivityRetainedScoped
    fun provideSseChatClient(
        appPreferences: AppPreferences,
        okHttpClient: OkHttpClient
    ): SseChatClient = SseChatClient(
        baseUrl = appPreferences.serverUrl,
        okHttpClient = okHttpClient
    )
}
