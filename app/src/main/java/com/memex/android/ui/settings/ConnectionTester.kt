package com.memex.android.ui.settings

import com.memex.android.data.api.ApiClient
import com.memex.android.data.security.InMemorySecureTokenStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Probes a Memex deployment with the supplied credentials without touching the app's
 * live API stack: `/health` proves reachability, `/api/v1/notes?limit=1` proves the
 * bearer key is accepted.
 */
suspend fun testMemexConnection(serverUrl: String, token: String?): Result<String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val apiService = ApiClient.createApiService(
                baseUrl = serverUrl,
                tokenStorage = InMemorySecureTokenStorage(initialToken = token)
            )

            val health = apiService.getHealth()
            if (!health.ok) {
                error("Server reachable but reported not healthy")
            }

            if (token.isNullOrBlank()) {
                return@runCatching "Server is healthy. Add a device key to load notes."
            }

            val notes = apiService.getNotes(limit = 1).notes
            if (notes.isEmpty()) {
                "Connected and authorized. No notes captured yet."
            } else {
                "Connected and authorized. Most recent note: ${notes.first().summary.take(60)}"
            }
        }
    }
