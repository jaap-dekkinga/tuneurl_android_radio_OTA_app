package com.tuneurlradio.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: String
) {
    NEWS(
        title = "News",
        selectedIcon = Icons.Filled.Newspaper,
        unselectedIcon = Icons.Outlined.Newspaper,
        route = "news"
    ),
    STATIONS(
        title = "Stations",
        selectedIcon = Icons.Filled.Radio,
        unselectedIcon = Icons.Outlined.Radio,
        route = "stations"
    ),
    SAVED(
        title = "Saved Turls",
        selectedIcon = Icons.Filled.Bookmark,
        unselectedIcon = Icons.Outlined.BookmarkBorder,
        route = "saved"
    ),
    TURLS(
        title = "Turls",
        selectedIcon = Icons.Filled.CalendarViewDay,
        unselectedIcon = Icons.Outlined.CalendarViewDay,
        route = "turls"
    ),
    SETTINGS(
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        route = "settings"
    )
}
