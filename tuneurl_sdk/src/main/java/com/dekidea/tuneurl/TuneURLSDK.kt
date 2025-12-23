package com.dekidea.tuneurl

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * TuneURL SDK Kotlin Wrapper
 * Provides easy access to native fingerprinting functionality
 */
object TuneURLSDK {
    
    private const val TAG = "TuneURLSDK"
    private var isInitialized = false
    
    init {
        try {
            System.loadLibrary("native-lib")
            isInitialized = true
            Log.d(TAG, "✓ TuneURL native library loaded successfully")
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
     * Extract fingerprint from audio file
     * @param audioFilePath Path to audio file (WAV format)
     * @return Fingerprint as ByteArray or null if extraction fails
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
            
            Log.d(TAG, "Extracting fingerprint from: $audioFilePath")
            val fingerprint = extractFingerprintFromRawFile(audioFilePath)
            
            if (fingerprint != null && fingerprint.isNotEmpty()) {
                Log.d(TAG, "✓ Fingerprint extracted: ${fingerprint.size} bytes")
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
     * Extract fingerprint from audio buffer
     * @param audioBuffer Audio data as ByteBuffer (16-bit PCM)
     * @param waveLength Length of audio data in samples
     * @return Fingerprint as ByteArray or null if extraction fails
     */
    fun extractFingerprintFromBuffer(audioBuffer: ByteBuffer, waveLength: Int): ByteArray? {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return null
        }
        
        return try {
            Log.d(TAG, "Extracting fingerprint from buffer: $waveLength samples")
            val fingerprint = extractFingerprint(audioBuffer, waveLength)
            
            if (fingerprint != null && fingerprint.isNotEmpty()) {
                Log.d(TAG, "✓ Fingerprint extracted: ${fingerprint.size} bytes")
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
     * Calculate similarity between two audio buffers
     * @param buffer1 First audio buffer
     * @param length1 Length of first buffer
     * @param buffer2 Second audio buffer
     * @param length2 Length of second buffer
     * @return Similarity score (0.0 to 1.0) or -1.0 on error
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
            val similarity = getSimilarity(buffer1, length1, buffer2, length2)

            val similarityPercent = similarity * 100
            Log.d(TAG, "Similarity calculated: %.2f%%".format(similarityPercent))

            similarity

        } catch (e: Exception) {
            Log.e(TAG, "Error calculating similarity", e)
            -1.0f
        }
    }
    
    /**
     * Convert fingerprint ByteArray to hex string for API transmission
     */
    fun fingerprintToHexString(fingerprint: ByteArray): String {
        return fingerprint.joinToString("") { "%02x".format(it) }
    }
    
    // Delegate to native interface
    private fun extractFingerprint(byteBuffer: ByteBuffer, waveLength: Int): ByteArray? {
        return TuneURLNative.extractFingerprint(byteBuffer, waveLength)
    }
    
    private fun extractFingerprintFromRawFile(filePath: String): ByteArray? {
        return TuneURLNative.extractFingerprintFromRawFile(filePath)
    }
    
    private fun getSimilarity(
        byteBuffer1: ByteBuffer, 
        waveLength1: Int, 
        byteBuffer2: ByteBuffer, 
        waveLength2: Int
    ): Float {
        return TuneURLNative.getSimilarity(byteBuffer1, waveLength1, byteBuffer2, waveLength2)
    }
}
