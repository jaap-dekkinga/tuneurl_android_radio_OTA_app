package com.tuneurlradio.app.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceCommandManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "VoiceCommandManager"
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val _recognitions = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val recognitions: SharedFlow<String> = _recognitions.asSharedFlow()
    
    private val _isVoiceAvailable = MutableStateFlow(false)
    val isVoiceAvailable: StateFlow<Boolean> = _isVoiceAvailable.asStateFlow()
    
    private val _isActivelyListening = MutableStateFlow(false)
    val isActivelyListening: StateFlow<Boolean> = _isActivelyListening.asStateFlow()

    init {
        // Check availability on init
        checkVoiceAvailability()
    }
    
    private fun checkVoiceAvailability() {
        // Check if SpeechRecognizer is available
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        
        // Also check if there's an activity that can handle speech recognition
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val activities = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val hasActivity = activities.isNotEmpty()
        
        // On Android 11+ (API 30+), isRecognitionAvailable may return false due to package visibility
        // restrictions even when speech recognition is actually available. We need the <queries>
        // element in AndroidManifest.xml to properly detect availability.
        // As a fallback, if we have the activity but isRecognitionAvailable is false,
        // we still try to use speech recognition.
        _isVoiceAvailable.value = isAvailable || hasActivity
        
        Log.d(TAG, "Voice recognition availability check:")
        Log.d(TAG, "  - SpeechRecognizer.isRecognitionAvailable: $isAvailable")
        Log.d(TAG, "  - Has speech recognition activity: $hasActivity")
        Log.d(TAG, "  - Android SDK: ${android.os.Build.VERSION.SDK_INT}")
        Log.d(TAG, "  - Final availability: ${_isVoiceAvailable.value}")
        
        if (!_isVoiceAvailable.value) {
            Log.w(TAG, "Speech recognition not available. Possible reasons:")
            Log.w(TAG, "  - Google app not installed")
            Log.w(TAG, "  - Running on emulator without Google Play Services")
            Log.w(TAG, "  - Speech recognition service disabled")
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Log.w(TAG, "  - Android 11+ package visibility: ensure <queries> is in AndroidManifest.xml")
            }
        }
    }

    fun startRecognition() {
        Log.d(TAG, "startRecognition called, isListening=$isListening")
        
        if (isListening) {
            Log.d(TAG, "Already listening, ignoring")
            return
        }

        // Re-check availability
        checkVoiceAvailability()
        
        if (!_isVoiceAvailable.value) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }

        Log.d(TAG, "Starting voice recognition...")
        isListening = true
        
        // Must create SpeechRecognizer on main thread
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                
                if (speechRecognizer == null) {
                    Log.e(TAG, "Failed to create SpeechRecognizer")
                    isListening = false
                    _isActivelyListening.value = false
                    return@post
                }
                
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "✓ Ready for speech - listening for voice commands")
                        _isActivelyListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "User started speaking")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "User stopped speaking")
                        _isActivelyListening.value = false
                    }

                    override fun onError(error: Int) {
                        _isActivelyListening.value = false
                        val errorMessage = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                            else -> "Unknown error ($error)"
                        }
                        Log.d(TAG, "Recognition error: $errorMessage")
                        
                        // Restart listening on recoverable errors
                        if (isListening && (error == SpeechRecognizer.ERROR_NO_MATCH || 
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_CLIENT)) {
                            // Delay before restarting to avoid rapid loops
                            mainHandler.postDelayed({
                                if (isListening) {
                                    Log.d(TAG, "Restarting speech recognition...")
                                    startListeningInternal()
                                }
                            }, 500)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        _isActivelyListening.value = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.firstOrNull()?.let { text ->
                            Log.d(TAG, "★ Voice command recognized: \"$text\"")
                            _recognitions.tryEmit(text)
                        }
                        
                        // Restart listening for next command
                        if (isListening) {
                            mainHandler.postDelayed({
                                if (isListening) {
                                    startListeningInternal()
                                }
                            }, 300)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.firstOrNull()?.let { text ->
                            Log.d(TAG, "Partial result: \"$text\"")
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                startListeningInternal()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error creating speech recognizer", e)
                isListening = false
                _isActivelyListening.value = false
            }
        }
    }

    private fun startListeningInternal() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        
        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Speech recognizer started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            _isActivelyListening.value = false
        }
    }

    fun stopRecognition() {
        Log.d(TAG, "Stopping voice recognition")
        isListening = false
        _isActivelyListening.value = false
        
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
                Log.d(TAG, "Speech recognizer stopped and destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognizer", e)
            }
        }
    }
}
