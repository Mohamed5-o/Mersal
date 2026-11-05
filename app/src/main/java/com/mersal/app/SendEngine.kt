package com.mersal.app

data class SendConfig(
    val phones: List<String>,
    val message: String,
    val count: Int,
    val intervalMs: Long
)

/** Shared state between the accessibility service and the UI. */
object SendEngine {
    const val MAX_COUNT = 100
    const val MAX_NUMBERS = 5
    const val MAX_QUICK = 25
    const val MAX_TOTAL = 100
    const val MIN_GAP_SECONDS = 1

    enum class State { IDLE, RUNNING, DONE, STOPPED, TIMEOUT, NO_WHATSAPP }

    @Volatile var state: State = State.IDLE
    @Volatile var sent: Int = 0
    @Volatile var total: Int = 0
    var listener: (() -> Unit)? = null

    fun update(s: State, sentNow: Int, totalNow: Int) {
        state = s
        sent = sentNow
        total = totalNow
        listener?.invoke()
    }

    fun stop() {
        val svc = MersalService.instance
        if (svc != null) svc.stopRun(State.STOPPED) else update(State.IDLE, 0, 0)
    }
}
