package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingTest {
    @Test fun configuredProductIds_areTrimmedDeduplicatedAndAllowEmptyBuilds() {
        assertEquals(emptyList<String>(), configuredBillingProductIds(""))
        assertEquals(listOf("pro_monthly", "pro_yearly"), configuredBillingProductIds(" pro_monthly,pro_yearly,pro_monthly "))
    }

    @Test fun fakeBillingPublishesPlayDerivedProductPriceAndRestoreState() {
        val product = BillingProduct("pro_monthly", "WristBrief Pro", "Managed AI and membership", "$4.99", "offer-token")
        val purchase = BillingPurchase(listOf("pro_monthly"), "purchase-token", pending = false, acknowledged = true)
        val repository = FakeBillingRepository(listOf(product), listOf(purchase))
        var state: BillingState? = null

        repository.connect { state = it }

        val ready = state as BillingState.Ready
        assertEquals("$4.99", ready.products.single().formattedPrice)
        assertEquals("purchase-token", ready.purchases.single().purchaseToken)
        assertTrue(ready.purchases.single().acknowledged)
    }

    @Test fun fakeBillingCanRepresentUnavailablePlayEnvironmentWithoutProducts() {
        val repository = FakeBillingRepository(available = false)
        var state: BillingState? = null
        repository.connect { state = it }
        assertTrue(state is BillingState.Unavailable)
    }
}
