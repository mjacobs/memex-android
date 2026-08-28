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

    /**
     * The server the stored key was entered for, when known. A key that records its
     * origin can never be attached to a request bound somewhere else, however the
     * configured server URL later changes.
     */
    fun getTokenOrigin(): String?

    fun setToken(token: String?, origin: String?)

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

    override fun getTokenOrigin(): String? {
        return sharedPreferences.getString(KEY_AUTH_TOKEN_ORIGIN, null)
    }

    override fun setToken(token: String?, origin: String?) {
        if (token.isNullOrBlank()) {
            clearToken()
        } else {
            sharedPreferences.edit()
                .putString(KEY_AUTH_TOKEN, token.trim())
                .apply {
                    if (origin.isNullOrBlank()) {
                        remove(KEY_AUTH_TOKEN_ORIGIN)
                    } else {
                        putString(KEY_AUTH_TOKEN_ORIGIN, origin.trim())
                    }
                }
                .apply()
        }
    }

    override fun clearToken() {
        sharedPreferences.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_AUTH_TOKEN_ORIGIN)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "memex_secure_prefs"
        private const val KEY_AUTH_TOKEN = "auth_bearer_token"
        private const val KEY_AUTH_TOKEN_ORIGIN = "auth_bearer_token_origin"
    }
}

/**
 * In-memory implementation of [SecureTokenStorage] for testing and mocking.
 */
class InMemorySecureTokenStorage(
    initialToken: String? = null,
    initialOrigin: String? = null
) : SecureTokenStorage {
    private var token: String? = initialToken
    private var origin: String? = initialOrigin

    override fun getToken(): String? = token

    override fun getTokenOrigin(): String? = origin

    override fun setToken(token: String?, origin: String?) {
        if (token.isNullOrBlank()) {
            clearToken()
        } else {
            this.token = token.trim()
            this.origin = origin?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    override fun clearToken() {
        this.token = null
        this.origin = null
    }
}
