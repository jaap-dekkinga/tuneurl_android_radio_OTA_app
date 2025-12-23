package com.tuneurlradio.app.tuneurl

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.dekidea.tuneurl.NativeResampler
import com.dekidea.tuneurl.TuneURLSDK
import com.dekidea.tuneurl.service.APIService
import com.dekidea.tuneurl.util.Constants
import com.google.gson.JsonParser
import com.tuneurlradio.app.R
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OTAListener - Over-The-Air (microphone) based TuneURL detection
 * 
 * Like iOS SDK's Listener class, this uses a trigger sound file for local detection.
 * When the trigger is detected in the audio, it then calls the API for the full match.
 */
class OTAListener(private val context: Context) : Constants {

    private val TAG = "OTAListener"
    
    // Audio capture settings
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val FINGERPRINT_SAMPLE_RATE = 10240
    
    // Detection settings
    private val TRIGGER_CHECK_INTERVAL_MS = 1000L  // Check for trigger every 1 second
    private val TRIGGER_SIMILARITY_THRESHOLD = 0.15f  // 15% similarity to detect trigger
    private val MIN_MATCH_PERCENTAGE = 10f  // Lower threshold since we pre-filter with trigger
    
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var recordingJob: Job? = null
    private var processingJob: Job? = null
    private val listenerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Audio buffer - keep ~10 seconds for better fingerprinting
    private val audioBuffer = mutableListOf<ByteArray>()
    private var currentBufferSize = 0
    private val MAX_BUFFER_SIZE = SAMPLE_RATE * 2 * 10 // 10 seconds of audio
    private val bufferLock = Any()
    
    // Capture additional audio after trigger detected
    private var isCapturingAfterTrigger = false
    private var captureStartTime = 0L
    private val CAPTURE_DURATION_MS = 3000L // Capture 3 more seconds after trigger
    
    // Trigger sound data (pre-loaded and resampled)
    private var triggerBuffer: ByteBuffer? = null
    private var triggerSampleCount = 0
    
    private var lastTriggerCheckTime = 0L
    private var isProcessing = false
    
    var onMatchDetected: ((TuneURLMatch) -> Unit)? = null
    private val searchResultReceiver = SearchResultReceiver()

