package com.tuneurlradio.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tuneurlradio.app.domain.model.EngagementDisplayMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToParsingSettings: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is SettingsEffect.OpenUrl -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))
                    context.startActivity(intent)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            SectionHeader("General")

            ListItem(
                headlineContent = { Text("Show \"Turls\" as") },
                leadingContent = {
                    Icon(Icons.Default.GridView, contentDescription = null)
                },
                supportingContent = {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        EngagementDisplayMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = state.engagementDisplayMode == mode,
                                onClick = {
                                    viewModel.handleIntent(SettingsIntent.SetEngagementDisplayMode(mode))
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = EngagementDisplayMode.entries.size
                                )
                            ) {
                                Text(mode.displayName)
                            }
                        }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Store \"Turls\" history") },
                leadingContent = {
                    Icon(Icons.Default.CalendarViewDay, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = state.storeAllEngagementsHistory,
                        onCheckedChange = {
                            viewModel.handleIntent(SettingsIntent.SetStoreAllEngagementsHistory(it))
                        }
                    )
                }
            )

            ListItem(
                headlineContent = { Text("Voice Commands") },
                leadingContent = {
                    Icon(Icons.Default.GraphicEq, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = state.voiceCommands,
                        onCheckedChange = {
                            viewModel.handleIntent(SettingsIntent.SetVoiceCommands(it))
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader("Parsing")

            ListItem(
                headlineContent = { Text("Parsing Settings") },
                leadingContent = {
                    Icon(Icons.Default.Tune, contentDescription = null)
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.clickable { onNavigateToParsingSettings() }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader("Info")

            ListItem(
                headlineContent = { Text("Website") },
                leadingContent = {
                    Icon(Icons.Default.Public, contentDescription = null)
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.clickable {
                    viewModel.handleIntent(SettingsIntent.OpenWebsite)
                }
            )

            ListItem(
                headlineContent = { Text("Privacy Policy") },
                leadingContent = {
                    Icon(Icons.Default.PrivacyTip, contentDescription = null)
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.clickable {
                    viewModel.handleIntent(SettingsIntent.OpenPrivacyPolicy)
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            VersionFooter()

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun VersionFooter() {
    val context = LocalContext.current
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val version = packageInfo.versionName ?: "-"
    val build = packageInfo.longVersionCode.toString()

    Text(
        text = "Version $version($build)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
