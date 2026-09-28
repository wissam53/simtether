package com.simtether.billing

import android.app.Activity
import android.app.Application
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.simtether.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Play Billing subscription gate for the client role.
 *
 * Product `simtether_pro` (SUBS, monthly) is created in Play Console
 * with a base plan plus an optional free-trial offer — the gate opens
 * only when both exist on a released track. Entitlement lives in
 * Play's on-device cache, so checks work offline; `entitled` stays
 * null until resolved so the UI can show a spinner instead of
 * flashing the paywall.
 *
 * Debug builds bypass the gate — there is no Play product locally.
 */
object Billing {
    const val PRODUCT_ID = "simtether_pro"
    private const val TAG = "SimTether.Billing"

    private lateinit var client: BillingClient

    private val _entitled = MutableStateFlow<Boolean?>(null)
    val entitled: StateFlow<Boolean?> = _entitled

    /** Localized recurring price label from Play ("₺150.00"). */
    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price

    /** Free-trial length in days when the loaded offer carries one —
     *  0 when the configured offer has no free phase. */
    private val _trialDays = MutableStateFlow(0)
    val trialDays: StateFlow<Int> = _trialDays

    private var productDetails: ProductDetails? = null
    /** Subscriptions launch against an offer, not the bare product —
     *  the chosen offer decides whether the trial applies. */
    private var offerToken: String? = null
    private var connected = false
    private var retries = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** A subscribe tap that arrived before billing was ready — fires
     *  once connection + product details are both in place. */
    private var pendingLaunch: java.lang.ref.WeakReference<Activity>? = null

    fun init(app: Application) {
        if (BuildConfig.DEBUG) { _entitled.value = true; return }
        // Sideloaded onto a Play-less device (de-Googled ROM, /e/,
        // Huawei): billing can never connect, so don't strand the
        // user behind a paywall with no way to pay.
        if (!hasPlayStore(app)) { _entitled.value = true; return }
        client = BillingClient.newBuilder(app)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK &&
                    purchases != null) {
                    purchases.forEach(::handlePurchase)
                }
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect()
    }

    private fun hasPlayStore(app: Application): Boolean = try {
        app.packageManager.getPackageInfo("com.android.vending", 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connected = result.responseCode == BillingClient.BillingResponseCode.OK
                if (connected) {
                    retries = 0
                    refreshPurchases()
                    loadProduct()
                } else {
                    Log.w(TAG, "billing setup failed: ${result.debugMessage}")
                    retryConnect()
                }
            }

            override fun onBillingServiceDisconnected() {
                connected = false
                retryConnect()
            }
        })
    }

    private fun retryConnect() {
        if (retries >= 5) {
            // Give up — surface the paywall rather than an endless
            // spinner, but never mark un-entitled on a *transient*
            // failure: a paying user on flaky Wi-Fi must not see the
            // paywall just because the query errored.
            if (_entitled.value == null) _entitled.value = false
            return
        }
        retries++
        handler.postDelayed({ if (!connected) connect() }, 2_000L * retries)
    }

    private fun refreshPurchases() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "purchase query failed: ${result.debugMessage}")
                return@queryPurchasesAsync
            }
            var owned = false
            for (p in purchases) {
                if (p.products.contains(PRODUCT_ID) &&
                    p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    owned = true
                    if (!p.isAcknowledged) acknowledge(p)
                }
            }
            _entitled.value = owned
        }
    }

    /** Re-query Play's purchase cache — the paywall "restore" path. */
    fun refresh() {
        if (connected) refreshPurchases() else connect()
    }

    private fun handlePurchase(p: Purchase) {
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (PRODUCT_ID !in p.products) return
        if (!p.isAcknowledged) acknowledge(p)
        _entitled.value = true
    }

    private fun acknowledge(p: Purchase) {
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(p.purchaseToken).build()
        ) { r ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK)
                Log.w(TAG, "ack failed: ${r.debugMessage}")
        }
    }

    private fun loadProduct() {
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            ).build()
        ) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "product query failed: ${result.debugMessage}")
                return@queryProductDetailsAsync
            }
            val pd = details.firstOrNull() ?: run {
                Log.w(TAG, "product $PRODUCT_ID not found — check Console config")
                return@queryProductDetailsAsync
            }
            productDetails = pd
            // Prefer the offer carrying a free-trial phase — launching
            // a different offer charges immediately instead.
            val offers = pd.subscriptionOfferDetails.orEmpty()
            val offer = offers.firstOrNull { o ->
                o.pricingPhases.pricingPhaseList
                    .any { it.priceAmountMicros == 0L }
            } ?: offers.firstOrNull()
            offerToken = offer?.offerToken
            val phases = offer?.pricingPhases?.pricingPhaseList.orEmpty()
            _trialDays.value = phases.firstOrNull { it.priceAmountMicros == 0L }
                ?.let { periodDays(it.billingPeriod) } ?: 0
            _price.value = phases.lastOrNull { it.priceAmountMicros > 0L }
                ?.formattedPrice
            maybeLaunch()
        }
    }

    /** Billing periods are ISO-8601 ("P14D", "P1W", "P1M") — collapse
     *  to days for the trial-length string. */
    private fun periodDays(period: String): Int {
        var days = 0
        for (m in Regex("(\\d+)([DWMY])").findAll(period)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            days += when (m.groupValues[2]) {
                "D" -> n; "W" -> n * 7; "M" -> n * 30; "Y" -> n * 365
                else -> 0
            }
        }
        return days
    }

    /** Opens Play's purchase sheet for the one-time product. */
    fun purchase(activity: Activity) {
        pendingLaunch = java.lang.ref.WeakReference(activity)
        when {
            !connected -> connect()
            productDetails == null -> loadProduct()
            else -> maybeLaunch()
        }
    }

    private fun maybeLaunch() {
        val activity = pendingLaunch?.get() ?: return
        val pd = productDetails ?: return
        if (!connected) return
        val token = offerToken ?: run {
            // No offer configured on the subscription — the flow would
            // fail with a bare product. Wait for Console config.
            Log.w(TAG, "no subscription offer on $PRODUCT_ID — check Console")
            return
        }
        pendingLaunch = null
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(pd)
                    .setOfferToken(token)
                    .build()
            )
        ).build()
        client.launchBillingFlow(activity, params)
    }
}
