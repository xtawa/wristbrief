package ink.underflo.wristbrief.mobile

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlDocumentsTest {
    @Test
    fun boundedReader_preservesUtf8Content() {
        val text = "订阅 ☕ RSS"
        assertEquals(text, readBoundedUtf8(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), 128))
    }

    @Test(expected = OpmlFormatException::class)
    fun boundedReader_rejectsOversizedDocumentBeforeUnboundedGrowth() {
        readBoundedUtf8(ByteArrayInputStream(ByteArray(33) { 'a'.code.toByte() }), 32)
    }

    @Test
    fun importStatus_reportsImportedDuplicateAndFailedCounts() {
        val status = opmlImportStatus(
            OpmlImportResult.Success(
                feeds = emptyList(),
                importedCount = 2,
                duplicateCount = 1,
                failedValidationCount = 3,
            ),
        )

        assertEquals("Imported 2 feeds. 1 already subscribed. 3 failed validation.", status)
    }

    @Test
    fun documentContract_usesOpmlNameAndCommonMimeTypes() {
        assertEquals("wristbrief-subscriptions.opml", OPML_EXPORT_FILE_NAME)
        assertEquals("text/x-opml", OPML_EXPORT_MIME_TYPE)
        assertTrue(OPML_IMPORT_MIME_TYPES.contains("application/xml"))
        assertTrue(OPML_IMPORT_MIME_TYPES.contains("application/octet-stream"))
    }
}