    init {
        try {
            val searchFilter = IntentFilter().apply {
                addAction(Constants.SEARCH_FINGERPRINT_RESULT_RECEIVED)
                addAction(Constants.SEARCH_FINGERPRINT_RESULT_ERROR)
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(searchResultReceiver, searchFilter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(searchResultReceiver, searchFilter)
            }

            // Load trigger sound on init
            loadTriggerSound()
            
            Log.d(TAG, "OTAListener initialized with trigger-based detection")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing OTAListener", e)
        }
    }
    
    /**
     * Load and prepare the trigger sound for comparison
     */
    private fun loadTriggerSound() {
        try {
            Log.d(TAG, "Loading trigger sound...")
            
            // Copy trigger sound from raw resources to cache
            val triggerFile = File(context.cacheDir, "trigger_sound.mp3")
            if (!triggerFile.exists()) {
                context.resources.openRawResource(R.raw.trigger_sound).use { input ->
                    FileOutputStream(triggerFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            // Decode MP3 to PCM
            val pcmData = decodeMp3ToPcm(triggerFile.absolutePath)
            val sampleRate = getDecodedSampleRate(triggerFile.absolutePath)
            
            if (pcmData == null || pcmData.isEmpty()) {
                Log.e(TAG, "Failed to decode trigger sound")
                return
            }
            
            Log.d(TAG, "Trigger decoded: ${pcmData.size} bytes at $sampleRate Hz")
            
            // Convert to mono if needed (assuming stereo input)
            val monoData = convertToMono(pcmData)
            
            // Resample to fingerprint sample rate
            val sourceBuffer = ByteBuffer.allocateDirect(monoData.size)
            sourceBuffer.order(ByteOrder.LITTLE_ENDIAN)
            sourceBuffer.put(monoData)
            sourceBuffer.rewind()
            
            val resampledSize = ((FINGERPRINT_SAMPLE_RATE.toDouble() / sampleRate.toDouble()) * monoData.size).toInt()
            val resampledBuffer = ByteBuffer.allocateDirect(resampledSize)
            resampledBuffer.order(ByteOrder.LITTLE_ENDIAN)
            
            val resampler = NativeResampler()
            try {
                resampler.create(sampleRate, FINGERPRINT_SAMPLE_RATE, 2048, 1)
                val outputLength = resampler.resampleEx(sourceBuffer, resampledBuffer, sourceBuffer.remaining())
                
                if (outputLength > 0) {
                    resampledBuffer.rewind()
                    resampledBuffer.limit(outputLength)
                    
                    // Store for later comparison
                    triggerBuffer = ByteBuffer.allocateDirect(outputLength)
                    triggerBuffer?.order(ByteOrder.LITTLE_ENDIAN)
                    triggerBuffer?.put(resampledBuffer)
                    triggerBuffer?.rewind()
                    triggerSampleCount = outputLength / 2
                    
                    Log.d(TAG, "✓ Trigger sound loaded: $triggerSampleCount samples at $FINGERPRINT_SAMPLE_RATE Hz")
                }
            } finally {
                resampler.destroy()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading trigger sound", e)
        }
    }

    fun startListening() {
        if (isListening) {
            Log.d(TAG, "Already listening, ignoring start call")
            return
        }
        
        if (triggerBuffer == null) {
            Log.e(TAG, "Trigger sound not loaded, cannot start listening")
            loadTriggerSound()
            if (triggerBuffer == null) return
        }
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }
        
        Log.d(TAG, "================================================")
        Log.d(TAG, "Starting OTA listening (trigger-based)")
        Log.d(TAG, "Sample rate: $SAMPLE_RATE Hz")
        Log.d(TAG, "Trigger threshold: ${(TRIGGER_SIMILARITY_THRESHOLD * 100).toInt()}%")
        Log.d(TAG, "================================================")
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }
            
            isListening = true
            synchronized(bufferLock) {
                audioBuffer.clear()
                currentBufferSize = 0
            }
            lastTriggerCheckTime = System.currentTimeMillis()
            
            audioRecord?.startRecording()
            startRecordingLoop()
            startTriggerDetectionLoop()
            
            Log.d(TAG, "OTA listening started successfully")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting OTA listening", e)
        }
    }

    fun stopListening() {
        if (!isListening) return
        
        Log.d(TAG, "Stopping OTA listening...")
        isListening = false
        
        recordingJob?.cancel()
        recordingJob = null
        processingJob?.cancel()
        processingJob = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }
        
        synchronized(bufferLock) {
            audioBuffer.clear()
            currentBufferSize = 0
        }
        
        Log.d(TAG, "OTA listening stopped")
    }
    
    private fun startRecordingLoop() {
        recordingJob = listenerScope.launch {
            val buffer = ByteArray(bufferSize)
            
            while (isListening && isActive) {
                try {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        addToBuffer(chunk)
                    }
                } catch (e: Exception) {
                    if (isListening) {
                        Log.e(TAG, "Error reading audio", e)
                    }
                }
            }
        }
    }
    
    private fun addToBuffer(chunk: ByteArray) {
        synchronized(bufferLock) {
            audioBuffer.add(chunk)
            currentBufferSize += chunk.size
            
            // Keep buffer at max size (rolling window)
            while (currentBufferSize > MAX_BUFFER_SIZE && audioBuffer.isNotEmpty()) {
                val removed = audioBuffer.removeAt(0)
                currentBufferSize -= removed.size
            }
        }
    }
    
