package ink.underflo.wristbrief.mobile

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

sealed interface BillingState {
    data object Loading : BillingState
    data class Unavailable(val message: String) : BillingState
    data class Ready(
        val products: List<BillingProduct>,
        val purchases: List<BillingPurchase>,
    ) : BillingState
    data class Error(val message: String) : BillingState
}

data class BillingProduct(
    val productId: String,
    val title: String,
    val description: String,
    val formattedPrice: String,
    val offerToken: String,
)

data class BillingPurchase(
    val productIds: List<String>,
    val purchaseToken: String,
    val pending: Boolean,
    val acknowledged: Boolean,
)

sealed interface MembershipPresentation {
    data object Loading : MembershipPresentation
    data class Unavailable(val message: String) : MembershipPresentation
    data class Error(val message: String) : MembershipPresentation
    data class Ready(
        val products: List<MembershipProductPresentation>,
        val restoredPurchaseCount: Int,
        val pendingPurchaseCount: Int,
    ) : MembershipPresentation
}

data class MembershipProductPresentation(
    val productId: String,
    val title: String,
    val description: String,
    val formattedPrice: String,
    val alreadyPurchased: Boolean,
)

fun BillingState.toMembershipPresentation(): MembershipPresentation = when (this) {
    BillingState.Loading -> MembershipPresentation.Loading
    is BillingState.Unavailable -> MembershipPresentation.Unavailable(message)
    is BillingState.Error -> MembershipPresentation.Error(message)
    is BillingState.Ready -> {
        val completedProductIds = purchases
            .asSequence()
            .filterNot(BillingPurchase::pending)
            .flatMap { it.productIds.asSequence() }
            .toSet()
        MembershipPresentation.Ready(
            products = products.map { product ->
                MembershipProductPresentation(
                    productId = product.productId,
                    title = product.title,
                    description = product.description,
                    formattedPrice = product.formattedPrice,
                    alreadyPurchased = product.productId in completedProductIds,
                )
            },
            restoredPurchaseCount = purchases.count { !it.pending },
            pendingPurchaseCount = purchases.count(BillingPurchase::pending),
        )
    }
}

interface BillingRepository {
    fun connect(onState: (BillingState) -> Unit)
    fun refresh()
    fun launchPurchase(activity: Activity, productId: String): BillingResult
    fun acknowledgePurchase(purchaseToken: String, onComplete: (Boolean) -> Unit)
    fun close()
}

class FakeBillingRepository(
    initialProducts: List<BillingProduct> = emptyList(),
    initialPurchases: List<BillingPurchase> = emptyList(),
    private val available: Boolean = true,
) : BillingRepository {
    private var products = initialProducts
    private var purchases = initialPurchases
    private var listener: ((BillingState) -> Unit)? = null

    override fun connect(onState: (BillingState) -> Unit) {
        listener = onState
        publish()
    }

    override fun refresh() = publish()

    override fun launchPurchase(activity: Activity, productId: String): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(if (products.any { it.productId == productId }) BillingResponseCode.OK else BillingResponseCode.ITEM_UNAVAILABLE)
            .setDebugMessage(if (products.any { it.productId == productId }) "Fake purchase flow" else "Unknown fake product")
            .build()

    override fun acknowledgePurchase(purchaseToken: String, onComplete: (Boolean) -> Unit) {
        purchases = purchases.map {
            if (it.purchaseToken == purchaseToken) it.copy(acknowledged = true) else it
        }
        publish()
        onComplete(true)
    }

    override fun close() {
        listener = null
    }

    fun replacePurchases(value: List<BillingPurchase>) {
        purchases = value
        publish()
    }

    private fun publish() {
        listener?.invoke(
            if (available) BillingState.Ready(products, purchases)
            else BillingState.Unavailable("Google Play Billing is unavailable on this device."),
        )
    }
}

