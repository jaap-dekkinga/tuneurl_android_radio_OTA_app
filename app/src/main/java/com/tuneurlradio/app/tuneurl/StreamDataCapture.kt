package com.tuneurlradio.app.tuneurl

import android.util.Log
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

class StreamDataCapture(private val cacheDir: File) {

    private val TAG = "StreamDataCapture"
    private val MAX_BUFFER_SIZE = 250_000
    private val mediaDataChunks = ConcurrentLinkedQueue<ByteArray>()
    private var currentBufferSize = 0

    private var client: OkHttpClient? = null
    private var currentCall: Call? = null
    private var isCapturing = false

    fun startCapture(streamUrl: String) {
        if (isCapturing) {
            Log.d(TAG, "Already capturing")
            return
        }

        Log.d(TAG, "================================================")
        Log.d(TAG, "Starting stream capture")
        Log.d(TAG, "URL: $streamUrl")
        Log.d(TAG, "================================================")

        isCapturing = true
        mediaDataChunks.clear()
        currentBufferSize = 0

        client = OkHttpClient.Builder().build()

        val request = Request.Builder()
            .url(streamUrl)
            .build()

        currentCall = client?.newCall(request)
        currentCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Stream capture failed: ${e.message}", e)
                isCapturing = false
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Stream response received: ${response.code}")
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "Stream response not successful: ${response.code}")
                    isCapturing = false
                    return
                }

                Log.d(TAG, "Starting to read stream data...")

                try {
                    val source = response.body?.source()
                    val buffer = okio.Buffer()
                    var totalBytesRead = 0L

                    while (isCapturing && source?.read(buffer, 8192) != -1L) {
                        val chunk = buffer.readByteArray()
                        addChunk(chunk)
                        totalBytesRead += chunk.size
                        
                        if (totalBytesRead % 50000 < 8192) {
                            Log.d(TAG, "Stream data captured: $totalBytesRead bytes total")
                        }
                    }
                    
                    Log.d(TAG, "Stream reading ended. Total bytes: $totalBytesRead")
                } catch (e: Exception) {
                    if (isCapturing) {
                        Log.e(TAG, "Error reading stream: ${e.message}", e)
                    }
                } finally {
                    response.close()
                }
            }
        })

        Log.d(TAG, "Stream capture request enqueued")
    }

    fun stopCapture() {
        Log.d(TAG, "Stopping stream capture...")
        isCapturing = false
        currentCall?.cancel()
        currentCall = null
        client = null
        Log.d(TAG, "Stream capture stopped")
    }

    private fun addChunk(chunk: ByteArray) {
        mediaDataChunks.add(chunk)
        currentBufferSize += chunk.size

        while (currentBufferSize > MAX_BUFFER_SIZE && mediaDataChunks.isNotEmpty()) {
            val removed = mediaDataChunks.poll()
            if (removed != null) {
                currentBufferSize -= removed.size
            }
        }
    }

    fun saveCurrentBufferToFile(): File? {
        Log.d(TAG, "saveCurrentBufferToFile called, chunks: ${mediaDataChunks.size}, size: $currentBufferSize")
        
        if (mediaDataChunks.isEmpty()) {
            Log.w(TAG, "No data in buffer to save")
            return null
        }

        val allData = mediaDataChunks.toList()
        val totalSize = allData.sumOf { it.size }

        val combinedData = ByteArray(totalSize)
        var offset = 0
        for (chunk in allData) {
            System.arraycopy(chunk, 0, combinedData, offset, chunk.size)
            offset += chunk.size
        }

        val file = File(cacheDir, "stream_chunk_${System.currentTimeMillis()}.mp3")
        file.writeBytes(combinedData)

        Log.d(TAG, "Saved ${combinedData.size} bytes to ${file.name}")

        return file
    }

    fun clearBuffer() {
        mediaDataChunks.clear()
        currentBufferSize = 0
        Log.d(TAG, "Buffer cleared")
    }
}
