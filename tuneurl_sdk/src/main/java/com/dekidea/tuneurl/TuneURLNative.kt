package com.dekidea.tuneurl

import java.nio.ByteBuffer

/**
 * TuneURL Native JNI Interface
 * Direct interface to the native C++ fingerprinting library
 */
internal object TuneURLNative {
    
    /**
     * Extract fingerprint from audio buffer
     * @param byteBuffer Audio data as ByteBuffer (16-bit PCM)
     * @param waveLength Length of audio data in samples
     * @return Fingerprint as ByteArray or null if extraction fails
     */
    external fun extractFingerprint(byteBuffer: ByteBuffer, waveLength: Int): ByteArray?
    
    /**
     * Extract fingerprint from audio file
     * @param filePath Path to audio file (WAV format)
     * @return Fingerprint as ByteArray or null if extraction fails
     */
    external fun extractFingerprintFromRawFile(filePath: String): ByteArray?
    
    /**
     * Calculate similarity between two audio buffers
     * @param byteBuffer1 First audio buffer
     * @param waveLength1 Length of first buffer
     * @param byteBuffer2 Second audio buffer
     * @param waveLength2 Length of second buffer
     * @return Similarity score (0.0 to 1.0)
     */
    external fun getSimilarity(
        byteBuffer1: ByteBuffer, 
        waveLength1: Int, 
        byteBuffer2: ByteBuffer, 
        waveLength2: Int
    ): Float
}
