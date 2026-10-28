package com.bluedragonmc.komodo

import java.util.concurrent.TimeUnit

/**
 * Tracks the proxy shutdown countdown and reports milestone events.
 *
 * The owner of the [ShutdownCountdown] instance must call [tick] once per second.
 */
class ShutdownCountdown(
    private val totalSeconds: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onMilestone: (remainingSeconds: Long) -> Unit = {},
    private val onComplete: () -> Unit = {},
) {

    private var startNanos = 0L
    private var lastRemaining = totalSeconds
    private var started = false
    private var completed = false

    init {
        require(totalSeconds > 0) { "The shutdown countdown must be longer than zero seconds." }
    }

    fun start() {
        check(!started) { "The shutdown countdown has already been started." }
        started = true
        startNanos = nanoTime()
        lastRemaining = totalSeconds
        onMilestone(totalSeconds)
    }

    fun tick() {
        check(started) { "The shutdown countdown has not been started." }
        if (completed) return

        val remaining = remainingSeconds()
        if (remaining < lastRemaining) {
            MILESTONE_SECONDS.lastOrNull { it in remaining..<lastRemaining }
                ?.let(onMilestone)
            lastRemaining = remaining
        }

        if (remaining <= 0) {
            completed = true
            onComplete()
        }
    }

    fun remainingSeconds(): Long {
        val nanosLeft = deadlineNanos() - nanoTime()
        return if (nanosLeft <= 0) 0 else (nanosLeft + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND
    }

    private fun deadlineNanos() = startNanos + TimeUnit.SECONDS.toNanos(totalSeconds)

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        private val MILESTONE_SECONDS = listOf(
            // Send a chat message to connected players whenever the countdown
            // crosses one of these numbers of seconds remaining.
            2 * 60 * 60L,
            60 * 60L,
            30 * 60L,
            20 * 60L,
            10 * 60L,
            5 * 60L,
            4 * 60L,
            3 * 60L,
            2 * 60L,
            60L,
            30L,
            20L,
            10L,
            5L,
            4L,
            3L,
            2L,
            1L,
        )

        fun formatChat(seconds: Long): String {
            require(seconds >= 0) { "Cannot format a negative duration." }
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val remainingSeconds = seconds % 60

            return when {
                hours > 0 && minutes == 0L && remainingSeconds == 0L -> plural(hours, "hour")
                hours > 0 -> "${plural(hours, "hour")} ${plural(minutes, "minute")}"
                minutes > 0 && remainingSeconds == 0L -> plural(minutes, "minute")
                minutes > 0 -> "${plural(minutes, "minute")} ${plural(remainingSeconds, "second")}"
                else -> plural(remainingSeconds, "second")
            }
        }

        private fun plural(amount: Long, unit: String) = if (amount == 1L) "1 $unit" else "$amount ${unit}s"
    }
}
