package com.dekidea.tuneurl

import java.nio.ByteBuffer

/**
 * Native audio resampler that replaces the external libresample dependency.
 * Uses linear interpolation for sample rate conversion.
 * 
 * This implementation is 16KB page-aligned compliant for Android 15+.
 */
class NativeResampler {
    private var nativeHandle: Long = 0
    
    /**
     * Create and initialize the resampler
     * @param inputRate Source sample rate (e.g., 44100)
     * @param outputRate Target sample rate (e.g., 10240)
     * @param bufferSize Ignored (kept for API compatibility)
     * @param channels Number of audio channels (1 for mono)
     */
    fun create(inputRate: Int, outputRate: Int, bufferSize: Int, channels: Int) {
        if (nativeHandle != 0L) {
            destroy()
        }
        nativeHandle = nativeCreate(inputRate, outputRate, channels)
    }
    
    /**
     * Resample audio data from input buffer to output buffer
     * @param input Direct ByteBuffer containing input audio (16-bit PCM)
     * @param output Direct ByteBuffer for resampled output
     * @param inputLength Number of bytes to process from input
     * @return Number of bytes written to output, or -1 on error
     */
    fun resampleEx(input: ByteBuffer, output: ByteBuffer, inputLength: Int): Int {
        if (nativeHandle == 0L) {
            return -1
        }
        return nativeResample(nativeHandle, input, output, inputLength)
    }
    
    /**
     * Release native resources
     */
    fun destroy() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }
    
    protected fun finalize() {
        destroy()
    }
    
    // Native methods
    private external fun nativeCreate(inputRate: Int, outputRate: Int, channels: Int): Long
    private external fun nativeResample(handle: Long, input: ByteBuffer, output: ByteBuffer, inputLength: Int): Int
    private external fun nativeDestroy(handle: Long)
    
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }
}
