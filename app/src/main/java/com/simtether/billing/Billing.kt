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
 * Product `simtether_pro` (base plan monthly + 7-day trial offer) is
 * created in Play Console — nothing works until it exists and the app
 * is uploaded to a track. Entitlement lives in Play's on-device cache,
 * so checks work offline; `entitled` stays null until resolved so the
 * UI can show a spinner instead of flashing the paywall.
 *
 * Debug builds bypass the gate — there is no Play product locally.
 */
object Billing {
    const val PRODUCT_ID = "simtether_pro"
    private const val TAG = "SimTether.Billing"

    private lateinit var client: BillingClient

    private val _entitled = MutableStateFlow<Boolean?>(null)
    val entitled: StateFlow<Boolean?> = _entitled

    /** Localized price label from Play ("₺150.00"), null until loaded. */
    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price

    private var productDetails: ProductDetails? = null
    private var connected = false
    private var retries = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** A subscribe tap that arrived before billing was ready — fires
     *  once connection + product details are both in place. */
    private var pendingLaunch: java.lang.ref.WeakReference<Activity>? = null

    fun init(app: Application) {
        if (BuildConfig.DEBUG) { _entitled.value = true; return }
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
            // Last phase = the recurring price (earlier phases are the
            // trial/discount periods).
            _price.value = pickOffer(pd)?.pricingPhases?.pricingPhaseList
                ?.lastOrNull()?.formattedPrice
            maybeLaunch()
        }
    }

    /** Prefer the offer with a free phase (the trial); else the first. */
    private fun pickOffer(pd: ProductDetails) =
        pd.subscriptionOfferDetails?.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        } ?: pd.subscriptionOfferDetails?.firstOrNull()

    /** Opens Play's subscription sheet; the trial auto-applies if eligible. */
    fun subscribe(activity: Activity) {
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
        val offerToken = pickOffer(pd)?.offerToken
            ?: run { Log.w(TAG, "no subscription offers"); pendingLaunch = null; return }
        pendingLaunch = null
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(pd)
                    .setOfferToken(offerToken)
                    .build()
            )
        ).build()
        client.launchBillingFlow(activity, params)
    }
}
