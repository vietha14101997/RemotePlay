# Cấu trúc chi tiết dự án (Project Structure)

Tài liệu này mô tả chi tiết các module và tệp tin quan trọng trong dự án Remote Play.

## 📦 app (Main Module)
Thư mục chính: `app/src/main/java/com/reka/remoteplay/`

### 🏗 Core Module (`/core`)
Chứa các thành phần dùng chung, không phụ thuộc vào logic của từng feature.
- **`network/`**: 
    - `WebSocketClient.kt`: Quản lý kết nối WebSocket để trao đổi tín hiệu (SDP/ICE Candidates) với server.
- **`model/`**: Định nghĩa các thực thể dữ liệu toàn cục.
- **`util/`**: Các helper classes cho log, permission, định dạng dữ liệu.

### 🎮 Feature: Streaming (`/feature/streaming`)
Module quan trọng nhất, xử lý việc nhận luồng video và gửi lệnh điều khiển.
- **`data/remote/WebRtcManager.kt`**: Khởi tạo PeerConnection, xử lý MediaStream và DataChannel.
- **`presentation/`**:
    - `StreamingRoute.kt`: Màn hình hiển thị video stream.
    - `CursorOverlay.kt`: Lớp phủ xử lý và hiển thị vị trí chuột từ xa.

### 🔗 Feature: Connection (`/feature/connection`)
Quản lý việc dò tìm server và thiết lập cấu hình.
- **`data/remote/SpeedTestClient.kt`**: Đo lường Latency, Jitter và Bandwidth để tự động điều chỉnh chất lượng stream.
- **`presentation/ConfigReviewScreen.kt`**: Màn hình xác nhận cấu hình trước khi bắt đầu phiên làm việc.

### 🛠 Services (`/service`)
- (Đang phát triển): Dự kiến chứa `StreamingService` để giữ kết nối ổn định.

---

## ⚙️ Cấu trúc Build & Config
- `build.gradle.kts` (Project): Quản lý plugins và phiên bản thư viện dùng chung.
- `app/build.gradle.kts`: Cấu hình dependencies (WebRTC, Compose, Hilt).
- `gradle.properties`: Cấu hình JVM và thông số build.
- `AGENTS.md`: Hướng dẫn dành cho các AI Agent hỗ trợ phát triển dự án.
