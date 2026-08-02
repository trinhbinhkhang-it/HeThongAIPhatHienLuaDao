# Kế hoạch triển khai: Chi tiết hóa các giai đoạn phát triển trong DocumentApp.tex

Cập nhật tài liệu DocumentApp.tex với lộ trình phát triển chi tiết cấp độ kỹ thuật sâu hơn, mô tả rõ từng bước thực hiện và tiêu chí nghiệm thu (kết quả) cho mỗi giai đoạn.

## Các giai đoạn phát triển chi tiết

### Giai đoạn 1: Thiết lập hạ tầng và Cấu hình nhân (Core Infrastructure)
*   **Các bước thực hiện:**
    1.  Cấu hình `build.gradle` để hỗ trợ NDK và liên kết với `CMakeLists.txt`.
    2.  Thiết lập cấu trúc thư mục JNI chuẩn: `cpp/include` (header), `cpp/libs` (thư viện TFLite), `cpp/src` (mã nguồn).
    3.  Nhúng thư viện TensorFlow Lite JNI (`.so`) cho các kiến trúc CPU (arm64-v8a, x86_64).
    4.  Viết boilerplate cho `native-lib.cpp` để kiểm tra việc load thư viện thành công.
*   **Kết quả dự kiến:** Dự án biên dịch không lỗi; Logcat hiển thị thông báo "Library loaded successfully" khi ứng dụng khởi chạy.

### Giai đoạn 2: Phát triển luồng thu âm và Quản lý bộ nhớ đệm (Audio & Memory Management)
*   **Các bước thực hiện:**
    1.  Xây dựng lớp `AudioStreamController` trong Kotlin sử dụng `AudioRecord` với chế độ `VOICE_COMMUNICATION`.
    2.  Thiết lập Coroutine (IO Dispatcher) để đọc luồng PCM 16-bit liên tục từ Microphone.
    3.  Xây dựng lớp `RingBuffer` bằng C++ để quản lý vùng nhớ đệm vòng tròn (Circular Buffer) trên RAM, tránh việc cấp phát bộ nhớ liên tục gây lag.
    4.  Viết hàm JNI `nativeWritePCM` để chuyển mảng `ShortArray` từ Kotlin xuống C++ một cách hiệu quả.
*   **Kết quả dự kiến:** Luồng âm thanh được ghi vào RAM liên tục; không xảy ra hiện tượng tràn bộ nhớ hoặc rò rỉ bộ nhớ (memory leak) khi chạy lâu.

### Giai đoạn 3: Tích hợp Trí tuệ nhân tạo và Xử lý tín hiệu (AI & DSP Integration)
*   **Các bước thực hiện:**
    1.  Xây dựng module `DSP.cpp` để chuẩn hóa âm thanh (Normalization) và cắt khung hình (Framing).
    2.  Triển khai lớp `ModelRunner.cpp` sử dụng TensorFlow Lite Interpreter để nạp mô hình `.tflite` từ thư mục `assets`.
    3.  Thiết lập cơ chế kích hoạt Inference (Dự đoán) định kỳ (ví dụ: mỗi 400ms dữ liệu âm thanh mới).
    4.  Tối ưu hóa đa luồng tại tầng C++ để việc chạy AI không gây nghẽn luồng thu âm.
*   **Kết quả dự kiến:** Hệ thống trả về điểm số xác suất (0.0 - 1.0) đại diện cho độ tin cậy của giọng nói sau mỗi 400ms.

### Giai đoạn 4: Xây dựng hệ thống Phản hồi và Cảnh báo UI (Feedback System)
*   **Các bước thực hiện:**
    1.  Triển khai cơ chế JNI Callback để tầng C++ gọi ngược lại hàm `onNativeAIResult` trong Kotlin.
    2.  Sử dụng `Dispatchers.Main` trong Kotlin để cập nhật giao diện người dùng ngay lập tức từ kết quả AI.
    3.  Xây dựng logic phân loại mức độ rủi ro: An toàn (<50%), Nghi vấn (50-80%), Nguy hiểm (>80%).
    4.  Cập nhật màu sắc (Xanh/Cam/Đỏ) và thông báo cảnh báo trên màn hình chính.
*   **Kết quả dự kiến:** Người dùng thấy được tỷ lệ Deepfake thay đổi theo thời gian thực khi đang nói chuyện.

### Giai đoạn 5: Tối ưu hóa, Bảo mật và Đóng gói (Optimization & Security)
*   **Các bước thực hiện:**
    1.  Xây dựng hàm `nativeRAMFlush` để xóa sạch dữ liệu âm thanh thô trên RAM ngay khi người dùng bấm dừng.
    2.  Xử lý vòng đời ứng dụng trong `MainActivity` (onDestroy) để giải phóng tài nguyên phần cứng (Microphone) và bộ nhớ AI.
    3.  Tối ưu hóa CPU bằng cách điều chỉnh Thread Priority cho luồng thu âm để tránh bị hệ thống Android tắt ngầm.
    4.  Kiểm tra và xử lý các trường hợp ngoại lệ (mất quyền truy cập Mic, file model bị lỗi).
*   **Kết quả dự kiến:** Ứng dụng hoạt động mượt mà; dữ liệu âm thanh được bảo mật hoàn toàn; không để lại dấu vết dữ liệu trên thiết bị sau khi sử dụng.

## Thay đổi đề xuất

### [Tài liệu]

#### [MODIFY] [DocumentApp.tex](file:///D:/VoiceAI/DocumentApp.tex)
- Thay thế section cũ bằng section chi tiết mới: `\section{Lộ trình phát triển chi tiết và Tiêu chí nghiệm thu}`.
