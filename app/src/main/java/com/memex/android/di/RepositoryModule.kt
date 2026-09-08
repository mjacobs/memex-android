package com.memex.android.di

import com.memex.android.data.api.MemexApiService
import com.memex.android.data.api.SseChatClient
import com.memex.android.data.repository.CaptureRepository
import com.memex.android.data.repository.CaptureRepositoryImpl
import com.memex.android.data.repository.ChatRepository
import com.memex.android.data.repository.ChatRepositoryImpl
import com.memex.android.data.repository.MemexRepository
import com.memex.android.data.repository.MemexRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.scopes.ActivityRetainedScoped

/**
 * Repositories, scoped alongside the API client they wrap. Each holds cached flows the
 * screens observe, so every ViewModel on a given activity must see the same instance.
 */
@Module
@InstallIn(ActivityRetainedComponent::class)
object RepositoryModule {

    @Provides
    @ActivityRetainedScoped
    fun provideMemexRepository(
        apiService: MemexApiService
    ): MemexRepository = MemexRepositoryImpl(apiService)

    @Provides
    @ActivityRetainedScoped
    fun provideCaptureRepository(
        apiService: MemexApiService
    ): CaptureRepository = CaptureRepositoryImpl(apiService)

    @Provides
    @ActivityRetainedScoped
    fun provideChatRepository(
        apiService: MemexApiService,
        sseChatClient: SseChatClient
    ): ChatRepository = ChatRepositoryImpl(
        apiService = apiService,
        sseChatClient = sseChatClient
    )
}
