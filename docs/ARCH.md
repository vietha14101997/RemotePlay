# Kiến trúc dự án (Architecture)

Dự án Remote Play áp dụng kiến trúc **Clean Architecture** kết hợp với **MVVM** (Model-View-ViewModel) và mô hình **Feature-based**.

## 🏗 Tổng quan các lớp
1.  **Presentation Layer (UI)**: Sử dụng Jetpack Compose. ViewModels quản lý state và xử lý UI logic.
2.  **Domain Layer**: Chứa các Business Logic, Use Cases và Models.
3.  **Data Layer**: Chứa implementations của Repositories, API clients (Retrofit, WebRTC, WebSocket) và Local DB.

## 📂 Phân chia thư mục
- `core/`: Các thành phần dùng chung cho toàn bộ ứng dụng.
- `feature/`: Mỗi thư mục con là một tính năng độc lập.
    - `data/`: Repositories, DTOs, Data Sources.
    - `domain/`: Use Cases, Domain Models.
    - `presentation/`: Compose Screens, ViewModels.
- `service/`: Xử lý các tác vụ nền (Foreground Services).

## 🔄 Luồng dữ liệu
`UI -> ViewModel -> Use Case -> Repository -> Data Source (Network/Local)`
