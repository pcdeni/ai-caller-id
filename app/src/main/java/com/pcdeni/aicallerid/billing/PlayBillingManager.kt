package com.pcdeni.aicallerid.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.pcdeni.aicallerid.data.Prefs

/**
 * One-time "unlock" purchase via Google Play Billing. The granted entitlement is cached in
 * [Prefs.appUnlockedStub] — the flag the screening and lookup gates already read — and Play
 * remains the source of truth via the restore query on every connection, so a refunded or
 * revoked purchase re-locks the app the next time it starts.
 */
class PlayBillingManager(
    context: Context,
    private val prefs: Prefs,
) : PurchasesUpdatedListener {

    // Billing callbacks arrive on a background thread; UI observers must post to main.
    var onStateChanged: (() -> Unit)? = null

    var formattedPrice: String? = null
        private set

    private var productDetails: ProductDetails? = null

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    fun connect() {
        if (client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    restorePurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${result.responseCode}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // Reconnection happens lazily on the next purchase or restore attempt.
            }
        })
    }

    fun launchPurchase(activity: Activity) {
        val details = productDetails
        if (details == null || !client.isReady) {
            connect()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach(::handlePurchase)
        }
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = detailsResult.productDetailsList.firstOrNull()
                formattedPrice = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
                onStateChanged?.invoke()
            } else {
                Log.e(TAG, "Product query failed: ${result.responseCode}")
            }
        }
    }

    private fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val owned = purchases.any {
                it.products.contains(PRODUCT_ID) &&
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            if (owned != prefs.appUnlockedStub) {
                prefs.appUnlockedStub = owned
                onStateChanged?.invoke()
            }
            purchases.forEach(::handlePurchase)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.products.contains(PRODUCT_ID)) return
        if (!prefs.appUnlockedStub) {
            prefs.appUnlockedStub = true
            onStateChanged?.invoke()
        }
        if (!purchase.isAcknowledged) {
            // Unacknowledged purchases are auto-refunded by Play after three days.
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(params) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.e(TAG, "Acknowledge failed: ${result.responseCode}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "PlayBillingManager"
        const val PRODUCT_ID = "unlock_pro"
    }
}
