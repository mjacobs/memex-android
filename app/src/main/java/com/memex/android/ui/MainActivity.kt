package com.memex.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.memex.android.data.api.ApiClient
import com.memex.android.data.local.SharedPreferencesAppPreferences
import com.memex.android.data.repository.CaptureRepository
import com.memex.android.data.repository.CaptureRepositoryImpl
import com.memex.android.data.repository.MemexRepository
import com.memex.android.data.repository.MemexRepositoryImpl
import com.memex.android.data.security.EncryptedSecureTokenStorage
import com.memex.android.ui.capture.CaptureViewModel
import com.memex.android.ui.capture.QuickCaptureBottomSheet
import com.memex.android.ui.feed.FeedScreen
import com.memex.android.ui.feed.FeedViewModel
import com.memex.android.ui.feed.NoteDetailScreen
import com.memex.android.ui.theme.MemexTheme
import com.memex.android.util.AudioRecorder
import com.memex.android.util.DefaultAudioRecorder
import com.memex.android.util.DefaultImageCompressor
import com.memex.android.util.ImageCompressor

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

class MainActivity : ComponentActivity() {

    private val tokenStorage by lazy { EncryptedSecureTokenStorage(applicationContext) }
    private val appPreferences by lazy { SharedPreferencesAppPreferences(applicationContext) }
    private val apiService by lazy {
        ApiClient.createApiService(
            baseUrl = appPreferences.serverUrl,
            tokenStorage = tokenStorage
        )
    }
    private val memexRepository by lazy { MemexRepositoryImpl(apiService) }
    private val captureRepository by lazy { CaptureRepositoryImpl(apiService) }
    private val audioRecorder by lazy { DefaultAudioRecorder(applicationContext) }
    private val imageCompressor by lazy { DefaultImageCompressor() }

    private val feedViewModel: FeedViewModel by viewModels {
        FeedViewModelFactory(memexRepository)
    }
    private val captureViewModel: CaptureViewModel by viewModels {
        CaptureViewModelFactory(captureRepository, audioRecorder, imageCompressor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MemexTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MemexAppContent(
                        feedViewModel = feedViewModel,
                        captureViewModel = captureViewModel
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemexAppContent(
    feedViewModel: FeedViewModel,
    captureViewModel: CaptureViewModel,
    modifier: Modifier = Modifier
) {
    val feedUiState by feedViewModel.uiState.collectAsState()
    var showCaptureSheet by rememberSaveable { mutableStateOf(false) }

    // Intercept system Back when viewing note detail
    BackHandler(enabled = feedUiState.selectedNote != null) {
        feedViewModel.clearSelectedNote()
    }

    val selectedNote = feedUiState.selectedNote
    if (selectedNote != null) {
        NoteDetailScreen(
            noteId = selectedNote.id,
            viewModel = feedViewModel,
            onBackClick = { feedViewModel.clearSelectedNote() },
            modifier = modifier
        )
    } else {
        FeedScreen(
            viewModel = feedViewModel,
            onNoteClick = { noteId -> feedViewModel.selectNote(noteId) },
            onQuickCaptureClick = { showCaptureSheet = true },
            modifier = modifier
        )
    }

    if (showCaptureSheet) {
        QuickCaptureBottomSheet(
            viewModel = captureViewModel,
            onDismissRequest = { showCaptureSheet = false },
            onSuccess = {
                showCaptureSheet = false
                feedViewModel.refresh()
            }
        )
    }
}
