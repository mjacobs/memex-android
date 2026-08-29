package com.memex.android.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.memex.android.ui.approvals.ApprovalsScreen
import com.memex.android.ui.approvals.ApprovalsViewModel
import com.memex.android.ui.capture.CaptureViewModel
import com.memex.android.ui.capture.QuickCaptureBottomSheet
import com.memex.android.ui.chat.ChatScreen
import com.memex.android.ui.chat.ChatViewModel
import com.memex.android.ui.feed.FeedScreen
import com.memex.android.ui.feed.FeedViewModel
import com.memex.android.ui.feed.NoteDetailScreen
import com.memex.android.ui.runs.RoutineRunsScreen
import com.memex.android.ui.runs.RunDetailScreen
import com.memex.android.ui.runs.RunsViewModel
import com.memex.android.ui.settings.SettingsScreen
import com.memex.android.ui.settings.SettingsViewModel
import com.memex.android.ui.tasks.TasksScreen
import com.memex.android.ui.tasks.TasksViewModel

object MemexRoutes {
    const val FEED = "feed"
    const val TASKS = "tasks"
    const val APPROVALS = "approvals"
    const val RUNS = "runs"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
}

/**
 * A destination reachable from the bottom navigation bar. Settings is deliberately
 * absent: it is reached from the Feed top bar, keeping the bar at five items.
 */
data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

val BOTTOM_DESTINATIONS = listOf(
    BottomDestination(MemexRoutes.FEED, "Feed", Icons.AutoMirrored.Filled.Notes),
    BottomDestination(MemexRoutes.TASKS, "Tasks", Icons.Default.TaskAlt),
    BottomDestination(MemexRoutes.APPROVALS, "Approvals", Icons.Default.Inbox),
    BottomDestination(MemexRoutes.RUNS, "Runs", Icons.Default.EventRepeat),
    BottomDestination(MemexRoutes.CHAT, "Chat", Icons.Default.Forum)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemexNavGraph(
    feedViewModel: FeedViewModel,
    captureViewModel: CaptureViewModel,
    tasksViewModel: TasksViewModel,
    approvalsViewModel: ApprovalsViewModel,
    runsViewModel: RunsViewModel,
    chatViewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isCaptureSheetVisible by captureViewModel.isCaptureSheetVisible.collectAsState()

    fun navigateToTab(route: String) {
        if (currentRoute == route) return
        // Popping first covers the destination already sitting below on the stack —
        // notably Feed, the start destination, which a navigate() with popUpTo to
        // itself leaves untouched, stranding the user on Settings.
        // saveState mirrors the navigate() path below, so popping back to a tab still
        // preserves the state of the tab being left — a half-typed chat draft, say.
        if (navController.popBackStack(route, inclusive = false, saveState = true)) return
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        // Each destination has its own Scaffold and TopAppBar, which apply the status
        // bar inset themselves. Consuming it here too would indent every screen twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                BOTTOM_DESTINATIONS.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navigateToTab(destination.route) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = MemexRoutes.FEED,
            // Only the bottom bar's own height is reserved here.
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            composable(MemexRoutes.FEED) {
                FeedRoute(
                    feedViewModel = feedViewModel,
                    captureViewModel = captureViewModel,
                    onSettingsClick = { navController.navigate(MemexRoutes.SETTINGS) }
                )
            }

            composable(MemexRoutes.TASKS) {
                TasksScreen(
                    viewModel = tasksViewModel,
                    onTaskSourceClick = { noteId ->
                        feedViewModel.selectNote(noteId)
                        navigateToTab(MemexRoutes.FEED)
                    }
                )
            }

            composable(MemexRoutes.APPROVALS) {
                ApprovalsScreen(
                    viewModel = approvalsViewModel,
                    onRunClick = { runId ->
                        runsViewModel.selectRun(runId)
                        navigateToTab(MemexRoutes.RUNS)
                    }
                )
            }

            composable(MemexRoutes.RUNS) {
                RunsRoute(
                    runsViewModel = runsViewModel,
                    onNoteClick = { noteId ->
                        runsViewModel.clearSelectedRun()
                        feedViewModel.selectNote(noteId)
                        navigateToTab(MemexRoutes.FEED)
                    },
                    onApprovalsClick = { navigateToTab(MemexRoutes.APPROVALS) }
                )
            }

            composable(MemexRoutes.CHAT) {
                ChatScreen(viewModel = chatViewModel)
            }

            composable(MemexRoutes.SETTINGS) {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }

    if (isCaptureSheetVisible) {
        QuickCaptureBottomSheet(
            viewModel = captureViewModel,
            onDismissRequest = { captureViewModel.closeCaptureSheet() },
            onSuccess = {
                captureViewModel.closeCaptureSheet()
                feedViewModel.refresh()
            }
        )
    }
}

@Composable
private fun FeedRoute(
    feedViewModel: FeedViewModel,
    captureViewModel: CaptureViewModel,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val feedUiState by feedViewModel.uiState.collectAsState()

    // Intercept system Back while a note detail is open.
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
            onQuickCaptureClick = { captureViewModel.openCaptureSheet() },
            onSettingsClick = onSettingsClick,
            modifier = modifier
        )
    }
}

@Composable
private fun RunsRoute(
    runsViewModel: RunsViewModel,
    onNoteClick: (String) -> Unit,
    onApprovalsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val runsUiState by runsViewModel.uiState.collectAsState()

    BackHandler(enabled = runsUiState.selectedRun != null) {
        runsViewModel.clearSelectedRun()
    }

    if (runsUiState.selectedRun != null) {
        RunDetailScreen(
            viewModel = runsViewModel,
            onBackClick = { runsViewModel.clearSelectedRun() },
            onNoteClick = onNoteClick,
            onApprovalsClick = onApprovalsClick,
            modifier = modifier
        )
    } else {
        RoutineRunsScreen(
            viewModel = runsViewModel,
            onRunClick = { runId -> runsViewModel.selectRun(runId) },
            modifier = modifier
        )
    }
}
