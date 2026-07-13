package rs.masumi.app

import android.content.ContentResolver
import android.net.Uri
import rs.masumi.core.importer.SourceCandidate
import java.io.InputStream

data class AndroidDocumentSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val displayName: String,
    override val mediaType: String?,
    override val isDirectory: Boolean,
) : SourceCandidate {
    override fun openStream(): InputStream =
        requireNotNull(resolver.openInputStream(uri)) { "Document stream unavailable" }
}
