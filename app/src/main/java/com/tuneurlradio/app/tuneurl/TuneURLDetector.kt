package com.tuneurlradio.app.tuneurl

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.core.content.ContextCompat
import com.dekidea.tuneurl.NativeResampler
import com.dekidea.tuneurl.TuneURLSDK
import com.dekidea.tuneurl.service.APIService
import com.dekidea.tuneurl.util.Constants
import com.google.gson.JsonParser
import com.tuneurlradio.app.R
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class TuneURLDetector(private val context: Context) : Constants {

    private val TAG = "TuneURLDetector"
    private val dataCapture: StreamDataCapture = StreamDataCapture(context.cacheDir)
    private val detectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var detectionJob: Job? = null
    @Volatile private var isDetecting = false
    private var currentStreamUrl: String? = null

    private val DETECTION_INTERVAL_MS = 500L
    private val FINGERPRINT_SAMPLE_RATE = 10240
    private val CONTINUOUS_FINGERPRINT_INTERVAL_MS = 2000L  // Match iOS: 2 seconds
    private val MIN_MATCH_PERCENTAGE = 25f

    // Local trigger gate window: the last 4 s of stream audio, checked every
    // 2 s (same as iOS StreamDetector.triggerWindowDuration).
    private val ANALYSIS_WINDOW_SECONDS = 4.0
    private val ANALYSIS_WINDOW_SAMPLES = (ANALYSIS_WINDOW_SECONDS * FINGERPRINT_SAMPLE_RATE).toInt()

    // iOS parity (StreamDetector.recognizedTrigger): once the trigger is
    // found, the server is NOT sent the window that contains the trigger.
    // It is sent the 5 s of audio that FOLLOWS the trigger, which is the part
    // that identifies the specific TuneURL. The trigger itself is identical
    // in every TuneURL, so fingerprinting it makes the server return an
    // arbitrary entry. These are the same constants iOS uses.
    private val TRIGGER_SOUND_DURATION_SECONDS = 2.0
    private val IDENTIFIABLE_AUDIO_DURATION_SECONDS = 5.0
    private val IDENTIFIABLE_AUDIO_SAMPLES = (IDENTIFIABLE_AUDIO_DURATION_SECONDS * FINGERPRINT_SAMPLE_RATE).toInt()

    // Only the tail of the ~15 s capture buffer is resampled; this must cover
    // the larger of the two windows above, plus a little margin.
    private val DECODE_TAIL_SECONDS = 6.0

    // Local v2 trigger gate. Mirrors iOS local detection.
    // The gate runs v2 explicitly; the server fingerprint stays on v1. See
    // v2_context_android.md / v2_architecture_android.md for the design.
    private val TRIGGER_SIMILARITY_THRESHOLD = 0.10f  // Match iOS gate (0.1)
    private var triggerBuffer: ByteBuffer? = null
    private var triggerSampleCount = 0

    private var lastFingerprintTime = 0L
    @Volatile private var recordingTuneURL = false

    // A trigger was found and we are waiting for the post-trigger audio to
    // arrive before fingerprinting it. While this is active the gate is not
    // re-run, so one trigger produces exactly one server request (the
    // trigger stays inside the 4 s window for ~2 ticks).
    @Volatile private var pendingLookup: Job? = null

    // Cooldown after a SUCCESSFUL server match: lastServerCallTime is set only
    // inside handleSearchSuccess when the server returned a
    // >= MIN_MATCH_PERCENTAGE match. The manager-level MATCH_COOLDOWN_MS
    // handles dedupe of the engagement sheet once a real match has fired.
    private val SERVER_CALL_COOLDOWN_MS = 8_000L
    @Volatile private var lastServerCallTime = 0L

    private var onMatchDetected: ((TuneURLMatch) -> Unit)? = null
    private val searchResultReceiver = SearchResultReceiver()

    /** Decoded PCM plus the format the DECODER actually produced. */
    private class DecodedAudio(
        val samples: ShortArray,   // interleaved 16-bit
        val sampleRate: Int,
        val channelCount: Int
    )

    init {
        try {
            val searchFilter = IntentFilter().apply {
                addAction(Constants.SEARCH_FINGERPRINT_RESULT_RECEIVED)
                addAction(Constants.SEARCH_FINGERPRINT_RESULT_ERROR)
            }

            // The result broadcast is sent by this app's own APIService and is
            // not consumed by any other app. NOT_EXPORTED is the correct flag.
            // On API < 33 the flag overload doesn't exist, so we fall back.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    searchResultReceiver,
                    searchFilter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(searchResultReceiver, searchFilter)
            }

            Log.d(TAG, "================================================")
            Log.d(TAG, "TuneURLDetector initialized")
            Log.d(TAG, "Broadcast receivers registered:")
            Log.d(TAG, "- ${Constants.SEARCH_FINGERPRINT_RESULT_RECEIVED}")
            Log.d(TAG, "- ${Constants.SEARCH_FINGERPRINT_RESULT_ERROR}")
            Log.d(TAG, "================================================")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receivers", e)
        }
    }

    fun startDetection(streamUrl: String, onMatch: (TuneURLMatch) -> Unit) {
        Log.d(TAG, "================================================")
        Log.d(TAG, "startDetection called")
        Log.d(TAG, "Stream URL: $streamUrl")
        Log.d(TAG, "================================================")

        // DIAG-V1-ROLLBACK: force v1 emission for server compatibility
        // (the AWS search-fingerprint endpoint expects v1 fingerprints)
        TuneURLSDK.setFormatVersion(1)
        Log.i(TAG, "DIAG-V1-ROLLBACK: format version forced to v${TuneURLSDK.getFormatVersion()}")

        // Load the bundled trigger sound for the local v2 gate. The gate uses
        // TuneURLSDK.findSimilarityAt(..., FORMAT_VERSION_V2) at the call
        // site, so it is independent of the singleton version above.
        if (triggerBuffer == null) {
            loadTriggerSound()
        }

        onMatchDetected = onMatch
        isDetecting = true
        currentStreamUrl = streamUrl

        dataCapture.startCapture(streamUrl)
        scheduleDetectionTask()

        Log.d(TAG, "TuneURL detection started (using raw stream capture)")
        Log.d(TAG, "Detection interval: ${DETECTION_INTERVAL_MS}ms")
        Log.d(TAG, "Fingerprint interval: ${CONTINUOUS_FINGERPRINT_INTERVAL_MS}ms")
        Log.d(TAG, "Local v2 trigger gate threshold: $TRIGGER_SIMILARITY_THRESHOLD")
    }

    fun stopDetection() {
        Log.d(TAG, "Stopping TuneURL detection...")
        isDetecting = false
        detectionJob?.cancel()
        detectionJob = null
        pendingLookup?.cancel()
        pendingLookup = null
        dataCapture.stopCapture()
        currentStreamUrl = null
        onMatchDetected = null
        Log.d(TAG, "TuneURL detection stopped")
    }

    private fun scheduleDetectionTask() {
        detectionJob?.cancel()

        Log.d(TAG, "Scheduling detection task...")

        detectionJob = detectorScope.launch {
            Log.d(TAG, "Detection task started")
            while (isDetecting) {
                try {
                    delay(DETECTION_INTERVAL_MS)
                    if (!isDetecting) break
                    processAudioBuffer()
                } catch (e: CancellationException) {
                    Log.d(TAG, "Detection task cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in detection task: ${e.message}", e)
                }
            }
            Log.d(TAG, "Detection task ended")
        }
    }

    private suspend fun processAudioBuffer() {
        try {
            if (recordingTuneURL) return

            // A trigger was already found; its post-trigger audio is being
            // collected. Don't re-detect the same trigger.
            if (pendingLookup?.isActive == true) return

            val currentTime = System.currentTimeMillis()
            if ((currentTime - lastFingerprintTime) >= CONTINUOUS_FINGERPRINT_INTERVAL_MS) {
                lastFingerprintTime = currentTime
                recordingTuneURL = true
                checkForTrigger()
            }
        } catch (e: CancellationException) {
            // Structured cooperative cancellation, not an error.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio buffer", e)
        } finally {
            recordingTuneURL = false
        }
    }

    /**
     * Step 1 (every 2 s): look for the trigger sound in the last 4 s of audio.
     * If it's there, work out how long ago it played and schedule step 2.
     */
    private suspend fun checkForTrigger() {
        withContext(Dispatchers.IO) {
            val recent = captureRecentAudio(DECODE_TAIL_SECONDS) ?: return@withContext

            val windowSamples = min(recent.size, ANALYSIS_WINDOW_SAMPLES)
            if (windowSamples < FINGERPRINT_SAMPLE_RATE) {
                Log.d(TAG, "Only ${recent.size} samples buffered — waiting for more audio")
                return@withContext
            }
            val window = recent.copyOfRange(recent.size - windowSamples, recent.size)
            val windowSeconds = windowSamples.toDouble() / FINGERPRINT_SAMPLE_RATE

            val tBuf = triggerBuffer
            val tLen = triggerSampleCount
            if (tBuf == null || tLen <= 0) {
                // No trigger asset: we can't locate a trigger, so fall back to
                // looking up the most recent audio directly (old behaviour).
                // The server flow is the safety net.
                Log.w(TAG, "Trigger buffer not loaded — bypassing local gate this cycle")
                scheduleServerLookup(0.0)
                return@withContext
            }

            val match = TuneURLSDK.findSimilarityAt(
                shortsToDirectBuffer(window), windowSamples,
                tBuf, tLen,
                TuneURLSDK.FORMAT_VERSION_V2
            )

            // Unconditional diagnostic, same fields as the iOS TuneURL_DIAG line.
            // (mostSimilarStartTime is meaningless when nothing matched.)
            val hasMatch = match != null && match.similarity > 0f
            Log.i(
                "TuneURL_DIAG",
                "local v2 similarity=%.4f score=%.2f mostSimilarStartTime=%.3f (threshold=%.2f) at t=%d".format(
                    match?.similarity ?: -1f,
                    match?.score ?: -1f,
                    if (hasMatch) match!!.mostSimilarStartTime else -1f,
                    TRIGGER_SIMILARITY_THRESHOLD,
                    System.currentTimeMillis()
                )
            )

            if (match == null || match.similarity < TRIGGER_SIMILARITY_THRESHOLD) {
                // No trigger in this window — don't bother the server.
                return@withContext
            }

            // Cooldown after a confirmed good match (see handleSearchSuccess).
            val sinceLast = System.currentTimeMillis() - lastServerCallTime
            if (sinceLast < SERVER_CALL_COOLDOWN_MS) {
                Log.d(
                    TAG,
                    "Server-call cooldown active (${SERVER_CALL_COOLDOWN_MS - sinceLast}ms remaining since last GOOD match) — skipping"
                )
                return@withContext
            }

            // Same arithmetic as iOS StreamDetector.recognizedTrigger:
            // how long ago did the trigger start, how much post-trigger audio
            // do we already have, and how long until we have 5 s of it?
            val timeSinceTriggerStart = windowSeconds - match.mostSimilarStartTime
            val recordedAfterTrigger = timeSinceTriggerStart - TRIGGER_SOUND_DURATION_SECONDS
            val remainingToRecord = max(IDENTIFIABLE_AUDIO_DURATION_SECONDS - recordedAfterTrigger, 0.0)

            Log.d(
                TAG,
                "Local v2 gate PASSED (similarity=%.4f). Trigger started %.2fs ago; fingerprinting post-trigger audio in %.2fs".format(
                    match.similarity, timeSinceTriggerStart, remainingToRecord
                )
            )

            scheduleServerLookup(remainingToRecord)
        }
    }

    /**
     * Step 2: after [delaySeconds], fingerprint the most recent 5 s of audio
     * (the audio that followed the trigger) with v1 and send it to the server.
     */
    private fun scheduleServerLookup(delaySeconds: Double) {
        if (pendingLookup?.isActive == true) return

        pendingLookup = detectorScope.launch {
            try {
                delay((delaySeconds * 1000).toLong())
                if (!isDetecting) return@launch

                val recent = captureRecentAudio(DECODE_TAIL_SECONDS) ?: return@launch
                val sampleCount = min(recent.size, IDENTIFIABLE_AUDIO_SAMPLES)
                if (sampleCount < IDENTIFIABLE_AUDIO_SAMPLES) {
                    Log.w(TAG, "Only $sampleCount samples of post-trigger audio available (wanted $IDENTIFIABLE_AUDIO_SAMPLES)")
                }
                val segment = recent.copyOfRange(recent.size - sampleCount, recent.size)

                // Uses the SDK singleton version, forced to v1 in startDetection
                // (the server expects v1).
                val fingerprintBytes = TuneURLSDK.extractFingerprintFromBuffer(
                    shortsToDirectBuffer(segment), sampleCount
                )

                if (fingerprintBytes != null) {
                    val fingerprintString = fingerprintBytes.joinToString(",") {
                        (it.toInt() and 0xff).toString()
                    }

                    Log.d(TAG, "================================================")
                    Log.d(TAG, "FINGERPRINT EXTRACTED (post-trigger audio)")
                    Log.d(TAG, "Audio: ${sampleCount.toDouble() / FINGERPRINT_SAMPLE_RATE}s, fingerprint: ${fingerprintBytes.size} bytes")
                    Log.d(TAG, "Searching via API...")
                    Log.d(TAG, "================================================")

                    searchFingerprintViaSDK(fingerprintString)
                } else {
                    Log.w(TAG, "Fingerprint extraction returned null")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in post-trigger lookup: ${e.message}", e)
            }
        }
    }

    /**
     * Decode the capture buffer and return the last [seconds] of audio as
     * mono 16-bit PCM at FINGERPRINT_SAMPLE_RATE. The end of the returned
     * array is the newest audio received from the stream.
     */
    private fun captureRecentAudio(seconds: Double): ShortArray? {
        val file = dataCapture.saveCurrentBufferToFile() ?: run {
            Log.w(TAG, "No stream data available yet")
            return null
        }

        val decoded = try {
            decodeToPcm(file.absolutePath)
        } finally {
            file.delete()
        }

        if (decoded == null || decoded.samples.isEmpty()) {
            Log.e(TAG, "Failed to decode stream buffer")
            return null
        }

        val mono = downmixToMono(decoded.samples, decoded.channelCount)

        // Resample only the tail we need (plus a little so the filter's
        // start-up edge falls outside the window we analyse).
        val tailFrames = min(mono.size, ceil((seconds + 0.25) * decoded.sampleRate).toInt())
        val tail = mono.copyOfRange(mono.size - tailFrames, mono.size)

        val resampled = resampleToFingerprintRate(tail, decoded.sampleRate) ?: return null
        val keep = min(resampled.size, (seconds * FINGERPRINT_SAMPLE_RATE).toInt())

        Log.d(
            TAG,
            "Decoded ${decoded.samples.size / max(decoded.channelCount, 1)} frames @ ${decoded.sampleRate} Hz, " +
                "${decoded.channelCount} ch -> ${keep} mono samples @ $FINGERPRINT_SAMPLE_RATE Hz"
        )

        return resampled.copyOfRange(resampled.size - keep, resampled.size)
    }

    /**
     * Load the bundled trigger sound (R.raw.trigger_sound, same asset as iOS),
     * decode it, mix to mono, resample to FINGERPRINT_SAMPLE_RATE, and stash
     * the resulting PCM in [triggerBuffer] for the local gate. Uses the same
     * decode/downmix/resample path as the stream so both sides match.
     */
    private fun loadTriggerSound() {
        try {
            Log.d(TAG, "Loading trigger sound for local v2 gate...")

            val triggerFile = File(context.cacheDir, "trigger_sound_detector.mp3")
            if (!triggerFile.exists()) {
                context.resources.openRawResource(R.raw.trigger_sound).use { input ->
                    FileOutputStream(triggerFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val decoded = decodeToPcm(triggerFile.absolutePath)
            if (decoded == null || decoded.samples.isEmpty()) {
                Log.e(TAG, "Failed to decode trigger sound — local gate will be bypassed")
                return
            }

            val mono = downmixToMono(decoded.samples, decoded.channelCount)
            val resampled = resampleToFingerprintRate(mono, decoded.sampleRate)
            if (resampled == null || resampled.isEmpty()) {
                Log.e(TAG, "Trigger resample produced no audio — local gate will be bypassed")
                return
            }

            triggerBuffer = shortsToDirectBuffer(resampled)
            triggerSampleCount = resampled.size

            Log.i(
                TAG,
                "✓ Trigger sound loaded: ${decoded.sampleRate} Hz, ${decoded.channelCount} ch -> " +
                    "$triggerSampleCount samples at $FINGERPRINT_SAMPLE_RATE Hz"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading trigger sound — local gate will be bypassed", e)
        }
    }

    /**
     * Decode a compressed audio file (MP3/AAC) to interleaved 16-bit PCM.
     *
     * The sample rate and channel count are taken from the DECODER's output
     * format, not the container: for HE-AAC the decoder outputs twice the
     * sample rate MediaExtractor reports, and mono streams must not be
     * treated as stereo. The loop also drains the decoder after end of input,
     * so the newest audio at the end of the buffer isn't dropped.
     */
    private fun decodeToPcm(filePath: String): DecodedAudio? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(filePath)

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) {
                Log.e(TAG, "No audio track found in stream buffer")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            // Container values are only a fallback until the decoder reports its
            // real output format.
            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            fun applyOutputFormat(format: MediaFormat) {
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBytes = ByteArrayOutputStream()
            var inputDone = false
            var outputDone = false
            var idleAfterInputDone = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferId = codec.dequeueInputBuffer(10_000)
                    if (inputBufferId >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferId)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputBufferId >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputBufferId)
                            if (outputBuffer != null) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.get(chunk)
                                pcmBytes.write(chunk)
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferId, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        applyOutputFormat(codec.outputFormat)
                    }
                    outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Safety net for decoders that never emit the EOS flag.
                        if (inputDone && ++idleAfterInputDone > 50) {
                            Log.w(TAG, "Decoder did not signal end of stream; stopping drain")
                            outputDone = true
                        }
                    }
                }
            }

            // The output format is authoritative once decoding has run.
            try {
                applyOutputFormat(codec.outputFormat)
            } catch (e: IllegalStateException) {
                // Ignore; keep what INFO_OUTPUT_FORMAT_CHANGED reported.
            }

            val bytes = pcmBytes.toByteArray()
            val samples: ShortArray = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val floats = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
                ShortArray(floats.remaining()) { i ->
                    (floats.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
            } else {
                val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
                ShortArray(shorts.remaining()).also { shorts.get(it) }
            }

            return DecodedAudio(samples, sampleRate, max(channelCount, 1))
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio", e)
            return null
        } finally {
            try { codec?.stop() } catch (e: Exception) { /* already stopped */ }
            codec?.release()
            extractor?.release()
        }
    }

    /**
     * Mix interleaved PCM down to mono by averaging all channels (what
     * AVAudioConverter does on iOS). The previous implementation kept only
     * the left channel and assumed the input was always stereo, which
     * halved the length of mono streams.
     */
    private fun downmixToMono(samples: ShortArray, channelCount: Int): ShortArray {
        if (channelCount <= 1) return samples
        val frames = samples.size / channelCount
        return ShortArray(frames) { frame ->
            var sum = 0
            val base = frame * channelCount
            for (c in 0 until channelCount) {
                sum += samples[base + c]
            }
            (sum / channelCount).toShort()
        }
    }

    /**
     * Resample mono PCM to FINGERPRINT_SAMPLE_RATE with the band-limited
     * native resampler.
     */
    private fun resampleToFingerprintRate(mono: ShortArray, sampleRate: Int): ShortArray? {
        if (mono.isEmpty()) return null
        if (sampleRate == FINGERPRINT_SAMPLE_RATE) return mono.copyOf()

        val sourceBuffer = shortsToDirectBuffer(mono)
        val outputSamples = ceil(mono.size.toDouble() * FINGERPRINT_SAMPLE_RATE / sampleRate).toInt() + 16
        val outputBuffer = ByteBuffer.allocateDirect(outputSamples * 2).order(ByteOrder.nativeOrder())

        val resampler = NativeResampler()
        try {
            resampler.create(sampleRate, FINGERPRINT_SAMPLE_RATE, 2048, 1)
            val outputBytes = resampler.resampleEx(sourceBuffer, outputBuffer, mono.size * 2)
            if (outputBytes <= 0) {
                Log.e(TAG, "Resampling failed ($sampleRate Hz -> $FINGERPRINT_SAMPLE_RATE Hz)")
                return null
            }
            outputBuffer.rewind()
            val shorts = outputBuffer.asShortBuffer()
            return ShortArray(outputBytes / 2).also { shorts.get(it) }
        } finally {
            resampler.destroy()
        }
    }

    /** Native-order direct buffer, as the JNI layer reads int16 in place. */
    private fun shortsToDirectBuffer(samples: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        buffer.asShortBuffer().put(samples)
        buffer.rewind()
        return buffer
    }

    private fun searchFingerprintViaSDK(fingerprint: String) {
        try {
            Log.d(TAG, "Searching fingerprint via SDK...")
            Log.d(TAG, "Fingerprint length: ${fingerprint.length} chars")
            
            // Log the full fingerprint for debugging/curl testing
            Log.d(TAG, "================================================")
            Log.d(TAG, "FINGERPRINT FOR CURL TESTING:")
            Log.d(TAG, "================================================")
            // Split fingerprint into chunks for logcat (max ~4000 chars per log)
            val chunkSize = 3500
            fingerprint.chunked(chunkSize).forEachIndexed { index, chunk ->
                Log.d(TAG, "FP_CHUNK_$index: $chunk")
            }
            Log.d(TAG, "================================================")
            Log.d(TAG, "CURL COMMAND (combine FP_CHUNK_* above into one string):")
            Log.d(TAG, "curl -X POST 'https://pnz3vadc52.execute-api.us-east-2.amazonaws.com/dev/search-fingerprint' \\")
            Log.d(TAG, "  -H 'Content-Type: application/json' \\")
            Log.d(TAG, "  -d '{\"fingerprint\": \"<PASTE_ALL_FP_CHUNKS_HERE>\"}'")
            Log.d(TAG, "================================================")

            val intent = Intent(context, APIService::class.java).apply {
                putExtra(Constants.TUNEURL_ACTION, Constants.ACTION_SEARCH_FINGERPRINT)
                putExtra(Constants.FINGERPRINT, fingerprint)
            }

            context.startService(intent)
            Log.d(TAG, "APIService started, waiting for broadcast result...")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting SDK search: ${e.message}", e)
        }
    }

    private inner class SearchResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "================================================")
            Log.d(TAG, "BROADCAST RECEIVED: ${intent?.action}")
            Log.d(TAG, "================================================")

            when (intent?.action) {
                Constants.SEARCH_FINGERPRINT_RESULT_RECEIVED -> {
                    val resultJson = intent.getStringExtra(Constants.TUNEURL_RESULT)
                    Log.d(TAG, "Search result received")
                    Log.d(TAG, "Result JSON: ${resultJson?.take(500)}...")
                    handleSearchSuccess(resultJson)
                }
                Constants.SEARCH_FINGERPRINT_RESULT_ERROR -> {
                    val errorJson = intent.getStringExtra(Constants.TUNEURL_RESULT)
                    Log.e(TAG, "Search error received: $errorJson")
                }
            }
        }
    }

    private fun handleSearchSuccess(resultJson: String?) {
        try {
            Log.d(TAG, "handleSearchSuccess called")

            if (resultJson == null) {
                Log.d(TAG, "No match found (null result)")
                return
            }

            Log.d(TAG, "Parsing JSON response...")

            val jsonObject = JsonParser.parseString(resultJson).asJsonObject
            val resultArray = jsonObject.getAsJsonArray("result")

            Log.d(TAG, "Result array size: ${resultArray?.size() ?: 0}")

            if (resultArray != null && resultArray.size() > 0) {
                val firstResult = resultArray[0].asJsonObject

                val matchId = firstResult.get("id")?.asString ?: ""
                val matchName = firstResult.get("name")?.asString ?: ""
                val matchPercentage = firstResult.get("matchPercentage")?.asFloat ?: 0f
                val matchInfo = firstResult.get("info")?.asString ?: ""

                val isValidMatch = matchId.isNotEmpty() &&
                        matchName.isNotEmpty() &&
                        matchPercentage >= MIN_MATCH_PERCENTAGE &&
                        matchInfo.isNotEmpty()

                Log.d(TAG, "================================================")
                Log.d(TAG, "Match validation:")
                Log.d(TAG, "ID: '$matchId'")
                Log.d(TAG, "Name: '$matchName'")
                Log.d(TAG, "Info: '$matchInfo'")
                Log.d(TAG, "Match %: $matchPercentage (required: >= $MIN_MATCH_PERCENTAGE%)")
                Log.d(TAG, "Valid: $isValidMatch")
                Log.d(TAG, "================================================")

                if (isValidMatch) {
                    // Issue 3 fix: arm the server-call cooldown only on a
                    // confirmed good match. Sub-threshold server responses
                    // leave the cooldown un-armed so the next, stronger
                    // window can still reach the server.
                    lastServerCallTime = System.currentTimeMillis()

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

                    Log.d(TAG, "================================================")
                    Log.d(TAG, "VALID TuneURL MATCH FOUND!")
                    Log.d(TAG, "name: ${match.name}")
                    Log.d(TAG, "type: ${match.type}")
                    Log.d(TAG, "info: ${match.info}")
                    Log.d(TAG, "================================================")

                    detectorScope.launch(Dispatchers.Main) {
                        Log.d(TAG, "Invoking onMatchDetected callback...")
                        onMatchDetected?.invoke(match)
                        Log.d(TAG, "Callback invoked successfully")
                    }
                } else {
                    if (matchPercentage > 0f && matchPercentage < MIN_MATCH_PERCENTAGE) {
                        Log.d(TAG, "Match skipped - $matchPercentage% is below $MIN_MATCH_PERCENTAGE% threshold")
                    } else {
                        Log.d(TAG, "Match validation failed - ignoring invalid/empty match")
                    }
                }
            } else {
                Log.d(TAG, "No match found (empty result array)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing search result: ${e.message}", e)
        }
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningAppProcesses = activityManager.runningAppProcesses ?: return false

        return runningAppProcesses.any { processInfo ->
            processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    processInfo.processName == context.packageName
        }
    }

    fun release() {
        try {
            context.unregisterReceiver(searchResultReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        detectorScope.cancel()
    }
}
