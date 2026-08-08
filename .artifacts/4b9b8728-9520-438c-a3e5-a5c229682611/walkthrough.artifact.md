# Hướng dẫn Kiểm tra Khả năng Âm thanh (Audio Capability Walkthrough)

Tôi đã hoàn thành việc triển khai ứng dụng chẩn đoán để kiểm chứng khả năng ghi âm trong cuộc gọi trên thiết bị của bạn.

## Các thay đổi đã thực hiện

### 1. Cấu hình Hệ thống & Quyền
- Cập nhật [AndroidManifest.xml](file:///D:/VoiceAI/app/src/main/AndroidManifest.xml) để thêm các quyền cần thiết:
    - `READ_PHONE_STATE`: Để nhận biết trạng thái cuộc gọi.
    - `RECORD_AUDIO`: Quyền ghi âm cơ bản.
    - `FOREGROUND_SERVICE_MICROPHONE`: Yêu cầu cho Android 14+ khi ghi âm ở chế độ nền.
- Đã tạm thời vô hiệu hóa việc build C++ Native trong [build.gradle.kts](file:///D:/VoiceAI/app/build.gradle.kts) để đảm bảo app có thể cài đặt ngay lập tức (do thiếu một số file header TFLite trong project hiện tại).

### 2. Thành phần Chẩn đoán
- **[DiagnosticService.kt](file:///D:/VoiceAI/app/src/main/java/com/voiceshield/ai/audio/DiagnosticService.kt)**: Một Foreground Service chạy ngầm, thực hiện ghi âm từ nguồn `MIC` và tính toán RMS/Peak mỗi 100ms. Nó cũng theo dõi flag `isClientSilenced` để báo cáo nếu hệ thống chặn dữ liệu.
- **[DiagnosticActivity.kt](file:///D:/VoiceAI/app/src/main/java/com/voiceshield/ai/DiagnosticActivity.kt)**: Giao diện hiển thị trực quan 13 thông số bạn yêu cầu.

### 3. Cập nhật Giao diện chính
- Thêm nút **"CHẨN ĐOÁN HỆ THỐNG (DIAGNOSTIC)"** vào [MainActivity.kt](file:///D:/VoiceAI/app/src/main/java/com/voiceshield/ai/MainActivity.kt).

## Hướng dẫn Kiểm tra & Xử lý sự cố

### 1. Nếu Facebook Messenger bị văng (Crash) trên máy ảo:
Việc chạy Messenger trên máy ảo (Emulator) rất khó khăn do:
- **Xung đột kiến trúc (ABI)**: Máy ảo thường là x86_64, trong khi APK Messenger bạn tải về có thể là ARM.
- **Thiếu RAM**: Messenger cực kỳ tốn tài nguyên.
- **Giải pháp**:
    - Hãy thử cài đặt **Messenger Lite** (bản cũ) hoặc **Skype Lite** để test luồng âm thanh.
    - Hoặc quan trọng nhất: **Nên dùng máy thật (Physical Device)**. Các kết quả về Microphone trên máy ảo thường không phản ánh đúng cơ chế bảo mật của Android thực tế.

### 2. Cách đọc kết quả báo cáo (Zalo/Messenger/GSM):
| Thông số | Trạng thái Tốt | Trạng thái Bị chặn |
| :--- | :--- | :--- |
| **Capture** | `ACTIVE` (Màu xanh) | `SILENCED` (Màu đỏ) |
| **OTHER APPS** | `1` (Nghĩa là Messenger đang dùng Mic) | `0` |
| **RMS / PEAK** | Nhảy số (> 0) | Đứng yên ở 0.00 |

### 3. Quy trình "Giành lại Microphone" (Last-in-win):
Nếu khi Messenger đang gọi mà RMS = 0:
1. Nhấn nút **"RESTART AUDIO (TRY RE-ACQUIRE)"** trong app VoiceShield.
2. Nếu RMS nhảy lên > 0, chúng ta đã thành công chiếm quyền ưu tiên từ Messenger.


render_diffs(file:///D:/VoiceAI/app/src/main/java/com/voiceshield/ai/audio/DiagnosticService.kt)
render_diffs(file:///D:/VoiceAI/app/src/main/java/com/voiceshield/ai/DiagnosticActivity.kt)
render_diffs(file:///D:/VoiceAI/app/src/main/java/com/voiceshield/ai/MainActivity.kt)
