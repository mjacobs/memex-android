package com.memex.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memex.android.data.local.AppPreferences
import com.memex.android.data.security.SecureTokenStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Outcome of the "Save & Test Connection" probe.
 */
sealed interface ConnectionResult {
    data class Success(val message: String) : ConnectionResult
    data class Failure(val message: String) : ConnectionResult
}

data class SettingsUiState(
    val serverUrl: String = "",
    /** Held only until saved; never persisted anywhere but the Keystore-backed store. */
    val tokenInput: String = "",
    val hasStoredToken: Boolean = false,
    val isTesting: Boolean = false,
    val connectionResult: ConnectionResult? = null,
    /** The base URL is bound at process start, so a change needs an app restart. */
    val restartRequired: Boolean = false
)

/**
 * Settings screen state. The bearer key is typed by hand here and written straight to
 * [SecureTokenStorage]; it is never logged, exported, or written to app preferences.
 *
 * Two rules protect the stored key and the app's ability to start:
 * a URL is normalized and validated before it is persisted, so a typo cannot leave
 * Retrofit unable to build its client on next launch; and a key is bound to the origin
 * it was entered for, so pointing the app at a different host discards it rather than
 * sending it somewhere new.
 */
class SettingsViewModel(
    private val tokenStorage: SecureTokenStorage,
    private val appPreferences: AppPreferences,
    private val connectionTester: suspend (serverUrl: String, token: String?) -> Result<String>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            serverUrl = appPreferences.serverUrl,
            hasStoredToken = !tokenStorage.getToken().isNullOrBlank()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** The URL bound when the process started; a change from it needs a restart. */
    private val bootServerUrl: String = appPreferences.serverUrl

    fun updateServerUrl(value: String) {
        _uiState.update { it.copy(serverUrl = value, connectionResult = null) }
    }

    fun updateTokenInput(value: String) {
        _uiState.update { it.copy(tokenInput = value, connectionResult = null) }
    }

    fun clearToken() {
        tokenStorage.clearToken()
        _uiState.update {
            it.copy(tokenInput = "", hasStoredToken = false, connectionResult = null)
        }
    }

    /**
     * Validates and persists the entered settings, then probes `/health` (unauthenticated)
     * and `/api/v1/notes?limit=1` (with the stored key).
     */
    fun saveAndTestConnection() {
        val state = _uiState.value

        val normalizedUrl = normalizeServerUrl(state.serverUrl)
        if (normalizedUrl == null) {
            // Nothing is persisted: an unusable base URL would break the API client on
            // the next launch, and Settings with it.
            _uiState.update {
                it.copy(
                    connectionResult = ConnectionResult.Failure(
                        "Enter a full http:// or https:// server URL. The saved URL is unchanged."
                    )
                )
            }
            return
        }

        val previousUrl = appPreferences.serverUrl
        val originChanged = originOf(normalizedUrl) != originOf(previousUrl)
        val enteredToken = state.tokenInput.takeIf { it.isNotBlank() }

        // A key belongs to the server it was issued for; never forward it to a new one.
        val credentialDropped = originChanged && enteredToken == null &&
            !tokenStorage.getToken().isNullOrBlank()
        if (credentialDropped) {
            tokenStorage.clearToken()
        }

        appPreferences.serverUrl = normalizedUrl
        if (enteredToken != null) {
            tokenStorage.setToken(enteredToken)
        }

        val token = tokenStorage.getToken()

        _uiState.update {
            it.copy(
                serverUrl = normalizedUrl,
                tokenInput = "",
                hasStoredToken = !token.isNullOrBlank(),
                isTesting = true,
                connectionResult = null,
                restartRequired = normalizedUrl != bootServerUrl
            )
        }

        viewModelScope.launch(dispatcher) {
            val result = try {
                connectionTester(normalizedUrl, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            _uiState.update {
                it.copy(
                    isTesting = false,
                    connectionResult = result.fold(
                        onSuccess = { message ->
                            if (credentialDropped) {
                                ConnectionResult.Success(
                                    "$message\n\nThe previous device key was discarded because the " +
                                        "server changed. Enter the key for this server."
                                )
                            } else {
                                ConnectionResult.Success(message)
                            }
                        },
                        onFailure = { error ->
                            ConnectionResult.Failure(error.message ?: "Connection failed")
                        }
                    )
                )
            }
        }
    }

    /** Returns the canonical URL, or null when it is not a usable http(s) endpoint. */
    private fun normalizeServerUrl(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.host.isBlank()) return null
        return url.toString()
    }

    /** Scheme, host, and port — what decides whether a stored key still applies. */
    private fun originOf(url: String): String {
        val parsed: HttpUrl = url.trim().toHttpUrlOrNull() ?: return url.trim()
        return "${parsed.scheme}://${parsed.host}:${parsed.port}"
    }
}
