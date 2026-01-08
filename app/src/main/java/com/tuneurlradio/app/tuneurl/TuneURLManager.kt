package com.tuneurlradio.app.tuneurl

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.dekidea.tuneurl.util.TuneURLManager as SDKTuneURLManager
import com.tuneurlradio.app.R
import com.tuneurlradio.app.data.local.SettingsDataStore
import com.tuneurlradio.app.data.repository.EngagementsRepository
import com.tuneurlradio.app.domain.model.Engagement
import com.tuneurlradio.app.domain.model.EngagementType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

data class TuneURLState(
    val isListening: Boolean = false,        // OTA (microphone) listening active
    val isStreamParsing: Boolean = false,    // Stream parsing active (when radio plays)
    val currentMatch: TuneURLMatch? = null,
    val showEngagementSheet: Boolean = false,
    val isMatchOpen: Boolean = false,        // Prevents duplicate matches (like iOS currentOpenMatchController)
    val isOTAPausedForVoice: Boolean = false // OTA paused temporarily for voice commands
)

/**
 * TuneURLManager - Coordinates TuneURL detection similar to iOS StateManager
 * 
 * Two modes of operation:
 * 1. OTA Listening (microphone) - Active when radio is NOT playing
 * 2. Stream Parsing - Active when radio IS playing
 * 
 * Key behavior (matching iOS):
 * - When radio plays: Stop OTA listening, start stream parsing
 * - When radio stops: Stop stream parsing, start OTA listening
 */
