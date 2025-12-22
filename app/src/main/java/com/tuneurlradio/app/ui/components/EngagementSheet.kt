package com.tuneurlradio.app.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.tuneurlradio.app.tuneurl.TuneURLMatch
import com.tuneurlradio.app.voice.VoiceCommandManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngagementSheet(
    match: TuneURLMatch,
    voiceCommandManager: VoiceCommandManager?,
    voiceCommandsEnabled: Boolean,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val showWebView = match.type == "open_page" || match.type == "save_page"
    val showCouponImage = match.type == "coupon"
    
    val autoDismissDelay = if (BuildConfig.DEBUG) 5000L else 15000L

    val hasRecordPermission = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        delay(autoDismissDelay)
        onDismiss()
    }

    LaunchedEffect(voiceCommandsEnabled, hasRecordPermission) {
        if (voiceCommandsEnabled && hasRecordPermission && voiceCommandManager != null) {
            voiceCommandManager.startRecognition()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceCommandManager?.stopRecognition()
        }
    }

    LaunchedEffect(voiceCommandManager) {
        if (voiceCommandManager != null && voiceCommandsEnabled && hasRecordPermission) {
            voiceCommandManager.recognitions.collect { text ->
                handleVoiceCommand(
                    text = text,
                    matchType = match.type,
                    matchInfo = match.info,
                    context = context,
                    onYesAction = {
                        when (match.type) {
                            "open_page", "save_page", "coupon" -> onAction("interested")
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
                    },
                    onNoAction = onDismiss
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
    
    when {
        lowerText.contains("yes") || lowerText.contains("save") || 
        lowerText.contains("call") || lowerText.contains("write") -> {
            onYesAction()
        }
        lowerText.contains("no") || lowerText.contains("dismiss") || 
        lowerText.contains("close") || lowerText.contains("cancel") -> {
            onNoAction()
        }
    }
}
