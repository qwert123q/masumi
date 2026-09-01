package rs.masumi.app.library

/** Keeps the durable anchor authoritative until asynchronous page geometry is bound. */
internal object ReaderLocationLifecycle {
    fun locationForSave(
        pagesBound: Boolean,
        readerLaidOut: Boolean,
        pendingRestoreApplied: Boolean,
        pending: ReadingLocation,
        captureBoundView: () -> ReadingLocation,
    ): ReadingLocation = if (pagesBound && readerLaidOut && pendingRestoreApplied) {
        captureBoundView()
    } else {
        pending
    }
}
