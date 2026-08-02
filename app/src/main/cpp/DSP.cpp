#include "DSP.h"
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

DSPEngine::DSPEngine(int sample_rate, int n_fft, int hop_length, int n_mels)
        : sample_rate(sample_rate), n_fft(n_fft), hop_length(hop_length), n_mels(n_mels) {
    create_hann_window();
    create_mel_filterbank();
}

float DSPEngine::hz_to_mel(float hz) {
    return 2595.0f * std::log10(1.0f + hz / 700.0f);
}

float DSPEngine::mel_to_hz(float mel) {
    return 700.0f * (std::pow(10.0f, mel / 2595.0f) - 1.0f);
}

void DSPEngine::create_hann_window() {
    hann_window.resize(n_fft);
    for (int i = 0; i < n_fft; ++i) {
        hann_window[i] = 0.5f * (1.0f - std::cos(2.0f * M_PI * i / (n_fft - 1)));
    }
}

void DSPEngine::create_mel_filterbank() {
    int num_spectrogram_bins = n_fft / 2 + 1;
    float min_mel = hz_to_mel(0.0f);
    float max_mel = hz_to_mel(sample_rate / 2.0f);

    std::vector<float> mel_pts(n_mels + 2);
    for (size_t i = 0; i < mel_pts.size(); ++i) {
        mel_pts[i] = min_mel + i * (max_mel - min_mel) / (n_mels + 1);
    }

    std::vector<int> bin_pts(n_mels + 2);
    for (size_t i = 0; i < bin_pts.size(); ++i) {
        float hz = mel_to_hz(mel_pts[i]);
        bin_pts[i] = std::floor((n_fft + 1) * hz / sample_rate);
    }

    mel_filterbank.assign(n_mels, std::vector<float>(num_spectrogram_bins, 0.0f));

    for (int m = 1; m <= n_mels; ++m) {
        for (int k = bin_pts[m - 1]; k < bin_pts[m]; ++k) {
            mel_filterbank[m - 1][k] = static_cast<float>(k - bin_pts[m - 1]) / (bin_pts[m] - bin_pts[m - 1]);
        }
        for (int k = bin_pts[m]; k < bin_pts[m + 1]; ++k) {
            mel_filterbank[m - 1][k] = static_cast<float>(bin_pts[m + 1] - k) / (bin_pts[m + 1] - bin_pts[m]);
        }
    }
}

std::vector<std::vector<float>> DSPEngine::compute_mel_spectrogram(const std::vector<int16_t>& pcm_data) {
    size_t num_frames = (pcm_data.size() - n_fft) / hop_length + 1;
    int num_bins = n_fft / 2 + 1;

    std::vector<std::vector<float>> mel_spec(n_mels, std::vector<float>(num_frames, 0.0f));

    for (size_t f = 0; f < num_frames; ++f) {
        std::vector<std::complex<float>> fft_in(n_fft, 0.0f);

        // Áp dụng cửa sổ Hann
        for (int i = 0; i < n_fft; ++i) {
            float sample = static_cast<float>(pcm_data[f * hop_length + i]) / 32768.0f; // Normalize [-1.0, 1.0]
            fft_in[i] = sample * hann_window[i];
        }

        // Discrete Fourier Transform (DFT)
        std::vector<float> power_spec(num_bins, 0.0f);
        for (int k = 0; k < num_bins; ++k) {
            std::complex<float> sum(0.0f, 0.0f);
            for (int n = 0; n < n_fft; ++n) {
                float angle = -2.0f * M_PI * k * n / n_fft;
                sum += fft_in[n] * std::complex<float>(std::cos(angle), std::sin(angle));
            }
            power_spec[k] = std::norm(sum);
        }

        // Nhân với Mel Filterbank
        for (int m = 0; m < n_mels; ++m) {
            float mel_energy = 0.0f;
            for (int k = 0; k < num_bins; ++k) {
                mel_energy += power_spec[k] * mel_filterbank[m][k];
            }
            // Log-Mel scaling
            mel_spec[m][f] = std::log(std::max(1e-5f, mel_energy));
        }
    }

    return mel_spec;
}