#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <vector>
#include <mutex>
#include <cstdint>

class CircularAudioBuffer {
private:
    std::vector<int16_t> buffer;
    size_t capacity;
    size_t head;
    size_t tail;
    size_t current_size;
    mutable std::mutex buffer_mutex;

public:
    // Khởi tạo đệm RAM mặc định 32,000 samples (2.0 giây âm thanh @ 16kHz)
    explicit CircularAudioBuffer(size_t size_in_samples = 32000);
    ~CircularAudioBuffer();

    // Ghi dữ liệu PCM mới vào Buffer (Xoay vòng ghi đè dữ liệu cũ)
    void write(const int16_t* data, size_t count);

    // Trích xuất khung dữ liệu PCM mới nhất để đưa vào AI Engine
    std::vector<int16_t> read_latest(size_t count);

    // Hàm tiêu hủy và giải phóng RAM tức thì
    void RAM_Flush();
};

#endif // RING_BUFFER_H