class GooglePlayBillingRepository(
    context: Context,
    productIds: Collection<String>,
) : BillingRepository {
    private val subscriptionProductIds = productIds.map(String::trim).filter(String::isNotEmpty).distinct()
    private var stateListener: ((BillingState) -> Unit)? = null
    private val productDetailsById = mutableMapOf<String, ProductDetails>()

    private val billingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, _ ->
            if (result.responseCode == BillingResponseCode.OK) refresh()
            else stateListener?.invoke(BillingState.Error(result.safeMessage("Purchase update failed")))
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override fun connect(onState: (BillingState) -> Unit) {
        stateListener = onState
        if (subscriptionProductIds.isEmpty()) {
            onState(BillingState.Unavailable("No Play subscription products are configured for this build."))
            return
        }
        onState(BillingState.Loading)
        if (billingClient.isReady) {
            refresh()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) refresh()
                else onState(BillingState.Unavailable(result.safeMessage("Google Play Billing unavailable")))
            }

            override fun onBillingServiceDisconnected() {
                // PBL 8 auto-service-reconnection handles the next API call.
            }
        })
    }

    override fun refresh() {
        val listener = stateListener ?: return
        if (subscriptionProductIds.isEmpty()) {
            listener(BillingState.Unavailable("No Play subscription products are configured for this build."))
            return
        }
        listener(BillingState.Loading)
        val productParams = QueryProductDetailsParams.newBuilder()
            .setProductList(subscriptionProductIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(ProductType.SUBS)
                    .build()
            })
            .build()

        billingClient.queryProductDetailsAsync(productParams) { result, queryResult ->
            if (result.responseCode != BillingResponseCode.OK) {
                listener(BillingState.Error(result.safeMessage("Could not load Play products")))
                return@queryProductDetailsAsync
            }
            productDetailsById.clear()
            queryResult.productDetailsList.forEach { productDetailsById[it.productId] = it }
            val products = queryResult.productDetailsList.mapNotNull(::mapProduct)

            val purchaseParams = QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()
            billingClient.queryPurchasesAsync(purchaseParams) { purchaseResult, purchases ->
                if (purchaseResult.responseCode != BillingResponseCode.OK) {
                    listener(BillingState.Error(purchaseResult.safeMessage("Could not restore Play purchases")))
                    return@queryPurchasesAsync
                }
                listener(
                    BillingState.Ready(
                        products = products,
                        purchases = purchases.map { purchase ->
                            BillingPurchase(
                                productIds = purchase.products,
                                purchaseToken = purchase.purchaseToken,
                                pending = purchase.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PENDING,
                                acknowledged = purchase.isAcknowledged,
                            )
                        },
                    ),
                )
            }
        }
    }

    override fun launchPurchase(activity: Activity, productId: String): BillingResult {
        val details = productDetailsById[productId]
            ?: return BillingResult.newBuilder().setResponseCode(BillingResponseCode.ITEM_UNAVAILABLE).setDebugMessage("Product not loaded").build()
        val offer = details.subscriptionOfferDetails?.firstOrNull()
            ?: return BillingResult.newBuilder().setResponseCode(BillingResponseCode.ITEM_UNAVAILABLE).setDebugMessage("No eligible subscription offer").build()
        val detailParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
            .build()
        return billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(detailParams)).build(),
        )
    }

    override fun acknowledgePurchase(purchaseToken: String, onComplete: (Boolean) -> Unit) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { billingResult ->
            val ok = billingResult.responseCode == BillingResponseCode.OK
            if (ok) refresh()
            onComplete(ok)
        }
    }

    override fun close() {
        stateListener = null
        billingClient.endConnection()
    }

    private fun mapProduct(details: ProductDetails): BillingProduct? {
        val offer = details.subscriptionOfferDetails?.firstOrNull() ?: return null
        val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: return null
        return BillingProduct(
            productId = details.productId,
            title = details.title,
            description = details.description,
            formattedPrice = price,
            offerToken = offer.offerToken,
        )
    }
}

fun configuredBillingProductIds(raw: String): List<String> =
    raw.split(',').map(String::trim).filter(String::isNotEmpty).distinct()

private fun BillingResult.safeMessage(fallback: String): String =
    debugMessage.takeIf { it.isNotBlank() }?.let { "$fallback: $it" } ?: fallback
