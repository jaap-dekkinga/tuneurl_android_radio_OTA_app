#ifndef RESAMPLER_H
#define RESAMPLER_H

#include <cstdint>
#include <vector>

/**
 * Simple linear interpolation resampler for audio data.
 * Replaces the external libresample dependency for 16KB page alignment compliance.
 */
class Resampler {
public:
    Resampler();
    ~Resampler();
    
    /**
     * Initialize the resampler
     * @param inputRate Source sample rate
     * @param outputRate Target sample rate
     * @param channels Number of audio channels (1 for mono)
     * @return true if initialization successful
     */
    bool create(int inputRate, int outputRate, int channels);
    
    /**
     * Resample audio data
     * @param input Input audio samples (16-bit signed)
     * @param inputLength Number of input samples
     * @param output Output buffer for resampled data
     * @param outputCapacity Maximum output buffer size
     * @return Number of output samples produced
     */
    int resample(const int16_t* input, int inputLength, int16_t* output, int outputCapacity);
    
    /**
     * Get the expected output size for a given input size
     */
    int getOutputSize(int inputLength) const;
    
    void destroy();

private:
    int mInputRate;
    int mOutputRate;
    int mChannels;
    bool mInitialized;
    double mRatio;
};

#endif // RESAMPLER_H
