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
        // Generic :ui screens reach the bridge link through this
        // backend — installed once, service lifetime doesn't matter
        // (sends no-op safely while ClientService is down).
        com.simtether.shared.UiBackend.impl =
            com.simtether.client.ClientBackend()
        // ClientService starts from MainActivity / BootReceiver.
    }
}
