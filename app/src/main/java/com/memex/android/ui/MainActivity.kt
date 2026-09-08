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
import com.memex.android.ui.approvals.ApprovalsViewModel
import com.memex.android.ui.capture.CaptureViewModel
import com.memex.android.ui.chat.ChatViewModel
import com.memex.android.ui.feed.FeedViewModel
import com.memex.android.ui.navigation.MemexNavGraph
import com.memex.android.ui.runs.RunsViewModel
import com.memex.android.ui.settings.SettingsViewModel
import com.memex.android.ui.tasks.TasksViewModel
import com.memex.android.ui.theme.MemexTheme
import com.memex.android.util.ShareIntentParser
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Activity-scoped so each screen's state survives moving between tabs, and so the
    // capture sheet the activity itself drives is the one the nav graph shows. Hilt
    // supplies the constructor arguments, so no ViewModelProvider.Factory is needed.
    private val feedViewModel: FeedViewModel by viewModels()
    private val captureViewModel: CaptureViewModel by viewModels()
    private val tasksViewModel: TasksViewModel by viewModels()
    private val approvalsViewModel: ApprovalsViewModel by viewModels()
    private val runsViewModel: RunsViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

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
                captureViewModel.restoreDraftFromDisk(cacheDir, contentResolver)
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
