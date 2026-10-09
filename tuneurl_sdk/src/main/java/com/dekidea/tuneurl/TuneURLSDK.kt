package com.dekidea.tuneurl

import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * TuneURL SDK Kotlin Wrapper
 * Provides easy access to native fingerprinting functionality.
 *
 * Public API is signature-compatible with the v1-era version; the format version
 * (1 = legacy, 2 = R2 with per-frame intensity tier in pair hash) is selected
 * once at SDK level and applied to every fingerprint operation thereafter.
 * Default is V2. Call setFormatVersion(TuneURLNative.FORMAT_VERSION_V1) to roll back.
 */
object TuneURLSDK {

    private const val TAG = "TuneURLSDK"

    /** Sample rate every fingerprint operation expects (FingerprintProperties). */
    private const val FINGERPRINT_SAMPLE_RATE = 10240.0
    private var isInitialized = false

    /**
     * Format version constants re-exported from TuneURLNative (which is
     * `internal` and not visible outside the SDK module). Use these at call
     * sites in the app module instead of TuneURLNative.FORMAT_VERSION_V*.
     */
    const val FORMAT_VERSION_V1: Int = TuneURLNative.FORMAT_VERSION_V1
    const val FORMAT_VERSION_V2: Int = TuneURLNative.FORMAT_VERSION_V2

    @Volatile
    private var formatVersion: Int = TuneURLNative.FORMAT_VERSION_V2

    init {
        try {
            System.loadLibrary("native-lib")
            isInitialized = true
            Log.d(TAG, "✓ TuneURL native library loaded (format=v$formatVersion)")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load TuneURL native library", e)
            isInitialized = false
        }
    }

    /**
     * Check if SDK is initialized
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Select the fingerprint format version used for all subsequent calls.
     * Must be TuneURLNative.FORMAT_VERSION_V1 or TuneURLNative.FORMAT_VERSION_V2.
     * Default is V2.
     */
    fun setFormatVersion(version: Int) {
        require(
            version == TuneURLNative.FORMAT_VERSION_V1 ||
                version == TuneURLNative.FORMAT_VERSION_V2
        ) { "Unsupported format version: $version" }
        formatVersion = version
        Log.i(TAG, "Fingerprint format version set to v$version")
    }

    /**
     * Current fingerprint format version. 1 = legacy, 2 = R2 with intensity tiers.
     */
    fun getFormatVersion(): Int = formatVersion

    /**
     * Extract fingerprint from audio file (raw int16 PCM, 10240 Hz, mono)
     */
    fun extractFingerprintFromFile(audioFilePath: String): ByteArray? {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return null
        }

