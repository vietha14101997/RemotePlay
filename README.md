# Remote Play - Android Client

Ứng dụng Android cho phép điều khiển và stream từ xa.

## 🚀 Tính năng chính
- Stream video chất lượng cao từ máy chủ.
- Điều khiển từ xa (Chuột, bàn phím, gamepad).
- Kiểm tra tốc độ mạng (Speed Test).
- Kết nối thông qua WebRTC và WebSocket.

## 🛠 Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Concurrency**: Coroutines, Flow
- **Network**: WebRTC, WebSocket, OkHttp, Retrofit
- **Dependency Injection**: Hilt (giả định dựa trên cấu trúc)
- **Architecture**: MVVM + Clean Architecture

## 📂 Cấu trúc dự án
- `app/src/main/java/com/reka/remoteplay/`
    - `core/`: Chứa các module cốt lõi (Network, Common UI, DI).
    - `feature/`: Chứa các tính năng (Streaming, Connection, Settings).
    - `ui/`: Theme và các thành phần giao diện chung.
    - `service/`: Các Android Services xử lý chạy nền.

## 📖 Tài liệu chi tiết
Xem thêm trong thư mục [docs/](docs/):
- [Kiến trúc (ARCH.md)](docs/ARCH.md)
- [Công nghệ sử dụng (TECH_STACK.md)](docs/TECH_STACK.md)
- [Hướng dẫn đóng góp (CONTRIBUTING.md)](docs/CONTRIBUTING.md)

## 🏗 Cài đặt
1. Clone dự án.
2. Mở bằng Android Studio (Koala hoặc mới hơn).
3. Build và chạy trên thiết bị Android (API 24+).