    /**
     * Main detection loop - checks for trigger sound periodically
     */
    private fun startTriggerDetectionLoop() {
        processingJob = listenerScope.launch {
            while (isListening && isActive) {
                try {
                    delay(500)
                    
                    val currentTime = System.currentTimeMillis()
                    if (!isProcessing && 
                        (currentTime - lastTriggerCheckTime) >= TRIGGER_CHECK_INTERVAL_MS) {
                        lastTriggerCheckTime = currentTime
                        isProcessing = true
                        checkForTrigger()
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in detection loop", e)
                }
            }
        }
    }
    
    /**
     * Check if trigger sound is present in current audio buffer
     */
    private suspend fun checkForTrigger() {
        withContext(Dispatchers.IO) {
            try {
                val triggerBuf = triggerBuffer ?: run {
                    isProcessing = false
                    return@withContext
                }
                
                // Get current audio data
                val pcmData: ByteArray
                synchronized(bufferLock) {
                    if (audioBuffer.isEmpty()) {
                        isProcessing = false
                        return@withContext
                    }
                    
                    val totalSize = audioBuffer.sumOf { it.size }
                    pcmData = ByteArray(totalSize)
                    var offset = 0
                    for (chunk in audioBuffer) {
                        System.arraycopy(chunk, 0, pcmData, offset, chunk.size)
                        offset += chunk.size
                    }
                }
                
                // Resample captured audio to fingerprint sample rate
                val sourceBuffer = ByteBuffer.allocateDirect(pcmData.size)
                sourceBuffer.order(ByteOrder.LITTLE_ENDIAN)
                sourceBuffer.put(pcmData)
                sourceBuffer.rewind()
                
                val resampledSize = ((FINGERPRINT_SAMPLE_RATE.toDouble() / SAMPLE_RATE.toDouble()) * pcmData.size).toInt()
                val resampledBuffer = ByteBuffer.allocateDirect(resampledSize)
                resampledBuffer.order(ByteOrder.LITTLE_ENDIAN)
                
                val resampler = NativeResampler()
                try {
                    resampler.create(SAMPLE_RATE, FINGERPRINT_SAMPLE_RATE, 2048, 1)
                    val outputLength = resampler.resampleEx(sourceBuffer, resampledBuffer, sourceBuffer.remaining())
                    
                    if (outputLength <= 0) {
                        isProcessing = false
                        return@withContext
                    }
                    
                    resampledBuffer.rewind()
                    val capturedSampleCount = outputLength / 2
                    
                    // Compare with trigger sound using SDK
                    triggerBuf.rewind()
                    val similarity = TuneURLSDK.calculateSimilarity(
                        resampledBuffer, capturedSampleCount,
                        triggerBuf, triggerSampleCount
                    )
                    
                    Log.d(TAG, "Trigger similarity: ${(similarity * 100).toInt()}%")
                    
                    if (similarity >= TRIGGER_SIMILARITY_THRESHOLD) {
                        Log.d(TAG, "================================================")
                        Log.d(TAG, "🎯 TRIGGER DETECTED! Similarity: ${(similarity * 100).toInt()}%")
                        Log.d(TAG, "Capturing additional audio for better fingerprinting...")
                        Log.d(TAG, "================================================")
                        
                        // Start capturing more audio after trigger
                        isCapturingAfterTrigger = true
                        captureStartTime = System.currentTimeMillis()
                        
                        // Wait for additional audio capture
                        delay(CAPTURE_DURATION_MS)
                        isCapturingAfterTrigger = false
                        
                        // Now extract fingerprint from the full buffer
                        val fullPcmData: ByteArray
                        synchronized(bufferLock) {
                            val totalSize = audioBuffer.sumOf { it.size }
                            fullPcmData = ByteArray(totalSize)
                            var offset = 0
                            for (chunk in audioBuffer) {
                                System.arraycopy(chunk, 0, fullPcmData, offset, chunk.size)
                                offset += chunk.size
                            }
                        }
                        
                        Log.d(TAG, "Captured ${fullPcmData.size} bytes (${fullPcmData.size / (SAMPLE_RATE * 2)} seconds)")
                        
                        // Resample full captured audio
                        val fullSourceBuffer = ByteBuffer.allocateDirect(fullPcmData.size)
                        fullSourceBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        fullSourceBuffer.put(fullPcmData)
                        fullSourceBuffer.rewind()
                        
                        val fullResampledSize = ((FINGERPRINT_SAMPLE_RATE.toDouble() / SAMPLE_RATE.toDouble()) * fullPcmData.size).toInt()
                        val fullResampledBuffer = ByteBuffer.allocateDirect(fullResampledSize)
                        fullResampledBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        
                        val fullResampler = NativeResampler()
                        try {
                            fullResampler.create(SAMPLE_RATE, FINGERPRINT_SAMPLE_RATE, 2048, 1)
                            val fullOutputLength = fullResampler.resampleEx(fullSourceBuffer, fullResampledBuffer, fullSourceBuffer.remaining())
                            
                            if (fullOutputLength > 0) {
                                fullResampledBuffer.rewind()
                                val fullSampleCount = fullOutputLength / 2
                                
                                val fingerprintBytes = TuneURLSDK.extractFingerprintFromBuffer(fullResampledBuffer, fullSampleCount)
                                
                                if (fingerprintBytes != null) {
                                    Log.d(TAG, "Fingerprint extracted: ${fingerprintBytes.size} bytes from $fullSampleCount samples")
                                    val fingerprintString = fingerprintBytes.joinToString(",") {
                                        (it.toInt() and 0xff).toString()
                                    }
                                    searchFingerprintViaSDK(fingerprintString)
                                }
                            }
                        } finally {
                            fullResampler.destroy()
                        }
                    }
                    
                } finally {
                    resampler.destroy()
                }
                
                isProcessing = false
                
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for trigger: ${e.message}", e)
                isProcessing = false
            }
        }
    }

    private fun searchFingerprintViaSDK(fingerprint: String) {
        try {
            val intent = Intent(context, APIService::class.java).apply {
                putExtra(Constants.TUNEURL_ACTION, Constants.ACTION_SEARCH_FINGERPRINT)
                putExtra(Constants.FINGERPRINT, fingerprint)
            }
            context.startService(intent)
            Log.d(TAG, "APIService started for fingerprint search")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SDK search: ${e.message}", e)
        }
    }
    
    private inner class SearchResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isListening) return
            
