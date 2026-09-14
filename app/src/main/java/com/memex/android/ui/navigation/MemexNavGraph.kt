package com.memex.android.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.memex.android.ui.adaptive.currentVerticalFoldBounds
import com.memex.android.ui.adaptive.currentWindowSupportsTwoPane
import com.memex.android.ui.adaptive.currentWindowWidthSizeClass
import com.memex.android.ui.adaptive.listPaneFractionForFold
import com.memex.android.ui.adaptive.usesNavigationRail
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

// The list pane keeps enough width for a readable note card; the rest goes to the
// detail, which carries the longer prose and the agent trace.
private const val LIST_PANE_WEIGHT = 0.4f
private const val DETAIL_PANE_WEIGHT = 0.6f

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

    // Compact windows — a phone in portrait, or the app in a narrow split-screen slot —
    // keep the bottom bar and one screen at a time. Medium and Expanded move navigation
    // to a side rail, and Expanded also shows list and detail side by side.
    val widthSizeClass = currentWindowWidthSizeClass()
    val isTwoPane = currentWindowSupportsTwoPane(widthSizeClass)

    // A half-opened foldable's hinge should be the pane boundary, not the fixed 40/60
    // split — otherwise a pane can render across or behind it. Away from a fold, the
    // fixed split still applies.
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val foldBounds = currentVerticalFoldBounds()
    val listPaneWeight = remember(containerWidthPx, foldBounds) {
        listPaneFractionForFold(
            containerWidth = containerWidthPx,
            foldLeft = foldBounds?.left,
            fallback = LIST_PANE_WEIGHT
        )
    }
    val detailPaneWeight = 1f - listPaneWeight

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

    if (widthSizeClass.usesNavigationRail) {
        Row(modifier = modifier.fillMaxSize()) {
            NavigationRail {
                BOTTOM_DESTINATIONS.forEach { destination ->
                    NavigationRailItem(
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
            MemexNavHost(
                navController = navController,
                feedViewModel = feedViewModel,
                captureViewModel = captureViewModel,
                tasksViewModel = tasksViewModel,
                approvalsViewModel = approvalsViewModel,
                runsViewModel = runsViewModel,
                chatViewModel = chatViewModel,
                settingsViewModel = settingsViewModel,
                isTwoPane = isTwoPane,
                listPaneWeight = listPaneWeight,
                detailPaneWeight = detailPaneWeight,
                onNavigateToTab = { route -> navigateToTab(route) },
                // The rail sits beside the content rather than over it, so there is no
                // bar height to reserve.
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
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
            MemexNavHost(
                navController = navController,
                feedViewModel = feedViewModel,
                captureViewModel = captureViewModel,
                tasksViewModel = tasksViewModel,
                approvalsViewModel = approvalsViewModel,
                runsViewModel = runsViewModel,
                chatViewModel = chatViewModel,
                settingsViewModel = settingsViewModel,
                isTwoPane = false,
                listPaneWeight = LIST_PANE_WEIGHT,
                detailPaneWeight = DETAIL_PANE_WEIGHT,
                onNavigateToTab = { route -> navigateToTab(route) },
                // Only the bottom bar's own height is reserved here.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
            )
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

/**
 * The destination graph. It is the same whether the surrounding chrome is a bottom bar
 * or a rail; only [isTwoPane] differs, and only the two list-detail destinations read
 * it.
 */
@Composable
private fun MemexNavHost(
    navController: NavHostController,
    feedViewModel: FeedViewModel,
    captureViewModel: CaptureViewModel,
    tasksViewModel: TasksViewModel,
    approvalsViewModel: ApprovalsViewModel,
    runsViewModel: RunsViewModel,
    chatViewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
    isTwoPane: Boolean,
    listPaneWeight: Float,
    detailPaneWeight: Float,
    onNavigateToTab: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = MemexRoutes.FEED,
        modifier = modifier
    ) {
        composable(MemexRoutes.FEED) {
            FeedRoute(
                feedViewModel = feedViewModel,
                captureViewModel = captureViewModel,
                isTwoPane = isTwoPane,
                listPaneWeight = listPaneWeight,
                detailPaneWeight = detailPaneWeight,
                onSettingsClick = { navController.navigate(MemexRoutes.SETTINGS) }
            )
        }

        composable(MemexRoutes.TASKS) {
            TasksScreen(
                viewModel = tasksViewModel,
                onTaskSourceClick = { noteId ->
                    feedViewModel.selectNote(noteId)
                    onNavigateToTab(MemexRoutes.FEED)
                }
            )
        }

        composable(MemexRoutes.APPROVALS) {
            ApprovalsScreen(
                viewModel = approvalsViewModel,
                onRunClick = { runId ->
                    runsViewModel.selectRun(runId)
                    onNavigateToTab(MemexRoutes.RUNS)
                }
            )
        }

        composable(MemexRoutes.RUNS) {
            RunsRoute(
                runsViewModel = runsViewModel,
                isTwoPane = isTwoPane,
                listPaneWeight = listPaneWeight,
                detailPaneWeight = detailPaneWeight,
                onNoteClick = { noteId ->
                    runsViewModel.clearSelectedRun()
                    feedViewModel.selectNote(noteId)
                    onNavigateToTab(MemexRoutes.FEED)
                },
                onApprovalsClick = { onNavigateToTab(MemexRoutes.APPROVALS) }
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

@Composable
private fun FeedRoute(
    feedViewModel: FeedViewModel,
    captureViewModel: CaptureViewModel,
    onSettingsClick: () -> Unit,
    isTwoPane: Boolean,
    listPaneWeight: Float = LIST_PANE_WEIGHT,
    detailPaneWeight: Float = DETAIL_PANE_WEIGHT,
    modifier: Modifier = Modifier
) {
    val feedUiState by feedViewModel.uiState.collectAsState()

    // Intercept system Back while a note detail covers the feed. Side by side the feed
    // never left, so Back keeps its usual meaning.
    BackHandler(enabled = !isTwoPane && feedUiState.selectedNote != null) {
        feedViewModel.clearSelectedNote()
    }

    val selectedNote = feedUiState.selectedNote

    if (isTwoPane) {
        Row(modifier = modifier.fillMaxSize()) {
            FeedScreen(
                viewModel = feedViewModel,
                onNoteClick = { noteId -> feedViewModel.selectNote(noteId) },
                onQuickCaptureClick = { captureViewModel.openCaptureSheet() },
                onSettingsClick = onSettingsClick,
                modifier = Modifier
                    .weight(listPaneWeight)
                    .fillMaxHeight()
            )
            VerticalDivider()
            Box(
                modifier = Modifier
                    .weight(detailPaneWeight)
                    .fillMaxHeight()
            ) {
                if (selectedNote != null) {
                    // Keyed by note ID so switching notes starts a fresh composition
                    // instead of reusing the previous note's remembered scroll state.
                    key(selectedNote.id) {
                        NoteDetailScreen(
                            noteId = selectedNote.id,
                            viewModel = feedViewModel,
                            onBackClick = { feedViewModel.clearSelectedNote() },
                            showBackButton = false
                        )
                    }
                } else {
                    EmptyDetailPane(message = "Pick a note from the feed to read it here.")
                }
            }
        }
        return
    }

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
    isTwoPane: Boolean,
    listPaneWeight: Float = LIST_PANE_WEIGHT,
    detailPaneWeight: Float = DETAIL_PANE_WEIGHT,
    modifier: Modifier = Modifier
) {
    val runsUiState by runsViewModel.uiState.collectAsState()

    BackHandler(enabled = !isTwoPane && runsUiState.selectedRun != null) {
        runsViewModel.clearSelectedRun()
    }

    if (isTwoPane) {
        Row(modifier = modifier.fillMaxSize()) {
            RoutineRunsScreen(
                viewModel = runsViewModel,
                onRunClick = { runId -> runsViewModel.selectRun(runId) },
                modifier = Modifier
                    .weight(listPaneWeight)
                    .fillMaxHeight()
            )
            VerticalDivider()
            Box(
                modifier = Modifier
                    .weight(detailPaneWeight)
                    .fillMaxHeight()
            ) {
                val selectedRun = runsUiState.selectedRun
                if (selectedRun != null) {
                    // Keyed by run ID so switching runs starts a fresh composition
                    // instead of reusing the previous run's remembered scroll state.
                    key(selectedRun.id) {
                        RunDetailScreen(
                            viewModel = runsViewModel,
                            onBackClick = { runsViewModel.clearSelectedRun() },
                            onNoteClick = onNoteClick,
                            onApprovalsClick = onApprovalsClick,
                            showBackButton = false
                        )
                    }
                } else {
                    EmptyDetailPane(message = "Pick a run to replay its agent trace here.")
                }
            }
        }
        return
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

/** What the detail pane shows before anything in the list has been picked. */
@Composable
private fun EmptyDetailPane(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
