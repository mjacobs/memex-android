package com.memex.android.data.api

import com.memex.android.data.security.SecureTokenStorage
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects `Authorization: Bearer <token>` into outgoing requests when a bearer token is
 * present in [SecureTokenStorage].
 *
 * [allowedOrigin] is the one server the key may be sent to. A client built before the
 * user pointed the app at a different server is still bound to the old base URL, so
 * without this check its next request would carry the new server's key to the old host.
 * Returning null disables the check, which is only appropriate for a client built for
 * one specific URL (the Settings connection probe).
 */
class AuthInterceptor(
    private val tokenStorage: SecureTokenStorage,
    private val allowedOrigin: () -> String? = { null }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = tokenStorage.getToken()

        val requestBuilder = originalRequest.newBuilder()
        val mayAuthenticate = allowedOrigin()
            ?.let { origin -> originOf(origin) == originOf(originalRequest.url.toString()) }
            ?: true

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
