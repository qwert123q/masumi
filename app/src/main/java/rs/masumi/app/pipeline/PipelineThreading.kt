package rs.masumi.app.pipeline

import android.os.Process
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pipeline work must stay below interactive UI work in the Linux scheduler.
 * Merely starting it from a Service does not lower the priority of Java or
 * native worker threads.
 */
internal object PipelineThreading {
    fun thread(name: String, task: Runnable): Thread = Thread(
        {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        },
        name,
    )

    fun factory(namePrefix: String, numbered: Boolean = false): ThreadFactory {
        val sequence = AtomicInteger()
        return ThreadFactory { task ->
            val name = if (numbered) "$namePrefix-${sequence.incrementAndGet()}" else namePrefix
            thread(name, task)
        }
    }
}
