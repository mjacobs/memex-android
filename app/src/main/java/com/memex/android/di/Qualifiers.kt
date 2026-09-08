package com.memex.android.di

import javax.inject.Qualifier

/** The main (UI) dispatcher a ViewModel confines its state updates to. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/** The dispatcher for CPU-bound work such as image compression. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** The dispatcher for blocking disk and network work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Supplies the current time as an ISO-8601 string; swapped for a fixed clock in tests. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NowProvider
