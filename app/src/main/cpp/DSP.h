#ifndef DSP_H
#define DSP_H

#include <vector>
#include <cmath>
#include <complex>

class DSPEngine {
private:
    int sample_rate;
    int n_fft;
    int hop_length;
    int n_mels;

    std::vector<float> hann_window;
    std::vector<std::vector<float>> mel_filterbank;

    void create_hann_window();
    void create_mel_filterbank();
    float hz_to_mel(float hz);
    float mel_to_hz(float mel);

public:
    DSPEngine(int sample_rate = 16000, int n_fft = 512, int hop_length = 160, int n_mels = 80);

    // Biến đổi chuỗi PCM 16-bit thành ma trận Mel-Spectrogram (2D Vector)
    std::vector<std::vector<float>> compute_mel_spectrogram(const std::vector<int16_t>& pcm_data);
};

#endif // DSP_H