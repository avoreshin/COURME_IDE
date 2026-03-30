package changelogai.core.llm.debug

import java.util.concurrent.atomic.AtomicInteger

object LLMTraceStore {

    private const val MAX_TRACES = 50
    private val counter = AtomicInteger(0)
    private val traces = ArrayDeque<LLMCallTrace>(MAX_TRACES)

    fun add(trace: LLMCallTrace) = synchronized(traces) {
        if (traces.size >= MAX_TRACES) traces.removeFirst()
        traces.addLast(trace)
    }

    fun nextId() = counter.incrementAndGet()

    fun getAll(): List<LLMCallTrace> = synchronized(traces) { traces.toList() }

    fun clear() = synchronized(traces) { traces.clear() }
}
