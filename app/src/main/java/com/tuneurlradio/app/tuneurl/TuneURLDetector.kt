package com.tuneurlradio.app.tuneurl

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.core.content.ContextCompat
import com.dekidea.tuneurl.NativeResampler
import com.dekidea.tuneurl.TuneURLNative
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

class TuneURLDetector(private val context: Context) : Constants {

    private val TAG = "TuneURLDetector"
    private val dataCapture: StreamDataCapture = StreamDataCapture(context.cacheDir)
    private val detectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var detectionJob: Job? = null
    private var isDetecting = false
    private var currentStreamUrl: String? = null

    private val DETECTION_INTERVAL_MS = 500L
    private val FINGERPRINT_SAMPLE_RATE = 10240
    private val CONTINUOUS_FINGERPRINT_INTERVAL_MS = 2000L  // Match iOS: 2 seconds
    private val MIN_MATCH_PERCENTAGE = 25f

    // Local v2 trigger gate. Mirrors iOS local detection (validated 6/6 on iOS).
    // The gate runs v2 explicitly; the server fingerprint stays on v1. See
    // v2_context_android.md / v2_architecture_android.md for the design.
    private val TRIGGER_SIMILARITY_THRESHOLD = 0.10f  // Match iOS gate (0.1)
    private var triggerBuffer: ByteBuffer? = null
    private var triggerSampleCount = 0

    private var lastFingerprintTime = 0L
    private var recordingTuneURL = false

    private var onMatchDetected: ((TuneURLMatch) -> Unit)? = null
    private val searchResultReceiver = SearchResultReceiver()

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
        // TuneURLSDK.calculateSimilarityAt(..., FORMAT_VERSION_V2) at the call
        // site, so it is independent of the singleton version above.
        if (triggerBuffer == null) {
            loadTriggerSound()
        }

        onMatchDetected = onMatch
        isDetecting = true
        currentStreamUrl = streamUrl

        dataCapture.startCapture(streamUrl)
        scheduleDetectionTask()

