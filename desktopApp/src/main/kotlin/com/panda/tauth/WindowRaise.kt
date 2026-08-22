package com.panda.tauth

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// The show requests a primary instance counts, turned into raises of its window. A request arrives
// over loopback from any process on the machine, so a raise is not the user coming back to it.
class WindowRaise internal constructor(private val monitor: InputMonitor) {
    constructor() : this(AwtInputMonitor)

    // Every request past the count a primary starts at is a raise, and the raise stands until input
    // reports the user, so a request that landed before this collection began is raised too.
    suspend fun raiseOnRequest(requests: StateFlow<Long>, onRaise: () -> Unit, onShownBy: (ShowSource) -> Unit): Unit =
        coroutineScope {
            // Named, because the launch below sits inside a suspending collector and would otherwise
            // read this receiver implicitly, where it looks like the collector's own scope.
            val raises = this
            var raised = NO_SHOW_REQUESTS
            var arrival: Job? = null
            requests.collect { count ->
                if (count > raised) {
                    raised = count
                    // Both run before this suspends, so nothing reads a window standing on the screen
                    // with the user named as what put it there.
                    onRaise()
                    onShownBy(ShowSource.SHOW_REQUEST)
                    // The arrival that ends this raise is input that follows it, not input a wait an
                    // earlier raise left standing would take.
                    arrival?.cancelAndJoin()
                    arrival = raises.launch {
                        awaitInput()
                        onShownBy(ShowSource.USER)
                    }
                }
            }
        }

    // The input the monitor reports is what says someone is at the machine. The raise asks for the
    // focus itself, so focus gained would report the raise as the arrival that ends it.
    private suspend fun awaitInput() {
        val input = Channel<Unit>(Channel.CONFLATED)
        val subscription = monitor.listen { input.trySend(Unit) }
        try {
            input.receive()
        } finally {
            subscription.detach()
            input.close()
        }
    }
}
