package com.tuneurlradio.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tuneurlradio.app.ui.screens.engagement.EngagementDetailScreen
import com.tuneurlradio.app.ui.screens.news.NewsScreen
import com.tuneurlradio.app.ui.screens.saved.SavedEngagementsScreen
import com.tuneurlradio.app.ui.screens.settings.ParsingSettingsScreen
import com.tuneurlradio.app.ui.screens.settings.SettingsScreen
import com.tuneurlradio.app.ui.screens.stations.StationsScreen
import com.tuneurlradio.app.ui.screens.turls.TurlsHistoryScreen

object Routes {
    const val PARSING_SETTINGS = "parsing_settings"

    /** Route template: `engagement_detail/{source}/{localId}`. */
    const val ENGAGEMENT_DETAIL_TEMPLATE =
        "engagement_detail/{${NavArgs.SOURCE}}/{${NavArgs.LOCAL_ID}}"

    /** Build a concrete navigation URL for the detail screen. */
    fun engagementDetail(source: EngagementSource, localId: Long): String =
        "engagement_detail/${source.route}/$localId"
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onStationClick: (Int) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = AppTab.NEWS.route,
        modifier = modifier
    ) {
        composable(AppTab.NEWS.route) {
            NewsScreen()
        }
        composable(AppTab.STATIONS.route) {
            StationsScreen(
                onStationClick = onStationClick
            )
        }
        composable(AppTab.SAVED.route) {
            SavedEngagementsScreen(
                onItemOpen = { localId ->
                    navController.navigate(
                        Routes.engagementDetail(EngagementSource.SAVED, localId)
                    )
                }
            )
        }
        composable(AppTab.TURLS.route) {
            TurlsHistoryScreen(
                onItemOpen = { localId ->
                    navController.navigate(
                        Routes.engagementDetail(EngagementSource.HISTORY, localId)
                    )
                }
            )
        }
        composable(AppTab.SETTINGS.route) {
            SettingsScreen(
                onNavigateToParsingSettings = {
                    navController.navigate(Routes.PARSING_SETTINGS)
                }
            )
        }
        composable(Routes.PARSING_SETTINGS) {
            ParsingSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.ENGAGEMENT_DETAIL_TEMPLATE,
            arguments = listOf(
                navArgument(NavArgs.SOURCE) { type = NavType.StringType },
                navArgument(NavArgs.LOCAL_ID) { type = NavType.LongType }
            )
        ) {
            EngagementDetailScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
