#include "Resampler.h"
#include <cmath>
#include <algorithm>

namespace {

// Zero crossings of the sinc on each side of the centre tap.
constexpr int kZeroCrossings = 16;

// Kaiser window shape parameter (~86 dB side-lobe attenuation).
constexpr double kKaiserBeta = 8.6;

// Cutoff as a fraction of the lower of the two Nyquist frequencies.
constexpr double kCutoffFraction = 0.95;

// Impulse-response table oversampling (entries per input sample).
constexpr int kTableResolution = 256;

constexpr double kPi = 3.14159265358979323846;

// Zeroth-order modified Bessel function of the first kind.
double besselI0(double x) {
    double sum = 1.0;
    double term = 1.0;
    const double halfX = x / 2.0;
    for (int k = 1; k < 50; k++) {
        term *= (halfX / k) * (halfX / k);
        sum += term;
        if (term < sum * 1e-12) break;
    }
    return sum;
}

} // namespace

Resampler::Resampler()
    : mInputRate(0), mOutputRate(0), mChannels(1), mInitialized(false), mRatio(1.0),
      mCutoff(0.5), mHalfLength(0), mTableResolution(kTableResolution) {
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

    // Low-pass cutoff in cycles per input sample. When downsampling the
    // output Nyquist (0.5 * ratio) is the limit; when upsampling, 0.5.
    mCutoff = 0.5 * std::min(1.0, mRatio) * kCutoffFraction;

    // The sinc's zero crossings are 1/(2*cutoff) input samples apart.
    mHalfLength = static_cast<int>(std::ceil(kZeroCrossings / (2.0 * mCutoff)));

    // Precompute the one-sided impulse response h(x), x in [0, halfLength].
    const int tableSize = mHalfLength * mTableResolution + 2;
    mTable.assign(tableSize, 0.0f);
    const double i0Beta = besselI0(kKaiserBeta);
    for (int i = 0; i < tableSize; i++) {
        const double x = static_cast<double>(i) / mTableResolution;
        if (x > mHalfLength) {
            mTable[i] = 0.0f;
            continue;
        }
        const double arg = 2.0 * mCutoff * x;
        const double sinc = (x == 0.0) ? 1.0 : std::sin(kPi * arg) / (kPi * arg);
        const double r = x / mHalfLength;
        const double window = besselI0(kKaiserBeta * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0Beta;
        mTable[i] = static_cast<float>(2.0 * mCutoff * sinc * window); // unity gain at DC
    }

    mInitialized = true;
    return true;
}

float Resampler::filterAt(double offset) const {
    const double x = std::fabs(offset) * mTableResolution;
    const int index = static_cast<int>(x);
    if (index >= static_cast<int>(mTable.size()) - 1) {
        return 0.0f;
    }
    const float frac = static_cast<float>(x - index);
    return mTable[index] + (mTable[index + 1] - mTable[index]) * frac;
}

int Resampler::getOutputSize(int inputLength) const {
    if (!mInitialized) return 0;
    const int inputFrames = inputLength / mChannels;
    const int outputFrames = static_cast<int>(std::floor(inputFrames * mRatio));
    return outputFrames * mChannels;
}

int Resampler::resample(const int16_t* input, int inputLength, int16_t* output, int outputCapacity) {
    if (!mInitialized || input == nullptr || output == nullptr || inputLength <= 0) {
        return 0;
    }

    const int inputFrames = inputLength / mChannels;
    int outputFrames = getOutputSize(inputLength) / mChannels;
    outputFrames = std::min(outputFrames, outputCapacity / mChannels);
    if (inputFrames <= 0 || outputFrames <= 0) {
        return 0;
    }

    const double step = 1.0 / mRatio; // input frames per output frame

    for (int o = 0; o < outputFrames; o++) {
        const double centre = o * step;
        const int centreIndex = static_cast<int>(std::floor(centre));
        const int first = std::max(0, centreIndex - mHalfLength + 1);
        const int last = std::min(inputFrames - 1, centreIndex + mHalfLength);

        for (int c = 0; c < mChannels; c++) {
            double acc = 0.0;
            double weightSum = 0.0;
            for (int k = first; k <= last; k++) {
                const float w = filterAt(centre - k);
                acc += w * input[k * mChannels + c];
                weightSum += w;
            }
            // Near the block edges part of the kernel falls outside the
            // input; renormalise so the edges don't fade out.
            double sample = (weightSum > 1e-6) ? acc / weightSum : acc;
            sample = std::max(-32768.0, std::min(32767.0, std::round(sample)));
            output[o * mChannels + c] = static_cast<int16_t>(sample);
        }
    }

    return outputFrames * mChannels;
}

void Resampler::destroy() {
    mInitialized = false;
    mInputRate = 0;
    mOutputRate = 0;
    mRatio = 1.0;
    mTable.clear();
    mTable.shrink_to_fit();
}
