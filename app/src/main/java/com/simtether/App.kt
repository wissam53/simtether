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
        // Resolve the subscription entitlement early — the client
        // gates on it and Play's cache answers offline.
        com.simtether.billing.Billing.init(this)
        // ClientService starts from MainActivity / BootReceiver.
    }
}
