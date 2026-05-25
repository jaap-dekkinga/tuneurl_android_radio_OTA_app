package com.tuneurlradio.app.navigation

/** Centralized nav arg names, so the route template and SavedStateHandle agree. */
object NavArgs {
    const val LOCAL_ID = "localId"
    const val SOURCE = "source"
}

/**
 * Which list the user came from when opening the engagement detail screen.
 * Determines whether Delete is available and whether the back-end lookup
 * goes to `saved_engagements` or `history_engagements`.
 */
enum class EngagementSource(val route: String) {
    SAVED("saved"),
    HISTORY("history");

    companion object {
        fun fromRoute(value: String): EngagementSource =
            entries.firstOrNull { it.route == value } ?: SAVED
    }
}
