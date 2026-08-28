package com.memex.android.data.local

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppPreferencesTest {

    @Test
    fun testUsableServerUrlsAreAccepted() {
        assertTrue(SharedPreferencesAppPreferences.isUsableServerUrl("https://memex.example.com"))
        assertTrue(SharedPreferencesAppPreferences.isUsableServerUrl("http://127.0.0.1:8080"))
        assertTrue(SharedPreferencesAppPreferences.isUsableServerUrl("  https://memex.example.com/  "))
    }

    @Test
    fun testUrlsRetrofitCannotBuildAClientForAreRejected() {
        // Each of these would throw when Retrofit builds its client, taking the app
        // down before Settings could be used to correct it.
        assertFalse(SharedPreferencesAppPreferences.isUsableServerUrl(""))
        assertFalse(SharedPreferencesAppPreferences.isUsableServerUrl("memex.example.com"))
        assertFalse(SharedPreferencesAppPreferences.isUsableServerUrl("ftp://memex.example.com"))
        assertFalse(SharedPreferencesAppPreferences.isUsableServerUrl("not a url at all"))
    }
}
