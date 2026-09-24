package com.simtether

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.launch

class App : Application() {
    // applicationContext callers (notifiers) resolve resources through
    // the Application — wrap it so they honour the in-app language.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.simtether.shared.LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Resolve the purchase entitlement early — the client
        // gates on it and Play's cache answers offline.
        com.simtether.billing.Billing.init(this)
        // Generic :ui screens reach the bridge link through this
        // backend — installed once, service lifetime doesn't matter
        // (sends no-op safely while ClientService is down).
        com.simtether.shared.UiBackend.impl =
            com.simtether.client.ClientBackend()

        // Entitlement enforcement at the service layer — the paywall
        // screen is only UI; a lapse must actually stop the link.
        // null (Play cache unresolved) is allowed — a transient query
        // failure must not strand a paying user.
        com.simtether.client.ClientServiceHolder.mayRun =
            { com.simtether.billing.Billing.entitled.value != false }
        scope.launch {
            com.simtether.billing.Billing.entitled.collect { e ->
                if (e == false) stopService(
                    Intent(this@App, com.simtether.client.ClientService::class.java))
            }
        }
        // ClientService starts from MainActivity / BootReceiver.
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.Dispatchers.Default)
}
