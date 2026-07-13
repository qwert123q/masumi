package rs.masumi.core.importer

data class SelectedSource(
    val source: SourceCandidate,
    val mediaType: PageMediaType,
)

data class SourceSelection(
    val accepted: List<SelectedSource>,
    val skippedCount: Int,
)

object SourceSelector {
    fun select(sources: List<SourceCandidate>): SourceSelection {
        val accepted = sources.mapNotNull { source ->
            if (source.isDirectory || source.displayName.startsWith('.')) {
                return@mapNotNull null
            }

            PageMediaType.detect(source.displayName, source.mediaType)
                ?.let { mediaType -> SelectedSource(source, mediaType) }
        }.sortedWith { left, right ->
            NaturalFileNameComparator.compare(left.source.displayName, right.source.displayName)
        }

        return SourceSelection(
            accepted = accepted,
            skippedCount = sources.size - accepted.size,
        )
    }
}