            when (intent?.action) {
                Constants.SEARCH_FINGERPRINT_RESULT_RECEIVED -> {
                    val resultJson = intent.getStringExtra(Constants.TUNEURL_RESULT)
                    handleSearchSuccess(resultJson)
                }
                Constants.SEARCH_FINGERPRINT_RESULT_ERROR -> {
                    val errorJson = intent.getStringExtra(Constants.TUNEURL_RESULT)
                    Log.e(TAG, "Search error: $errorJson")
                }
            }
        }
    }

    private fun handleSearchSuccess(resultJson: String?) {
        try {
            if (resultJson == null) {
                Log.d(TAG, "No match found (null result)")
                return
            }
            
            val jsonObject = JsonParser.parseString(resultJson).asJsonObject
            val resultArray = jsonObject.getAsJsonArray("result")
            
            if (resultArray != null && resultArray.size() > 0) {
                val firstResult = resultArray[0].asJsonObject
                
                val matchId = firstResult.get("id")?.asString ?: ""
                val matchName = firstResult.get("name")?.asString ?: ""
                val matchPercentage = firstResult.get("matchPercentage")?.asFloat ?: 0f
                val matchInfo = firstResult.get("info")?.asString ?: ""
                
                // Lower threshold since we already detected trigger
                val isValidMatch = matchId.isNotEmpty() &&
                        matchName.isNotEmpty() &&
                        matchPercentage >= MIN_MATCH_PERCENTAGE &&
                        matchInfo.isNotEmpty()
                
                Log.d(TAG, "================================================")
                Log.d(TAG, "API Match: $matchName")
                Log.d(TAG, "Match %: $matchPercentage (threshold: $MIN_MATCH_PERCENTAGE%)")
                Log.d(TAG, "Valid: $isValidMatch")
                Log.d(TAG, "================================================")
                
                if (isValidMatch) {
                    val match = TuneURLMatch(
                        id = matchId,
                        name = matchName,
                        description = firstResult.get("description")?.asString ?: "",
                        info = matchInfo,
                        matchPercentage = matchPercentage,
                        type = firstResult.get("type")?.asString ?: "open_page",
                        time = null,
                        date = TimeUtils.getCurrentTimeAsFormattedString()
                    )
                    
                    Log.d(TAG, "✓ VALID MATCH - Triggering engagement!")
                    
                    listenerScope.launch(Dispatchers.Main) {
                        onMatchDetected?.invoke(match)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing search result: ${e.message}", e)
        }
    }
    
    // Audio decoding helpers
    private fun getDecodedSampleRate(filePath: String): Int {
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(filePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    return format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sample rate", e)
        } finally {
            extractor?.release()
        }
        return 44100
    }
    
    private fun decodeMp3ToPcm(filePath: String): ByteArray? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(filePath)

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex < 0) return null

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val pcmDataList = mutableListOf<ByteArray>()
            var isEOS = false

            while (!isEOS) {
                val inputBufferId = codec.dequeueInputBuffer(10000)
                if (inputBufferId >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferId)
                    val sampleSize = extractor.readSampleData(inputBuffer!!, 0)

                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferId >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferId)
                    if (bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer?.get(chunk)
                        pcmDataList.add(chunk)
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }

            val totalSize = pcmDataList.sumOf { it.size }
            val pcmData = ByteArray(totalSize)
            var offset = 0
            for (chunk in pcmDataList) {
                System.arraycopy(chunk, 0, pcmData, offset, chunk.size)
                offset += chunk.size
            }

            return pcmData

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding MP3", e)
            return null
        } finally {
            codec?.stop()
            codec?.release()
            extractor?.release()
        }
    }
    
    private fun convertToMono(stereoData: ByteArray): ByteArray {
        var resultLength = stereoData.size / 2
        if ((resultLength and 1) != 0) {
            resultLength -= 1
        }

        val monoData = ByteArray(resultLength)
        var dstIndex = 0
        var i = 0
        while (i < resultLength && dstIndex + 3 < stereoData.size) {
            monoData[i] = stereoData[dstIndex]
            monoData[i + 1] = stereoData[dstIndex + 1]
            dstIndex += 4
            i += 2
        }

        return monoData
    }
    
    fun release() {
        stopListening()
        try {
            context.unregisterReceiver(searchResultReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        listenerScope.cancel()
    }
}