@Singleton
class TuneURLManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engagementsRepository: EngagementsRepository,
    private val settingsDataStore: SettingsDataStore
) {
    private val TAG = "TuneURLManager"
    
    // Stream parser for when radio is playing (like iOS StreamParser)
    private var streamParser: TuneURLDetector? = null
    
    // OTA listener for when radio is NOT playing (like iOS OTAParser)
    private var otaListener: OTAListener? = null
    
    private var currentStationId: Int? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(TuneURLState())
    val state: StateFlow<TuneURLState> = _state.asStateFlow()
    
    // Track recently shown match IDs to prevent duplicates (like iOS)
    private val recentlyShownMatchIds = mutableSetOf<String>()
    private var lastMatchTime = 0L
    private val MATCH_COOLDOWN_MS = 30000L  // 30 second cooldown between same match

    init {
        Log.d(TAG, "TuneURLManager initialized")
    }

    /**
     * Start stream parsing when radio starts playing
     * This automatically stops OTA listening (like iOS behavior)
     */
    fun startStreamParsing(streamUrl: String, stationId: Int? = null) {
        Log.d(TAG, "================================================")
        Log.d(TAG, "Starting stream parsing (radio playing)")
        Log.d(TAG, "Stream URL: $streamUrl")
        Log.d(TAG, "Station ID: $stationId")
        Log.d(TAG, "================================================")

        // Stop OTA listening when radio starts (iOS behavior)
        stopOTAListening()

        currentStationId = stationId
        
        streamParser?.release()
        streamParser = TuneURLDetector(context)
        
        streamParser?.startDetection(streamUrl) { match ->
            Log.d(TAG, "================================================")
            Log.d(TAG, "STREAM MATCH DETECTED!")
            Log.d(TAG, "Match name: ${match.name}")
            Log.d(TAG, "Match type: ${match.type}")
            Log.d(TAG, "================================================")
            handleMatch(match, isOTA = false)
        }

        _state.value = _state.value.copy(isStreamParsing = true)
        Log.d(TAG, "Stream parsing started")
    }

    /**
     * Stop stream parsing when radio stops
     * This automatically starts OTA listening (like iOS behavior)
     */
    fun stopStreamParsing() {
        Log.d(TAG, "Stopping stream parsing...")
        streamParser?.stopDetection()
        streamParser?.release()
        streamParser = null
        _state.value = _state.value.copy(isStreamParsing = false)
        
        // Start OTA listening when radio stops (iOS behavior)
        startOTAListening()
        
        Log.d(TAG, "Stream parsing stopped, OTA listening started")
    }

    /**
     * Start OTA (microphone) listening
     * Only active when radio is NOT playing
     */
    fun startOTAListening() {
        if (_state.value.isStreamParsing) {
            Log.d(TAG, "Radio is playing, skipping OTA listening")
            return
        }
        
        if (_state.value.isListening) {
            Log.d(TAG, "Already OTA listening, ignoring start call")
            return
        }

        Log.d(TAG, "================================================")
        Log.d(TAG, "Starting OTA (microphone) listening")
        Log.d(TAG, "================================================")

        otaListener?.release()
        otaListener = OTAListener(context)
        
        otaListener?.onMatchDetected = { match ->
            Log.d(TAG, "================================================")
            Log.d(TAG, "OTA MATCH DETECTED!")
            Log.d(TAG, "Match name: ${match.name}")
            Log.d(TAG, "Match type: ${match.type}")
            Log.d(TAG, "================================================")
            handleMatch(match, isOTA = true)
        }
        
        otaListener?.startListening()
        _state.value = _state.value.copy(isListening = true)
        Log.d(TAG, "OTA listening started")
    }

    /**
     * Stop OTA (microphone) listening
     */
    fun stopOTAListening() {
        if (!_state.value.isListening) return
        
        Log.d(TAG, "Stopping OTA listening...")
        otaListener?.stopListening()
        otaListener?.release()
        otaListener = null
        _state.value = _state.value.copy(isListening = false)
        Log.d(TAG, "OTA listening stopped")
    }

    /**
     * Legacy method for backward compatibility
     * Now starts stream parsing (for when radio is playing)
     */
    fun startListening(streamUrl: String, stationId: Int? = null) {
        startStreamParsing(streamUrl, stationId)
    }

    /**
     * Legacy method for backward compatibility
     * Now stops stream parsing and starts OTA listening
     */
    fun stopListening() {
        stopStreamParsing()
    }

    /**
     * Toggle OTA listening on/off
     * If radio is playing, this will stop the radio and start OTA listening
     */
    fun toggleOTAListening(): Boolean {
        return if (_state.value.isListening) {
            stopOTAListening()
            false
        } else {
            startOTAListening()
            true
        }
    }

    private fun handleMatch(match: TuneURLMatch, isOTA: Boolean) {
        // Guard: Don't show if a match is already open (like iOS currentOpenMatchController check)
        if (_state.value.isMatchOpen || _state.value.showEngagementSheet) {
            Log.d(TAG, "A match is already open, ignoring new match: ${match.name}")
            return
        }
        
        // Guard: Don't show the same match within cooldown period
        val currentTime = System.currentTimeMillis()
        if (recentlyShownMatchIds.contains(match.id) && (currentTime - lastMatchTime) < MATCH_COOLDOWN_MS) {
            Log.d(TAG, "Match ${match.id} was recently shown, ignoring duplicate")
            return
        }
        
        // Track this match
        recentlyShownMatchIds.add(match.id)
        lastMatchTime = currentTime
        
        // Clean up old match IDs (keep only recent ones)
        if (recentlyShownMatchIds.size > 10) {
            recentlyShownMatchIds.clear()
            recentlyShownMatchIds.add(match.id)
        }
        
        Log.d(TAG, "Processing new match: ${match.name}")
        
        val engagement = match.toEngagement(if (isOTA) null else currentStationId)

        scope.launch {
            SDKTuneURLManager.addRecordOfInterest(
                context,
                match.id,
                "heard",
                match.date ?: ""
            )

            val storeHistory = settingsDataStore.storeHistory.first()
            if (storeHistory && engagement.canSave) {
                engagementsRepository.saveToHistory(engagement)
            }

            // Check if app is in background - always show notification in background
            val isAppInBackground = !isAppInForeground()
            Log.d(TAG, "App is in ${if (isAppInBackground) "BACKGROUND" else "FOREGROUND"}")
            
            if (isAppInBackground) {
                // Always show notification when app is in background
                Log.d(TAG, "Showing notification for background trigger")
                showNotification(match)
            } else {
                // App is in foreground - check display mode preference
                val displayMode = settingsDataStore.engagementDisplayMode.first()
                if (displayMode == com.tuneurlradio.app.domain.model.EngagementDisplayMode.NOTIFICATION) {
                    showNotification(match)
                } else {
                    _state.value = _state.value.copy(
                        currentMatch = match,
                        showEngagementSheet = true,
                        isMatchOpen = true
                    )
                }
            }
        }
    }
    
    /**
     * Check if app is in foreground
     */
    private fun isAppInForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningAppProcesses = activityManager.runningAppProcesses ?: return false

        return runningAppProcesses.any { processInfo ->
            processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    processInfo.processName == context.packageName
        }
    }

    private fun showNotification(match: TuneURLMatch) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "tuneurl_triggers",
                "TuneURL Triggers",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = android.content.Intent(context, Class.forName("com.tuneurlradio.app.MainActivity")).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "com.tuneurlradio.app.SHOW_ENGAGEMENT"
            putExtra("match_id", match.id)
            putExtra("match_name", match.name)
            putExtra("match_description", match.description)
            putExtra("match_info", match.info)
            putExtra("match_type", match.type)
            putExtra("match_percentage", match.matchPercentage)
        }

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(context, "tuneurl_triggers")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("New Turl!")
            .setContentText(match.description.ifEmpty { "If you're interested, then click 'save'" })
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(2001, notification)
    }

    fun dismissEngagement() {
        _state.value = _state.value.copy(
            showEngagementSheet = false,
            currentMatch = null,
            isMatchOpen = false  // Allow new matches after dismissal
        )
        
        // Resume OTA listening if it was paused for voice commands
        resumeOTAListeningAfterVoice()
    }
    
    /**
     * Temporarily pause OTA listening to allow voice commands to use the microphone
     * Called when engagement sheet opens and voice commands are enabled
     * Like iOS behavior where StateManager.shared.isListening is checked
     */
    fun pauseOTAListeningForVoice() {
        if (!_state.value.isListening) {
            Log.d(TAG, "OTA not listening, no need to pause for voice")
            return
        }
        
        Log.d(TAG, "Pausing OTA listening for voice commands")
        otaListener?.stopListening()
        _state.value = _state.value.copy(
            isListening = false,
            isOTAPausedForVoice = true
        )
    }
    
    /**
     * Resume OTA listening after voice commands are done
     * Called when engagement sheet closes
     */
    fun resumeOTAListeningAfterVoice() {
        if (!_state.value.isOTAPausedForVoice) {
            Log.d(TAG, "OTA was not paused for voice, no need to resume")
            return
        }
        
        Log.d(TAG, "Resuming OTA listening after voice commands")
        _state.value = _state.value.copy(isOTAPausedForVoice = false)
        
        // Only resume if radio is not playing
        if (!_state.value.isStreamParsing) {
            otaListener?.startListening()
            _state.value = _state.value.copy(isListening = true)
        }
    }
    
    /**
     * Check if OTA is currently listening (for voice command manager to know)
     */
    fun isOTAListening(): Boolean = _state.value.isListening
    
    /**
     * Show engagement sheet from notification click
     * Called when user taps on a TuneURL notification
     */
    fun showEngagementFromNotification(match: TuneURLMatch) {
        Log.d(TAG, "Showing engagement from notification: ${match.name}")
        
        // Reset any existing match state
        _state.value = _state.value.copy(
            currentMatch = match,
            showEngagementSheet = true,
            isMatchOpen = true
        )
    }

    fun saveEngagement(match: TuneURLMatch) {
        val engagement = match.toEngagement(currentStationId)
        scope.launch {
            engagementsRepository.saveEngagement(engagement)
            SDKTuneURLManager.addRecordOfInterest(
                context,
                match.id,
                "interested",
                match.date ?: ""
            )
        }
    }

    fun recordInterest(match: TuneURLMatch, action: String) {
        scope.launch {
            SDKTuneURLManager.addRecordOfInterest(
                context,
                match.id,
                action,
                match.date ?: ""
            )

            if (action == "interested") {
                val engagement = match.toEngagement(currentStationId)
                engagementsRepository.saveEngagement(engagement)
            }
        }
    }

    fun release() {
        stopOTAListening()
        stopStreamParsing()
        currentStationId = null
    }
}

fun TuneURLMatch.toEngagement(stationId: Int?): Engagement {
    return Engagement(
        id = id.hashCode(),
        rawType = type,
        type = EngagementType.fromString(type),
        name = name,
        description = description,
        info = info,
        heardAt = Date(),
        sourceStationId = stationId
    )
}
