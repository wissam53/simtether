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
        // Flavor wiring: the rooted build installs its call-audio relay
        // into :bridge here; the store build's RootFeatures is a no-op,
        // so this call is inert on Play.
        RootFeatures.install(applicationContext)
        // The bridge service starts from MainActivity / BootReceiver.
    }
}
