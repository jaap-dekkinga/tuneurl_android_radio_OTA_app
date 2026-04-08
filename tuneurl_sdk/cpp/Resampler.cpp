#include "Resampler.h"
#include <cmath>
#include <algorithm>

Resampler::Resampler() 
    : mInputRate(0), mOutputRate(0), mChannels(1), mInitialized(false), mRatio(1.0) {
}

Resampler::~Resampler() {
    destroy();
}

bool Resampler::create(int inputRate, int outputRate, int channels) {
    if (inputRate <= 0 || outputRate <= 0 || channels <= 0) {
        return false;
    }
    
    mInputRate = inputRate;
    mOutputRate = outputRate;
    mChannels = channels;
    mRatio = static_cast<double>(outputRate) / static_cast<double>(inputRate);
    mInitialized = true;
    
    return true;
}

int Resampler::getOutputSize(int inputLength) const {
    if (!mInitialized) return 0;
    return static_cast<int>(std::ceil(inputLength * mRatio));
}

int Resampler::resample(const int16_t* input, int inputLength, int16_t* output, int outputCapacity) {
    if (!mInitialized || input == nullptr || output == nullptr || inputLength <= 0) {
        return 0;
    }
    
    int outputLength = getOutputSize(inputLength);
    if (outputLength > outputCapacity) {
        outputLength = outputCapacity;
    }
    
    // Linear interpolation resampling
    double step = static_cast<double>(inputLength - 1) / static_cast<double>(outputLength - 1);
    
    for (int i = 0; i < outputLength; i++) {
        double srcPos = i * step;
        int srcIndex = static_cast<int>(srcPos);
        double frac = srcPos - srcIndex;
        
        if (srcIndex >= inputLength - 1) {
            output[i] = input[inputLength - 1];
        } else {
            // Linear interpolation between two samples
            double sample = input[srcIndex] * (1.0 - frac) + input[srcIndex + 1] * frac;
            // Clamp to int16 range
            sample = std::max(-32768.0, std::min(32767.0, sample));
            output[i] = static_cast<int16_t>(sample);
        }
    }
    
    return outputLength;
}

void Resampler::destroy() {
    mInitialized = false;
    mInputRate = 0;
    mOutputRate = 0;
    mRatio = 1.0;
}
