package com.tuneurlradio.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.dekidea.tuneurl.util.Constants
import com.dekidea.tuneurl.util.TuneURLManager as SDKTuneURLManager
import com.tuneurlradio.app.ui.main.MainIntent
import com.tuneurlradio.app.ui.main.MainScreen
import com.tuneurlradio.app.ui.main.MainViewModel
import com.tuneurlradio.app.ui.screens.player.PlayerScreen
import com.tuneurlradio.app.ui.theme.TuneURLRadioTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { entry ->
            Log.d(TAG, "Permission ${entry.key} granted: ${entry.value}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        initializeTuneURLSDK()
        requestRequiredPermissions()

        setContent {
            TuneURLRadioTheme {
                val mainViewModel: MainViewModel = hiltViewModel()
                val state by mainViewModel.state.collectAsState()
                val context = LocalContext.current

                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = mainViewModel,
                        onExpandPlayer = {
                            mainViewModel.handleIntent(MainIntent.ExpandPlayer)
                        }
                    )

                    AnimatedVisibility(
                        visible = state.expandedPlayer,
                        enter = slideInVertically { it },
                        exit = slideOutVertically { it }
                    ) {
                        BackHandler {
                            mainViewModel.handleIntent(MainIntent.CollapsePlayer)
                        }

                        PlayerScreen(
                            station = state.currentStation,
                            isPlaying = state.isPlaying,
                            playerState = state.playerState,
                            trackName = state.trackName,
                            artistName = state.artistName,
                            volume = state.volume,
                            sleepTimerEndTime = state.sleepTimerEndTime,
                            onVolumeChange = { mainViewModel.handleIntent(MainIntent.SetVolume(it)) },
                            onPlayPauseClick = {
                                mainViewModel.handleIntent(MainIntent.TogglePlayback)
                            },
                            onShareClick = {
                                state.currentStation?.socialURL?.let { url ->
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, url)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share"))
                                }
                            },
                            onSleepTimerSet = { duration ->
                                mainViewModel.handleIntent(MainIntent.SetSleepTimer(duration))
                            }
                        )
                    }

                    if (state.showEngagementSheet && state.currentMatch != null) {
                        com.tuneurlradio.app.ui.components.EngagementSheet(
                            match = state.currentMatch!!,
                            voiceCommandManager = mainViewModel.voiceCommandManager,
                            voiceCommandsEnabled = state.voiceCommandsEnabled,
                            onDismiss = { mainViewModel.handleIntent(MainIntent.DismissEngagement) },
                            onAction = { action -> mainViewModel.handleIntent(MainIntent.RecordInterest(action)) }
                        )
                    }
                }
            }
        }
    }

    private fun initializeTuneURLSDK() {
        try {
            SDKTuneURLManager.updateStringSetting(
                this,
                Constants.SETTING_TUNEURL_API_BASE_URL,
                "http://ec2-54-213-252-225.us-west-2.compute.amazonaws.com"
            )

            SDKTuneURLManager.updateStringSetting(
                this,
                Constants.SETTING_SEARCH_FINGERPRINT_URL,
                "https://pnz3vadc52.execute-api.us-east-2.amazonaws.com/dev/search-fingerprint"
            )

            SDKTuneURLManager.updateStringSetting(
                this,
                Constants.SETTING_INTERESTS_API_URL,
                "https://65neejq3c9.execute-api.us-east-2.amazonaws.com/interests"
            )

            SDKTuneURLManager.updateStringSetting(
                this,
                Constants.SETTING_POLL_API_URL,
                "http://pollapiwebservice.us-east-2.elasticbeanstalk.com/api/pollapi"
            )

            SDKTuneURLManager.updateStringSetting(
                this,
                Constants.SETTING_GET_CYOA_API_URL,
                "https://pnz3vadc52.execute-api.us-east-2.amazonaws.com/dev/get-cyoa-mp3"
            )

            Log.d(TAG, "TuneURL SDK initialized with API endpoints")
            Log.d(TAG, "Search URL: https://pnz3vadc52.execute-api.us-east-2.amazonaws.com/dev/search-fingerprint")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TuneURL SDK", e)
        }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All required permissions already granted")
        }
    }
}
