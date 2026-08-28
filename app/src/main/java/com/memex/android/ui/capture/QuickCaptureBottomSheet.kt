package com.memex.android.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.memex.android.data.api.CaptureResponse
import com.memex.android.ui.components.AudioWaveformMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Material 3 ModalBottomSheet providing multi-modal quick capture (Text, Voice, Image, Link).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureBottomSheet(
    onDismissRequest: () -> Unit,
    viewModel: CaptureViewModel,
    modifier: Modifier = Modifier,
    onSuccess: (CaptureResponse) -> Unit = {},
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val viewState by viewModel.viewState.collectAsState()
    val context = LocalContext.current

    var permissionErrorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            permissionErrorMessage = null
            val tempFile = File(context.cacheDir, "capture_voice_${System.currentTimeMillis()}.m4a")
            viewModel.startRecording(tempFile)
        } else {
            permissionErrorMessage = "Audio recording permission is required to capture voice notes."
        }
    }

    LaunchedEffect(viewState.uiState) {
        val state = viewState.uiState
        if (state is CaptureUiState.Success) {
            onSuccess(state.response)
            viewModel.reset()
            onDismissRequest()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.reset()
            onDismissRequest()
        },
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Capture",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = {
                    viewModel.reset()
                    onDismissRequest()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mode Tabs
            PrimaryTabRow(
                selectedTabIndex = viewState.mode.ordinal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = viewState.mode == CaptureMode.TEXT,
                    onClick = {
                        permissionErrorMessage = null
                        viewModel.setMode(CaptureMode.TEXT)
                    },
                    text = { Text("Text") },
                    icon = { Icon(Icons.Default.Edit, contentDescription = "Text") }
                )
                Tab(
                    selected = viewState.mode == CaptureMode.VOICE,
                    onClick = {
                        permissionErrorMessage = null
                        viewModel.setMode(CaptureMode.VOICE)
                    },
                    text = { Text("Voice") },
                    icon = { Icon(Icons.Default.Mic, contentDescription = "Voice") }
                )
                Tab(
                    selected = viewState.mode == CaptureMode.IMAGE,
                    onClick = {
                        permissionErrorMessage = null
                        viewModel.setMode(CaptureMode.IMAGE)
                    },
                    text = { Text("Image") },
                    icon = { Icon(Icons.Default.Image, contentDescription = "Image") }
                )
                Tab(
                    selected = viewState.mode == CaptureMode.LINK,
                    onClick = {
                        permissionErrorMessage = null
                        viewModel.setMode(CaptureMode.LINK)
                    },
                    text = { Text("Link") },
                    icon = { Icon(Icons.Default.Link, contentDescription = "Link") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Display active error (either ViewModel or permission error)
            val displayedError = viewState.errorMessage ?: permissionErrorMessage
            AnimatedVisibility(
                visible = displayedError != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = displayedError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            permissionErrorMessage = null
                            viewModel.dismissError()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss error",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Uploading progress
            AnimatedVisibility(
                visible = viewState.uiState is CaptureUiState.Uploading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val uploadState = viewState.uiState as? CaptureUiState.Uploading
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uploadState?.progressMessage ?: "Processing capture...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Tab Content
            when (viewState.mode) {
                CaptureMode.TEXT -> TextCaptureContent(
                    text = viewState.textInput,
                    isSubmitting = viewState.isSubmitting,
                    onTextChanged = { viewModel.updateTextInput(it) },
                    onSubmit = { viewModel.submitText() }
                )
                CaptureMode.VOICE -> VoiceCaptureContent(
                    isRecording = viewState.isRecording,
                    durationSeconds = viewState.recordingDurationSeconds,
                    amplitude = viewState.amplitude,
                    isSubmitting = viewState.isSubmitting,
                    onStartRecording = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            permissionErrorMessage = null
                            val tempFile = File(context.cacheDir, "capture_voice_${System.currentTimeMillis()}.m4a")
                            viewModel.startRecording(tempFile)
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopRecording = { viewModel.stopRecordingAndSubmit() },
                    onCancelRecording = { viewModel.cancelRecording() }
                )
                CaptureMode.IMAGE -> ImageCaptureContent(
                    selectedBytes = viewState.selectedImageBytes,
                    caption = viewState.imageCaption,
                    isSubmitting = viewState.isSubmitting,
                    onImageUriSelected = { uri ->
                        viewModel.onImageUriSelected(context.contentResolver, uri)
                    },
                    onCaptionChanged = { viewModel.updateImageCaption(it) },
                    onSubmit = { viewModel.submitImage() }
                )
                CaptureMode.LINK -> LinkCaptureContent(
                    url = viewState.linkUrl,
                    title = viewState.linkTitle,
                    note = viewState.linkNote,
                    isSubmitting = viewState.isSubmitting,
                    onUrlChanged = { viewModel.updateLinkUrl(it) },
                    onTitleChanged = { viewModel.updateLinkTitle(it) },
                    onNoteChanged = { viewModel.updateLinkNote(it) },
                    onSubmit = { viewModel.submitLink() }
                )
            }
        }
    }
}

@Composable
private fun TextCaptureContent(
    text: String,
    isSubmitting: Boolean,
    onTextChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            placeholder = { Text("What's on your mind? Capture thoughts, actions, or ideas...") },
            minLines = 4,
            maxLines = 10,
            enabled = !isSubmitting,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSubmit,
            enabled = text.isNotBlank() && !isSubmitting,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Saving...")
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Capture Note")
            }
        }
    }
}

