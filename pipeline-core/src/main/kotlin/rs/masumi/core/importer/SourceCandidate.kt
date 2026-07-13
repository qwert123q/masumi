package rs.masumi.core.importer

import java.io.InputStream

interface SourceCandidate {
    val displayName: String
    val mediaType: String?
    val isDirectory: Boolean

    fun openStream(): InputStream
}
