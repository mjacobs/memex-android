package com.memex.android.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Interface for securely storing and retrieving the device bearer authentication token.
 */
interface SecureTokenStorage {
    fun getToken(): String?
    fun setToken(token: String?)
    fun clearToken()
}

/**
 * Android Keystore-backed EncryptedSharedPreferences implementation of [SecureTokenStorage].
 * Uses AES256_GCM encryption scheme with MasterKey.
 */
class EncryptedSecureTokenStorage(
    private val context: Context,
    private val prefsName: String = PREFS_NAME
) : SecureTokenStorage {

    private val sharedPreferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getToken(): String? {
        return sharedPreferences.getString(KEY_AUTH_TOKEN, null)
    }

    override fun setToken(token: String?) {
        if (token.isNullOrBlank()) {
            clearToken()
        } else {
            sharedPreferences.edit().putString(KEY_AUTH_TOKEN, token.trim()).apply()
        }
    }

    override fun clearToken() {
        sharedPreferences.edit().remove(KEY_AUTH_TOKEN).apply()
    }

    companion object {
        const val PREFS_NAME = "memex_secure_prefs"
        private const val KEY_AUTH_TOKEN = "auth_bearer_token"
    }
}

/**
 * In-memory implementation of [SecureTokenStorage] for testing and mocking.
 */
class InMemorySecureTokenStorage(
    initialToken: String? = null
) : SecureTokenStorage {
    private var token: String? = initialToken

    override fun getToken(): String? = token

    override fun setToken(token: String?) {
        this.token = if (token.isNullOrBlank()) null else token.trim()
    }

    override fun clearToken() {
        this.token = null
    }
}
