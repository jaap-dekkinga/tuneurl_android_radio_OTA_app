package com.tuneurlradio.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParsingSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parsing Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("OTA Match Threshold") },
                supportingContent = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Current: ${state.otaMatchThreshold}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = state.otaMatchThreshold.toFloat(),
                            onValueChange = {
                                viewModel.handleIntent(SettingsIntent.SetOtaMatchThreshold(it.roundToInt()))
                            },
                            valueRange = 1f..20f,
                            steps = 18,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Stream Match Threshold") },
                supportingContent = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Current: ${state.streamMatchThreshold}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = state.streamMatchThreshold.toFloat(),
                            onValueChange = {
                                viewModel.handleIntent(SettingsIntent.SetStreamMatchThreshold(it.roundToInt()))
                            },
                            valueRange = 1f..20f,
                            steps = 18,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            )
        }
    }
}
