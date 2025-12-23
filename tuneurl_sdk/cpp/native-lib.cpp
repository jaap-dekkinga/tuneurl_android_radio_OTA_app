#include <jni.h>
#include <string>
#include <iostream>
#include "Fingerprint.h"
#include "FingerprintManager.h"
#include "Resampler.h"

// JNI methods for TuneURLNative.kt
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_dekidea_tuneurl_TuneURLNative_extractFingerprint(JNIEnv* env, jobject /* this */, jobject byteBuffer, jint waveLength) {

    int16_t* wave = (int16_t*) env->GetDirectBufferAddress(byteBuffer);

    Fingerprint* fingerprint = ExtractFingerprint(wave, waveLength);

    jbyteArray result = (env)->NewByteArray(fingerprint->dataSize);

    (env)->SetByteArrayRegion(result, 0, fingerprint->dataSize, reinterpret_cast<const jbyte *>(fingerprint->data));

    FingerprintFree(fingerprint);

    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_dekidea_tuneurl_TuneURLNative_extractFingerprintFromRawFile(JNIEnv* env, jobject /* this */, jstring filePath) {

    const char * _nativeString = env->GetStringUTFChars(filePath, 0);

    Fingerprint* fingerprint = ExtractFingerprintFromRawFile(_nativeString);

    jbyteArray result = (env)->NewByteArray(fingerprint->dataSize);

    (env)->SetByteArrayRegion(result, 0, fingerprint->dataSize, reinterpret_cast<const jbyte*>(fingerprint->data));

    FingerprintFree(fingerprint);

    env->ReleaseStringUTFChars(filePath, _nativeString);

    return result;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_dekidea_tuneurl_TuneURLNative_getSimilarity(JNIEnv* env, jobject /* this */, jobject byteBuffer1, jint waveLength1, jobject byteBuffer2, jint waveLength2) {

    int16_t* wave1 = (int16_t*) env->GetDirectBufferAddress(byteBuffer1);

    Fingerprint* fingerprint1 = ExtractFingerprint(wave1, waveLength1);

    int16_t* wave2 = (int16_t*) env->GetDirectBufferAddress(byteBuffer2);

    Fingerprint* fingerprint2 = ExtractFingerprint(wave2, waveLength2);

    FingerprintSimilarity similarity = CompareFingerprints(fingerprint1, fingerprint2);

    FingerprintFree(fingerprint1);

    FingerprintFree(fingerprint2);

    return similarity.similarity;
}

// JNI methods for NativeResampler.kt
extern "C" JNIEXPORT jlong JNICALL
Java_com_dekidea_tuneurl_NativeResampler_nativeCreate(JNIEnv* env, jobject /* this */, jint inputRate, jint outputRate, jint channels) {
    Resampler* resampler = new Resampler();
    if (resampler->create(inputRate, outputRate, channels)) {
        return reinterpret_cast<jlong>(resampler);
    }
    delete resampler;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dekidea_tuneurl_NativeResampler_nativeResample(JNIEnv* env, jobject /* this */, jlong handle, jobject inputBuffer, jobject outputBuffer, jint inputLength) {
    if (handle == 0) {
        return -1;
    }
    
    Resampler* resampler = reinterpret_cast<Resampler*>(handle);
    
    int16_t* input = (int16_t*) env->GetDirectBufferAddress(inputBuffer);
    int16_t* output = (int16_t*) env->GetDirectBufferAddress(outputBuffer);
    
    if (input == nullptr || output == nullptr) {
        return -1;
    }
    
    jlong outputCapacity = env->GetDirectBufferCapacity(outputBuffer);
    int inputSamples = inputLength / 2;  // Convert bytes to samples (16-bit = 2 bytes)
    int outputCapacitySamples = outputCapacity / 2;
    
    int outputSamples = resampler->resample(input, inputSamples, output, outputCapacitySamples);
    
    return outputSamples * 2;  // Convert samples back to bytes
}

extern "C" JNIEXPORT void JNICALL
Java_com_dekidea_tuneurl_NativeResampler_nativeDestroy(JNIEnv* env, jobject /* this */, jlong handle) {
    if (handle != 0) {
        Resampler* resampler = reinterpret_cast<Resampler*>(handle);
        resampler->destroy();
        delete resampler;
    }
}