@Composable
private fun VoiceCaptureContent(
    isRecording: Boolean,
    durationSeconds: Long,
    amplitude: Float,
    isSubmitting: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit
) {
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Waveform Visualizer
        AudioWaveformMeter(
            amplitude = amplitude,
            isRecording = isRecording,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Timer Display
        Text(
            text = formattedTime,
            fontFamily = FontFamily.Monospace,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (isRecording) "Recording audio (AAC)..." else "Tap microphone to start recording",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isRecording) {
                OutlinedButton(
                    onClick = onCancelRecording,
                    enabled = !isSubmitting
                ) {
                    Text("Discard")
                }

                Spacer(modifier = Modifier.width(20.dp))

                FilledIconButton(
                    onClick = onStopRecording,
                    enabled = !isSubmitting,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.size(64.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop & Submit",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            } else {
                FilledIconButton(
                    onClick = onStartRecording,
                    enabled = !isSubmitting,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Start Recording",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageCaptureContent(
    selectedBytes: ByteArray?,
    caption: String,
    isSubmitting: Boolean,
    onImageUriSelected: (Uri) -> Unit,
    onCaptionChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onImageUriSelected(uri)
        }
    }

    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(selectedBytes) {
        if (selectedBytes != null && selectedBytes.isNotEmpty()) {
            val bitmap = withContext(Dispatchers.Default) {
                try {
                    BitmapFactory.decodeByteArray(selectedBytes, 0, selectedBytes.size)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
            previewBitmap = bitmap
        } else {
            previewBitmap = null
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (previewBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Image(
                    bitmap = previewBitmap!!,
                    contentDescription = "Selected image preview",
                    modifier = Modifier.matchParentSize()
                )
                IconButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Change Image")
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(16.dp)
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { galleryLauncher.launch("image/*") }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Choose image",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap to choose photo (< 1 MB)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = caption,
            onValueChange = onCaptionChanged,
            placeholder = { Text("Add caption / context (optional)...") },
            maxLines = 3,
            enabled = !isSubmitting,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSubmit,
            enabled = selectedBytes != null && !isSubmitting,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Uploading...")
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Capture Image")
            }
        }
    }
}

@Composable
private fun LinkCaptureContent(
    url: String,
    title: String,
    note: String,
    isSubmitting: Boolean,
    onUrlChanged: (String) -> Unit,
    onTitleChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChanged,
            placeholder = { Text("https://example.com/article...") },
            label = { Text("URL") },
            singleLine = true,
            enabled = !isSubmitting,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = title,
            onValueChange = onTitleChanged,
            placeholder = { Text("Page Title (optional)") },
            label = { Text("Title") },
            singleLine = true,
            enabled = !isSubmitting,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = note,
            onValueChange = onNoteChanged,
            placeholder = { Text("Why are you saving this? (optional note)") },
            label = { Text("Note") },
            minLines = 2,
            maxLines = 4,
            enabled = !isSubmitting,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSubmit,
            enabled = url.isNotBlank() && !isSubmitting,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Saving...")
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Link")
            }
        }
    }
}
