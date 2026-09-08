package com.memex.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. The [HiltAndroidApp] annotation generates the root
 * dependency container that every `@AndroidEntryPoint` and `@HiltViewModel` in the app
 * is served from.
 */
@HiltAndroidApp
class MemexApplication : Application()
