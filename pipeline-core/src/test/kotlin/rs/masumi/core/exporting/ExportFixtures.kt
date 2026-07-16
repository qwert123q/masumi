package rs.masumi.core.exporting

object ExportFixtures {
    val dependencies = ExportDependencies(typesettingRunArtifactKey = "1".repeat(64))
    const val destinationUri = "content://provider/tree/folder"
    val destinationKey = ExportIdentity.destinationKey(destinationUri)
    val exportKey = ExportIdentity.exportKey(destinationKey, dependencies)

    fun job(): ExportJobRecord = ExportJobRecord(
        jobId = "export-job",
        projectId = "export-project",
        exportKey = exportKey,
        destinationUri = destinationUri,
        destinationKey = destinationKey,
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        dependencies = dependencies,
        pages = listOf(
            ExportJobPage(
                pageId = "4".repeat(64),
                pageOrder = 0,
                sourceSha256 = "5".repeat(64),
                typesettingPageArtifactKey = "6".repeat(64),
                outputName = "0001.png",
                source = ExportPageSource.FLATTENED,
            ),
        ),
    )
}
