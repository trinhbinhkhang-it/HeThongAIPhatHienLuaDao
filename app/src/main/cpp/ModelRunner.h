#ifndef MODEL_RUNNER_H
#define MODEL_RUNNER_H

#include <vector>
#include <cstddef>
#include <tensorflow/lite/c/c_api.h>

class ModelRunner {
private:
    TfLiteModel* model = nullptr;
    TfLiteInterpreterOptions* options = nullptr;
    TfLiteInterpreter* interpreter = nullptr;

public:
    ModelRunner();
    ~ModelRunner();

    // Nạp model từ bộ nhớ RAM (file .tflite đọc từ assets)
    bool loadModelFromBuffer(const void* buffer, size_t bufferSize);

    // Truyền ma trận Mel Spectrogram [80 x 197] vào model và trả về xác suất Deepfake
    float runInference(const std::vector<std::vector<float>>& melSpec);

    // Giải phóng bộ nhớ TFLite
    void release();
};

#endif // MODEL_RUNNER_H