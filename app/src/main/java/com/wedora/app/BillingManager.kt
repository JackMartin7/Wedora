package com.wedora.app

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Play Billing for Premium subscriptions.
 *
 * Unlike a typical subscription integration, purchases are never verified
 * server-side against Google's Play Developer API — the Cloud Functions this
 * project does have (see functions/src/index.ts) don't cover billing. A signed-in
 * user's own Firestore write is what grants [UserProfile.FIELD_IS_PREMIUM], the
 * same as it always could be by hand via Console; firestore.rules has to trust
 * that write coming from a client holding a real (if unverified) purchase token
 * the same way it already trusts every other client write in this app. This is a
 * known, accepted gap — a rooted device or a modified client could still forge
 * entitlement — not something this class can close without a backend.
 *
 * Connection is held for the process's lifetime (like [PremiumStatus] and
 * [MatchNotificationWatcher]), not scoped to [PaymentSubscriptionActivity],
 * because [syncEntitlement] needs to run at cold start — before that screen has
 * ever been opened — to catch cancellations/expirations/refunds that happened
 * while the app wasn't running (see its own doc comment for why a live
 * `PurchasesUpdatedListener` alone can't do that).
 */
object BillingManager {

    private const val TAG = "WedoraBilling"

    const val PRODUCT_ID_MONTHLY = "wedora_premium_monthly"
    const val PRODUCT_ID_YEARLY = "wedora_premium_yearly"

    /** What a [launchBillingFlow] call ultimately resolves to. */
    sealed class PurchaseResult {
        object Success : PurchaseResult()
        object Cancelled : PurchaseResult()
        data class Failed(val message: String) : PurchaseResult()
    }

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var billingClient: BillingClient
    private var isConnected = false

    /** Populated by [queryProductDetails] once the connection is up. */
    private val productDetailsCache = mutableMapOf<String, com.android.billingclient.api.ProductDetails>()

