#include "Resampler.h"
#include <cmath>
#include <algorithm>
#include <vector>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Windowed sinc interpolation for high-quality resampling
// This is similar to what iOS AVAudioEngine uses internally
static inline double sincFunction(double x) {
    if (std::abs(x) < 1e-8) {
        return 1.0;
    }
    double pix = M_PI * x;
    return std::sin(pix) / pix;
}

// Blackman window for better frequency response
static inline double blackmanWindow(double x, double width) {
    if (std::abs(x) >= width) {
        return 0.0;
    }
    double n = x / width;
    return 0.42 - 0.5 * std::cos(2.0 * M_PI * (n + 0.5)) + 0.08 * std::cos(4.0 * M_PI * (n + 0.5));
}

// Windowed sinc interpolation kernel
static inline double windowedSinc(double x, double width) {
    return sincFunction(x) * blackmanWindow(x, width);
}

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
    
    // Windowed sinc interpolation with adaptive kernel width
    // Kernel width: larger for downsampling, smaller for upsampling
    const double kernelWidth = std::max(4.0, 8.0 / mRatio);
    const int kernelSize = static_cast<int>(std::ceil(kernelWidth));
    
    double step = static_cast<double>(inputLength - 1) / static_cast<double>(outputLength - 1);
    
    for (int i = 0; i < outputLength; i++) {
        double srcPos = i * step;
        int centerIndex = static_cast<int>(std::round(srcPos));
        
        double sum = 0.0;
        double weightSum = 0.0;
        
        // Apply windowed sinc filter
        for (int j = -kernelSize; j <= kernelSize; j++) {
            int sampleIndex = centerIndex + j;
            
            // Handle boundaries with mirroring for better quality
            if (sampleIndex < 0) {
                sampleIndex = -sampleIndex;
            } else if (sampleIndex >= inputLength) {
                sampleIndex = 2 * inputLength - sampleIndex - 2;
            }
            
            // Clamp to valid range
            sampleIndex = std::max(0, std::min(inputLength - 1, sampleIndex));
            
            double distance = srcPos - (centerIndex + j);
            double weight = windowedSinc(distance, kernelWidth);
            
            sum += input[sampleIndex] * weight;
            weightSum += weight;
        }
        
        // Normalize by weight sum to maintain amplitude
        double sample = (weightSum > 1e-8) ? (sum / weightSum) : 0.0;
        
        // Clamp to int16 range
        sample = std::max(-32768.0, std::min(32767.0, sample));
        output[i] = static_cast<int16_t>(std::round(sample));
    }
    
    return outputLength;
}

void Resampler::destroy() {
    mInitialized = false;
    mInputRate = 0;
    mOutputRate = 0;
    mRatio = 1.0;
}
