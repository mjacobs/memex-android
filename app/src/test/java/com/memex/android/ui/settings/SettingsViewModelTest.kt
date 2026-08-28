package com.memex.android.ui.settings

import com.memex.android.data.local.InMemoryAppPreferences
import com.memex.android.data.security.InMemorySecureTokenStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tokenStorage: InMemorySecureTokenStorage
    private lateinit var appPreferences: InMemoryAppPreferences

    private var testedUrls = mutableListOf<String>()
    private var testedTokens = mutableListOf<String?>()
    private var testerResult: Result<String> = Result.success("Connected and authorized.")

    private fun viewModel(): SettingsViewModel = SettingsViewModel(
        tokenStorage = tokenStorage,
        appPreferences = appPreferences,
        connectionTester = { url, token ->
            testedUrls += url
            testedTokens += token
            testerResult
        },
        dispatcher = testDispatcher
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tokenStorage = InMemorySecureTokenStorage(
            initialToken = "original-device-key",
            initialOrigin = "https://memex.example.com/"
        )
        appPreferences = InMemoryAppPreferences(initialServerUrl = "https://memex.example.com/")
        testedUrls = mutableListOf()
        testedTokens = mutableListOf()
        testerResult = Result.success("Connected and authorized.")
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInvalidUrlIsNeitherPersistedNorProbed() = runTest {
        val viewModel = viewModel()

        viewModel.updateServerUrl("memex.example.com")
        viewModel.saveAndTestConnection()
        advanceUntilIdle()

        assertEquals("https://memex.example.com/", appPreferences.serverUrl)
        assertTrue(testedUrls.isEmpty())
        assertTrue(viewModel.uiState.value.connectionResult is ConnectionResult.Failure)
        assertFalse(viewModel.uiState.value.isTesting)
    }

    @Test
    fun testValidUrlIsNormalizedBeforePersisting() = runTest {
        val viewModel = viewModel()

        viewModel.updateServerUrl("  https://memex.example.com  ")
        viewModel.saveAndTestConnection()
        advanceUntilIdle()

        assertEquals("https://memex.example.com/", appPreferences.serverUrl)
        assertEquals(listOf("https://memex.example.com/"), testedUrls)
        assertTrue(viewModel.uiState.value.connectionResult is ConnectionResult.Success)
    }

    @Test
    fun testChangingServerOriginDiscardsTheStoredKey() = runTest {
        val viewModel = viewModel()

        viewModel.updateServerUrl("https://other-memex.example.com/")
        viewModel.saveAndTestConnection()
        advanceUntilIdle()

        // The key belonged to the previous origin; it is never sent to the new one.
        assertNull(tokenStorage.getToken())
        assertEquals(listOf<String?>(null), testedTokens)
        assertFalse(viewModel.uiState.value.hasStoredToken)
        assertTrue(viewModel.uiState.value.restartRequired)
    }

    @Test
    fun testSameOriginKeepsTheStoredKey() = runTest {
        val viewModel = viewModel()

        viewModel.updateServerUrl("https://memex.example.com/")
        viewModel.saveAndTestConnection()
        advanceUntilIdle()

        assertEquals("original-device-key", tokenStorage.getToken())
        assertEquals(listOf<String?>("original-device-key"), testedTokens)
        assertTrue(viewModel.uiState.value.hasStoredToken)
    }

    @Test
    fun testNewKeyEnteredWithNewServerIsUsed() = runTest {
        val viewModel = viewModel()

        viewModel.updateServerUrl("https://other-memex.example.com/")
        viewModel.updateTokenInput("new-device-key")
        viewModel.saveAndTestConnection()
        advanceUntilIdle()

        assertEquals("new-device-key", tokenStorage.getToken())
        assertEquals(listOf<String?>("new-device-key"), testedTokens)
        // The entered key is dropped from UI state once stored.
        assertEquals("", viewModel.uiState.value.tokenInput)
    }

    @Test
    fun testSavedKeyRecordsTheServerItWasEnteredFor() = runTest {
        val viewModel = viewModel()

        viewModel.updateServerUrl("https://other-memex.example.com/")
        viewModel.updateTokenInput("new-device-key")
        viewModel.saveAndTestConnection()
        advanceUntilIdle()

        assertEquals("https://other-memex.example.com/", tokenStorage.getTokenOrigin())
    }

    @Test
    fun testFailedProbeSurfacesTheError() = runTest {
        testerResult = Result.failure(Exception("Unauthorized"))
        val viewModel = viewModel()

        viewModel.saveAndTestConnection()
        advanceUntilIdle()

        val result = viewModel.uiState.value.connectionResult
        assertTrue(result is ConnectionResult.Failure)
        assertEquals("Unauthorized", (result as ConnectionResult.Failure).message)
    }

    @Test
    fun testClearTokenRemovesStoredKey() = runTest {
        val viewModel = viewModel()

        viewModel.clearToken()

        assertNull(tokenStorage.getToken())
        assertFalse(viewModel.uiState.value.hasStoredToken)
    }
}
