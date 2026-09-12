#include "ModelRunner.h"
#include <android/log.h>
#include <cmath>

#define LOG_TAG "SafeCallsCT_AI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

ModelRunner::ModelRunner() {}

ModelRunner::~ModelRunner() {
    release();
}

void ModelRunner::release() {
    if (interpreter != nullptr) {
        TfLiteInterpreterDelete(interpreter);
        interpreter = nullptr;
    }
    if (options != nullptr) {
        TfLiteInterpreterOptionsDelete(options);
        options = nullptr;
    }
    if (model != nullptr) {
        TfLiteModelDelete(model);
        model = nullptr;
    }
}

bool ModelRunner::loadModelFromBuffer(const void* buffer, size_t bufferSize) {
    release();

    // 1. Khởi tạo Model từ RAM Buffer
    model = TfLiteModelCreate(buffer, bufferSize);
    if (!model) {
        LOGE("--> Failed to create TfLiteModel from RAM buffer!");
        return false;
    }

    // 2. Cấu hình luồng xử lý (Sử dụng 2 CPU Threads)
    options = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(options, 2);

    // 3. Tạo Interpreter
    interpreter = TfLiteInterpreterCreate(model, options);
    if (!interpreter) {
        LOGE("--> Failed to create TfLiteInterpreter!");
        return false;
    }

    // 4. Cấp phát bộ nhớ cho các Tensors Input / Output
    if (TfLiteInterpreterAllocateTensors(interpreter) != kTfLiteOk) {
        LOGE("--> Failed to allocate TFLite tensors!");
        return false;
    }

    LOGI("--> TFLite Deepfake Model Loaded Successfully into Memory!");
    return true;
}

float ModelRunner::runInference(const std::vector<std::vector<float>>& melSpec) {
    if (!interpreter || melSpec.empty()) return 0.0f;

    size_t mels = melSpec.size();       // 80
    size_t frames = melSpec[0].size();  // 197

    // 1. Phẳng hóa ma trận Mel 2D [80 x 197] thành mảng 1D cho Tensor
    std::vector<float> inputBuffer;
    inputBuffer.reserve(mels * frames);

    for (const auto& row : melSpec) {
        inputBuffer.insert(inputBuffer.end(), row.begin(), row.end());
    }

    // 2. Nạp dữ liệu vào Input Tensor (Index 0)
    TfLiteTensor* inputTensor = TfLiteInterpreterGetInputTensor(interpreter, 0);
    if (!inputTensor) {
        LOGE("--> Input Tensor is null!");
        return 0.0f;
    }

    size_t inputByteSize = inputBuffer.size() * sizeof(float);
    if (TfLiteTensorCopyFromBuffer(inputTensor, inputBuffer.data(), inputByteSize) != kTfLiteOk) {
        LOGE("--> Failed to copy data into Input Tensor!");
        return 0.0f;
    }

    // 3. Chạy Suy Luận Model AI
    if (TfLiteInterpreterInvoke(interpreter) != kTfLiteOk) {
        LOGE("--> Failed to invoke TFLite Interpreter!");
        return 0.0f;
    }

    // 4. Trích xuất Kết quả từ Output Tensor (Index 0)
    const TfLiteTensor* outputTensor = TfLiteInterpreterGetOutputTensor(interpreter, 0);
    if (!outputTensor) {
        LOGE("--> Output Tensor is null!");
        return 0.0f;
    }

    float rawOutput = 0.0f;
    TfLiteTensorCopyToBuffer(outputTensor, &rawOutput, sizeof(float));

    // Chuyển kết quả Logit thô về Xác suất % bằng hàm Sigmoid: 1 / (1 + exp(-x))
    float probability = 1.0f / (1.0f + std::exp(-rawOutput));

    return probability;
}