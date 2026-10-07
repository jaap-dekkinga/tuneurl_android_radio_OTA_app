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

    // Local trigger search. The trigger is slid across the most recent
    // TRIGGER_SEARCH_WINDOW_SECONDS of microphone audio in steps of
    // TRIGGER_SLIDE_HOP_SECONDS, and each trigger-length slice is compared
    // with the trigger on its own.
    //
    // Why: the native compare truncates both fingerprints to the size of the
    // smaller one, so comparing the whole 10 s buffer against the 1.4 s
    // trigger only ever looked at the OLDEST ~1.3 s of the buffer. A trigger
    // was therefore only noticed ~9 s after it played. Slicing the audio to
    // trigger length makes the truncation harmless.
    private val TRIGGER_SEARCH_WINDOW_SECONDS = 3.0
    private val TRIGGER_SLIDE_HOP_SECONDS = 0.25

    // With the sliding search one trigger stays visible for ~3.5 s, i.e. on
    // several consecutive checks. Ignore further hits for this long after a
    // detection so one trigger is recognised once.
    private val TRIGGER_COOLDOWN_MS = 6_000L

    // Content capture after a trigger: same arithmetic as the stream path
    // (TuneURLDetector) and iOS. The capture starts TRIGGER_SOUND_DURATION
    // after the trigger START and is IDENTIFIABLE_AUDIO_DURATION long.
    private val TRIGGER_SOUND_DURATION_SECONDS = 2.0
    private val IDENTIFIABLE_AUDIO_DURATION_SECONDS = 5.0
    private val CONTENT_CAPTURE_TIMEOUT_MS = 10_000L

    // After a first hit, look once more this much later (see checkForTrigger).
    private val TRIGGER_RELOOK_DELAY_MS = 1_000L
    
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
    
    // Total microphone bytes recorded since listening started (guarded by
    // bufferLock). Lets us address audio by absolute position, so "the 5 s
    // after the trigger" can be cut out exactly, independent of wall-clock
    // jitter. The rolling buffer holds [totalBytesCaptured - currentBufferSize,
    // totalBytesCaptured).
    private var totalBytesCaptured = 0L

    // When the last trigger was recognised locally (for TRIGGER_COOLDOWN_MS).
    @Volatile private var lastTriggerDetectedAt = 0L

    // Trigger sound data (pre-loaded and resampled)
    private var triggerBuffer: ByteBuffer? = null
    private var triggerSampleCount = 0
    
    private var lastTriggerCheckTime = 0L
    private var isProcessing = false
    
    var onMatchDetected: ((TuneURLMatch) -> Unit)? = null
    private val searchResultReceiver = SearchResultReceiver()

    // Issue 1 fix: track whether the receiver is currently registered, so
    // stopListening can unregister it eagerly (preventing cross-talk with
    // the stream-detector broadcast) and startListening can re-register.
    @Volatile private var receiverRegistered = false

    init {
        try {
            ensureSearchReceiverRegistered()

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

        // Issue 1 fix: stopListening() unregisters the receiver to avoid
        // cross-talk with the stream detector. Re-register before we start
        // again so we can hear our own API results.
        ensureSearchReceiverRegistered()
        
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
                totalBytesCaptured = 0L
            }
            lastTriggerDetectedAt = 0L
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

        // Issue 1 fix: cancel ALL children of listenerScope, not just the two
        // named jobs. checkForTrigger() spawns nested coroutines on the scope
        // (resampling, post-trigger capture, API search dispatch) — without
        // cancelling them we'd see OTA TuneURL_DIAG lines firing AFTER stop.
        listenerScope.coroutineContext.cancelChildren()
        recordingJob = null
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

        // Issue 1 fix: unregister the broadcast receiver on stop, not just on
        // release. While stopped-but-not-released, the stream detector can be
        // running; without this, an APIService result for the stream detector
        // would also fire OTAListener's receiver (which then calls back into
        // onMatchDetected via the OTA path).
        receiverRegistered = unregisterSearchReceiverIfRegistered()

        Log.d(TAG, "OTA listening stopped")
    }

    /**
     * Re-register the search broadcast receiver. Called from startListening
     * after a previous stopListening cycle un-registered it.
     */
    private fun ensureSearchReceiverRegistered() {
        if (receiverRegistered) return
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
            receiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Error re-registering search receiver", e)
        }
    }

    /**
     * Unregister the receiver if it's currently registered. Returns the new
     * value `receiverRegistered` should hold (always false here, but written
     * this way so the caller's assignment reads cleanly).
     */
    private fun unregisterSearchReceiverIfRegistered(): Boolean {
        if (!receiverRegistered) return false
        return try {
            context.unregisterReceiver(searchResultReceiver)
            false
        } catch (e: IllegalArgumentException) {
            // Already unregistered — nothing to do.
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering search receiver", e)
            false
        }
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
            totalBytesCaptured += chunk.size
            
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
     * Check whether the trigger sound is present in the most recent microphone
     * audio. Runs entirely on the device; the server is only contacted after
     * a trigger has been recognised here.
     */
    private suspend fun checkForTrigger() {
        withContext(Dispatchers.IO) {
            try {
                val triggerBuf = triggerBuffer ?: return@withContext
                if (triggerSampleCount <= 0) return@withContext

                // One trigger is visible on several consecutive checks.
                val now = System.currentTimeMillis()
                if (now - lastTriggerDetectedAt < TRIGGER_COOLDOWN_MS) return@withContext

                val firstLook = searchForTrigger(triggerBuf, "") ?: return@withContext
                if (firstLook.similarity < TRIGGER_SIMILARITY_THRESHOLD) {
                    return@withContext
                }

                lastTriggerDetectedAt = now

                // The first hit often catches the trigger while it is still
                // only partly inside the window, which places its start
                // imprecisely. Look once more when it is fully in view and
                // keep whichever look matched better. This costs no time: the
                // audio that follows the trigger still has to be recorded.
                delay(TRIGGER_RELOOK_DELAY_MS)
                val secondLook = searchForTrigger(triggerBuf, " (re-look)")
                val hit = if (secondLook != null && secondLook.similarity > firstLook.similarity) {
                    secondLook
                } else {
                    firstLook
                }

                val triggerStartPos = hit.triggerStartPos
                val contentStartPos = triggerStartPos + secondsToBytes(TRIGGER_SOUND_DURATION_SECONDS)
                val contentEndPos = contentStartPos + secondsToBytes(IDENTIFIABLE_AUDIO_DURATION_SECONDS)

                Log.d(TAG, "================================================")
                Log.d(TAG, "🎯 TRIGGER DETECTED! Similarity: ${(hit.similarity * 100).toInt()}%")
                Log.d(
                    TAG,
                    "Trigger started %.2fs ago; capturing %.1fs of audio after it".format(
                        (capturedBytes() - triggerStartPos).toDouble() / BYTES_PER_SECOND,
                        IDENTIFIABLE_AUDIO_DURATION_SECONDS
                    )
                )
                Log.d(TAG, "================================================")

                // Wait until the audio that follows the trigger has been
                // recorded (delay() is cancellable, so stopListening ends this).
                val deadline = System.currentTimeMillis() + CONTENT_CAPTURE_TIMEOUT_MS
                while (isListening &&
                    capturedBytes() < contentEndPos &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(100)
                }
                if (!isListening) return@withContext

                val contentPcm = copyAudioRange(contentStartPos, contentEndPos)
                if (contentPcm == null) {
                    Log.w(TAG, "Audio after the trigger is not available — skipping lookup")
                    return@withContext
                }

                val (content, contentSamples) = resampleToFingerprintRate(contentPcm)
                    ?: return@withContext

                val fingerprintBytes = TuneURLSDK.extractFingerprintFromBuffer(content, contentSamples)
                if (fingerprintBytes != null) {
                    Log.d(TAG, "Fingerprint extracted: ${fingerprintBytes.size} bytes from $contentSamples samples")
                    val fingerprintString = fingerprintBytes.joinToString(",") {
                        (it.toInt() and 0xff).toString()
                    }
                    searchFingerprintViaSDK(fingerprintString)
                }
            } catch (e: CancellationException) {
                // Issue 2 fix: cooperative cancellation must propagate so the
                // outer detection loop (and any code awaiting this scope) sees
                // the cancellation rather than continuing as if nothing happened.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for trigger: ${e.message}", e)
            } finally {
                isProcessing = false
            }
        }
    }

    /** One local search: how well the trigger matched and where it starts. */
    private data class TriggerLook(
        val similarity: Float,
        /** Absolute position (bytes since listening started) of the trigger start. */
        val triggerStartPos: Long
    )

    /**
     * Slide the trigger across the most recent TRIGGER_SEARCH_WINDOW_SECONDS
     * of microphone audio and log the result. Returns null when there isn't
     * enough audio yet or the audio couldn't be prepared.
     */
    private fun searchForTrigger(triggerBuf: ByteBuffer, logSuffix: String): TriggerLook? {
        // Most recent audio only, not the whole 10 s buffer.
        val (pcmData, endPos) = copyRecentAudio(TRIGGER_SEARCH_WINDOW_SECONDS) ?: return null
        val (window, windowSamples) = resampleToFingerprintRate(pcmData) ?: return null
        if (windowSamples < triggerSampleCount) {
            // Less than one trigger length recorded so far.
            return null
        }

        // The local trigger gate is a v2 feature and must NOT inherit whatever
        // the singleton was last set to (which can be v1 if the stream path
        // ran earlier in the session), so the version is passed explicitly.
        val hopSamples = (TRIGGER_SLIDE_HOP_SECONDS * FINGERPRINT_SAMPLE_RATE).toInt()
        val match = TuneURLSDK.slideSimilarityAt(
            window, windowSamples,
            triggerBuf, triggerSampleCount,
            hopSamples,
            TuneURLSDK.FORMAT_VERSION_V2
        )

        val similarity = match?.similarity ?: -1f
        val windowSeconds = windowSamples.toDouble() / FINGERPRINT_SAMPLE_RATE
        // Where the trigger starts inside the window (slice offset refined by
        // the position the compare reports in that slice).
        val triggerStartSeconds = match?.startSeconds ?: 0.0

        // Unconditional diagnostic, one line per search. The prefix is
        // unchanged so existing log filters keep working.
        Log.i(
            "TuneURL_DIAG",
            "OTA local v2 similarity=%.4f (threshold=%.2f) triggerStart=%.2fs window=%.2fs positions=%d%s"
                .format(
                    similarity, TRIGGER_SIMILARITY_THRESHOLD,
                    if (similarity > 0f) triggerStartSeconds else -1.0,
                    windowSeconds, match?.positionsChecked ?: 0, logSuffix
                )
        )

        if (match == null) return null
        val windowStartPos = endPos - pcmData.size
        return TriggerLook(similarity, windowStartPos + secondsToBytes(triggerStartSeconds))
    }

    private val BYTES_PER_SECOND = SAMPLE_RATE * 2  // 16-bit mono

    /** Whole 16-bit samples only, so positions never split a sample. */
    private fun secondsToBytes(seconds: Double): Long = (seconds * SAMPLE_RATE).toLong() * 2

    private fun capturedBytes(): Long = synchronized(bufferLock) { totalBytesCaptured }

    /**
     * Copy the most recent [seconds] of microphone audio (less if that much
     * hasn't been recorded yet). Returns the audio and the absolute position
     * of its end, or null if nothing has been recorded.
     */
    private fun copyRecentAudio(seconds: Double): Pair<ByteArray, Long>? {
        synchronized(bufferLock) {
            val endPos = totalBytesCaptured and 1L.inv()
            val bufferStart = totalBytesCaptured - currentBufferSize
            val available = (endPos - bufferStart) and 1L.inv()
            val wanted = minOf(secondsToBytes(seconds), available)
            if (wanted <= 0L) return null
            val data = copyAudioRange(endPos - wanted, endPos) ?: return null
            return Pair(data, endPos)
        }
    }

    /**
     * Copy microphone audio between two absolute byte positions (counted from
     * the start of listening). Returns null if part of that range has already
     * left the rolling buffer or has not been recorded yet.
     */
    private fun copyAudioRange(fromPos: Long, toPos: Long): ByteArray? {
        synchronized(bufferLock) {
            val bufferStart = totalBytesCaptured - currentBufferSize
            if (toPos <= fromPos || fromPos < bufferStart || toPos > totalBytesCaptured) {
                return null
            }
            val out = ByteArray((toPos - fromPos).toInt())
            var chunkStart = bufferStart
            for (chunk in audioBuffer) {
                val chunkEnd = chunkStart + chunk.size
                if (chunkEnd > fromPos && chunkStart < toPos) {
                    val from = maxOf(fromPos, chunkStart)
                    val to = minOf(toPos, chunkEnd)
                    System.arraycopy(
                        chunk, (from - chunkStart).toInt(),
                        out, (from - fromPos).toInt(),
                        (to - from).toInt()
                    )
                }
                chunkStart = chunkEnd
                if (chunkStart >= toPos) break
            }
            return out
        }
    }

    /**
     * Resample 44.1 kHz 16-bit mono PCM to the fingerprint sample rate.
     * Returns a direct buffer and the number of samples in it.
     */
    private fun resampleToFingerprintRate(pcmData: ByteArray): Pair<ByteBuffer, Int>? {
        val sourceBuffer = ByteBuffer.allocateDirect(pcmData.size)
        sourceBuffer.order(ByteOrder.LITTLE_ENDIAN)
        sourceBuffer.put(pcmData)
        sourceBuffer.rewind()

        val resampledSize =
            ((FINGERPRINT_SAMPLE_RATE.toDouble() / SAMPLE_RATE.toDouble()) * pcmData.size).toInt() and 1.inv()
        if (resampledSize <= 0) return null
        val resampledBuffer = ByteBuffer.allocateDirect(resampledSize)
        resampledBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val resampler = NativeResampler()
        try {
            resampler.create(SAMPLE_RATE, FINGERPRINT_SAMPLE_RATE, 2048, 1)
            val outputLength = resampler.resampleEx(sourceBuffer, resampledBuffer, sourceBuffer.remaining())
            if (outputLength <= 0) return null
            resampledBuffer.rewind()
            return Pair(resampledBuffer, outputLength / 2)
        } finally {
            resampler.destroy()
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
        // stopListening already cancels children and unregisters the receiver.
        // Cancelling the scope itself is the final hard kill — after this,
        // no new coroutines can be launched on listenerScope.
        listenerScope.cancel()
    }
}
