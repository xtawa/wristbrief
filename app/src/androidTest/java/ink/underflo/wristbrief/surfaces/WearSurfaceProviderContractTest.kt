package ink.underflo.wristbrief.surfaces

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import ink.underflo.wristbrief.complication.UnreadComplicationDataSourceService
import ink.underflo.wristbrief.tile.ContinueListeningTileService
import ink.underflo.wristbrief.tile.LatestUnreadTileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearSurfaceProviderContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packageManager: PackageManager = context.packageManager

    @Test
    fun tileProvidersAreDiscoverableAndProtectedByWearBindPermission() {
        val providers = packageManager.queryIntentServices(
            Intent(TILE_PROVIDER_ACTION).setPackage(context.packageName),
            PackageManager.MATCH_ALL,
        )
        val servicesByName = providers.associateBy { it.serviceInfo.name }

        val expectedProviders = listOf(
            LatestUnreadTileService::class.java,
            ContinueListeningTileService::class.java,
        )
        assertEquals(expectedProviders.map { it.name }.toSet(), servicesByName.keys)

        expectedProviders.forEach { providerClass ->
            val serviceInfo = servicesByName.getValue(providerClass.name).serviceInfo
            assertTrue(serviceInfo.exported)
            assertEquals(TILE_BIND_PERMISSION, serviceInfo.permission)
        }
    }

    @Test
    fun complicationProviderIsDiscoverableWithSupportedTypeMetadata() {
        val providers = packageManager.queryIntentServices(
            Intent(COMPLICATION_PROVIDER_ACTION).setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        )
        val provider = providers.single {
            it.serviceInfo.name == UnreadComplicationDataSourceService::class.java.name
        }.serviceInfo

        assertTrue(provider.exported)
        assertEquals(COMPLICATION_BIND_PERMISSION, provider.permission)
        assertEquals("SHORT_TEXT,LONG_TEXT", provider.metaData.getString(SUPPORTED_TYPES_METADATA))
        assertEquals(0, provider.metaData.getInt(UPDATE_PERIOD_METADATA))
    }

    @Test
    fun complicationPreviewBuildsOnlyDeclaredTextTypes() {
        val provider = UnreadComplicationDataSourceService()

        assertTrue(provider.getPreviewData(ComplicationType.SHORT_TEXT) is ShortTextComplicationData)
        assertTrue(provider.getPreviewData(ComplicationType.LONG_TEXT) is LongTextComplicationData)
        assertNull(provider.getPreviewData(ComplicationType.RANGED_VALUE))
    }

    @Test
    fun manifestComponentsResolveToTheInstalledWearPackage() {
        val tileComponent = ComponentName(context, LatestUnreadTileService::class.java)
        val complicationComponent = ComponentName(context, UnreadComplicationDataSourceService::class.java)

        assertEquals(
            LatestUnreadTileService::class.java.name,
            packageManager.getServiceInfo(tileComponent, PackageManager.GET_META_DATA).name,
        )
        assertEquals(
            UnreadComplicationDataSourceService::class.java.name,
            packageManager.getServiceInfo(complicationComponent, PackageManager.GET_META_DATA).name,
        )
    }

    private companion object {
        const val TILE_PROVIDER_ACTION = "androidx.wear.tiles.action.BIND_TILE_PROVIDER"
        const val TILE_BIND_PERMISSION = "com.google.android.wearable.permission.BIND_TILE_PROVIDER"
        const val COMPLICATION_PROVIDER_ACTION =
            "android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST"
        const val COMPLICATION_BIND_PERMISSION =
            "com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER"
        const val SUPPORTED_TYPES_METADATA =
            "android.support.wearable.complications.SUPPORTED_TYPES"
        const val UPDATE_PERIOD_METADATA =
            "android.support.wearable.complications.UPDATE_PERIOD_SECONDS"
    }
}
