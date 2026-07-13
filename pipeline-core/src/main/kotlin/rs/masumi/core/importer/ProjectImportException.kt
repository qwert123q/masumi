package rs.masumi.core.importer

import rs.masumi.core.model.ImportErrorCode

class ProjectImportException(
    val code: ImportErrorCode,
    val reportRelativePath: String,
    cause: Throwable? = null,
) : RuntimeException(code.safeMessage(), cause)

internal fun ImportErrorCode.safeMessage(): String = when (this) {
    ImportErrorCode.NO_SUPPORTED_PAGES -> "The selected folder contains no supported page images."
    ImportErrorCode.IMPORT_IO_FAILED -> "A source page could not be imported."
    ImportErrorCode.PROJECT_ALREADY_EXISTS -> "The project identifier is already in use."
}
