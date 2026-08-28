package com.memex.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.memex.android.data.api.ApiClient
import com.memex.android.data.local.SharedPreferencesAppPreferences
import com.memex.android.data.repository.CaptureRepositoryImpl
import com.memex.android.data.repository.MemexRepositoryImpl
import com.memex.android.data.security.EncryptedSecureTokenStorage
import com.memex.android.ui.capture.CaptureViewModel
import com.memex.android.ui.capture.QuickCaptureBottomSheet
import com.memex.android.ui.feed.FeedScreen
import com.memex.android.ui.feed.FeedViewModel
import com.memex.android.ui.feed.NoteDetailScreen
import com.memex.android.ui.theme.MemexTheme
import com.memex.android.util.DefaultAudioRecorder
import com.memex.android.util.DefaultImageCompressor

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

    private val feedViewModel by lazy { FeedViewModel(memexRepository) }
    private val captureViewModel by lazy {
        CaptureViewModel(
            captureRepository = captureRepository,
            audioRecorder = audioRecorder,
            imageCompressor = imageCompressor
        )
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
    var selectedNoteId by remember { mutableStateOf<String?>(null) }
    var showCaptureSheet by remember { mutableStateOf(false) }

    if (selectedNoteId != null) {
        NoteDetailScreen(
            noteId = selectedNoteId!!,
            viewModel = feedViewModel,
            onBackClick = { selectedNoteId = null },
            modifier = modifier
        )
    } else {
        FeedScreen(
            viewModel = feedViewModel,
            onNoteClick = { selectedNoteId = it },
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
