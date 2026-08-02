#include "RingBuffer.h"
#include <algorithm>
#include <cstring>

// Khởi tạo bộ đệm RAM vòng tròn
CircularAudioBuffer::CircularAudioBuffer(size_t size_in_samples)
        : capacity(size_in_samples), head(0), tail(0), current_size(0) {
    buffer.resize(capacity, 0);
}

// Destructor - Tự động xóa bộ nhớ khi hủy object
CircularAudioBuffer::~CircularAudioBuffer() {
    RAM_Flush();
}

// Ghi dữ liệu PCM mới vào RAM (Tự động ghi đè dữ liệu cũ nhất khi vượt quá 2.0s)
void CircularAudioBuffer::write(const int16_t* data, size_t count) {
    std::lock_guard<std::mutex> lock(buffer_mutex);

    for (size_t i = 0; i < count; ++i) {
        buffer[head] = data[i];
        head = (head + 1) % capacity;

        if (current_size < capacity) {
            current_size++;
        } else {
            // Đẩy con trỏ tail để đè dữ liệu cũ
            tail = (tail + 1) % capacity;
        }
    }
}

// Trích xuất khung dữ liệu âm thanh mới nhất để chuẩn bị đưa vào AI
std::vector<int16_t> CircularAudioBuffer::read_latest(size_t count) {
    std::lock_guard<std::mutex> lock(buffer_mutex);

    size_t read_count = std::min(count, current_size);
    std::vector<int16_t> result(read_count);

    size_t start_pos = (head >= read_count) ? (head - read_count) : (capacity + head - read_count);

    for (size_t i = 0; i < read_count; ++i) {
        result[i] = buffer[(start_pos + i) % capacity];
    }

    return result;
}

// Xóa sạch dữ liệu trên RAM (Đảm bảo Zero-Storage & Tuân thủ Quyền riêng tư)
void CircularAudioBuffer::RAM_Flush() {
    std::lock_guard<std::mutex> lock(buffer_mutex);
    std::fill(buffer.begin(), buffer.end(), 0);
    head = 0;
    tail = 0;
    current_size = 0;
}