package ink.underflo.wristbrief.mobile

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val OPML_EXPORT_FILE_NAME = "wristbrief-subscriptions.opml"
internal const val OPML_EXPORT_MIME_TYPE = "text/x-opml"
internal val OPML_IMPORT_MIME_TYPES = arrayOf(
    "text/x-opml",
    "text/xml",
    "application/xml",
    "text/plain",
    "application/octet-stream",
)
private const val MAX_OPML_DOCUMENT_BYTES = 8_000_000

internal fun readBoundedUtf8(input: InputStream, maxBytes: Int = MAX_OPML_DOCUMENT_BYTES): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) throw OpmlFormatException("OPML file is too large")
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

internal fun readOpmlDocument(resolver: ContentResolver, uri: Uri): String =
    resolver.openInputStream(uri)?.use { input -> readBoundedUtf8(input) }
        ?: throw OpmlFormatException("Could not open selected OPML file")

internal fun writeOpmlDocument(resolver: ContentResolver, uri: Uri, content: String) {
    val stream = resolver.openOutputStream(uri, "wt")
        ?: throw IllegalStateException("Could not create OPML file")
    stream.writer(Charsets.UTF_8).buffered().use { writer -> writer.write(content) }
}

internal fun opmlImportStatus(result: OpmlImportResult.Success): String = buildString {
    append("Imported ${result.importedCount} feed")
    if (result.importedCount != 1) append('s')
    append('.')
    if (result.duplicateCount > 0) {
        append(" ${result.duplicateCount} already subscribed.")
    }
    if (result.failedValidationCount > 0) {
        append(" ${result.failedValidationCount} failed validation.")
    }
}

internal fun opmlExportStatus(feedCount: Int): String =
    "Exported $feedCount feed${if (feedCount == 1) "" else "s"} to OPML."
