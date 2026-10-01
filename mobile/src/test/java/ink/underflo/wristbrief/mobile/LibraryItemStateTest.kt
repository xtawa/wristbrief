package ink.underflo.wristbrief.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryItemStateTest {
    @Test
    fun defaultBatchApiPreservesExistingAdaptersAndMissingState() {
        val adapter = object : ItemStateReaderAndWriter {
            override fun isRead(itemId: String) = itemId == "read"
            override fun isSaved(itemId: String) = itemId == "saved"
            override fun setRead(itemId: String, isRead: Boolean) = Unit
            override fun setSaved(itemId: String, isSaved: Boolean) = Unit
        }
        assertEquals(
            mapOf(
                "read" to MobileLibraryItemState(isRead = true),
                "saved" to MobileLibraryItemState(isSaved = true),
                "missing" to MobileLibraryItemState(),
            ),
            adapter.itemStates(listOf("read", "saved", "missing")),
        )
    }
}
