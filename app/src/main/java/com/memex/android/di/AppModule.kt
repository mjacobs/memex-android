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
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Process-wide collaborators. These are all backed by application state that outlives
 * any one screen — the preferences file and the Keystore-backed token store — so they
 * are created once and shared.
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
    fun provideImageCompressor(): ImageCompressor = DefaultImageCompressor()
}

/**
 * [AudioRecorder] owns a `MediaRecorder` and a coroutine scope that gets permanently
 * cancelled by `release()`. `CaptureViewModel.onCleared()` calls `release()`, so this
 * must not be a process-wide singleton (Hilt would hand the next `CaptureViewModel`
 * instance an already-released recorder). Scoping it to the ViewModel keeps its
 * lifetime aligned with the one screen that uses it.
 */
@Module
@InstallIn(ViewModelComponent::class)
object AudioRecorderModule {

    @Provides
    @ViewModelScoped
    fun provideAudioRecorder(
        @ApplicationContext context: Context
    ): AudioRecorder = DefaultAudioRecorder(context)
}
