package com.memex.android.data.api

import com.memex.android.data.security.SecureTokenStorage
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp Interceptor that injects `Authorization: Bearer <token>` into outgoing requests
 * when a valid bearer token is present in [SecureTokenStorage].
 */
class AuthInterceptor(
    private val tokenStorage: SecureTokenStorage
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = tokenStorage.getToken()

        val requestBuilder = originalRequest.newBuilder()
        if (!token.isNullOrBlank() && originalRequest.header(HEADER_AUTHORIZATION) == null) {
            requestBuilder.header(HEADER_AUTHORIZATION, "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}
