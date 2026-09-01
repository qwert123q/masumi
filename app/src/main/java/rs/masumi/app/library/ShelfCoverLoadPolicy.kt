package rs.masumi.app.library

/** Pure ordering policy so the shelf can resolve its visible cover cohort first. */
internal object ShelfCoverLoadPolicy {
    data class Plan(
        val initial: List<String>,
        val deferred: List<String>,
    )

    fun split(projectIds: List<String>, initialViewportCount: Int): Plan {
        val visibleCount = initialViewportCount.coerceIn(0, projectIds.size)
        return Plan(
            initial = projectIds.take(visibleCount),
            deferred = if (visibleCount == 0) {
                emptyList()
            } else {
                projectIds.drop(visibleCount).take(visibleCount)
            },
        )
    }

    fun initialViewportCount(viewportHeight: Int, cardHeight: Int, itemCount: Int): Int {
        if (itemCount <= 0) return 0
        if (viewportHeight <= 0 || cardHeight <= 0) return 1
        return ((viewportHeight + cardHeight - 1) / cardHeight).coerceIn(1, itemCount)
    }

    fun shouldUseInitialCohort(
        shelfAlreadyRevealed: Boolean,
        missingCoverCount: Int,
    ): Boolean {
        require(missingCoverCount >= 0)
        return !shelfAlreadyRevealed && missingCoverCount > 0
    }

    fun shouldScheduleViewport(shelfAlreadyRevealed: Boolean): Boolean = shelfAlreadyRevealed

    fun remainingRevealDelay(
        startedAtMillis: Long,
        deadlineMillis: Long,
        nowMillis: Long,
    ): Long = (startedAtMillis + deadlineMillis - nowMillis).coerceAtLeast(0L)
}

internal enum class ShelfCoverCompletionAction {
    APPLY_AND_RESOLVE,
    KEEP_RETRYABLE,
    DISCARD,
}

internal data class ShelfCoverRequest(
    val coverKey: String,
    val renderGeneration: Long,
)

/** Keeps deduplication local to one render generation so stale work cannot block a fresh shelf. */
internal class ShelfCoverRequestTracker {
    private val active = mutableSetOf<ShelfCoverRequest>()

    fun tryStart(coverKey: String, renderGeneration: Long): ShelfCoverRequest? {
        val request = ShelfCoverRequest(coverKey, renderGeneration)
        return request.takeIf(active::add)
    }

    fun complete(
        request: ShelfCoverRequest,
        currentRenderGeneration: Long,
        stillDesired: Boolean,
        bitmapAvailable: Boolean,
    ): ShelfCoverCompletionAction {
        if (!active.remove(request)) return ShelfCoverCompletionAction.DISCARD
        if (request.renderGeneration != currentRenderGeneration || !stillDesired) {
            return ShelfCoverCompletionAction.DISCARD
        }
        return if (bitmapAvailable) {
            ShelfCoverCompletionAction.APPLY_AND_RESOLVE
        } else {
            ShelfCoverCompletionAction.KEEP_RETRYABLE
        }
    }

    fun clear() {
        active.clear()
    }
}
