---
name: tong-quan-du-an
description: Thông tin cơ bản về dự án RemotePlay và các công nghệ sử dụng.
type: project
---

# Tổng quan dự án RemotePlay

Dự án này là một ứng dụng Android cho phép điều khiển và chơi từ xa (Remote Play).

## Công nghệ sử dụng
- **Ngôn ngữ:** Kotlin
- **UI Framework:** Jetpack Compose
- **Dependency Injection:** Hilt
- **Networking:** Retrofit, OkHttp, Moshi
- **Streaming:** WebRTC (Stream WebRTC Android SDK)
- **Navigation:** Jetpack Navigation Compose
- **Architecture:** MVVM / Clean Architecture (dựa trên cấu trúc module app và feature)

## Mục tiêu chính
Cung cấp trải nghiệm chơi game từ xa mượt mà với độ trễ thấp, hỗ trợ các thiết bị đầu vào bên ngoài như chuột, bàn phím và gamepad thông qua `ExternalInputHandler`.

## Trạng thái hiện tại
- Đã thiết lập cấu trúc cơ bản với Hilt và Navigation.
- Đang phát triển tính năng streaming và xử lý input từ bên ngoài trong `MainActivity`.
