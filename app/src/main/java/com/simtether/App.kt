package com.simtether

import android.app.Application

class App : Application() {
    // applicationContext callers (notifiers) resolve resources through
    // the Application — wrap it so they honour the in-app language.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.simtether.shared.LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Role-specific services start from MainActivity role selection.
    }
}

/** Notification taps → in-app navigation requests (thread deep-links). */
object NavBus {
    val openThread = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val openCalls = kotlinx.coroutines.flow.MutableStateFlow(false)
}
