package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun membershipPresentationUsesPlayPriceAndNeverCarriesPurchaseToken() {
        val state = BillingState.Ready(
            products = listOf(BillingProduct("pro_monthly", "WristBrief Pro", "Managed AI", "$4.99", "offer-token")),
            purchases = listOf(BillingPurchase(listOf("pro_monthly"), "secret-purchase-token", pending = false, acknowledged = true)),
        )

        val presentation = state.toMembershipPresentation() as MembershipPresentation.Ready

        assertEquals("$4.99", presentation.products.single().formattedPrice)
        assertTrue(presentation.products.single().alreadyPurchased)
        assertEquals(1, presentation.restoredPurchaseCount)
        assertFalse(presentation.toString().contains("secret-purchase-token"))
        assertFalse(presentation.toString().contains("offer-token"))
    }

    @Test fun membershipPresentationKeepsPendingPurchasesSeparateFromOwnedPlans() {
        val state = BillingState.Ready(
            products = listOf(BillingProduct("pro_monthly", "WristBrief Pro", "Managed AI", "$4.99", "offer-token")),
            purchases = listOf(BillingPurchase(listOf("pro_monthly"), "pending-token", pending = true, acknowledged = false)),
        )

        val presentation = state.toMembershipPresentation() as MembershipPresentation.Ready

        assertFalse(presentation.products.single().alreadyPurchased)
        assertEquals(0, presentation.restoredPurchaseCount)
        assertEquals(1, presentation.pendingPurchaseCount)
    }

    @Test fun membershipPresentationPreservesExplicitLoadingUnavailableAndErrorStates() {
        assertTrue(BillingState.Loading.toMembershipPresentation() is MembershipPresentation.Loading)
        assertEquals("No Play", (BillingState.Unavailable("No Play").toMembershipPresentation() as MembershipPresentation.Unavailable).message)
        assertEquals("Retry later", (BillingState.Error("Retry later").toMembershipPresentation() as MembershipPresentation.Error).message)
    }
}
