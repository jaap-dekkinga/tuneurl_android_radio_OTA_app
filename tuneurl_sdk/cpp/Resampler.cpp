#ifndef RESAMPLER_H
#define RESAMPLER_H

#include <cstdint>
#include <vector>

/**
 * Band-limited (windowed-sinc) audio resampler.
 *
 * Replaces the earlier linear-interpolation resampler. Linear interpolation
 * applies no low-pass filter, so when downsampling 44.1 kHz -> 10.24 kHz for
 * fingerprinting, everything between ~5 kHz and ~22 kHz aliased back into the
 * fingerprint band as spurious spectral peaks. This implementation applies a
 * Kaiser-windowed sinc low-pass at 95% of the output Nyquist frequency, the
 * same kind of filtering AVAudioConverter performs on iOS.
 *
 * Input and output are interleaved 16-bit PCM with `channels` channels.
 * Each call is stateless (no history carried between calls); callers pass a
 * complete block of audio.
 */
class Resampler {
public:
    Resampler();
    ~Resampler();

    bool create(int inputRate, int outputRate, int channels);
    int resample(const int16_t* input, int inputLength, int16_t* output, int outputCapacity);
    int getOutputSize(int inputLength) const;
    void destroy();

private:
    float filterAt(double offset) const;

    int mInputRate;
    int mOutputRate;
    int mChannels;
    bool mInitialized;
    double mRatio;          // outputRate / inputRate

    // Filter design (all expressed in input-sample units)
    double mCutoff;         // normalized cutoff, cycles per input sample
    int mHalfLength;        // filter half-width in input samples
    int mTableResolution;   // table entries per input sample
    std::vector<float> mTable; // one-sided impulse response
};

#endif // RESAMPLER_H
