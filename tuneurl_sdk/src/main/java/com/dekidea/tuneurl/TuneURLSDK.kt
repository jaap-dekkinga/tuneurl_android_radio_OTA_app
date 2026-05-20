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
