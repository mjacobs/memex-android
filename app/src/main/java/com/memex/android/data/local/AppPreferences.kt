package com.memex.android.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Interface for non-secret user preferences (e.g. server URL, device ID).
 */
interface AppPreferences {
    var serverUrl: String
    var deviceId: String
    fun clear()
}

/**
 * SharedPreferences implementation of [AppPreferences].
 */
class SharedPreferencesAppPreferences(
    private val context: Context,
    private val prefsName: String = PREFS_NAME
) : AppPreferences {

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    override var serverUrl: String
        get() = sharedPreferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) {
            val normalized = if (value.isBlank()) DEFAULT_SERVER_URL else value.trim()
            sharedPreferences.edit().putString(KEY_SERVER_URL, normalized).apply()
        }

    override var deviceId: String
        get() = sharedPreferences.getString(KEY_DEVICE_ID, DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID
        set(value) {
            val normalized = if (value.isBlank()) DEFAULT_DEVICE_ID else value.trim()
            sharedPreferences.edit().putString(KEY_DEVICE_ID, normalized).apply()
        }

    override fun clear() {
        sharedPreferences.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "memex_app_prefs"
        const val DEFAULT_SERVER_URL = "https://memex-PROJECT_NUMBER.us-central1.run.app"
        const val DEFAULT_DEVICE_ID = "android"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
    }
}

/**
 * In-memory implementation of [AppPreferences] for testing and previewing.
 */
class InMemoryAppPreferences(
    initialServerUrl: String = SharedPreferencesAppPreferences.DEFAULT_SERVER_URL,
    initialDeviceId: String = SharedPreferencesAppPreferences.DEFAULT_DEVICE_ID
) : AppPreferences {
    override var serverUrl: String = initialServerUrl
    override var deviceId: String = initialDeviceId

    override fun clear() {
        serverUrl = SharedPreferencesAppPreferences.DEFAULT_SERVER_URL
        deviceId = SharedPreferencesAppPreferences.DEFAULT_DEVICE_ID
    }
}
