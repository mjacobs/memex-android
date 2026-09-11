package com.memex.android.ui.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.memex.android.data.api.ApiClient
import com.memex.android.data.local.SharedPreferencesAppPreferences
import com.memex.android.data.repository.*
import com.memex.android.data.security.EncryptedSecureTokenStorage
import com.memex.android.ui.MainActivity
import com.memex.android.ui.theme.MemexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ClipboardActivity : ComponentActivity() {
    private val preferences by lazy { SharedPreferencesAppPreferences(applicationContext) }
    private val repository by lazy {
        ApiClipboardRepository(ApiClient.createApiService(preferences.serverUrl,
            ApiClient.createOkHttpClient(EncryptedSecureTokenStorage(applicationContext),
                allowedOrigin = { preferences.serverUrl })))
    }
    private val model: ClipboardViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ClipboardViewModel(repository) as T
        }
    }
    private var localError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Old export files are app-private and excluded from backup by the manifest.
        File(cacheDir, "clipboard").listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - 3600000) it.deleteRecursively() }
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) {
            lifecycleScope.launch {
                try { model.stage(withContext(Dispatchers.IO) { incoming(intent) }) }
                catch (_: Exception) { localError = "Could not read this share. Share one text, image, or file up to 10 MiB." }
            }
        }
        setContent { MemexTheme { Screen() } }
    }
    override fun onResume() { super.onResume(); model.refresh() }

    private fun incoming(intent: Intent): ClipboardRequest {
        require((intent.clipData?.itemCount ?: 0) <= 1)
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
        if (uri != null) {
            require(uri.scheme == "content")
            var filename: String? = null
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) filename = it.getString(0)
            }
            val bytes = contentResolver.openInputStream(uri)?.use { readClipboardBytes(it) }
                ?: error("Cannot open share")
            return clipboardFile(bytes, contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream", filename)
        }
        return clipboardText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: error("No text"))
    }

    private fun retrieve(item: ClipboardItem, action: String) {
        lifecycleScope.launch {
            try {
                val current = repository.get(item.id)
                require(current.isLive())
                when (action) {
                    "copy" -> {
                        val clip = ClipData.newPlainText("Memex Clipboard", current.text)
                        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                        localError = "Copied"
                    }
                    "open" -> {
                        val url = Uri.parse(current.text)
                        require(url.scheme in listOf("https", "http"))
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    }
                    else -> {
                        val bytes = withContext(Dispatchers.IO) { repository.content(item.id) }
                        require(current.isLive())
                        val file = withContext(Dispatchers.IO) {
                            val dir = File(cacheDir, "clipboard").apply { mkdirs() }
                            val name = current.filename?.substringAfterLast('/')?.substringAfterLast('\\')
                                ?.takeIf { it !in listOf("", ".", "..") } ?: "download"
                            val exportDir = File(dir, current.id).apply { mkdirs() }
                            File(exportDir, name).apply { writeBytes(bytes) }
                        }
                        val uri = FileProvider.getUriForFile(this@ClipboardActivity, "$packageName.clipboard", file)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = current.mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("Clipboard file", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(share, "Share or save file"))
                    }
                }
            } catch (_: Exception) { localError = "Item unavailable. Refresh and try again." }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Screen() {
        val state by model.state.collectAsState()
        var text by remember { mutableStateOf("") }
        Scaffold(topBar = { TopAppBar(title = { Text("Clipboard") }, navigationIcon = {
            TextButton(onClick = { finish() }) { Text("Back") }
        }, actions = { TextButton(onClick = { model.refresh() }) { Text("Refresh") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Private shared history · expires after 24 hours", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { startActivity(Intent(this@ClipboardActivity, MainActivity::class.java)) }) { Text("Memex connection settings") }
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Paste text or a URL") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !state.busy && text.isNotEmpty(), onClick = {
                        try { model.stage(clipboardText(text)); text = ""; model.send() }
                        catch (_: Exception) { localError = "Text exceeds 64 KiB" }
                    }) { Text("Send to Clipboard") }
                    if (state.hasDraft) Row {
                        Button(enabled = !state.busy, onClick = { model.send() }) { Text("Send shared item / retry") }
                        TextButton(enabled = !state.busy, onClick = { model.discard() }) { Text("Discard") }
                    }
                    state.message?.let { Text(it) }
                    localError?.let { Text(it); TextButton(onClick = { localError = null }) { Text("Dismiss") } }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!state.busy && state.items.isEmpty()) Text("Your clipboard is empty")
                }
                items(state.items, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.text ?: item.filename ?: item.kind, maxLines = 5)
                            Text("${item.sizeBytes} bytes · expires ${item.expiresAt}", style = MaterialTheme.typography.labelSmall)
                            Row {
                                if (item.text != null) TextButton(onClick = { retrieve(item, "copy") }) { Text("Copy") }
                                if (item.kind == "url") TextButton(onClick = { retrieve(item, "open") }) { Text("Open") }
                                if (item.kind in listOf("image", "file")) TextButton(onClick = { retrieve(item, "share") }) { Text("Share / save") }
                                TextButton(enabled = !state.busy, onClick = { model.delete(item) }) { Text("Delete") }
                            }
                            if (item.canKeep) TextButton(enabled = !state.busy, onClick = { model.keep(item) }) { Text("Keep in Memex") }
                            else Text("Keep is unavailable for this file type", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
