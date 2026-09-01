package rs.masumi.app.translation

internal class TranslationSettingsRequestCoordinator {
    internal data class Ticket(private val generation: Long)

    private data class ActiveRequest(
        val ticket: Ticket,
        val cancel: () -> Unit,
    )

    private var generation = 0L
    private var activeRequest: ActiveRequest? = null

    fun start(cancel: () -> Unit): Ticket {
        val previous: (() -> Unit)?
        val ticket: Ticket
        synchronized(this) {
            previous = activeRequest?.cancel
            ticket = Ticket(++generation)
            activeRequest = ActiveRequest(ticket, cancel)
        }
        previous?.invoke()
        return ticket
    }

    @Synchronized
    fun finish(ticket: Ticket): Boolean {
        if (activeRequest?.ticket != ticket) return false
        activeRequest = null
        return true
    }

    fun cancelActive() {
        val cancel: (() -> Unit)?
        synchronized(this) {
            generation += 1
            cancel = activeRequest?.cancel
            activeRequest = null
        }
        cancel?.invoke()
    }
}
