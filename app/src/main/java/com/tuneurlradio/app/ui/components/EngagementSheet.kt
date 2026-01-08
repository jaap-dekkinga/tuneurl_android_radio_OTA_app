package com.tuneurlradio.app.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.tuneurlradio.app.BuildConfig
import com.tuneurlradio.app.tuneurl.TuneURLManager
import com.tuneurlradio.app.tuneurl.TuneURLMatch
import com.tuneurlradio.app.voice.VoiceCommandManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "EngagementSheet"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngagementSheet(
    match: TuneURLMatch,
    voiceCommandManager: VoiceCommandManager?,
    tuneURLManager: TuneURLManager?,
    voiceCommandsEnabled: Boolean,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val showWebView = match.type == "open_page" || match.type == "save_page"
    val showCouponImage = match.type == "coupon"
    
    val autoDismissDelay = if (BuildConfig.DEBUG) 15000L else 15000L

    val hasRecordPermission = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    // Check if voice recognition is available
    val isVoiceAvailable by voiceCommandManager?.isVoiceAvailable?.collectAsState() ?: remember { mutableStateOf(false) }
    val isActivelyListening by voiceCommandManager?.isActivelyListening?.collectAsState() ?: remember { mutableStateOf(false) }
    
    // Track if action was taken to prevent auto-dismiss after voice command
    var actionTaken by remember { mutableStateOf(false) }
    
    // Determine if voice commands can be used
    val canUseVoice = voiceCommandsEnabled && hasRecordPermission && isVoiceAvailable

    // Auto-dismiss timer
    LaunchedEffect(Unit) {
        delay(autoDismissDelay)
        if (!actionTaken) {
            Log.d(TAG, "Auto-dismissing engagement sheet after timeout")
            onDismiss()
        }
    }

    // Pause OTA listening and start voice recognition when sheet opens
    // Like iOS: when engagement sheet opens, we need the microphone for voice commands
    LaunchedEffect(Unit) {
        Log.d(TAG, "EngagementSheet opened - voiceCommandsEnabled=$voiceCommandsEnabled, hasRecordPermission=$hasRecordPermission, isVoiceAvailable=$isVoiceAvailable")
        
        if (canUseVoice && voiceCommandManager != null) {
            // Pause OTA listening to free up the microphone for voice commands
            // This matches iOS behavior where StateManager.shared.isListening is checked
            Log.d(TAG, "Pausing OTA listening for voice commands")
            tuneURLManager?.pauseOTAListeningForVoice()
            
            // Small delay to let the microphone be released by OTA listener
            delay(500)
            Log.d(TAG, "Starting voice recognition for engagement sheet")
            voiceCommandManager.startRecognition()
        } else {
            if (!voiceCommandsEnabled) Log.d(TAG, "Voice commands disabled in settings")
            if (!hasRecordPermission) Log.d(TAG, "No RECORD_AUDIO permission")
            if (!isVoiceAvailable) Log.d(TAG, "Voice recognition not available on device")
            if (voiceCommandManager == null) Log.d(TAG, "VoiceCommandManager is null")
        }
    }

    // Stop voice recognition and resume OTA listening when sheet closes
    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "EngagementSheet closing - stopping voice recognition")
            voiceCommandManager?.stopRecognition()
            // OTA listening will be resumed in TuneURLManager.dismissEngagement()
        }
    }

    // Listen for voice commands
    LaunchedEffect(Unit) {
        if (voiceCommandManager != null && canUseVoice) {
            Log.d(TAG, "Setting up voice command listener")
            voiceCommandManager.recognitions.collect { text ->
                Log.d(TAG, "Voice command received: \"$text\"")
                handleVoiceCommand(
                    text = text,
                    matchType = match.type,
                    matchInfo = match.info,
                    context = context,
                    onYesAction = {
                        Log.d(TAG, "Voice command: YES action triggered")
                        actionTaken = true
                        when (match.type) {
                            "open_page", "save_page", "coupon" -> onAction("interested")
                            "phone" -> {
                                try {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${match.info}"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error opening phone", e)
                                }
                                onAction("acted")
                            }
                            "sms" -> {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:${match.info}"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error opening SMS", e)
                                }
                                onAction("acted")
                            }
                            else -> onAction("interested")
                        }
                    },
                    onNoAction = {
                        Log.d(TAG, "Voice command: NO action triggered")
                        actionTaken = true
                        onDismiss()
                    }
                )
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (match.type != "poll") {
                Text(
                    text = "Are you interested?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))
                
                // Voice command indicator
                if (voiceCommandsEnabled && hasRecordPermission) {
                    if (isVoiceAvailable) {
                        Text(
                            text = if (isActivelyListening) "🎤 Listening... Say \"Yes\" or \"No\"" else "🎤 Say \"Yes\" or \"No\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isActivelyListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "Voice commands unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (match.description.isNotEmpty() && match.type != "poll") {
                Text(
                    text = match.description,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    showWebView -> {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webViewClient = WebViewClient()
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    loadUrl(match.info)
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    showCouponImage -> {
                        AsyncImage(
                            model = match.info,
                            contentDescription = "Coupon",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    match.type == "phone" || match.type == "sms" -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Phone Number",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = match.info,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = match.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (match.type != "poll") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                when (match.type) {
                                    "open_page", "save_page", "coupon" -> {
                                        onAction("interested")
                                    }
                                    "phone" -> {
                                        try {
                                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${match.info}"))
                                            context.startActivity(intent)
                                        } catch (e: Exception) {}
                                        onAction("acted")
                                    }
                                    "sms" -> {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:${match.info}"))
                                            context.startActivity(intent)
                                        } catch (e: Exception) {}
                                        onAction("acted")
                                    }
                                    else -> onAction("interested")
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF34C759)
                        )
                    ) {
                        Text(
                            text = when (match.type) {
                                "open_page", "save_page", "coupon" -> "👍Yes, Save"
                                "sms" -> "👍Yes, Write"
                                "phone" -> "👍Yes, Call"
                                else -> "👍Yes"
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF3B30)
                        )
                    ) {
                        Text(
                            text = "👎No",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun handleVoiceCommand(
    text: String,
    matchType: String,
    matchInfo: String,
    context: android.content.Context,
    onYesAction: () -> Unit,
    onNoAction: () -> Unit
) {
    val lowerText = text.lowercase()
    Log.d(TAG, "Processing voice command: \"$lowerText\"")
    
    when {
        lowerText.contains("yes") || lowerText.contains("save") || 
        lowerText.contains("call") || lowerText.contains("write") ||
        lowerText.contains("okay") || lowerText.contains("sure") ||
        lowerText.contains("yeah") || lowerText.contains("yep") -> {
            Log.d(TAG, "Matched YES command")
            onYesAction()
        }
        lowerText.contains("no") || lowerText.contains("dismiss") || 
        lowerText.contains("close") || lowerText.contains("cancel") ||
        lowerText.contains("nope") || lowerText.contains("skip") -> {
            Log.d(TAG, "Matched NO command")
            onNoAction()
        }
        else -> {
            Log.d(TAG, "No matching command found for: \"$lowerText\"")
        }
    }
}