        return try {
            val file = File(audioFilePath)
            if (!file.exists()) {
                Log.e(TAG, "Audio file does not exist: $audioFilePath")
                return null
            }

            Log.d(TAG, "Extracting fingerprint from: $audioFilePath (v$formatVersion)")
            val fingerprint = TuneURLNative.extractFingerprintFromRawFile(audioFilePath, formatVersion)

            if (fingerprint != null && fingerprint.isNotEmpty()) {
                Log.d(TAG, "✓ Fingerprint extracted: ${fingerprint.size} bytes")
                logFingerprintHeader(fingerprint)
            } else {
                Log.w(TAG, "Fingerprint extraction returned empty result")
            }

            fingerprint
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting fingerprint", e)
            null
        }
    }

    /**
     * Extract fingerprint from audio buffer (16-bit PCM)
     */
    fun extractFingerprintFromBuffer(audioBuffer: ByteBuffer, waveLength: Int): ByteArray? {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return null
        }

        return try {
            Log.d(TAG, "Extracting fingerprint from buffer: $waveLength samples (v$formatVersion)")
            val fingerprint = TuneURLNative.extractFingerprint(audioBuffer, waveLength, formatVersion)

            if (fingerprint != null && fingerprint.isNotEmpty()) {
                Log.d(TAG, "✓ Fingerprint extracted: ${fingerprint.size} bytes")
                logFingerprintHeader(fingerprint)
            } else {
                Log.w(TAG, "Fingerprint extraction returned empty result")
            }

            fingerprint
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting fingerprint from buffer", e)
            null
        }
    }

    /**
     * Extract a fingerprint from a buffer (16-bit PCM at the fingerprint
     * sample rate) with an EXPLICIT format version, ignoring the SDK-wide
     * setting. Use this for fingerprints sent to the match server, which
     * expects V1, so the result doesn't depend on which code path last
     * called [setFormatVersion].
     */
    fun extractFingerprintFromBufferAt(audioBuffer: ByteBuffer, waveLength: Int, version: Int): ByteArray? {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return null
        }
        require(
            version == TuneURLNative.FORMAT_VERSION_V1 ||
                version == TuneURLNative.FORMAT_VERSION_V2
        ) { "Unsupported format version: $version" }
        return try {
            val fingerprint = TuneURLNative.extractFingerprint(audioBuffer, waveLength, version)
            if (fingerprint != null && fingerprint.isNotEmpty()) {
                Log.d(TAG, "✓ Fingerprint extracted (explicit v$version): ${fingerprint.size} bytes")
            } else {
                Log.w(TAG, "Fingerprint extraction returned empty result (explicit v$version)")
            }
            fingerprint
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting fingerprint (explicit v$version)", e)
            null
        }
    }

    /**
     * Calculate similarity between two audio buffers. Both buffers are fingerprinted
     * with the current format version. Mismatched versions return 0.0 (C++ silently
     * rejects). Returns -1.0 on a Kotlin-level error.
     */
    fun calculateSimilarity(
        buffer1: ByteBuffer,
        length1: Int,
        buffer2: ByteBuffer,
        length2: Int
    ): Float {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return -1.0f
        }

        return try {
            val similarity = TuneURLNative.getSimilarity(
                buffer1, length1, buffer2, length2, formatVersion
            )

            val similarityPercent = similarity * 100
            Log.d(TAG, "Similarity (v$formatVersion): %.2f%% (raw=%.4f)".format(similarityPercent, similarity))

            similarity
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating similarity", e)
            -1.0f
        }
    }

    /**
     * Calculate similarity between two audio buffers using an EXPLICIT format
     * version, ignoring the SDK's current singleton setting. Use this when a
     * caller needs a specific version for one call (e.g. local v2 trigger gate
     * while the rest of the app is on v1). Mismatched versions between the two
     * buffers cannot happen here — both fingerprints in a single call always
     * use the same version (the JNI layer enforces this).
     *
     * Returns -1.0 on a Kotlin-level error, 0.0..1.0 otherwise.
     */
    fun calculateSimilarityAt(
        buffer1: ByteBuffer,
        length1: Int,
        buffer2: ByteBuffer,
        length2: Int,
        version: Int
    ): Float {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return -1.0f
        }
        require(
            version == TuneURLNative.FORMAT_VERSION_V1 ||
                version == TuneURLNative.FORMAT_VERSION_V2
        ) { "Unsupported format version: $version" }

        return try {
            val similarity = TuneURLNative.getSimilarity(
                buffer1, length1, buffer2, length2, version
            )
            Log.d(TAG, "Similarity (explicit v$version): raw=%.4f".format(similarity))
            similarity
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating similarity (explicit v$version)", e)
            -1.0f
        }
    }

    /**
     * Result of locating a reference sound (e.g. the TuneURL trigger) inside
     * a longer audio window.
     *
     * @property similarity 0.0..1.0, same value as [calculateSimilarityAt]
     * @property mostSimilarStartTime seconds from the start of the searched
     *           window where the best match begins
     * @property score matched features per frame
     */
    data class SimilarityMatch(
        val similarity: Float,
        val mostSimilarStartTime: Float,
        val score: Float
    )

    /**
     * Compare [buffer1] (the window being searched) against [buffer2] (the
     * reference sound) with an explicit format version, and report where in
     * [buffer1] the best match was found. This is what the iOS StreamDetector
     * uses to work out how long ago the trigger played.
     *
     * Returns null if the SDK isn't initialized or a fingerprint couldn't be
     * extracted.
     */
    fun findSimilarityAt(
        buffer1: ByteBuffer,
        length1: Int,
        buffer2: ByteBuffer,
        length2: Int,
        version: Int
    ): SimilarityMatch? {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return null
        }
        require(
            version == TuneURLNative.FORMAT_VERSION_V1 ||
                version == TuneURLNative.FORMAT_VERSION_V2
        ) { "Unsupported format version: $version" }

        return try {
            val values = TuneURLNative.getSimilarityDetails(
                buffer1, length1, buffer2, length2, version
            ) ?: return null
            if (values.size < 3) return null
            SimilarityMatch(
                similarity = values[0],
                mostSimilarStartTime = values[1],
                score = values[2]
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating similarity details (explicit v$version)", e)
            null
        }
    }

    /**
     * Result of sliding a reference sound across a longer audio window.
     *
     * @property similarity best similarity found, 0.0..1.0
     * @property offsetSamples where in the window the best-matching slice
     *           starts, in samples from the start of the window
     * @property startSeconds where in the window the reference sound itself
     *           starts, in seconds from the start of the window: the slice
     *           offset refined by the position the native compare reports
     *           inside that slice. Can be slightly negative when the sound
     *           began just before the window. Only meaningful when
     *           [similarity] is above the caller's threshold.
     * @property positionsChecked how many slices were compared
     */
    data class SlidingMatch(
        val similarity: Float,
        val offsetSamples: Int,
        val startSeconds: Double,
        val positionsChecked: Int
    )

    /**
     * Look for [reference] (e.g. the TuneURL trigger) anywhere inside [window]
     * by comparing it with every reference-length slice of the window, moving
     * [hopSamples] at a time.
     *
     * Use this instead of [calculateSimilarityAt] / [findSimilarityAt] when
     * the window is longer than the reference: the native compare truncates
     * both fingerprints to the size of the smaller one, so a direct compare
     * only examines the START of the window. Slices of reference length are
     * not affected by that truncation.
     *
     * Both buffers must be direct ByteBuffers of 16-bit PCM at the fingerprint
     * sample rate. Returns null if the SDK isn't initialized, the window is
     * shorter than the reference, or a buffer isn't direct.
     */
    fun slideSimilarityAt(
        window: ByteBuffer,
        windowSamples: Int,
        reference: ByteBuffer,
        referenceSamples: Int,
        hopSamples: Int,
        version: Int
    ): SlidingMatch? {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return null
        }
        require(
            version == TuneURLNative.FORMAT_VERSION_V1 ||
                version == TuneURLNative.FORMAT_VERSION_V2
        ) { "Unsupported format version: $version" }
        require(hopSamples > 0) { "hopSamples must be positive" }

        if (referenceSamples <= 0 || windowSamples < referenceSamples) return null
        if (!window.isDirect || !reference.isDirect) {
            Log.e(TAG, "slideSimilarityAt needs direct ByteBuffers")
            return null
        }
        if (window.capacity() < windowSamples * 2 || reference.capacity() < referenceSamples * 2) {
            Log.e(TAG, "slideSimilarityAt: buffer smaller than the stated sample count")
            return null
        }

        return try {
            // The native side reads from the start of a direct buffer, so each
            // slice is copied into its own buffer rather than passed by offset.
            val sliceBytes = referenceSamples * 2
            val slice = ByteBuffer.allocateDirect(sliceBytes)

            var best = 0f
            var bestOffset = 0
            var bestStartInSlice = 0f
            var positions = 0
            var offset = 0
            while (offset + referenceSamples <= windowSamples) {
                val source = window.duplicate()
                source.limit(offset * 2 + sliceBytes)
                source.position(offset * 2)
                slice.clear()
                slice.put(source)

                val values = TuneURLNative.getSimilarityDetails(
                    slice, referenceSamples, reference, referenceSamples, version
                )
                if (values != null && values.size >= 2 && values[0] > best) {
                    best = values[0]
                    bestOffset = offset
                    bestStartInSlice = values[1]
                }
                positions++
                offset += hopSamples
            }
            // The native start time is unset (a huge negative number) when
            // nothing matched; ignore anything outside one reference length.
            val referenceSeconds = referenceSamples / FINGERPRINT_SAMPLE_RATE
            val startInSlice = bestStartInSlice.toDouble()
                .takeIf { best > 0f && it >= -referenceSeconds && it <= referenceSeconds } ?: 0.0
            SlidingMatch(
                similarity = best,
                offsetSamples = bestOffset,
                startSeconds = bestOffset / FINGERPRINT_SAMPLE_RATE + startInSlice,
                positionsChecked = positions
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in sliding similarity (explicit v$version)", e)
            null
        }
    }

    /**
     * Convert fingerprint ByteArray to hex string for API transmission
     */
    fun fingerprintToHexString(fingerprint: ByteArray): String {
        return fingerprint.joinToString("") { "%02x".format(it) }
    }

    /**
     * Diagnostic: log the v2 header bytes if present. For a properly-emitted
     * v2 fingerprint this should print:
     *   magic=0xFF format=0x02 hashProto=0x02 calib=0x01
     * For v1 emission the magic byte will be the high byte of the first
     * landmark's x-coordinate (typically 0x00 for x < 256), not 0xFF.
     *
     * This is the Bug A2 ("V2 not actually emitted") byte-level check from
     * v2_architecture_android.md Section 7.
     */
    fun logFingerprintHeader(fingerprint: ByteArray) {
        if (fingerprint.size < 4) {
            Log.w(TAG, "Fingerprint too short to inspect header: ${fingerprint.size} bytes")
            return
        }
        Log.i(
            TAG,
            "fingerprint header: magic=0x%02X format=0x%02X hashProto=0x%02X calib=0x%02X".format(
                fingerprint[0].toInt() and 0xFF,
                fingerprint[1].toInt() and 0xFF,
                fingerprint[2].toInt() and 0xFF,
                fingerprint[3].toInt() and 0xFF
            )
        )
    }
}
