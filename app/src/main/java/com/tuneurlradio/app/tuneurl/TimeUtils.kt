package com.tuneurlradio.app.tuneurl

import java.util.Calendar

object TimeUtils {

    fun getCurrentTimeAsFormattedString(): String = formatAsIso(System.currentTimeMillis())

    /**
     * Format a wall-clock millis value in the wire format the Interests endpoint
     * expects: `YYYY-MM-ddTHHmm` in the device's local time zone (matching the
     * existing on-recognition reporting). Used for `acted` / `shared` reports
     * fired from saved/history items so the report carries the original
     * heard-at timestamp rather than the moment the user opened the screen.
     */
    fun formatAsIso(millis: Long): String {
        return try {
            val calendar = Calendar.getInstance().apply { timeInMillis = millis }
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            "$year-${pad(month)}-${pad(day)}T${pad(hour)}${pad(minute)}"
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun pad(value: Int): String {
        return if (value >= 10) value.toString() else "0$value"
    }
}
