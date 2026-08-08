# Kế hoạch Thử nghiệm: Chống Deepfake cho cuộc gọi Messenger (Facebook)

Chúng ta sẽ chuyển hướng sang kiểm chứng khả năng bảo vệ các cuộc gọi qua **Facebook Messenger**. Đây là ứng dụng OTT phổ biến nhất và có cơ chế quản lý âm thanh tương tự Zalo nhưng đôi khi có các chính sách ưu tiên khác nhau tùy phiên bản Android.

## Yêu cầu Người dùng Xem xét

> [!IMPORTANT]
> **Bắt buộc sử dụng máy thật**: Bài test này không thể thực hiện trên máy ảo vì cần mô phỏng sự tranh chấp Microphone thực tế giữa VoiceShield và Messenger.

## Các bước thực hiện

### 1. Nâng cấp công cụ Diagnostic (OTT Edition)

#### [MODIFY] [DiagnosticService.kt](file:///D:/VoiceAI/app/src/main/java/com/voiceshield/ai/audio/DiagnosticService.kt)
- Thêm logic theo dõi `audioManager.activeRecordingConfigurations` để phát hiện khi Messenger đang chiếm Microphone.
- Gửi thông tin về số lượng ứng dụng đang thu âm song song về UI.

#### [MODIFY] [DiagnosticActivity.kt](file:///D:/VoiceAI/app/src/main/java/com/voiceshield/ai/DiagnosticActivity.kt)
- Hiển thị thêm dòng: **"OTHER APPS RECORDING"**.
- Thêm nút **"RESTART AUDIO"**: Cho phép app thử khởi động lại luồng thu âm ngay trong lúc cuộc gọi Messenger đang diễn ra (thử "giành" lại quyền truy cập).

### 2. Quy trình kiểm chứng Messenger

Bạn hãy thực hiện bài test sau trên **máy thật**:

1.  Mở app VoiceShieldAI -> Nhấn **Start Diagnostic**.
2.  Mở **Facebook Messenger** -> Thực hiện một cuộc gọi (Call hoặc Video Call).
3.  Quay lại VoiceShieldAI (dùng đa nhiệm hoặc chia đôi màn hình):
    *   Quan sát **RMS**: Nếu RMS > 0, chúng ta có tín hiệu.
    *   Nếu RMS = 0: Nhấn nút **"RESTART AUDIO"** trong app VoiceShieldAI.
    *   Nếu sau khi Restart mà **RMS > 0**, hướng đi này khả thi 100%.

## Verification Plan

### Manual Verification
- Xác nhận trạng thái **Capture: ACTIVE** và **RMS > 0** khi đang trong cuộc gọi Messenger.
- Nếu thành công, chúng ta sẽ bắt đầu gắn model AI vào luồng dữ liệu này.

---
**Tôi sẽ bắt đầu cập nhật code ngay bây giờ để bạn có công cụ test sớm nhất.**