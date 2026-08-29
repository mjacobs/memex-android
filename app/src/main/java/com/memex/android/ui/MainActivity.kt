package com.memex.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.memex.android.data.api.ApiClient
import com.memex.android.data.api.SseChatClient
import com.memex.android.data.local.AppPreferences
import com.memex.android.data.local.SharedPreferencesAppPreferences
import com.memex.android.data.repository.CaptureRepository
import com.memex.android.data.repository.CaptureRepositoryImpl
import com.memex.android.data.repository.ChatRepository
import com.memex.android.data.repository.ChatRepositoryImpl
import com.memex.android.data.repository.MemexRepository
import com.memex.android.data.repository.MemexRepositoryImpl
import com.memex.android.data.security.EncryptedSecureTokenStorage
import com.memex.android.data.security.SecureTokenStorage
import com.memex.android.ui.approvals.ApprovalsViewModel
import com.memex.android.ui.capture.CaptureViewModel
import com.memex.android.ui.chat.ChatViewModel
import com.memex.android.ui.feed.FeedViewModel
import com.memex.android.ui.navigation.MemexNavGraph
import com.memex.android.ui.runs.RunsViewModel
import com.memex.android.ui.settings.SettingsViewModel
import com.memex.android.ui.settings.testMemexConnection
import com.memex.android.ui.tasks.TasksViewModel
import com.memex.android.ui.theme.MemexTheme
import com.memex.android.util.AudioRecorder
import com.memex.android.util.DefaultAudioRecorder
import com.memex.android.util.DefaultImageCompressor
import com.memex.android.util.ImageCompressor
import com.memex.android.util.ShareIntentParser

class FeedViewModelFactory(
    private val repository: MemexRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FeedViewModel::class.java)) {
            return FeedViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class CaptureViewModelFactory(
    private val captureRepository: CaptureRepository,
    private val audioRecorder: AudioRecorder,
    private val imageCompressor: ImageCompressor
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CaptureViewModel::class.java)) {
            return CaptureViewModel(
                captureRepository = captureRepository,
                audioRecorder = audioRecorder,
                imageCompressor = imageCompressor
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class TasksViewModelFactory(
    private val repository: MemexRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TasksViewModel::class.java)) {
            return TasksViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class ApprovalsViewModelFactory(
    private val repository: MemexRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ApprovalsViewModel::class.java)) {
            return ApprovalsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class RunsViewModelFactory(
    private val repository: MemexRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RunsViewModel::class.java)) {
            return RunsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class ChatViewModelFactory(
    private val chatRepository: ChatRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(chatRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class SettingsViewModelFactory(
    private val tokenStorage: SecureTokenStorage,
    private val appPreferences: AppPreferences
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(
                tokenStorage = tokenStorage,
                appPreferences = appPreferences,
                connectionTester = ::testMemexConnection
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class MainActivity : ComponentActivity() {

    private val tokenStorage by lazy { EncryptedSecureTokenStorage(applicationContext) }
    private val appPreferences by lazy { SharedPreferencesAppPreferences(applicationContext) }
    private val okHttpClient by lazy {
        ApiClient.createOkHttpClient(
            tokenStorage = tokenStorage,
            // The key travels only to the currently configured server, even if this
            // client was built before the user pointed the app somewhere else.
            allowedOrigin = { appPreferences.serverUrl }
        )
    }
    private val apiService by lazy {
        ApiClient.createApiService(
            baseUrl = appPreferences.serverUrl,
            okHttpClient = okHttpClient
        )
    }
    private val memexRepository by lazy { MemexRepositoryImpl(apiService) }
    private val captureRepository by lazy { CaptureRepositoryImpl(apiService) }
    private val chatRepository by lazy {
        ChatRepositoryImpl(
            apiService = apiService,
            sseChatClient = SseChatClient(
                baseUrl = appPreferences.serverUrl,
                okHttpClient = okHttpClient
            )
        )
    }
    private val audioRecorder by lazy { DefaultAudioRecorder(applicationContext) }
    private val imageCompressor by lazy { DefaultImageCompressor() }

    private val feedViewModel: FeedViewModel by viewModels {
        FeedViewModelFactory(memexRepository)
    }
    private val captureViewModel: CaptureViewModel by viewModels {
        CaptureViewModelFactory(captureRepository, audioRecorder, imageCompressor)
    }
    private val tasksViewModel: TasksViewModel by viewModels {
        TasksViewModelFactory(memexRepository)
    }
    private val approvalsViewModel: ApprovalsViewModel by viewModels {
        ApprovalsViewModelFactory(memexRepository)
    }
    private val runsViewModel: RunsViewModel by viewModels {
        RunsViewModelFactory(memexRepository)
    }
    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModelFactory(chatRepository)
    }
    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModelFactory(tokenStorage, appPreferences)
    }

    companion object {
        private const val KEY_SHARE_HANDLED = "key_share_handled"
        private const val KEY_HAS_DRAFT = "key_has_draft"
    }

    private var isShareHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        captureViewModel.setCacheDir(cacheDir)

        if (savedInstanceState != null) {
            isShareHandled = savedInstanceState.getBoolean(KEY_SHARE_HANDLED, false)
            if (savedInstanceState.getBoolean(KEY_HAS_DRAFT, false) && captureViewModel.needsRestoration()) {
                captureViewModel.restoreDraftFromDisk(cacheDir)
            }
        } else {
            handleIncomingShareIntent(intent)
        }

        setContent {
            MemexTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MemexNavGraph(
                        feedViewModel = feedViewModel,
                        captureViewModel = captureViewModel,
                        tasksViewModel = tasksViewModel,
                        approvalsViewModel = approvalsViewModel,
                        runsViewModel = runsViewModel,
                        chatViewModel = chatViewModel,
                        settingsViewModel = settingsViewModel
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHARE_HANDLED, isShareHandled)
        outState.putBoolean(KEY_HAS_DRAFT, captureViewModel.isCaptureSheetVisible.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(incomingIntent: Intent?) {
        val incomingShare = ShareIntentParser.parse(incomingIntent) ?: return
        isShareHandled = true
        captureViewModel.handleIncomingShare(contentResolver, incomingShare)
        incomingIntent?.action = null
    }
}