        Log.d(TAG, "TuneURL detection started (using raw MP3 capture)")
        Log.d(TAG, "Detection interval: ${DETECTION_INTERVAL_MS}ms")
        Log.d(TAG, "Fingerprint interval: ${CONTINUOUS_FINGERPRINT_INTERVAL_MS}ms")
        Log.d(TAG, "Local v2 trigger gate threshold: $TRIGGER_SIMILARITY_THRESHOLD")
    }

    fun stopDetection() {
        Log.d(TAG, "Stopping TuneURL detection...")
        isDetecting = false
        detectionJob?.cancel()
        detectionJob = null
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

            val currentTime = System.currentTimeMillis()
            if ((currentTime - lastFingerprintTime) >= CONTINUOUS_FINGERPRINT_INTERVAL_MS) {
                Log.d(TAG, "Processing MP3 buffer for fingerprinting...")
                lastFingerprintTime = currentTime
                recordingTuneURL = true
                processMP3BufferAndMatch()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio buffer", e)
        }
    }

    private suspend fun processMP3BufferAndMatch() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing MP3 buffer...")

                val mp3File = dataCapture.saveCurrentBufferToFile()

                if (mp3File == null) {
                    Log.w(TAG, "No MP3 data available yet")
                    recordingTuneURL = false
                    return@withContext
                }

                Log.d(TAG, "MP3 file saved: ${mp3File.name}, size: ${mp3File.length()} bytes")

                val pcmData = decodeMp3ToPcm(mp3File.absolutePath)
                val sampleRate = getDecodedSampleRate(mp3File.absolutePath)

                mp3File.delete()

                if (pcmData == null || pcmData.isEmpty()) {
                    Log.e(TAG, "Failed to decode MP3")
                    recordingTuneURL = false
                    return@withContext
                }

                Log.d(TAG, "MP3 decoded: ${pcmData.size} bytes PCM at $sampleRate Hz")

                val monoData = convertToMono(pcmData)
                Log.d(TAG, "Converted to mono: ${monoData.size} bytes")

                val sourceBuffer = ByteBuffer.allocateDirect(monoData.size)
                sourceBuffer.order(ByteOrder.LITTLE_ENDIAN)
                sourceBuffer.put(monoData)
                sourceBuffer.rewind()

                val resampledSize = ((FINGERPRINT_SAMPLE_RATE.toDouble() / sampleRate.toDouble()) * monoData.size).toInt()
                val resampledBuffer = ByteBuffer.allocateDirect(resampledSize)
                resampledBuffer.order(ByteOrder.LITTLE_ENDIAN)

                val resample = NativeResampler()
                try {
                    resample.create(sampleRate, FINGERPRINT_SAMPLE_RATE, 2048, 1)

                    val outputLength = resample.resampleEx(sourceBuffer, resampledBuffer, sourceBuffer.remaining())

                    if (outputLength <= 0) {
                        Log.e(TAG, "Resampling failed")
                        recordingTuneURL = false
                        return@withContext
                    }

                    Log.d(TAG, "Resampled to $FINGERPRINT_SAMPLE_RATE Hz: $outputLength bytes")

                    resampledBuffer.rewind()
                    val sampleCount = outputLength / 2

                    // --- LOCAL v2 TRIGGER GATE -------------------------------
                    // Compare the current stream window against the bundled
                    // trigger sound, using v2 explicitly (does NOT touch the
                    // singleton). Skip the server call when the gate is below
                    // threshold. If the trigger asset failed to load we fall
                    // through (treat as "gate passes") so we never silently
                    // drop into a do-nothing state — the server flow is the
                    // safety net.
                    val tBuf = triggerBuffer
                    val tLen = triggerSampleCount
                    if (tBuf != null && tLen > 0) {
                        tBuf.rewind()
                        val similarity = TuneURLSDK.calculateSimilarityAt(
                            resampledBuffer, sampleCount,
                            tBuf, tLen,
                            TuneURLNative.FORMAT_VERSION_V2
                        )
                        // Restore position for the v1 fingerprint extraction
                        // below; calculateSimilarity advances the buffer.
                        resampledBuffer.rewind()

                        // DIAG line prescribed by v2_architecture_android.md §4.
                        // Unconditional — this is THE log line the iOS effort
                        // identified as the single most useful diagnostic.
                        Log.i(
                            "TuneURL_DIAG",
                            "local v2 similarity=%.4f (threshold=%.2f) at t=%d"
                                .format(similarity, TRIGGER_SIMILARITY_THRESHOLD, System.currentTimeMillis())
                        )

                        if (similarity < TRIGGER_SIMILARITY_THRESHOLD) {
                            // No trigger in this window — don't bother the server.
                            recordingTuneURL = false
                            return@withContext
                        }
                        Log.d(TAG, "Local v2 gate PASSED (similarity=$similarity) — proceeding to server")
                    } else {
                        Log.w(TAG, "Trigger buffer not loaded — bypassing local gate this cycle")
                    }
                    // ---------------------------------------------------------

                    val fingerprintBytes = TuneURLSDK.extractFingerprintFromBuffer(resampledBuffer, sampleCount)

                    if (fingerprintBytes != null) {
                        val fingerprintString = fingerprintBytes.joinToString(",") {
                            (it.toInt() and 0xff).toString()
                        }

                        Log.d(TAG, "================================================")
                        Log.d(TAG, "FINGERPRINT EXTRACTED!")
                        Log.d(TAG, "Size: ${fingerprintBytes.size} bytes")
                        Log.d(TAG, "Searching via API...")
                        Log.d(TAG, "================================================")

                        searchFingerprintViaSDK(fingerprintString)
                    } else {
                        Log.w(TAG, "Fingerprint extraction returned null")
                    }

                } finally {
                    resample.destroy()
                }

                recordingTuneURL = false

            } catch (e: Exception) {
                Log.e(TAG, "Error processing MP3 buffer: ${e.message}", e)
                recordingTuneURL = false
            }
        }
    }

    /**
     * Load the bundled trigger sound (R.raw.trigger_sound, same asset as iOS),
     * decode it, mix to mono, resample to FINGERPRINT_SAMPLE_RATE, and stash
     * the resulting PCM in [triggerBuffer] for the local gate.
     *
     * The asset is the same MP3 used by OTAListener; we copy it through
     * MediaCodec via the same pipeline as the stream so the decoded byte
     * representation is consistent.
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

            val pcmData = decodeMp3ToPcm(triggerFile.absolutePath)
            val sampleRate = getDecodedSampleRate(triggerFile.absolutePath)

            if (pcmData == null || pcmData.isEmpty()) {
                Log.e(TAG, "Failed to decode trigger sound — local gate will be bypassed")
                return
            }

            val monoData = convertToMono(pcmData)

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
                val outputLength = resampler.resampleEx(
                    sourceBuffer, resampledBuffer, sourceBuffer.remaining()
                )

                if (outputLength > 0) {
                    resampledBuffer.rewind()
                    resampledBuffer.limit(outputLength)

                    val held = ByteBuffer.allocateDirect(outputLength)
                    held.order(ByteOrder.LITTLE_ENDIAN)
                    held.put(resampledBuffer)
                    held.rewind()

                    triggerBuffer = held
                    triggerSampleCount = outputLength / 2

                    Log.i(
                        TAG,
                        "✓ Trigger sound loaded: $triggerSampleCount samples at $FINGERPRINT_SAMPLE_RATE Hz"
                    )
                } else {
                    Log.e(TAG, "Trigger resample produced 0 bytes — local gate will be bypassed")
                }
            } finally {
                resampler.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading trigger sound — local gate will be bypassed", e)
        }
    }

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

            if (audioTrackIndex < 0) {
                Log.e(TAG, "No audio track found in MP3")
                return null
            }

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
