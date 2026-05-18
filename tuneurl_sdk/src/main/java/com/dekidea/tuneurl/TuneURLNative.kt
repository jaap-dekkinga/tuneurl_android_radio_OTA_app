package com.dekidea.tuneurl

import java.nio.ByteBuffer

/**
 * TuneURL Native JNI Interface
 * Direct interface to the native C++ fingerprinting library.
 *
 * The emitVersion parameter selects the fingerprint format / hash protocol:
 *   1 = legacy v1 (no header, plain landmarkHash)
 *   2 = v2 (4-byte header: magic 0xFF, formatVersion, hashProtocol, calibVersion;
 *           hash packs per-frame intensity tier into bits 28-29)
 *
 * Constants in C++ (see Fingerprint.h):
 *   FORMAT_VERSION_V1 = 1, FORMAT_VERSION_V2 = 2
 */
internal object TuneURLNative {

    const val FORMAT_VERSION_V1: Int = 1
    const val FORMAT_VERSION_V2: Int = 2

    /**
     * Extract fingerprint from audio buffer
     * @param byteBuffer Audio data as direct ByteBuffer (16-bit PCM, 10240 Hz, mono)
     * @param waveLength Length of audio data in samples
     * @param emitVersion FORMAT_VERSION_V1 or FORMAT_VERSION_V2
     * @return Fingerprint as ByteArray or null if extraction fails
     */
    external fun extractFingerprint(
        byteBuffer: ByteBuffer,
        waveLength: Int,
        emitVersion: Int
    ): ByteArray?

    /**
     * Extract fingerprint from a raw int16 PCM file
     * @param filePath Path to raw PCM file (int16, 10240 Hz, mono)
     * @param emitVersion FORMAT_VERSION_V1 or FORMAT_VERSION_V2
     * @return Fingerprint as ByteArray or null if extraction fails
     */
    external fun extractFingerprintFromRawFile(
        filePath: String,
        emitVersion: Int
    ): ByteArray?

    /**
     * Calculate similarity between two audio buffers
     * @param byteBuffer1 First audio buffer
     * @param waveLength1 Length of first buffer in samples
     * @param byteBuffer2 Second audio buffer
     * @param waveLength2 Length of second buffer in samples
     * @param emitVersion FORMAT_VERSION_V1 or FORMAT_VERSION_V2 — used for BOTH fingerprints
     * @return Similarity score (0.0 to 1.0). Returns 0.0 if the two fingerprints
     *         have mismatched versions (silent fallthrough in C++).
     */
    external fun getSimilarity(
        byteBuffer1: ByteBuffer,
        waveLength1: Int,
        byteBuffer2: ByteBuffer,
        waveLength2: Int,
        emitVersion: Int
    ): Float
}
