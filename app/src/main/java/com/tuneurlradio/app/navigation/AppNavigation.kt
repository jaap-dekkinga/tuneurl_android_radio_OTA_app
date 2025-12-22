package com.tuneurlradio.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.tuneurlradio.app.ui.screens.news.NewsScreen
import com.tuneurlradio.app.ui.screens.saved.SavedEngagementsScreen
import com.tuneurlradio.app.ui.screens.settings.ParsingSettingsScreen
import com.tuneurlradio.app.ui.screens.settings.SettingsScreen
import com.tuneurlradio.app.ui.screens.stations.StationsScreen
import com.tuneurlradio.app.ui.screens.turls.TurlsHistoryScreen

object Routes {
    const val PARSING_SETTINGS = "parsing_settings"
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
            SavedEngagementsScreen()
        }
        composable(AppTab.TURLS.route) {
            TurlsHistoryScreen()
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
    }
}
