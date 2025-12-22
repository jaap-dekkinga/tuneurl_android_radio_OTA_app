package com.tuneurlradio.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tuneurlradio.app.navigation.AppNavigation
import com.tuneurlradio.app.navigation.AppTab
import com.tuneurlradio.app.ui.components.ListeningControlButton
import com.tuneurlradio.app.ui.components.MiniPlayerView

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onExpandPlayer: () -> Unit
) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.title
                            )
                        },
                        label = { Text(tab.title) },
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AppNavigation(
                navController = navController,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                onStationClick = { stationId ->
                    viewModel.handleIntent(MainIntent.SelectStation(stationId))
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .offset(y = (-8).dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniPlayerView(
                    currentStation = state.currentStation,
                    isPlaying = state.isPlaying,
                    onPlayPauseClick = { viewModel.handleIntent(MainIntent.TogglePlayback) },
                    onPlayerClick = onExpandPlayer,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                ListeningControlButton(
                    isListening = state.isOTAListening,
                    onClick = { viewModel.handleIntent(MainIntent.ToggleOTAListening) }
                )
            }
        }
    }
}
