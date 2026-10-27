package dev.marionette.voice

object QuietHours {
    fun contains(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    fun minutesUntilEnd(minute: Int, end: Int): Int = (end - minute + DAY_MINUTES) % DAY_MINUTES

    fun label(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    const val DAY_MINUTES = 24 * 60
}
