#include <jni.h>
#include <string>
#include <android/log.h>
#include <chrono>
#include <cstdlib>
#include <mutex> // ➕ Bổ sung thư viện khóa luồng
#include "RingBuffer.h"
#include "DSP.h"

#define LOG_TAG "VoiceShield_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static CircularAudioBuffer* g_audioBuffer = nullptr;
static DSPEngine* g_dspEngine = nullptr;
static std::mutex g_dspMutex; // ➕ Tạo Mutex bảo vệ RAM

void runMockAIAndSendCallback(JNIEnv* env, jobject thiz, const std::vector<std::vector<float>>& melSpec) {
    if (melSpec.empty()) return;

    float mockScore = 0.70f + static_cast<float>(rand()) / (RAND_MAX / (0.95f - 0.70f));
    LOGI("--> [MOCK AI RESULT] Deepfake Probability: %.2f%%", mockScore * 100.0f);

    jclass clazz = env->GetObjectClass(thiz);
    jmethodID methodID = env->GetMethodID(clazz, "onNativeAIResult", "(F)V");

    if (methodID != nullptr) {
        env->CallVoidMethod(thiz, methodID, mockScore);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_voiceshield_ai_audio_AudioStreamController_nativeInitBuffer(
        JNIEnv* env, jobject /* this */, jint samples) {
    std::lock_guard<std::mutex> lock(g_dspMutex); // 🔒 Lock

    if (g_audioBuffer != nullptr) delete g_audioBuffer;
    if (g_dspEngine != nullptr) delete g_dspEngine;

    g_audioBuffer = new CircularAudioBuffer(static_cast<size_t>(samples));
    g_dspEngine = new DSPEngine(16000, 512, 160, 80);
    LOGI("--> Initialized RingBuffer & C++ DSP Engine successfully");
}

extern "C" JNIEXPORT void JNICALL
Java_com_voiceshield_ai_audio_AudioStreamController_nativeWritePCM(
        JNIEnv* env, jobject /* this */, jshortArray data, jint size) {
    std::lock_guard<std::mutex> lock(g_dspMutex); // 🔒 Lock

    if (g_audioBuffer == nullptr) return;

    jshort* pcmData = env->GetShortArrayElements(data, nullptr);
    g_audioBuffer->write(reinterpret_cast<int16_t*>(pcmData), static_cast<size_t>(size));
    env->ReleaseShortArrayElements(data, pcmData, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_voiceshield_ai_audio_AudioStreamController_nativeProcessDSP(
        JNIEnv* env, jobject thiz) {
    std::vector<std::vector<float>> mel_spec;

    {
        std::lock_guard<std::mutex> lock(g_dspMutex); // 🔒 Lock khi đọc dữ liệu từ RAM
        if (g_audioBuffer == nullptr || g_dspEngine == nullptr) return;

        std::vector<int16_t> pcm_2s = g_audioBuffer->read_latest(32000);
        if (pcm_2s.size() < 32000) return;

        auto start_time = std::chrono::high_resolution_clock::now();
        mel_spec = g_dspEngine->compute_mel_spectrogram(pcm_2s);
        auto end_time = std::chrono::high_resolution_clock::now();

        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();
        if (!mel_spec.empty()) {
            LOGI("--> [DSP SUCCESS] Mel Matrix: [%zu mels x %zu frames] | Processing Time: %ld ms",
                 mel_spec.size(), mel_spec[0].size(), static_cast<long>(duration));
        }
    } // 🔓 Unlock trước khi gọi Callback về Kotlin

    if (!mel_spec.empty()) {
        runMockAIAndSendCallback(env, thiz, mel_spec);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_voiceshield_ai_audio_AudioStreamController_nativeRAMFlush(
        JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_dspMutex); // 🔒 Lock triệt để khi hủy bộ nhớ

    if (g_audioBuffer != nullptr) {
        g_audioBuffer->RAM_Flush();
        delete g_audioBuffer;
        g_audioBuffer = nullptr;
    }
    if (g_dspEngine != nullptr) {
        delete g_dspEngine;
        g_dspEngine = nullptr;
    }
    LOGI("--> Flushed and released RAM & DSP resources");
}