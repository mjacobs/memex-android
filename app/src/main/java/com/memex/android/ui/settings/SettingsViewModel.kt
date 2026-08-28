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

/**
 * Outcome of the "Save & Test Connection" probe.
 */
sealed interface ConnectionResult {
    data class Success(val message: String) : ConnectionResult
    data class Failure(val message: String) : ConnectionResult
}

data class SettingsUiState(
    val serverUrl: String = "",
    val deviceId: String = "",
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
            deviceId = appPreferences.deviceId,
            hasStoredToken = !tokenStorage.getToken().isNullOrBlank()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val savedServerUrl: String = appPreferences.serverUrl

    fun updateServerUrl(value: String) {
        _uiState.update { it.copy(serverUrl = value, connectionResult = null) }
    }

    fun updateDeviceId(value: String) {
        _uiState.update { it.copy(deviceId = value, connectionResult = null) }
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
     * Persists the entered settings, then probes `/health` and `/api/v1/notes?limit=1`
     * against the saved URL with the saved key.
     */
    fun saveAndTestConnection() {
        val state = _uiState.value
        appPreferences.serverUrl = state.serverUrl
        appPreferences.deviceId = state.deviceId
        if (state.tokenInput.isNotBlank()) {
            tokenStorage.setToken(state.tokenInput)
        }

        val effectiveUrl = appPreferences.serverUrl
        val token = tokenStorage.getToken()

        _uiState.update {
            it.copy(
                serverUrl = effectiveUrl,
                deviceId = appPreferences.deviceId,
                tokenInput = "",
                hasStoredToken = !token.isNullOrBlank(),
                isTesting = true,
                connectionResult = null,
                restartRequired = effectiveUrl != savedServerUrl
            )
        }

        viewModelScope.launch(dispatcher) {
            val result = try {
                connectionTester(effectiveUrl, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            _uiState.update {
                it.copy(
                    isTesting = false,
                    connectionResult = result.fold(
                        onSuccess = { message -> ConnectionResult.Success(message) },
                        onFailure = { error ->
                            ConnectionResult.Failure(error.message ?: "Connection failed")
                        }
                    )
                )
            }
        }
    }
}
