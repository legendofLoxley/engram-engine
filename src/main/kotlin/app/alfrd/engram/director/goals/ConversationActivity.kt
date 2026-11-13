package app.alfrd.engram.director.goals

import java.util.concurrent.atomic.AtomicLong

/**
 * When someone last talked to the engine: the owner's Horizon tool, a chat turn, a debug conversation. The goal runner reads it to know the box is idle and, between
 * and during steps, to know a conversation has started and it must give way. It records a time and nothing else: no text, no identity.
 */
class ConversationActivity(private val clock: () -> Long = System::currentTimeMillis) {
    private val last = AtomicLong(0L)

    fun touch() { last.set(clock()) }

    /** Epoch milliseconds of the latest touch; 0 when there has been none since the process started. */
    fun lastAt(): Long = last.get()

    /** True when something touched after [startedAt], i.e. a conversation began while a step was running. */
    fun since(startedAt: Long): Boolean = last.get() > startedAt

    companion object {
        /** The process-wide instance the routes touch and the runner reads. */
        val shared = ConversationActivity()
    }
}
