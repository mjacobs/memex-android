package com.memex.android.di

import android.content.Context
import com.memex.android.data.local.AppPreferences
import com.memex.android.data.local.SharedPreferencesAppPreferences
import com.memex.android.data.security.EncryptedSecureTokenStorage
import com.memex.android.data.security.SecureTokenStorage
import com.memex.android.util.AudioRecorder
import com.memex.android.util.DefaultAudioRecorder
import com.memex.android.util.DefaultImageCompressor
import com.memex.android.util.ImageCompressor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Process-wide collaborators. These are all backed by application state that outlives
 * any one screen — the preferences file, the Keystore-backed token store, and the
 * microphone — so they are created once and shared.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppPreferences(
        @ApplicationContext context: Context
    ): AppPreferences = SharedPreferencesAppPreferences(context)

    @Provides
    @Singleton
    fun provideSecureTokenStorage(
        @ApplicationContext context: Context
    ): SecureTokenStorage = EncryptedSecureTokenStorage(context)

    @Provides
    @Singleton
    fun provideAudioRecorder(
        @ApplicationContext context: Context
    ): AudioRecorder = DefaultAudioRecorder(context)

    @Provides
    @Singleton
    fun provideImageCompressor(): ImageCompressor = DefaultImageCompressor()
}