    /**
     * The callback for whichever [launchBillingFlow] call is currently in
     * flight. `PurchasesUpdatedListener` is a single app-wide callback (there's
     * exactly one `BillingClient`), so only one purchase can be in flight at a
     * time — true here since it's only ever triggered from a single button tap
     * on [PaymentSubscriptionActivity].
     */
    private var purchaseCallback: ((PurchaseResult) -> Unit)? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        val callback = purchaseCallback
        purchaseCallback = null

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchase = purchases?.firstOrNull()
                if (purchase == null) {
                    callback?.invoke(PurchaseResult.Failed("No purchase returned"))
                } else {
                    handlePurchase(purchase, callback)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                callback?.invoke(PurchaseResult.Cancelled)
            else -> {
                Log.w(TAG, "Purchase flow failed: ${billingResult.responseCode} ${billingResult.debugMessage}")
                callback?.invoke(PurchaseResult.Failed(billingResult.debugMessage))
            }
        }
    }

    /**
     * Call once, from [WedoraApplication.onCreate]. Connects immediately (not
     * lazily on Payment screen open) so [syncEntitlement] can run at cold
     * start — see the class doc comment.
     */
    fun attach(context: Context) {
        billingClient = BillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesUpdatedListener)
            // Only subscriptions are sold here, but the Builder still
            // requires this call to be made at all — one-time products is
            // the only opt-in flag the current API exposes; subscriptions'
            // own pending-purchase support isn't gated by it.
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect()

        // A cold start with an already-persisted session has no AuthStateListener
        // transition to react to (Firebase delivers the initial state
        // synchronously to a listener added afterwards, so this still fires for
        // that case) — same reasoning as PremiumStatus/MatchNotificationWatcher's
        // own attach().
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            if (auth.realUid != null) syncEntitlement()
        }
    }

    private fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                isConnected = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                if (isConnected) {
                    queryProductDetails()
                    syncEntitlement()
                } else {
                    Log.w(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            // Play's own guidance is to retry, not to treat this as terminal —
            // a later launchBillingFlow/syncEntitlement call simply no-ops
            // (isConnected stays false) until the next successful reconnect.
            override fun onBillingServiceDisconnected() {
                isConnected = false
                connect()
            }
        })
    }

    private fun queryProductDetails() {
        val products = listOf(PRODUCT_ID_MONTHLY, PRODUCT_ID_YEARLY).map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetailsList.forEach { productDetailsCache[it.productId] = it }
            } else {
                Log.w(TAG, "queryProductDetails failed: ${billingResult.debugMessage}")
            }
        }
    }

    /** Cached price string for a plan, or null before [queryProductDetails] has resolved. */
    fun formattedPrice(productId: String): String? =
        productDetailsCache[productId]
            ?.subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.firstOrNull()
            ?.formattedPrice

    /**
     * Launches Google's purchase sheet for [productId] from [activity].
     * [onResult] fires exactly once, with the outcome — including
     * [PurchaseResult.Cancelled] if the user backs out of the payment sheet.
     */
    fun launchBillingFlow(activity: Activity, productId: String, onResult: (PurchaseResult) -> Unit) {
        if (!isConnected) {
            onResult(PurchaseResult.Failed("Not connected to Google Play"))
            return
        }
        val productDetails = productDetailsCache[productId]
        val offerToken = productDetails?.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (productDetails == null || offerToken == null) {
            onResult(PurchaseResult.Failed("Plan not available yet"))
            return
        }

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()

        purchaseCallback = onResult
        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    /**
     * Acknowledges [purchase] (required within 3 days or Play refunds it
     * automatically) and grants Premium. Shared by the live
     * `PurchasesUpdatedListener` path (a purchase just made) and
     * [syncEntitlement] (a purchase from a previous session that was never
     * acknowledged — e.g. the app was killed mid-flow).
     */
    private fun handlePurchase(purchase: Purchase, onResult: ((PurchaseResult) -> Unit)?) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                grantEntitlement(purchase, onResult)
                if (!purchase.isAcknowledged) {
                    val params = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(params) { billingResult ->
                        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            Log.w(TAG, "acknowledgePurchase failed: ${billingResult.debugMessage}")
                        }
                    }
                }
            }
            // Delayed payment methods (e.g. some bank transfers) — not yet
            // chargeable, so not yet granted. Play will redeliver this purchase
            // as PURCHASED via a later PurchasesUpdatedListener callback or
            // syncEntitlement call once it clears.
            Purchase.PurchaseState.PENDING ->
                onResult?.invoke(PurchaseResult.Failed("Payment pending — you'll be upgraded once it clears"))
            else -> onResult?.invoke(PurchaseResult.Failed("Purchase not completed"))
        }
    }

    private fun grantEntitlement(purchase: Purchase, onResult: ((PurchaseResult) -> Unit)?) {
        val uid = FirebaseAuth.getInstance().realUid
        if (uid == null) {
            onResult?.invoke(PurchaseResult.Failed("Not signed in"))
            return
        }
        val productId = purchase.products.firstOrNull()
        firestore.collection(UserProfile.COLLECTION).document(uid)
            .set(
                mapOf(
                    UserProfile.FIELD_IS_PREMIUM to true,
                    UserProfile.FIELD_PURCHASE_TOKEN to purchase.purchaseToken,
                    UserProfile.FIELD_PRODUCT_ID to productId
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener { onResult?.invoke(PurchaseResult.Success) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to record entitlement after purchase", e)
                onResult?.invoke(PurchaseResult.Failed(e.message ?: "Couldn't save your purchase"))
            }
    }

    /**
     * Reconciles Firestore's `isPremium` against what Google Play actually
     * says is currently active — the only mechanism in this app (there is no
     * backend to run Play's Real-time Developer Notifications against) that
     * ever turns `isPremium` back off after a cancellation, expiration, or
     * refund. Called on every cold start and sign-in ([attach]) and every
     * successful billing connection ([connect]), which is as close to "on
     * every app open" as a client-only integration can get.
     *
     * [onFoundActive], if given, reports whether an active subscription was
     * found — used by "Restore Purchase" to tell the user something actually
     * happened. The app-launch call sites (no callback) don't need that;
     * silently reconciling in the background is the whole point there.
     */
    fun syncEntitlement(onFoundActive: ((Boolean) -> Unit)? = null) {
        if (!isConnected) {
            onFoundActive?.invoke(false)
            return
        }
        val uid = FirebaseAuth.getInstance().realUid
        if (uid == null) {
            onFoundActive?.invoke(false)
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                onFoundActive?.invoke(false)
                return@queryPurchasesAsync
            }

            val activePurchase = purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            if (activePurchase != null) {
                handlePurchase(activePurchase) { result ->
                    onFoundActive?.invoke(result is PurchaseResult.Success)
                }
            } else {
                revokeEntitlementIfPurchaseBased(uid)
                onFoundActive?.invoke(false)
            }
        }
    }

    /**
     * Only revokes Premium that this class itself granted (a stored
     * [UserProfile.FIELD_PURCHASE_TOKEN]) — never a hand-grant made via
     * Firebase Console, which never sets that field. Without this check, a
     * Console-granted Premium account would lose it the moment this ran,
     * since Play naturally has no matching purchase for a grant it never
     * saw.
     */
    private fun revokeEntitlementIfPurchaseBased(uid: String) {
        val doc = firestore.collection(UserProfile.COLLECTION).document(uid)
        doc.get()
            .addOnSuccessListener { snapshot ->
                val isPremium = snapshot.getBoolean(UserProfile.FIELD_IS_PREMIUM) ?: false
                val grantedByPurchase = snapshot.getString(UserProfile.FIELD_PURCHASE_TOKEN) != null
                if (isPremium && grantedByPurchase) {
                    doc.set(mapOf(UserProfile.FIELD_IS_PREMIUM to false), SetOptions.merge())
                }
            }
    }
}
