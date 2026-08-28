package com.memex.android.data.api

import com.memex.android.data.security.SecureTokenStorage
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects `Authorization: Bearer <token>` into outgoing requests when a bearer token is
 * present in [SecureTokenStorage].
 *
 * Two independent checks must both pass. The key must record the origin it was entered
 * for and that origin must match the request — an unbound key is withheld rather than
 * guessed at. [allowedOrigin], when non-null, additionally pins the client to one
 * server, so a client built before the user pointed the app elsewhere cannot carry the
 * newly entered key to the host it is still bound to.
 */
class AuthInterceptor(
    private val tokenStorage: SecureTokenStorage,
    private val allowedOrigin: () -> String? = { null }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = tokenStorage.getToken()

        val requestBuilder = originalRequest.newBuilder()
        val requestOrigin = originOf(originalRequest.url.toString())
        val configuredOrigin = allowedOrigin()?.let { originOf(it) }
        // A key is only ever sent to the server it was entered for. A key with no
        // recorded origin cannot be shown to belong anywhere, so it is withheld rather
        // than guessed at; re-entering it in Settings records the binding.
        val keyOrigin = tokenStorage.getTokenOrigin()?.let { originOf(it) }

        val mayAuthenticate = (configuredOrigin == null || configuredOrigin == requestOrigin) &&
            (keyOrigin != null && keyOrigin == requestOrigin)

        if (mayAuthenticate &&
            !token.isNullOrBlank() &&
            originalRequest.header(HEADER_AUTHORIZATION) == null
        ) {
            requestBuilder.header(HEADER_AUTHORIZATION, "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"

        /** Scheme, host, and port — what decides whether a key applies to a request. */
        fun originOf(url: String): String? {
            val parsed = url.trim().toHttpUrlOrNull() ?: return null
            return "${parsed.scheme}://${parsed.host}:${parsed.port}"
        }
    }
}
